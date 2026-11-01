package mihon.feature.translation.model

import android.graphics.RectF

/**
 * Text recognition result for an individual line inside a region.
 */
data class OcrLine(
    val text: String,
    val confidence: Float,
    val lineBounds: RectF? = null,
)

/**
 * Full OCR result associated with a [DetectedRegion].
 */
data class OcrResult(
    val regionId: String,
    val text: String,
    val confidence: Float,
    val languageCode: String = "ko",
    val lines: List<OcrLine> = emptyList(),
)
