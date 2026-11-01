package mihon.feature.translation.contract

import android.graphics.Bitmap
import mihon.feature.translation.model.ChapterContext
import mihon.feature.translation.model.DetectedRegion
import mihon.feature.translation.model.OcrResult
import mihon.feature.translation.model.TranslationBatch
import mihon.feature.translation.model.TranslationResult

/**
 * Contract for OCR text recognition.
 * Implementations may wrap PP-OCRv5 Korean, MangaOCR, or other local OCR engines.
 */
interface TextRecognizer : AutoCloseable {
    /**
     * Recognizes text for the given [regions] on the [pageBitmap].
     */
    suspend fun recognize(pageBitmap: Bitmap, regions: List<DetectedRegion>): List<OcrResult>
}

/**
 * Contract for context-aware dialogue translation.
 * Implementations may wrap Qwen-0.6B via llama.cpp or HPLT Marian NMT via ONNX Runtime.
 */
interface DialogueTranslator : AutoCloseable {
    val modelId: String
        get() = "Local translation model"

    /**
     * Translates a cohesive batch of dialogue units with chapter-wide context.
     */
    suspend fun translateBatch(
        batch: TranslationBatch,
        context: ChapterContext,
    ): List<TranslationResult>
}
