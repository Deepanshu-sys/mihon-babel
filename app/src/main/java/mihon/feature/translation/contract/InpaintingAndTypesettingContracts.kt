package mihon.feature.translation.contract

import android.graphics.Bitmap
import mihon.feature.translation.model.DetectedRegion
import mihon.feature.translation.model.MaskSet
import mihon.feature.translation.model.TranslationResult

/**
 * Metadata output from typesetting a region.
 */
data class TypesettingResult(
    val regionId: String,
    val fontSizeSp: Float,
    val wasClampedToMinSize: Boolean,
    val didOverflowBounds: Boolean,
)

/**
 * Contract for background cleanup and text removal.
 * Implementations may decide between fast boundary color sampling for flat bubbles
 * and learned inpainting (AOT-GAN / MI-GAN) for textured/artwork regions.
 */
interface PageInpainter : AutoCloseable {
    /**
     * Erases text strokes using [maskSet] while preserving speech bubble borders and underlying art.
     * Returns a new cleaned [Bitmap]. The caller is responsible for recycling [sourceBitmap] when appropriate.
     */
    suspend fun inpaint(
        sourceBitmap: Bitmap,
        maskSet: MaskSet,
        regions: List<DetectedRegion>,
    ): Bitmap
}

/**
 * Contract for typography and lettering.
 * Evaluates shape-aware line wrapping, font fitting, and drawing directly onto the page canvas.
 */
interface PageTypesetter : AutoCloseable {
    /**
     * Typesets [translations] into the [cleanedBitmap] according to [regions] shape and bounds.
     * Returns the finalized page [Bitmap] along with layout diagnostics for each region.
     */
    suspend fun typeset(
        cleanedBitmap: Bitmap,
        regions: List<DetectedRegion>,
        translations: List<TranslationResult>,
    ): Pair<Bitmap, List<TypesettingResult>>
}
