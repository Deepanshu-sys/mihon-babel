package mihon.feature.translation.model

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Pixel-accurate mask representation for text removal.
 * Holds the binary stroke mask, resolution scaling factors, and region mappings.
 */
data class MaskSet(
    /**
     * Binary 8-bit mask bitmap where white (255) indicates pixels to inpaint/remove
     * and black (0) indicates preserved original artwork/bubble background.
     */
    val strokeMask: Bitmap,

    /**
     * Scaling transform between detection/masker coordinate space and source page resolution.
     */
    val scaleFactorX: Float = 1.0f,
    val scaleFactorY: Float = 1.0f,

    /**
     * Pixel bounds of each region on the mask bitmap, keyed by [DetectedRegion.id].
     */
    val regionPixelBounds: Map<String, Rect> = emptyMap(),

    /** Tight OCR bounds before stroke-mask dilation, keyed by [DetectedRegion.id]. */
    val regionTextPixelBounds: Map<String, Rect> = emptyMap(),

    /**
     * Mask dilation applied in mask pixels (scaled relative to detector-to-page transform).
     */
    val appliedDilationPixels: Int = 0,

    /**
     * Region IDs that actually received masking pixels in [strokeMask].
     * Regions not in this set were skipped by the masker (e.g. guard fired, empty region)
     * and must NOT have translated text rendered over the original untouched text.
     */
    val maskedRegionIds: Set<String> = emptySet(),
) : AutoCloseable {
    override fun close() {
        if (!strokeMask.isRecycled) {
            strokeMask.recycle()
        }
    }
}
