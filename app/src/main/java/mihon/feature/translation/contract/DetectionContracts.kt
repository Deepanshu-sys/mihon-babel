package mihon.feature.translation.contract

import android.graphics.Bitmap
import mihon.feature.translation.model.DetectedRegion
import mihon.feature.translation.model.MaskSet

/**
 * Contract for detecting comic text lines, speech bubbles, and free text.
 * Implementations may wrap RT-DETR-v2, DBNet, or Comic Text Detector.
 */
interface ComicDetector : AutoCloseable {
    /**
     * Detects regions on the given [pageBitmap].
     * Coordinates in the returned [DetectedRegion] list are normalized to [0, 1].
     */
    suspend fun detect(pageBitmap: Bitmap): List<DetectedRegion>
}

/**
 * Contract for generating pixel-level stroke masks from detected regions.
 * Kept strictly separate from geometric bounding box detection.
 */
interface TextMasker : AutoCloseable {
    /**
     * Generates a [MaskSet] indicating exact pixel strokes to be removed.
     */
    suspend fun generateMask(pageBitmap: Bitmap, regions: List<DetectedRegion>): MaskSet
}
