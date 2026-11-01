package mihon.feature.translation.model

import android.graphics.PointF
import android.graphics.RectF

/**
 * High-level semantic classification of a detected comic region.
 */
enum class RegionType {
    /** Text block detected without a separate bubble or artwork classifier. */
    UNKNOWN_TEXT,
    /** A speech or thought bubble container */
    BUBBLE,
    /** Text situated inside a bubble */
    TEXT_IN_BUBBLE,
    /** Free-floating text directly over artwork or screentone (e.g. SFX, narration, caption) */
    FREE_TEXT,
}

/**
 * Rendering intent for a detected region, used by Pass 2 to decide whether to translate+typeset,
 * erase only (for a seam-split half that donated its text to the other half), or leave untouched.
 *
 * TRANSLATE   – erase source lettering and typeset the translated English text (default).
 * ERASE_ONLY  – erase source lettering but do NOT typeset; used for the smaller half of a
 *               boundary-split bubble whose text was merged into the larger half on the adjacent page.
 * KEEP        – do not erase or typeset; used by the prop-art filter for confirmed English prop text.
 */
enum class TranslateMode {
    TRANSLATE,
    ERASE_ONLY,
    KEEP,
}

/**
 * Geometric and structural representation of a detected region on a comic page.
 * Coordinates are normalized to [0, 1] relative to the original page dimensions.
 */
data class DetectedRegion(
    val id: String,
    val type: RegionType,
    val boundingBox: RectF,
    /** Larger balloon/text container used for lettering; [boundingBox] remains the OCR glyph bounds. */
    val containerBounds: RectF? = null,
    val polygon: List<PointF> = emptyList(),
    val confidence: Float = 1.0f,
    val readingOrder: Int = 0,
    val parentRegionId: String? = null,
    val rotationDegrees: Float = 0f,
    val isModelDetectedBubble: Boolean = false,
    /** Whether the region background is darker than the text (white-on-dark). Used by the masker
     *  to apply an appropriate guard against inverting non-text dark art. */
    val isInvertedBackground: Boolean = false,
    /** Controls how Pass 2 processes this region. Default is TRANSLATE. */
    val translateMode: TranslateMode = TranslateMode.TRANSLATE,
)
