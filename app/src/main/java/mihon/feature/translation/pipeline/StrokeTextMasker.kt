package mihon.feature.translation.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import mihon.feature.translation.contract.TextMasker
import mihon.feature.translation.model.DetectedRegion
import mihon.feature.translation.model.MaskSet
import mihon.feature.translation.model.RegionType
import mihon.feature.translation.model.TranslateMode
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Builds an adaptive local-contrast mask around OCR glyphs instead of masking whole text boxes. */
class StrokeTextMasker : TextMasker {

    override suspend fun generateMask(
        pageBitmap: Bitmap,
        regions: List<DetectedRegion>,
    ): MaskSet {
        val width = pageBitmap.width
        val height = pageBitmap.height
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val expandedBounds = mutableMapOf<String, Rect>()
        val textBounds = mutableMapOf<String, Rect>()
        val maskedRegionIds = mutableSetOf<String>()
        val dilation = (min(width, height) / 250).coerceIn(3, 5)
        val searchPad = (min(width, height) / 180).coerceIn(4, 7)

        try {
            for (region in regions) {
                currentCoroutineContext().ensureActive()
                if (region.translateMode == TranslateMode.KEEP) continue
                val core = Rect(
                    ((region.boundingBox.left * width).toInt() - searchPad).coerceIn(0, width),
                    ((region.boundingBox.top * height).toInt() - searchPad).coerceIn(0, height),
                    (ceil(region.boundingBox.right * width).toInt() + searchPad).coerceIn(0, width),
                    (ceil(region.boundingBox.bottom * height).toInt() + searchPad).coerceIn(0, height),
                )
                // IMPORTANT: Do NOT union `core` with a rectangular `safeInner` box derived from `containerBounds`!
                // Speech bubbles are ovals/ellipses inside a rectangular `containerBounds`, so the 4 corners of a
                // rectangular `safeInner` poke outside the curved oval border and erase chunks of the bubble outline,
                // adjacent SFX (Ch 1 P70), or overlapping watermarks (Ch 1 P40, P76; Ch 2 P10, P46, P76).
                if (core.width() < 2 || core.height() < 2) continue

                val windowRadius = (min(core.width(), core.height()) / 4).coerceIn(3, 15)
                val analysisPad = windowRadius * 2
                val analysis = Rect(
                    (core.left - analysisPad).coerceAtLeast(0),
                    (core.top - analysisPad).coerceAtLeast(0),
                    (core.right + analysisPad).coerceAtMost(width),
                    (core.bottom + analysisPad).coerceAtMost(height),
                )
                val analysisWidth = analysis.width()
                val analysisHeight = analysis.height()
                val analysisArea = analysisWidth.toLong() * analysisHeight
                val container = if (region.type == RegionType.TEXT_IN_BUBBLE && region.containerBounds != null) {
                    val cb = region.containerBounds
                    val inset = 5 // 5px inset to protect curved speech bubble stroke outlines
                    // When a speech bubble is cut by the top or bottom page slice seam (`cb.top <= 0.008f` on Ch 1 P07
                    // or `cb.bottom >= 0.992f` on Ch 1 P42 / Ch 2 P76), there is no bubble border at the page seam—use 0px inset!
                    val topInset = if (cb.top <= 0.008f) 0 else inset
                    val bottomInset = if (cb.bottom >= 0.992f) 0 else inset
                    val cLeft = ((cb.left * width).toInt() + inset).coerceIn(0, width)
                    val cTop = ((cb.top * height).toInt() + topInset).coerceIn(0, height)
                    val cRight = (ceil(cb.right * width).toInt() - inset).coerceIn(0, width)
                    val cBottom = (ceil(cb.bottom * height).toInt() - bottomInset).coerceIn(0, height)
                    if (cRight > cLeft && cBottom > cTop) Rect(cLeft, cTop, cRight, cBottom) else null
                } else null

                val expanded = Rect(
                    (core.left - dilation).coerceAtLeast(0),
                    (core.top - dilation).coerceAtLeast(0),
                    (core.right + dilation).coerceAtMost(width),
                    (core.bottom + dilation).coerceAtMost(height),
                ).apply {
                    if (container != null) intersect(container)
                }
                val clippedCore = Rect(core).apply {
                    if (container != null) intersect(container)
                }
                expandedBounds[region.id] = expanded
                textBounds[region.id] = clippedCore

                // A pathological full-page OCR box is safer left untouched than used to erase art.
                if (analysisArea > MAX_ANALYSIS_PIXELS) continue

                val gray = ByteArray(analysisArea.toInt())
                val row = IntArray(analysisWidth)
                val integralStride = analysisWidth + 1
                val integral = IntArray((analysisWidth + 1) * (analysisHeight + 1))
                // For non-inverted speech bubbles (`TEXT_IN_BUBBLE`), record the leftmost and rightmost bright
                // bubble-interior pixel (`lum >= 165`) on each horizontal scanline of `analysis`. Any dark pixel outside
                // `(rowFirstBright[localY] + 1) until rowLastBright[localY]` sits on or outside the curved oval border!
                val isLightBubble = region.type == RegionType.TEXT_IN_BUBBLE && !region.isInvertedBackground
                val rowFirstBright = IntArray(analysisHeight) { analysisWidth }
                val rowLastBright = IntArray(analysisHeight) { -1 }
                for (y in 0 until analysisHeight) {
                    if (y % 16 == 0) currentCoroutineContext().ensureActive()
                    pageBitmap.getPixels(row, 0, analysisWidth, analysis.left, analysis.top + y, analysisWidth, 1)
                    var rowSum = 0
                    val grayOffset = y * analysisWidth
                    val integralRow = (y + 1) * integralStride
                    val integralAbove = y * integralStride
                    var firstB = analysisWidth
                    var lastB = -1
                    for (x in 0 until analysisWidth) {
                        val pixel = row[x]
                        val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                        gray[grayOffset + x] = luminance.toByte()
                        if (luminance >= 165) {
                            if (firstB == analysisWidth) firstB = x
                            lastB = x
                        }
                        rowSum += luminance
                        integral[integralRow + x + 1] = integral[integralAbove + x + 1] + rowSum
                    }
                    rowFirstBright[y] = firstB
                    rowLastBright[y] = lastB
                }

                val expandedMask = IntArray(expanded.width() * expanded.height())
                val expandedWidth = expanded.width()
                maskBitmap.getPixels(
                    expandedMask,
                    0,
                    expandedWidth,
                    expanded.left,
                    expanded.top,
                    expandedWidth,
                    expanded.height(),
                )
                var foundStroke = false
                var strokeCount = 0
                var unclippedStrokes = 0
                var clippedStrokes = 0
                val coreArea = max(1, core.width() * core.height())
                for (pageY in core.top until core.bottom) {
                    if ((pageY - core.top) % 16 == 0) currentCoroutineContext().ensureActive()
                    val localY = pageY - analysis.top
                    val top = (localY - windowRadius).coerceAtLeast(0)
                    val bottom = (localY + windowRadius + 1).coerceAtMost(analysisHeight)
                    val firstB = rowFirstBright[localY]
                    val lastB = rowLastBright[localY]
                    for (pageX in core.left until core.right) {
                        val localX = pageX - analysis.left
                        // Inside a light speech bubble, never mask pixels on or outside the curved oval border!
                        if (isLightBubble && (localX <= firstB || localX >= lastB)) continue
                        val left = (localX - windowRadius).coerceAtLeast(0)
                        val right = (localX + windowRadius + 1).coerceAtMost(analysisWidth)
                        val sum = rectangleSum(integral, integralStride, left, top, right, bottom)
                        val mean = sum.toFloat() / ((right - left) * (bottom - top)).coerceAtLeast(1)
                        val luminance = gray[localY * analysisWidth + localX].toInt() and 0xFF
                        val minContrast = if (region.type == RegionType.TEXT_IN_BUBBLE) MIN_BUBBLE_LOCAL_CONTRAST else MIN_LOCAL_CONTRAST
                        val threshold = max(minContrast, abs(mean - 128f) * BACKGROUND_ADAPTATION)

                        // Polarity-aware stroke detection:
                        // 1. Inside a light speech bubble (`isLightBubble`), text strokes are strictly DARKER than the
                        //    bright bubble interior (`mean >= 140f` and `(mean - luminance) >= threshold` and `luminance < 210`).
                        //    Never flip to `isDarkBg` in dark exterior corners outside the oval (`mean < 115f`), and never
                        //    mask bright white `254` panel steps behind translucent `233` bubbles (e.g. Ch 2 P01, Ch 1 P78, P81)!
                        // 2. For inverted/dark backgrounds, text strokes are brighter than local mean (`(luminance - mean) >= threshold`).
                        val isStroke = if (isLightBubble) {
                            mean >= 140f && luminance < 215 && (mean - luminance) >= threshold
                        } else {
                            val isDarkBg = region.isInvertedBackground || mean < 115f
                            if (isDarkBg) {
                                (luminance - mean) >= threshold
                            } else if (mean > 225f) {
                                (mean - luminance) >= threshold
                            } else {
                                abs(luminance - mean) >= threshold
                            }
                        }
                        if (!isStroke) continue

                        unclippedStrokes++
                        if (container != null && (pageX !in container.left until container.right || pageY !in container.top until container.bottom)) {
                            clippedStrokes++
                            continue
                        }

                        foundStroke = true
                        strokeCount++
                        for (dy in -dilation..dilation) {
                            val y = pageY + dy
                            if (y !in expanded.top until expanded.bottom) continue
                            if (container != null && (y !in container.top until container.bottom)) continue
                            val ly = y - analysis.top
                            val rFirstB = if (ly in 0 until analysisHeight) rowFirstBright[ly] else 0
                            val rLastB = if (ly in 0 until analysisHeight) rowLastBright[ly] else analysisWidth - 1
                            for (dx in -dilation..dilation) {
                                val x = pageX + dx
                                if (x in expanded.left until expanded.right) {
                                    if (container != null && (x !in container.left until container.right)) continue
                                    val lx = x - analysis.left
                                    if (isLightBubble && (lx <= rFirstB || lx >= rLastB)) continue
                                    expandedMask[(y - expanded.top) * expandedWidth + (x - expanded.left)] = OPAQUE_WHITE
                                }
                            }
                        }
                    }
                }

                if (unclippedStrokes > 0 && (clippedStrokes.toFloat() / unclippedStrokes) > 0.50f) {
                    logcat(LogPriority.WARN) {
                        "StrokeTextMasker: container clipping removed >50% strokes (${clippedStrokes}/${unclippedStrokes}) for region ${region.id}"
                    }
                }

                if (foundStroke) {
                    val totalExpandedPixels = max(1, expandedWidth * expanded.height())
                    var maskedPixels = 0
                    for (k in 0 until totalExpandedPixels) {
                        if (expandedMask[k] == OPAQUE_WHITE) maskedPixels++
                    }
                    val maskRatio = maskedPixels.toFloat() / totalExpandedPixels
                    val strokeRatio = strokeCount.toFloat() / coreArea

                    // Safety guard: if a large FREE_TEXT region's mask covers an implausibly large share of its area
                    // over complex mid-tone artwork, skip erasing it to protect background artwork.
                    // Do NOT skip when the region sits on a uniform flat black (`overallMean <= 45f`) or uniform flat white
                    // (`overallMean >= 215f`) gutter (e.g. Ch 1 P33 "우리 집의 장녀 소영 누나" on a 600px slice)!
                    if (maskRatio > 0.70f || strokeRatio > 0.50f) {
                        val totalAnalysisSum = rectangleSum(integral, integralStride, 0, 0, analysisWidth, analysisHeight)
                        val overallMean = totalAnalysisSum.toFloat() / max(1, analysisWidth * analysisHeight)
                        val isUniformFlatGutter = overallMean <= 45f || overallMean >= 215f
                        val isLargeFreeText = !isUniformFlatGutter &&
                            region.type == RegionType.FREE_TEXT &&
                            core.width().toLong() * core.height().toLong() > width.toLong() * height.toLong() * 0.15
                        if (isLargeFreeText) {
                            logcat(LogPriority.INFO) {
                                "StrokeTextMasker: skipping large FREE_TEXT region ${region.id} with high mask/stroke ratio to protect artwork"
                            }
                            continue
                        }
                    }
                    maskBitmap.setPixels(
                        expandedMask,
                        0,
                        expandedWidth,
                        expanded.left,
                        expanded.top,
                        expandedWidth,
                        expanded.height(),
                    )
                    maskedRegionIds.add(region.id)
                } else if (region.type == RegionType.TEXT_IN_BUBBLE) {
                    // Stroke detector found no clear contrast strokes, but this is a model-detected bubble.
                    // Mask the core text box (clamped inside the bright oval interior) so the bubble flat fill cleanly removes the Korean text.
                    for (pageY in core.top until core.bottom) {
                        val ly = pageY - analysis.top
                        val rFirstB = if (ly in 0 until analysisHeight) rowFirstBright[ly] else 0
                        val rLastB = if (ly in 0 until analysisHeight) rowLastBright[ly] else analysisWidth - 1
                        val rowOffset = (pageY - expanded.top) * expandedWidth
                        for (pageX in core.left until core.right) {
                            val lx = pageX - analysis.left
                            if (isLightBubble && (lx <= rFirstB || lx >= rLastB)) continue
                            val idx = rowOffset + (pageX - expanded.left)
                            if (idx in expandedMask.indices) {
                                expandedMask[idx] = OPAQUE_WHITE
                            }
                        }
                    }
                    maskBitmap.setPixels(
                        expandedMask,
                        0,
                        expandedWidth,
                        expanded.left,
                        expanded.top,
                        expandedWidth,
                        expanded.height(),
                    )
                    maskedRegionIds.add(region.id)
                    logcat(LogPriority.INFO) {
                        "StrokeTextMasker: region ${region.id} in bubble had no stroke contrast; applied fallback rect mask for bubble erase"
                    }
                }
            }
        } catch (error: Throwable) {
            if (!maskBitmap.isRecycled) maskBitmap.recycle()
            throw error
        }

        return MaskSet(
            strokeMask = maskBitmap,
            scaleFactorX = 1.0f,
            scaleFactorY = 1.0f,
            regionPixelBounds = expandedBounds,
            regionTextPixelBounds = textBounds,
            appliedDilationPixels = dilation,
            maskedRegionIds = maskedRegionIds,
        )
    }

    override fun close() = Unit

    private fun rectangleSum(integral: IntArray, stride: Int, left: Int, top: Int, right: Int, bottom: Int): Int =
        integral[bottom * stride + right] - integral[top * stride + right] -
            integral[bottom * stride + left] + integral[top * stride + left]

    private companion object {
        const val MIN_LOCAL_CONTRAST = 12f
        const val MIN_BUBBLE_LOCAL_CONTRAST = 8f
        const val BACKGROUND_ADAPTATION = 0.10f
        const val MAX_ANALYSIS_PIXELS = 8_000_000L
        const val OPAQUE_WHITE = -1
    }
}
