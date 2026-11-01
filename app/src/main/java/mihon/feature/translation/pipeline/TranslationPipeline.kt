package mihon.feature.translation.pipeline

import android.graphics.Bitmap
import android.os.Debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import mihon.feature.translation.contract.ComicDetector
import mihon.feature.translation.contract.DialogueTranslator
import mihon.feature.translation.contract.PageInpainter
import mihon.feature.translation.contract.PageTypesetter
import mihon.feature.translation.contract.TextMasker
import mihon.feature.translation.contract.TextRecognizer
import mihon.feature.translation.contract.TranslationStorage
import mihon.feature.translation.model.ChapterContext
import mihon.feature.translation.model.ChapterTranslationManifest
import mihon.feature.translation.model.DialogueUnit
import mihon.feature.translation.model.PageDiagnostics
import mihon.feature.translation.model.PageStatus
import mihon.feature.translation.model.RegionRecord
import mihon.feature.translation.model.TranslationBatch
import kotlin.coroutines.coroutineContext

/**
 * Stage listener for reporting fine-grained progress during chapter processing.
 */
fun interface PipelineProgressListener {
    fun onProgress(pageIndex: Int, totalPages: Int, stageDescription: String, stagePercent: Float)
}

/**
 * Orchestrator for processing comic chapter pages through detection, OCR, translation,
 * inpainting, and typesetting. All model backends are independently pluggable.
 */
class TranslationPipeline(
    private val detector: ComicDetector,
    private val masker: TextMasker,
    private val recognizer: TextRecognizer,
    private val translator: DialogueTranslator,
    private val inpainter: PageInpainter,
    private val typesetter: PageTypesetter,
    private val storage: TranslationStorage,
) {

    /**
     * Translates a single page image through the full pipeline.
     * Intermediate resources and masks are safely closed.
     */
    suspend fun processPage(
        pageBitmap: Bitmap,
        pageIndex: Int,
        totalPages: Int,
        chapterContext: ChapterContext,
        progressListener: PipelineProgressListener? = null,
    ): Result<PageDiagnostics> {
        val startTime = System.currentTimeMillis()
        var detDuration = 0L
        var ocrDuration = 0L
        var transDuration = 0L
        var inpaintDuration = 0L
        var typesetDuration = 0L

        return try {
            coroutineContext.ensureActive()

            // 1. Detection
            progressListener?.onProgress(pageIndex, totalPages, "Detecting text & speech bubbles", 0.1f)
            val detStart = System.currentTimeMillis()
            val detectedRegions = detector.detect(pageBitmap)
            detDuration = System.currentTimeMillis() - detStart
            coroutineContext.ensureActive()

            if (detectedRegions.isEmpty()) {
                // Page has no text/bubbles; save original as-is
                storage.savePageImage(chapterContext.chapterId, pageIndex, pageBitmap).getOrThrow()
                val totalTime = System.currentTimeMillis() - startTime
                return Result.success(
                    PageDiagnostics(
                        pageIndex = pageIndex,
                        detectionDurationMs = detDuration,
                        totalPageDurationMs = totalTime,
                        regionCount = 0,
                    ),
                )
            }

            // 2. Text Mask Generation (Separate contract)
            progressListener?.onProgress(pageIndex, totalPages, "Generating stroke masks", 0.25f)
            val maskSet = masker.generateMask(pageBitmap, detectedRegions)

            // 3. OCR Recognition
            progressListener?.onProgress(pageIndex, totalPages, "Recognizing Korean text", 0.4f)
            val ocrStart = System.currentTimeMillis()
            val ocrResults = recognizer.recognize(pageBitmap, detectedRegions)
            ocrDuration = System.currentTimeMillis() - ocrStart
            coroutineContext.ensureActive()

            val avgConfidence = if (ocrResults.isNotEmpty()) {
                ocrResults.map { it.confidence }.average().toFloat()
            } else {
                0f
            }

            // 4. Batch Translation with Chapter Context
            progressListener?.onProgress(pageIndex, totalPages, "Translating dialogue", 0.6f)
            val transStart = System.currentTimeMillis()
            val dialogues = ocrResults.mapIndexed { index, ocr ->
                DialogueUnit(
                    regionId = ocr.regionId,
                    pageIndex = pageIndex,
                    readingOrder = index,
                    sourceText = ocr.text,
                )
            }
            val batch = TranslationBatch(pageIndex = pageIndex, dialogues = dialogues)
            val translationResults = translator.translateBatch(batch, chapterContext)
            transDuration = System.currentTimeMillis() - transStart
            coroutineContext.ensureActive()

            // 5. Inpainting / Background Healing
            progressListener?.onProgress(pageIndex, totalPages, "Healing background & erasing strokes", 0.75f)
            val inpaintStart = System.currentTimeMillis()
            val cleanedBitmap = maskSet.use {
                inpainter.inpaint(pageBitmap, maskSet, detectedRegions)
            }
            inpaintDuration = System.currentTimeMillis() - inpaintStart
            coroutineContext.ensureActive()

            // 6. Typesetting & Typography
            progressListener?.onProgress(pageIndex, totalPages, "Typesetting translated dialogue", 0.9f)
            val typesetStart = System.currentTimeMillis()
            val (typesetBitmap, layoutResults) = typesetter.typeset(
                cleanedBitmap = cleanedBitmap,
                regions = detectedRegions,
                translations = translationResults,
            )
            typesetDuration = System.currentTimeMillis() - typesetStart
            coroutineContext.ensureActive()

            // Clean up intermediate cleaned bitmap if a new one was allocated
            if (cleanedBitmap != typesetBitmap && !cleanedBitmap.isRecycled) {
                cleanedBitmap.recycle()
            }

            // 7. Save Final Rendered Page
            storage.savePageImage(chapterContext.chapterId, pageIndex, typesetBitmap).getOrThrow()
            if (!typesetBitmap.isRecycled && typesetBitmap != pageBitmap) {
                typesetBitmap.recycle()
            }

            val totalTime = System.currentTimeMillis() - startTime
            val javaHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val nativeHeap = Debug.getNativeHeapAllocatedSize()
            val anyClamped = layoutResults.any { it.wasClampedToMinSize }

            Result.success(
                PageDiagnostics(
                    pageIndex = pageIndex,
                    detectionDurationMs = detDuration,
                    ocrDurationMs = ocrDuration,
                    translationDurationMs = transDuration,
                    inpaintingDurationMs = inpaintDuration,
                    typesettingDurationMs = typesetDuration,
                    totalPageDurationMs = totalTime,
                    javaHeapAllocatedBytes = javaHeap,
                    nativeHeapAllocatedBytes = nativeHeap,
                    regionCount = detectedRegions.size,
                    averageOcrConfidence = avgConfidence,
                    wasClampedToMinFontSize = anyClamped,
                ),
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            val totalTime = System.currentTimeMillis() - startTime
            Result.failure(e)
        }
    }
}
