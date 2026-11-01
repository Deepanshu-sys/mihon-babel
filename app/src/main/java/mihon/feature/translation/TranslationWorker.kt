package mihon.feature.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.core.metro.metroGraph
import mihon.feature.translation.contract.DialogueTranslator
import mihon.feature.translation.model.TranslationPhase
import mihon.feature.translation.pipeline.ChapterPageResolver
import mihon.feature.translation.pipeline.ChapterTranslationCoordinator
import mihon.feature.translation.pipeline.ComicPageTypesetter
import mihon.feature.translation.pipeline.LocalLlmDialogueTranslator
import mihon.feature.translation.pipeline.LocalLlmRuntime
import mihon.feature.translation.pipeline.MlKitComicDetectorAndOcr
import mihon.feature.translation.pipeline.ModelAssetManager
import mihon.feature.translation.pipeline.OnnxComicBubbleDetector
import mihon.feature.translation.pipeline.SmartPageInpainter
import mihon.feature.translation.pipeline.StrokeTextMasker
import mihon.feature.translation.pipeline.TranslationEngineKind
import mihon.feature.translation.pipeline.TranslationModelSelector
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Sequential background worker that drains the TranslationManager queue one chapter at a time.
 */
class TranslationWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val NOTIFICATION_ID = 482910
    }

    private var lastNotificationTime = 0L
    private var lastNotificationText: String? = null

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle("Offline chapter translation")
            setContentText("Preparing translation queue")
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setProgress(0, 0, true)
        }.build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        val appGraph: mihon.app.di.AppGraph = applicationContext.metroGraph()
        val translationManager = appGraph.translationManager

        runCatching { setForegroundSafely() }

        while (true) {
            val job = translationManager.nextJob() ?: break
            runJob(job, appGraph, translationManager)
        }

        return Result.success()
    }

    private suspend fun runJob(
        job: TranslationJob,
        appGraph: mihon.app.di.AppGraph,
        translationManager: TranslationManager,
    ) {
        val chapterId = job.chapterId
        logcat(LogPriority.INFO) { "Starting translation job for chapter $chapterId (${job.chapterName})" }
        writeBenchmarkStatus(chapterId, "RUNNING")

        if (translationManager.isAbortRequested(chapterId)) {
            logcat(LogPriority.INFO) { "Chapter $chapterId was cancelled before starting" }
            translationManager.onJobCancelled(chapterId)
            return
        }

        try {
            translationManager.report(
                chapterId = chapterId,
                phase = TranslationPhase.DOWNLOADING,
                current = 0,
                total = 1,
                message = "Resolving chapter pages...",
            )
            updateNotificationThrottled(job.chapterName, 0, translationManager.pendingQueueSize, force = true)

            val resolver = ChapterPageResolver(
                context = applicationContext,
                downloadProvider = appGraph.downloadProvider,
                sourceManager = appGraph.sourceManager,
                storageManager = appGraph.storageManager,
                chapterCache = appGraph.chapterCache,
            )

            val resolvedPages = try {
                resolver.resolvePageStreams(
                    chapterId = chapterId,
                    chapterName = job.chapterName,
                    chapterUrl = job.chapterUrl,
                    mangaTitle = job.mangaTitle,
                    scanlator = job.scanlator,
                    sourceId = job.sourceId,
                    onProgress = { current, total, message ->
                        if (translationManager.isAbortRequested(chapterId)) {
                            throw UserCancelledException()
                        }
                        translationManager.report(chapterId, TranslationPhase.DOWNLOADING, current, total, message)
                        updateNotificationThrottled(job.chapterName, 0, translationManager.pendingQueueSize)
                    },
                )
            } catch (e: UserCancelledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to resolve pages for chapter $chapterId" }
                translationManager.finish(chapterId, e.message ?: "Failed to resolve pages")
                writeBenchmarkStatus(chapterId, "FAILED", e.message ?: "Failed to resolve pages")
                return
            }

            try {
                if (translationManager.isAbortRequested(chapterId)) throw UserCancelledException()

                val rawPageStreams = resolvedPages.pageStreams
                if (rawPageStreams.isEmpty()) {
                    translationManager.finish(chapterId, "Chapter has no readable pages")
                    writeBenchmarkStatus(chapterId, "FAILED", "No readable pages")
                    return
                }
                val pageStreams = if (job.maxPages > 0) rawPageStreams.take(job.maxPages) else rawPageStreams

                val assetManager = ModelAssetManager(applicationContext) { fileName, copiedBytes, totalBytes ->
                    val percent = if (totalBytes > 0L) {
                        ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    updateNotificationThrottled(job.chapterName, percent, translationManager.pendingQueueSize)
                }
                val effortKey = appGraph.translationPreferences.effortLevel.get()
                val selectedEffort = TranslationEffortLevel.fromKey(effortKey)
                val selection = TranslationModelSelector.select(selectedEffort)
                val translator = createTranslator(applicationContext, assetManager, selection.engine)
                val detectorAndOcr = MlKitComicDetectorAndOcr(
                    bubbleDetector = OnnxComicBubbleDetector(assetManager),
                    context = applicationContext,
                )
                val masker = StrokeTextMasker()
                val inpainter = SmartPageInpainter(assetManager)
                val fontName = appGraph.translationPreferences.comicFont.get()
                val typesetter = ComicPageTypesetter(applicationContext, fontFileName = fontName)

                val coordinator = ChapterTranslationCoordinator(
                    storage = translationManager.storage,
                    detectorAndOcr = detectorAndOcr,
                    translator = translator,
                    masker = masker,
                    inpainter = inpainter,
                    typesetter = typesetter,
                )

                try {
                    coordinator.translateChapter(
                        chapterId = chapterId,
                        pageStreams = pageStreams,
                        mangaTitle = job.mangaTitle,
                        translatorSelectionReason = selection.reason,
                        forceReprocess = job.forceReprocess,
                        mangaId = job.mangaId,
                    ) { phase, current, total, message ->
                        if (translationManager.isAbortRequested(chapterId)) {
                            throw UserCancelledException()
                        }
                        translationManager.report(chapterId, phase, current, total, message)
                        val percent = translationManager.progress.value[chapterId]?.percent ?: 0
                        updateNotificationThrottled(job.chapterName, percent, translationManager.pendingQueueSize)
                    }

                    // Best-effort CBZ pack and promote
                    runCatching { translationManager.storage.packChapterCbz(chapterId, pageStreams.size) }
                    translationManager.storage.promoteCompletedVersion(chapterId)

                    if (!translationManager.storage.isChapterTranslationComplete(chapterId)) {
                        logcat(LogPriority.WARN) { "Chapter $chapterId translation ended incomplete" }
                        translationManager.finish(chapterId, "Translation incomplete")
                        writeBenchmarkStatus(chapterId, "FAILED", "Translation incomplete")
                    } else {
                        logcat(LogPriority.INFO) { "Chapter $chapterId translation completed successfully" }
                        translationManager.finish(chapterId, null)
                        writeBenchmarkStatus(chapterId, "SUCCESS")
                    }
                } finally {
                    detectorAndOcr.close()
                    translator.close()
                    masker.close()
                    inpainter.close()
                    typesetter.close()
                }
            } finally {
                resolvedPages.cleanup()
            }
        } catch (e: UserCancelledException) {
            if (translationManager.isAbortRequested(chapterId)) {
                logcat(LogPriority.INFO) { "Chapter $chapterId cancelled by user" }
                translationManager.onJobCancelled(chapterId)
                writeBenchmarkStatus(chapterId, "CANCELLED", "Cancelled by user")
            } else {
                // Bug 3 fix: if a stray cancellation occurred without user abort request,
                // mark chapter as ERROR so it never hangs in TRANSLATING/RENDERING forever.
                logcat(LogPriority.WARN, e) { "Chapter $chapterId interrupted unexpectedly without abort request" }
                translationManager.finish(chapterId, "Translation interrupted")
                writeBenchmarkStatus(chapterId, "FAILED", "Translation interrupted")
            }
        } catch (e: CancellationException) {
            logcat(LogPriority.INFO) { "TranslationWorker coroutine cancelled while running chapter $chapterId" }
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Chapter $chapterId translation encountered an error: ${e.message}" }
            translationManager.finish(chapterId, e.message ?: "Translation failed")
            writeBenchmarkStatus(chapterId, "FAILED", e.message ?: "Translation failed")
        }
    }

    private fun updateNotificationThrottled(
        chapterName: String,
        percent: Int,
        queuedCount: Int,
        force: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        val text = if (queuedCount > 0) {
            "Translating $chapterName - $percent% (+$queuedCount queued)"
        } else {
            "Translating $chapterName - $percent%"
        }
        if (!force && text == lastNotificationText && (now - lastNotificationTime < 1000L)) {
            return
        }
        if (!force && (now - lastNotificationTime < 1000L)) {
            return
        }
        lastNotificationTime = now
        lastNotificationText = text

        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle("Offline chapter translation")
            setContentText(text)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setProgress(100, percent.coerceIn(0, 100), false)
        }.build()
        applicationContext.notify(NOTIFICATION_ID, notification)
    }

    private fun createTranslator(
        context: Context,
        assetManager: ModelAssetManager,
        engine: TranslationEngineKind,
    ): DialogueTranslator {
        val effortLevel = when (engine) {
            TranslationEngineKind.INDEX_LOW -> "LOW"
            TranslationEngineKind.INDEX_HIGH -> "HIGH"
            else -> "MID"
        }
        return LocalLlmDialogueTranslator(
            context = context,
            assetManager = assetManager,
            modelFileName = LocalLlmDialogueTranslator.INDEX_MODEL_FILE,
            modelId = when (effortLevel) {
                "LOW" -> "Index-Translate 2B Q4_K_M rev${LocalLlmDialogueTranslator.PIPELINE_REVISION} (Low Effort)"
                "HIGH" -> "Index-Translate 2B Q4_K_M rev${LocalLlmDialogueTranslator.PIPELINE_REVISION} (High Effort)"
                else -> "Index-Translate 2B Q4_K_M rev${LocalLlmDialogueTranslator.PIPELINE_REVISION} (Medium Effort)"
            },
            systemPrompt = LocalLlmDialogueTranslator.INDEX_SYSTEM_PROMPT,
            deepContext = (effortLevel == "HIGH"),
            enableContext = (effortLevel != "LOW"),
            temperature = LocalLlmDialogueTranslator.INDEX_TEMPERATURE,
            contextSize = when (effortLevel) {
                "LOW" -> 1536
                "HIGH" -> 3072
                else -> 2048
            },
        )
    }

    private fun writeBenchmarkStatus(chapterId: Long, status: String, error: String? = null) {
        if (chapterId <= 0L) return
        try {
            val json = org.json.JSONObject().apply {
                put("chapterId", chapterId)
                put("status", status)
                if (error != null) put("error", error)
                put("timestamp", System.currentTimeMillis())
            }.toString()
            val appFile = File(applicationContext.getExternalFilesDir(null), "translation_status_${chapterId}.json")
            appFile.writeText(json)
            try {
                File("/sdcard/translation_status_${chapterId}.json").writeText(json)
            } catch (_: Exception) {}
        } catch (t: Throwable) {
            android.util.Log.w("TranslationWorker", "Failed to write benchmark status: ${t.message}")
        }
    }
}
