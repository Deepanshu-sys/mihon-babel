package mihon.feature.translation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.feature.translation.model.ChapterTranslationManifest
import mihon.feature.translation.model.TranslationPhase
import mihon.feature.translation.pipeline.LocalLlmRuntime
import mihon.feature.translation.pipeline.LocalTranslationStorage
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class TranslationJob(
    val chapterId: Long,
    val chapterName: String,
    val chapterUrl: String,
    val mangaTitle: String,
    val scanlator: String? = null,
    val sourceId: Long,
    val forceReprocess: Boolean = false,
    val maxPages: Int = 0,
    val mangaId: Long? = null,
)

class UserCancelledException(message: String = "Cancelled by user") : Exception(message)

data class TranslationProgress(
    val chapterId: Long,
    val state: ChapterTranslationState,
    val current: Int = 0,
    val total: Int = 0,
    val percent: Int = 0,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
)

enum class ChapterTranslationState {
    NOT_TRANSLATED,
    QUEUED,
    DOWNLOADING,
    SCANNING,
    TRANSLATING,
    RENDERING,
    TRANSLATED,
    ERROR;

    val isActive: Boolean
        get() = this == QUEUED || this == DOWNLOADING || this == SCANNING || this == TRANSLATING || this == RENDERING
}

@Inject
@SingleIn(AppScope::class)
class TranslationManager(
    private val context: Context,
    val preferences: TranslationPreferences,
) {
    val storage = LocalTranslationStorage(context)
    private val workManager = WorkManager.getInstance(context)

    private val queueFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "translations/queue.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queueLock = Any()
    private var initialStateLoaded = false
    @Volatile
    private var activeJob: TranslationJob? = null
    private val pendingJobs = mutableListOf<TranslationJob>()
    private val abortedChapterIds = ConcurrentHashMap.newKeySet<Long>()
    private val translatedChapterIds = ConcurrentHashMap.newKeySet<Long>()

    private val _progress = MutableStateFlow<Map<Long, TranslationProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, TranslationProgress>> = _progress.asStateFlow()

    val pendingQueueSize: Int
        get() = synchronized(queueLock) { pendingJobs.size }

    init {
        scope.launch(Dispatchers.IO) {
            loadInitialState()
        }
    }

    private fun loadInitialState() {
        val diskTranslatedIds = mutableSetOf<Long>()
        val baseDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "translations")
        if (baseDir.isDirectory) {
            val chapterDirs = baseDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (dir in chapterDirs) {
                val chapterId = dir.name.toLongOrNull() ?: continue
                if (storage.isChapterTranslationCompleteSync(chapterId)) {
                    diskTranslatedIds.add(chapterId)
                }
            }
        }

        var shouldTriggerDrain = false
        synchronized(queueLock) {
            if (initialStateLoaded) return
            initialStateLoaded = true

            translatedChapterIds.addAll(diskTranslatedIds)

            val savedJobs = runCatching {
                if (queueFile.isFile && queueFile.length() > 0L) {
                    json.decodeFromString<List<TranslationJob>>(queueFile.readText())
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())

            val existingIds = pendingJobs.map { it.chapterId }.toSet() + setOfNotNull(activeJob?.chapterId)
            for (saved in savedJobs) {
                if (saved.chapterId !in existingIds && !abortedChapterIds.contains(saved.chapterId)) {
                    pendingJobs.add(saved)
                }
            }

            _progress.update { currentMap ->
                val merged = currentMap.toMutableMap()
                for (id in translatedChapterIds) {
                    if (!merged.containsKey(id)) {
                        merged[id] = TranslationProgress(
                            chapterId = id,
                            state = ChapterTranslationState.TRANSLATED,
                            percent = 100,
                            progress = 1f,
                            message = "Complete",
                        )
                    }
                }
                for (job in pendingJobs) {
                    if (!merged.containsKey(job.chapterId)) {
                        merged[job.chapterId] = TranslationProgress(
                            chapterId = job.chapterId,
                            state = ChapterTranslationState.QUEUED,
                            message = "Queued for translation...",
                        )
                    }
                }
                merged
            }

            shouldTriggerDrain = pendingJobs.isNotEmpty()
        }

        if (shouldTriggerDrain) {
            triggerDrainWorker()
        }
    }

    /**
     * Persists both the currently executing job (at index 0) and all pending jobs
     * so an unexpected process kill or reboot never loses the in-flight chapter job.
     */
    private fun persistQueueLocked() {
        try {
            queueFile.parentFile?.mkdirs()
            val tmpFile = File(queueFile.parentFile, "queue.json.tmp")
            val jobsToSave = buildList {
                activeJob?.let { add(it) }
                addAll(pendingJobs)
            }
            tmpFile.writeText(json.encodeToString(jobsToSave))
            LocalTranslationStorage.renameAtomic(tmpFile, queueFile)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to persist translation queue" }
        }
    }

    fun enqueue(job: TranslationJob) {
        val chapterId = job.chapterId
        synchronized(queueLock) {
            val current = _progress.value[chapterId]
            if (current != null && current.state.isActive) {
                logcat(LogPriority.INFO) { "Chapter $chapterId already active in state ${current.state}" }
                return
            }
            if (job.forceReprocess) {
                translatedChapterIds.remove(chapterId)
            } else if (translatedChapterIds.contains(chapterId)) {
                if (storage.isChapterTranslationCompleteSync(chapterId)) {
                    logcat(LogPriority.INFO) { "Chapter $chapterId is already translated" }
                    return
                }
                translatedChapterIds.remove(chapterId)
            }

            abortedChapterIds.remove(chapterId)
            pendingJobs.removeAll { it.chapterId == chapterId }
            pendingJobs.add(job)
            persistQueueLocked()

            _progress.update { map ->
                map + (chapterId to TranslationProgress(
                    chapterId = chapterId,
                    state = ChapterTranslationState.QUEUED,
                    message = "Queued for translation...",
                ))
            }
        }
        triggerDrainWorker()
    }

    private fun triggerDrainWorker() {
        val request = OneTimeWorkRequestBuilder<TranslationWorker>()
            .addTag(TAG_TRANSLATION_WORK)
            .build()
        workManager.enqueueUniqueWork(
            TRANSLATION_DRAIN_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun cancel(chapterId: Long) {
        var shouldCancelRunningLlm = false
        synchronized(queueLock) {
            val removedFromPending = pendingJobs.removeAll { it.chapterId == chapterId }
            val isCurrentlyRunning = activeJob?.chapterId == chapterId
            if (removedFromPending || isCurrentlyRunning) {
                abortedChapterIds.add(chapterId)
            }
            if (isCurrentlyRunning) {
                activeJob = null
                shouldCancelRunningLlm = true
            }
            if (removedFromPending || isCurrentlyRunning) {
                persistQueueLocked()
            }
        }
        // Only signal native LLM abort if this exact chapter is the one currently running!
        if (shouldCancelRunningLlm) {
            LocalLlmRuntime.cancelActive()
        }

        _progress.update { map ->
            if (isTranslated(chapterId)) {
                map + (chapterId to TranslationProgress(
                    chapterId = chapterId,
                    state = ChapterTranslationState.TRANSLATED,
                    percent = 100,
                    progress = 1f,
                    message = "Complete",
                ))
            } else {
                map - chapterId
            }
        }
    }

    fun delete(chapterId: Long) {
        cancel(chapterId)
        translatedChapterIds.remove(chapterId)
        _progress.update { it - chapterId }
        scope.launch(Dispatchers.IO) {
            try {
                storage.deleteChapterTranslation(chapterId)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to delete translation for chapter $chapterId" }
            }
            translatedChapterIds.remove(chapterId)
            _progress.update { it - chapterId }
        }
    }

    fun cancelAll() {
        synchronized(queueLock) {
            activeJob?.let { abortedChapterIds.add(it.chapterId) }
            activeJob = null
            for (job in pendingJobs) {
                abortedChapterIds.add(job.chapterId)
            }
            pendingJobs.clear()
            persistQueueLocked()
        }
        LocalLlmRuntime.cancelActive()
        workManager.cancelUniqueWork(TRANSLATION_DRAIN_NAME)
        workManager.cancelAllWorkByTag(TAG_TRANSLATION_WORK)
        _progress.update { map ->
            map.filterValues { it.state == ChapterTranslationState.TRANSLATED }
        }
    }

    /**
     * Fast O(1) in-memory check safe for Compose main-thread recomposition in MangaScreen.
     */
    fun isTranslated(chapterId: Long): Boolean = translatedChapterIds.contains(chapterId)

    internal fun nextJob(): TranslationJob? {
        synchronized(queueLock) {
            while (pendingJobs.isNotEmpty()) {
                val job = pendingJobs.removeAt(0)
                if (abortedChapterIds.remove(job.chapterId)) {
                    persistQueueLocked()
                    _progress.update { map ->
                        if (isTranslated(job.chapterId)) map else (map - job.chapterId)
                    }
                    continue
                }
                activeJob = job
                persistQueueLocked()
                return job
            }
            activeJob = null
            persistQueueLocked()
            return null
        }
    }

    internal fun isAbortRequested(chapterId: Long): Boolean = abortedChapterIds.contains(chapterId)

    internal fun onJobCancelled(chapterId: Long) {
        synchronized(queueLock) {
            if (activeJob?.chapterId == chapterId) {
                activeJob = null
                persistQueueLocked()
            }
            abortedChapterIds.remove(chapterId)
        }
        _progress.update { map ->
            if (isTranslated(chapterId)) {
                map + (chapterId to TranslationProgress(
                    chapterId = chapterId,
                    state = ChapterTranslationState.TRANSLATED,
                    percent = 100,
                    progress = 1f,
                    message = "Complete",
                ))
            } else {
                map - chapterId
            }
        }
    }

    internal fun report(
        chapterId: Long,
        phase: TranslationPhase,
        current: Int,
        total: Int,
        message: String,
    ) {
        if (abortedChapterIds.contains(chapterId)) return
        val unitProgress = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
        val baseAndSpan = when (phase) {
            TranslationPhase.DOWNLOADING -> 0 to 5
            TranslationPhase.SCANNING -> 5 to 25     // 5% -> 30%
            TranslationPhase.TRANSLATING -> 30 to 30 // 30% -> 60%
            TranslationPhase.RENDERING -> 60 to 38   // 60% -> 98%
            TranslationPhase.FINALIZING -> 98 to 2   // 98% -> 100%
        }
        val computedPercent = (baseAndSpan.first + (unitProgress * baseAndSpan.second).toInt()).coerceIn(0, 100)
        val previousPercent = _progress.value[chapterId]?.percent ?: 0
        val monotonicPercent = maxOf(previousPercent, computedPercent)

        val state = when (phase) {
            TranslationPhase.DOWNLOADING -> ChapterTranslationState.DOWNLOADING
            TranslationPhase.SCANNING -> ChapterTranslationState.SCANNING
            TranslationPhase.TRANSLATING -> ChapterTranslationState.TRANSLATING
            TranslationPhase.RENDERING -> ChapterTranslationState.RENDERING
            TranslationPhase.FINALIZING -> ChapterTranslationState.RENDERING
        }

        _progress.update { map ->
            map + (chapterId to TranslationProgress(
                chapterId = chapterId,
                state = state,
                current = current,
                total = total,
                percent = monotonicPercent,
                progress = monotonicPercent / 100f,
                message = message,
            ))
        }
    }

    internal fun finish(chapterId: Long, error: String?) {
        synchronized(queueLock) {
            if (activeJob?.chapterId == chapterId) {
                activeJob = null
                persistQueueLocked()
            }
            abortedChapterIds.remove(chapterId)
        }
        if (error != null) {
            _progress.update { map ->
                map + (chapterId to TranslationProgress(
                    chapterId = chapterId,
                    state = ChapterTranslationState.ERROR,
                    percent = 0,
                    progress = 0f,
                    message = error,
                    error = error,
                ))
            }
        } else {
            translatedChapterIds.add(chapterId)
            _progress.update { map ->
                map + (chapterId to TranslationProgress(
                    chapterId = chapterId,
                    state = ChapterTranslationState.TRANSLATED,
                    percent = 100,
                    progress = 1f,
                    message = "Complete",
                ))
            }
        }
    }

    fun translateChapter(
        chapterId: Long,
        chapterName: String,
        chapterUrl: String,
        mangaTitle: String,
        scanlator: String?,
        sourceId: Long,
        forceReprocess: Boolean = false,
        maxPages: Int = 0,
        mangaId: Long? = null,
    ) {
        enqueue(
            TranslationJob(
                chapterId = chapterId,
                chapterName = chapterName,
                chapterUrl = chapterUrl,
                mangaTitle = mangaTitle,
                scanlator = scanlator,
                sourceId = sourceId,
                forceReprocess = forceReprocess,
                maxPages = maxPages,
                mangaId = mangaId,
            ),
        )
    }

    fun cancelTranslation(chapterId: Long) = cancel(chapterId)
    fun deleteTranslation(chapterId: Long) = delete(chapterId)

    fun isChapterTranslatedSync(chapterId: Long): Boolean = isTranslated(chapterId)
    suspend fun isChapterTranslated(chapterId: Long): Boolean = isTranslated(chapterId)

    fun getPageStreamSync(chapterId: Long, pageIndex: Int): InputStream? = storage.getPageStreamSync(chapterId, pageIndex)
    suspend fun getPageStream(chapterId: Long, pageIndex: Int): InputStream? = storage.getPageStream(chapterId, pageIndex)
    suspend fun getManifest(chapterId: Long): ChapterTranslationManifest? = storage.getManifest(chapterId)

    fun getTranslatedPageStreamOrNull(chapterId: Long, pageIndex: Int): InputStream? {
        if (!isTranslated(chapterId)) return null
        return storage.getPageStreamSync(chapterId, pageIndex)
    }

    fun getChapterTranslationState(chapterId: Long): ChapterTranslationState {
        return _progress.value[chapterId]?.state ?: if (isTranslated(chapterId)) ChapterTranslationState.TRANSLATED else ChapterTranslationState.NOT_TRANSLATED
    }

    fun getTranslationProgressFlow(chapterId: Long): Flow<TranslationProgress> {
        return progress.map { map ->
            map[chapterId] ?: TranslationProgress(
                chapterId = chapterId,
                state = if (isTranslated(chapterId)) ChapterTranslationState.TRANSLATED else ChapterTranslationState.NOT_TRANSLATED,
                percent = if (isTranslated(chapterId)) 100 else 0,
                progress = if (isTranslated(chapterId)) 1f else 0f,
            )
        }
    }

    companion object {
        const val TRANSLATION_DRAIN_NAME = "MihonAT_Translation_Drain"
        const val TAG_TRANSLATION_WORK = "translation_work"
    }
}
