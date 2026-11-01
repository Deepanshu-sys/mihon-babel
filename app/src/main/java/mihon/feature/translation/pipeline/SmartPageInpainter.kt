package mihon.feature.translation.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mihon.feature.translation.contract.PageInpainter
import mihon.feature.translation.model.DetectedRegion
import mihon.feature.translation.model.MaskSet
import mihon.feature.translation.model.RegionType
import mihon.feature.translation.model.TranslateMode
import java.nio.FloatBuffer
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cleans confidently flat speech balloons with a robust border-color estimate and
 * sends textured/free lettering through the bundled manga-specific LaMa ONNX model.
 */
class SmartPageInpainter(
    private val modelAssetManager: ModelAssetManager,
) : PageInpainter {

    private val environment = OrtEnvironment.getEnvironment()
    @Volatile private var session: OrtSession? = null

    private val maskPixelsBuf = IntArray(TILE_PIXELS)
    private val inputTensorBuf = FloatArray(4 * TILE_PIXELS)
    private val tilePatchBuf = IntArray(TILE_PIXELS)
    private val maskPatchBuf = IntArray(TILE_PIXELS)

    override suspend fun inpaint(
        sourceBitmap: Bitmap,
        maskSet: MaskSet,
        regions: List<DetectedRegion>,
    ): Bitmap {
        var cleaned: Bitmap? = null
        var flatFillCount = 0
        var lamaTileCount = 0

        try {
            cleaned = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                ?: error("Could not allocate an inpainting output bitmap")

            val nonFlatRegions = mutableListOf<Rect>()
            for (region in regions) {
                currentCoroutineContext().ensureActive()
                if (region.translateMode == TranslateMode.KEEP) continue
                val rect = maskSet.regionPixelBounds[region.id] ?: region.boundingBox.toPixels(sourceBitmap)
                if (rect.isEmpty || !containsMask(maskSet.strokeMask, rect)) continue

                val textRect = maskSet.regionTextPixelBounds[region.id] ?: rect
                val trustedContainer = region.containerBounds?.toPixels(sourceBitmap)

                val isModelBubble = region.type == RegionType.TEXT_IN_BUBBLE &&
                    trustedContainer != null &&
                    region.isModelDetectedBubble
                // Always check flat/smooth background fill BEFORE `isLargeFreeText` so multi-line FREE_TEXT on
                // short flat-black/white slices (e.g. Ch 1 P33 "우리 집의 장녀 소영 누나" on a 600px slice) is
                // cleanly erased in <1ms rather than skipped!
                val sampledColor = sampleFlatBubbleColor(sourceBitmap, textRect, if (isModelBubble) trustedContainer else null)

                if (sampledColor != null) {
                    flatFillCount++
                    fillFlatBubbleTextRegion(
                        bitmap = cleaned,
                        mask = maskSet.strokeMask,
                        textRect = textRect,
                        containerRect = trustedContainer,
                        fallbackRect = rect,
                        color = sampledColor,
                    )
                    continue
                } else if (tryGradientRowFill(cleaned, maskSet.strokeMask, rect, trustedContainer, isModelBubble)) {
                    flatFillCount++
                    continue
                }

                val isLargeFreeText = region.type == RegionType.FREE_TEXT &&
                    rect.width().toLong() * rect.height().toLong() > sourceBitmap.width.toLong() * sourceBitmap.height.toLong() * 0.15

                if (isLargeFreeText) {
                    val tightBoundsAndCount = findTightMaskBounds(maskSet.strokeMask, rect)
                    if (tightBoundsAndCount != null) {
                        val (tightRect, nonZeroCount) = tightBoundsAndCount
                        val rectArea = rect.width().toLong() * rect.height().toLong()
                        val occupancy = if (rectArea > 0) nonZeroCount.toFloat() / rectArea else 0f
                        if (occupancy <= 0.18f && !tightRect.isEmpty) {
                            nonFlatRegions.add(tightRect)
                        } else {
                            // Remove from maskedRegionIds so ComicPageTypesetter never renders English over un-erased Korean!
                            (maskSet.maskedRegionIds as? MutableSet<String>)?.remove(region.id)
                            logcat(LogPriority.INFO) {
                                "SmartInpainter: skipping large FREE_TEXT region ${region.id} with high mask occupancy ($occupancy)"
                            }
                        }
                    }
                    continue
                }

                nonFlatRegions.add(rect)
            }

            if (nonFlatRegions.isNotEmpty()) {
                val uniqueTiles = LinkedHashSet<Pair<Int, Int>>()
                for (rect in nonFlatRegions) {
                    val xTiles = tileOrigins(rect.left, rect.right, sourceBitmap.width)
                    val yTiles = tileOrigins(rect.top, rect.bottom, sourceBitmap.height)
                    for (tileTop in yTiles) {
                        for (tileLeft in xTiles) {
                            uniqueTiles.add(tileLeft to tileTop)
                        }
                    }
                }
                val model = getSession()
                for ((tileLeft, tileTop) in uniqueTiles) {
                    currentCoroutineContext().ensureActive()
                    lamaTileCount++
                    inpaintTile(
                        source = sourceBitmap,
                        output = cleaned,
                        mask = maskSet.strokeMask,
                        model = model,
                        tileLeft = tileLeft,
                        tileTop = tileTop,
                    )
                }
            }
            logcat(LogPriority.INFO) {
                "SmartInpainter: regions=${regions.size} flatFills=$flatFillCount lamaTiles=$lamaTileCount"
            }
            return cleaned
        } catch (t: Throwable) {
            cleaned?.recycle()
            throw t
        }
    }

    private fun containsMask(mask: Bitmap, rect: Rect): Boolean {
        val safeLeft = rect.left.coerceIn(0, mask.width)
        val safeRight = rect.right.coerceIn(0, mask.width)
        val safeTop = rect.top.coerceIn(0, mask.height)
        val safeBottom = rect.bottom.coerceIn(0, mask.height)
        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        if (width <= 0 || height <= 0) return false
        val row = IntArray(width)
        for (y in safeTop until safeBottom) {
            mask.getPixels(row, 0, width, safeLeft, y, width, 1)
            if (row.any { Color.alpha(it) > 0 }) return true
        }
        return false
    }

    private fun findTightMaskBounds(mask: Bitmap, rect: Rect): Pair<Rect, Int>? {
        val clampedL = rect.left.coerceIn(0, mask.width)
        val clampedT = rect.top.coerceIn(0, mask.height)
        val clampedR = rect.right.coerceIn(clampedL, mask.width)
        val clampedB = rect.bottom.coerceIn(clampedT, mask.height)
        val w = clampedR - clampedL
        val h = clampedB - clampedT
        if (w <= 0 || h <= 0) return null

        var minX = clampedR
        var maxX = clampedL - 1
        var minY = clampedB
        var maxY = clampedT - 1
        var nonZeroCount = 0

        val row = IntArray(w)
        for (y in clampedT until clampedB) {
            mask.getPixels(row, 0, w, clampedL, y, w, 1)
            for (x in 0 until w) {
                if (Color.alpha(row[x]) > 0) {
                    nonZeroCount++
                    val px = clampedL + x
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (nonZeroCount == 0 || minX > maxX || minY > maxY) return null
        return Pair(Rect(minX, minY, maxX + 1, maxY + 1), nonZeroCount)
    }

    private fun fillFlatBubbleTextRegion(
        bitmap: Bitmap,
        mask: Bitmap,
        textRect: Rect,
        containerRect: Rect?,
        fallbackRect: Rect,
        color: Int,
    ) {
        val searchRect = if (containerRect != null) {
            val clip = Rect(fallbackRect)
            if (clip.intersect(containerRect)) clip else fallbackRect
        } else {
            fallbackRect
        }
        fillOnlyMaskedPixels(bitmap, mask, searchRect, color, containerRect, textRect)
    }


    private fun fillOnlyMaskedPixels(
        bitmap: Bitmap,
        mask: Bitmap,
        rect: Rect,
        color: Int,
        containerRect: Rect? = null,
        coreTextRect: Rect? = null,
    ) {
        val maxW = min(bitmap.width, mask.width)
        val maxH = min(bitmap.height, mask.height)
        val safeLeft = rect.left.coerceIn(0, maxW)
        val safeRight = rect.right.coerceIn(0, maxW)
        val safeTop = rect.top.coerceIn(0, maxH)
        val safeBottom = rect.bottom.coerceIn(0, maxH)
        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        if (width <= 0 || height <= 0) return

        // 1. Dilate mask by 5px inside speech bubbles (to swallow JPEG/WebP ringing halos) or 3px on free text
        val dilateRadius = if (containerRect != null) 5 else 3
        val dilated = BooleanArray(width * height)
        val maskRow = IntArray(width)
        for (y in 0 until height) {
            mask.getPixels(maskRow, 0, width, safeLeft, safeTop + y, width, 1)
            for (x in 0 until width) {
                if (Color.alpha(maskRow[x]) > 0) {
                    val yMin = max(0, y - dilateRadius)
                    val yMax = min(height - 1, y + dilateRadius)
                    val xMin = max(0, x - dilateRadius)
                    val xMax = min(width - 1, x + dilateRadius)
                    for (ny in yMin..yMax) {
                        val rowOff = ny * width
                        for (nx in xMin..xMax) {
                            dilated[rowOff + nx] = true
                        }
                    }
                }
            }
        }

        // 2. Compute 4-Quadrant Bilinear Gradient Anchors (TL, TR, BL, BR) from unmasked background pixels
        // so radial glows, angled sunsets, blush vignettes, and vertical shading all blend seamlessly (<0.5ms).
        val baseR = Color.red(color)
        val baseG = Color.green(color)
        val baseB = Color.blue(color)
        val tlSamples = ArrayList<Int>(24)
        val trSamples = ArrayList<Int>(24)
        val blSamples = ArrayList<Int>(24)
        val brSamples = ArrayList<Int>(24)
        val sampleRow = IntArray(width)
        val bandH = min(8, max(2, height / 4))
        val halfW = width / 2

        for (y in 0 until bandH) {
            bitmap.getPixels(sampleRow, 0, width, safeLeft, safeTop + y, width, 1)
            for (x in 0 until width step max(1, width / 16)) {
                if (!dilated[y * width + x]) {
                    val p = sampleRow[x]
                    if (kotlin.math.abs(Color.red(p) - baseR) <= 48 &&
                        kotlin.math.abs(Color.green(p) - baseG) <= 48 &&
                        kotlin.math.abs(Color.blue(p) - baseB) <= 48
                    ) {
                        if (x < halfW) tlSamples += p else trSamples += p
                    }
                }
            }
        }
        for (y in max(0, height - bandH) until height) {
            bitmap.getPixels(sampleRow, 0, width, safeLeft, safeTop + y, width, 1)
            for (x in 0 until width step max(1, width / 16)) {
                if (!dilated[y * width + x]) {
                    val p = sampleRow[x]
                    if (kotlin.math.abs(Color.red(p) - baseR) <= 48 &&
                        kotlin.math.abs(Color.green(p) - baseG) <= 48 &&
                        kotlin.math.abs(Color.blue(p) - baseB) <= 48
                    ) {
                        if (x < halfW) blSamples += p else brSamples += p
                    }
                }
            }
        }

        val tlR = if (tlSamples.size >= 3) median(tlSamples.map(Color::red)) else baseR
        val tlG = if (tlSamples.size >= 3) median(tlSamples.map(Color::green)) else baseG
        val tlB = if (tlSamples.size >= 3) median(tlSamples.map(Color::blue)) else baseB
        val trR = if (trSamples.size >= 3) median(trSamples.map(Color::red)) else tlR
        val trG = if (trSamples.size >= 3) median(trSamples.map(Color::green)) else tlG
        val trB = if (trSamples.size >= 3) median(trSamples.map(Color::blue)) else tlB
        val blR = if (blSamples.size >= 3) median(blSamples.map(Color::red)) else baseR
        val blG = if (blSamples.size >= 3) median(blSamples.map(Color::green)) else baseG
        val blB = if (blSamples.size >= 3) median(blSamples.map(Color::blue)) else baseB
        val brR = if (brSamples.size >= 3) median(brSamples.map(Color::red)) else blR
        val brG = if (brSamples.size >= 3) median(brSamples.map(Color::green)) else blG
        val brB = if (brSamples.size >= 3) median(brSamples.map(Color::blue)) else blB

        // 3. Cleanly erase original text glyphs using per-scanline interior clamping + 2D bilinear fallback
        val insetMargin = 5
        val topInset = if (containerRect != null && containerRect.top <= 12) 0 else insetMargin
        val bottomInset = if (containerRect != null && containerRect.bottom >= maxH - 12) 0 else insetMargin
        val baseLum = (baseR * 299 + baseG * 587 + baseB * 114) / 1000
        val isLightBubble = containerRect != null && baseLum >= 160
        val outputRow = IntArray(width)
        val denomY = max(1, height - 1)
        val denomX = max(1, width - 1)
        for (y in 0 until height) {
            val py = safeTop + y
            var leftR = tlR + ((blR - tlR) * y) / denomY
            var leftG = tlG + ((blG - tlG) * y) / denomY
            var leftB = tlB + ((blB - tlB) * y) / denomY
            var rightR = trR + ((brR - trR) * y) / denomY
            var rightG = trG + ((brG - trG) * y) / denomY
            var rightB = trB + ((brB - trB) * y) / denomY

            bitmap.getPixels(outputRow, 0, width, safeLeft, py, width, 1)
            // For light speech bubbles, find the outermost bright bubble-interior pixels (`firstBrightX .. lastBrightX`)
            // on this exact horizontal scanline `py`. Never paint outside `firstBrightX .. lastBrightX` (preventing
            // square white corners poking outside oval borders), and sample `leftRGB..rightRGB` directly from unmasked
            // interior pixels on this scanline so translucent panel steps (e.g. Ch 2 P01, Ch 1 P78, P81) have zero box!
            var firstBrightX = 0
            var lastBrightX = width - 1
            if (isLightBubble) {
                firstBrightX = width
                lastBrightX = -1
                var rowLeftSample: Int? = null
                var rowRightSample: Int? = null
                for (sx in 0 until width) {
                    val sp = outputRow[sx]
                    val sr = Color.red(sp); val sg = Color.green(sp); val sb = Color.blue(sp)
                    val sLum = (sr * 299 + sg * 587 + sb * 114) / 1000
                    if (sLum >= 155 && abs(sr - baseR) <= 55 && abs(sg - baseG) <= 55 && abs(sb - baseB) <= 55) {
                        if (firstBrightX == width) firstBrightX = sx
                        lastBrightX = sx
                        if (!dilated[y * width + sx] && sLum >= 165) {
                            if (rowLeftSample == null) rowLeftSample = sp
                            rowRightSample = sp
                        }
                    }
                }
                if (rowLeftSample != null && rowRightSample != null) {
                    leftR = Color.red(rowLeftSample); leftG = Color.green(rowLeftSample); leftB = Color.blue(rowLeftSample)
                    rightR = Color.red(rowRightSample); rightG = Color.green(rowRightSample); rightB = Color.blue(rowRightSample)
                }
            }
            var changed = false
            val isFlatBlackFreeTextGutter = containerRect == null && maxOf(baseR, baseG, baseB) <= 12
            for (x in 0 until width) {
                val px = safeLeft + x
                if (containerRect != null && !containerRect.contains(px, py)) continue
                if (isLightBubble && (x <= firstBrightX || x >= lastBrightX)) continue
                val idx = y * width + x
                val pOrig = outputRow[x]
                val isMotionBlurredGlowOnBlackGutter = if (isFlatBlackFreeTextGutter && !dilated[idx]) {
                    val pr = Color.red(pOrig); val pg = Color.green(pOrig); val pb = Color.blue(pOrig)
                    maxOf(pr, pg, pb) > maxOf(baseR, baseG, baseB) + 4 && (maxOf(pr, pg, pb) - minOf(pr, pg, pb)) < 15
                } else false
                if (dilated[idx] || isMotionBlurredGlowOnBlackGutter) {
                    val pixR = leftR + ((rightR - leftR) * x) / denomX
                    val pixG = leftG + ((rightG - leftG) * x) / denomX
                    val pixB = leftB + ((rightB - leftB) * x) / denomX

                    val isMaskEdge = (x > 0 && !dilated[idx - 1]) ||
                        (x < width - 1 && !dilated[idx + 1]) ||
                        (y > 0 && !dilated[idx - width]) ||
                        (y < height - 1 && !dilated[idx + width])
                    val blendedColor = if (isMaskEdge) {
                        val orig = outputRow[x]
                        Color.rgb(
                            (pixR * 3 + Color.red(orig)) shr 2,
                            (pixG * 3 + Color.green(orig)) shr 2,
                            (pixB * 3 + Color.blue(orig)) shr 2,
                        )
                    } else {
                        Color.rgb(pixR, pixG, pixB)
                    }

                    val isNearBorder = containerRect != null && (
                        px < containerRect.left + insetMargin ||
                        px > containerRect.right - insetMargin ||
                        py < containerRect.top + topInset ||
                        py > containerRect.bottom - bottomInset
                    )
                    if (isNearBorder) {
                        val p = outputRow[x]
                        val pLum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                        if (pLum >= 110) {
                            outputRow[x] = blendedColor
                            changed = true
                        }
                    } else {
                        outputRow[x] = blendedColor
                        changed = true
                    }
                }
            }
            if (changed) bitmap.setPixels(outputRow, 0, width, safeLeft, py, width, 1)
        }
    }


    private suspend fun inpaintTile(
        source: Bitmap,
        output: Bitmap,
        mask: Bitmap,
        model: OrtSession,
        tileLeft: Int,
        tileTop: Int,
    ) {
        val validLeft = max(0, tileLeft)
        val validTop = max(0, tileTop)
        val validRight = minOf(source.width, tileLeft + TILE_SIZE)
        val validBottom = minOf(source.height, tileTop + TILE_SIZE)
        val validW = validRight - validLeft
        val validH = validBottom - validTop
        if (validW <= 0 || validH <= 0) return

        output.getPixels(tilePatchBuf, 0, validW, validLeft, validTop, validW, validH)
        mask.getPixels(maskPatchBuf, 0, validW, validLeft, validTop, validW, validH)

        var hasAnyMasked = false
        for (i in 0 until validW * validH) {
            if (Color.alpha(maskPatchBuf[i]) > 0) {
                hasAnyMasked = true
                break
            }
        }
        if (!hasAnyMasked) return

        for (tileY in 0 until TILE_SIZE) {
            val pageY = tileTop + tileY
            val clampedY = mirrorIndex(pageY, source.height)
            val inY = pageY in validTop until validBottom
            val rowStart = tileY * TILE_SIZE
            for (tileX in 0 until TILE_SIZE) {
                val pageX = tileLeft + tileX
                val inX = pageX in validLeft until validRight
                val index = rowStart + tileX
                val insideValid = inX && inY
                val patchIdx = if (insideValid) (pageY - validTop) * validW + (pageX - validLeft) else -1
                val maskVal = if (insideValid) Color.alpha(maskPatchBuf[patchIdx]) else 0
                maskPixelsBuf[index] = maskVal

                val pixel = if (insideValid) {
                    tilePatchBuf[patchIdx]
                } else {
                    source.getPixel(mirrorIndex(pageX, source.width), clampedY)
                }
                val masked = maskVal > 0
                inputTensorBuf[index] = if (masked) 0f else Color.red(pixel) / 255f
                inputTensorBuf[TILE_PIXELS + index] = if (masked) 0f else Color.green(pixel) / 255f
                inputTensorBuf[2 * TILE_PIXELS + index] = if (masked) 0f else Color.blue(pixel) / 255f
                inputTensorBuf[3 * TILE_PIXELS + index] = if (masked) 1f else 0f
            }
        }

        val inputTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputTensorBuf),
            longArrayOf(1, 4, TILE_SIZE.toLong(), TILE_SIZE.toLong()),
        )
        try {
            val inputName = model.inputNames.firstOrNull() ?: error("LaMa model has no input")
            val outputName = model.outputNames.firstOrNull() ?: error("LaMa model has no output")
            model.run(mapOf(inputName to inputTensor)).use { result ->
                currentCoroutineContext().ensureActive()
                val tensor = result.get(outputName).orElseThrow() as? OnnxTensor
                    ?: error("LaMa model did not return an output tensor")
                val values = tensor.floatBuffer

                for (localY in 0 until validH) {
                    val tileY = (validTop + localY) - tileTop
                    for (localX in 0 until validW) {
                        val tileX = (validLeft + localX) - tileLeft
                        val index = tileY * TILE_SIZE + tileX
                        if (maskPixelsBuf[index] == 0) continue

                        val red = (values.get(index).coerceIn(0f, 1f) * 255f).roundToInt()
                        val green = (values.get(TILE_PIXELS + index).coerceIn(0f, 1f) * 255f).roundToInt()
                        val blue = (values.get(2 * TILE_PIXELS + index).coerceIn(0f, 1f) * 255f).roundToInt()

                        // 2px boundary feathering against original pixel
                        val isBoundary = (tileX > 0 && maskPixelsBuf[index - 1] == 0) ||
                            (tileX < TILE_SIZE - 1 && maskPixelsBuf[index + 1] == 0) ||
                            (tileY > 0 && maskPixelsBuf[index - TILE_SIZE] == 0) ||
                            (tileY < TILE_SIZE - 1 && maskPixelsBuf[index + TILE_SIZE] == 0)

                        val patchIdx = localY * validW + localX
                        tilePatchBuf[patchIdx] = if (isBoundary) {
                            val orig = tilePatchBuf[patchIdx]
                            Color.rgb(
                                (red * 3 + Color.red(orig)) shr 2,
                                (green * 3 + Color.green(orig)) shr 2,
                                (blue * 3 + Color.blue(orig)) shr 2,
                            )
                        } else {
                            Color.rgb(red, green, blue)
                        }
                    }
                }
                output.setPixels(tilePatchBuf, 0, validW, validLeft, validTop, validW, validH)
            }
        } finally {
            inputTensor.close()
        }
    }

    /** Returns a median border color only when its RGB spread is low enough to be flat or a smooth gradient. */
    private fun sampleFlatBubbleColor(bitmap: Bitmap, rect: Rect, containerRect: Rect? = null): Int? {
        val ring = (max(rect.width(), rect.height()) / 50).coerceIn(3, 12)
        val step = max(1, ring / 3)
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val right = rect.right.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val bottom = rect.bottom.coerceIn(0, bitmap.height - 1)

        fun collectRingSamples(clipRect: Rect?): ArrayList<Int> {
            val out = ArrayList<Int>()
            for (distance in 1..ring) {
                val topY = top - distance
                val bottomY = bottom + distance
                for (x in left - ring..right + ring step step) {
                    if (topY in 0 until bitmap.height && x in 0 until bitmap.width) {
                        if (clipRect == null || clipRect.contains(x, topY)) out += bitmap.getPixel(x, topY)
                    }
                    if (bottomY in 0 until bitmap.height && x in 0 until bitmap.width) {
                        if (clipRect == null || clipRect.contains(x, bottomY)) out += bitmap.getPixel(x, bottomY)
                    }
                }
                val leftX = left - distance
                val rightX = right + distance
                for (y in top until bottom step step) {
                    if (leftX in 0 until bitmap.width && y in 0 until bitmap.height) {
                        if (clipRect == null || clipRect.contains(leftX, y)) out += bitmap.getPixel(leftX, y)
                    }
                    if (rightX in 0 until bitmap.width && y in 0 until bitmap.height) {
                        if (clipRect == null || clipRect.contains(rightX, y)) out += bitmap.getPixel(rightX, y)
                    }
                }
            }
            return out
        }

        // If `rect` tightly fills `containerRect` (e.g. Ch 2 P00, P02, P16), clipping the outer ring to `containerRect`
        // yields 0 samples; fall back to unclipped border sampling around `rect` so narration boxes heal in <1ms.
        var samples = collectRingSamples(containerRect)
        if (samples.size < 16 && containerRect != null) {
            samples = collectRingSamples(null)
        }
        if (samples.size < 8) return null

        val veryLightSamples = samples.filter { pixel ->
            val lum = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            lum >= 185
        }
        val midToneDotSamples = samples.count { pixel ->
            val lum = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            lum in 49..165
        }
        // Screentone / Halftone Dot Guard: if border ring has both bright white (>= 40%) and halftone gray dots (>= 15%),
        // reject flat fill so LaMa preserves the screentone pattern instead of bleaching it!
        if (veryLightSamples.size >= (samples.size * 4) / 10 && midToneDotSamples >= (samples.size * 15) / 100) {
            return null
        }
        // Restrict `veryDarkSamples` to true neutral dark/black backgrounds (lum <= 28 and max RGB channel <= 42)
        // so colored artwork like purple smoke tendrils (Blue = 85..135 on Ch 1 P80/P82 & Ch 2 P00/P02) is routed
        // to LaMa ONNX FP16 instead of getting smeared with a flat black bilinear patch!
        val veryDarkSamples = samples.filter { pixel ->
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            lum <= 28 && maxOf(r, g, b) <= 42
        }
        // For unboxed `FREE_TEXT` (`containerRect == null`) over dark backgrounds, verify that the interior of `rect`
        // does not cross colored artwork/smoke tendrils (e.g. purple smoke on Ch 1 P80 & Ch 2 P00 where the outer border
        // ring happens to land on black pixels while a purple smoke tendril passes through the interior of `rect`).
        val hasColoredInteriorOnDarkFreeText = if (containerRect == null && veryDarkSamples.size >= (samples.size * 70) / 100) {
            var coloredOrMidInterior = 0
            var totalInterior = 0
            val stepX = max(1, (right - left) / 10)
            val stepY = max(1, (bottom - top) / 6)
            for (iy in top..bottom step stepY) {
                for (ix in left..right step stepX) {
                    val p = bitmap.getPixel(ix, iy)
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    // Ignore bright white narration glyph strokes (`lum >= 170`); check background/smoke pixels (`lum < 170`).
                    // IMPORTANT: Check color chroma (`maxRGB - minRGB >= 15`), NOT `maxRGB > 38`, because neutral gray
                    // anti-aliased edges of white letters on a flat `[0, 0, 0]` black gutter have `r≈g≈b in 40..169` (`chroma <= 5`)!
                    if (lum < 170) {
                        totalInterior++
                        if ((maxOf(r, g, b) - minOf(r, g, b)) >= 15) {
                            coloredOrMidInterior++
                        }
                    }
                }
            }
            totalInterior >= 6 && coloredOrMidInterior >= max(2, (totalInterior * 6) / 100)
        } else false
        if (hasColoredInteriorOnDarkFreeText) return null

        val targetSamples = when {
            veryLightSamples.size >= (samples.size * 6) / 10 && veryLightSamples.size >= 8 -> veryLightSamples
            veryDarkSamples.size >= (samples.size * 70) / 100 && veryDarkSamples.size >= 8 -> veryDarkSamples
            else -> {
                val lightSamples = samples.filter { pixel ->
                    val lum = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                    lum >= 110
                }
                if (lightSamples.size >= 8) lightSamples else samples
            }
        }
        if (targetSamples.size < 8) return null

        fun channelValues(channel: (Int) -> Int) = targetSamples.map(channel)
        val reds = channelValues(Color::red)
        val greens = channelValues(Color::green)
        val blues = channelValues(Color::blue)
        // Use trimmed stdDev only inside confirmed speech bubble containers; use strict untrimmed stdDev on FREE_TEXT
        // so textured background artwork, hair strands, and colored smoke reliably trigger LaMa ONNX FP16.
        val maxStdDev = if (containerRect != null) {
            maxOf(trimmedStandardDeviation(reds), trimmedStandardDeviation(greens), trimmedStandardDeviation(blues))
        } else {
            maxOf(standardDeviation(reds), standardDeviation(greens), standardDeviation(blues))
        }
        val isSmoothBubble = containerRect != null &&
            midToneDotSamples <= (samples.size * 5) / 100 && (
                veryLightSamples.size >= (samples.size * 65) / 100 ||
                    veryDarkSamples.size >= (samples.size * 75) / 100
                )
        val isSmoothFreeTextGutter = containerRect == null &&
            midToneDotSamples <= (samples.size * 5) / 100 && (
                veryLightSamples.size >= (samples.size * 80) / 100 ||
                    veryDarkSamples.size >= (samples.size * 80) / 100
                )
        val allowedStdDev = when {
            isSmoothBubble -> SMOOTH_GRADIENT_BUBBLE_MAX_STD_DEV
            isSmoothFreeTextGutter -> 14.0
            else -> FLAT_BACKGROUND_MAX_STD_DEV
        }
        if (maxStdDev > allowedStdDev) return null

        return Color.rgb(median(reds), median(greens), median(blues))
    }

    private fun trimmedStandardDeviation(values: List<Int>): Double {
        if (values.size < 8) return standardDeviation(values)
        val sorted = values.sorted()
        val trim = sorted.size / 6
        val core = sorted.subList(trim, sorted.size - trim)
        return standardDeviation(core)
    }

    private fun sampleDominantBorderColor(bitmap: Bitmap, rect: Rect): Int {
        val samples = ArrayList<Int>(64)
        val insetX = max(1, rect.width() / 8)
        val insetY = max(1, rect.height() / 8)

        val left = (rect.left - insetX).coerceIn(0, bitmap.width - 1)
        val right = (rect.right + insetX).coerceIn(0, bitmap.width - 1)
        val top = (rect.top - insetY).coerceIn(0, bitmap.height - 1)
        val bottom = (rect.bottom + insetY).coerceIn(0, bitmap.height - 1)

        val stepX = max(1, (right - left) / 16)
        val stepY = max(1, (bottom - top) / 16)

        for (x in left..right step stepX) {
            samples += bitmap.getPixel(x, top)
            samples += bitmap.getPixel(x, bottom)
        }
        for (y in top..bottom step stepY) {
            samples += bitmap.getPixel(left, y)
            samples += bitmap.getPixel(right, y)
        }

        if (samples.isEmpty()) return Color.WHITE
        val reds = samples.map(Color::red).sorted()
        val greens = samples.map(Color::green).sorted()
        val blues = samples.map(Color::blue).sorted()
        val mid = samples.size / 2
        return Color.rgb(reds[mid], greens[mid], blues[mid])
    }

    private fun isBubbleInteriorLight(bitmap: Bitmap, rect: Rect): Boolean {
        var lightCount = 0
        var total = 0
        val step = max(1, min(rect.width(), rect.height()) / 4)
        for (y in rect.top..rect.bottom step step) {
            for (x in rect.left..rect.right step step) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val p = bitmap.getPixel(x, y)
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    if (lum > 200) lightCount++
                    total++
                }
            }
        }
        return total > 0 && (lightCount.toFloat() / total) > 0.40f
    }

    private fun tileOrigins(start: Int, end: Int, pageSize: Int): List<Int> {
        val safeStart = start.coerceIn(0, pageSize)
        val safeEnd = end.coerceIn(safeStart, pageSize)
        val desiredStart = safeStart - CONTEXT_PAD
        val desiredEnd = safeEnd + CONTEXT_PAD
        val maxOrigin = (pageSize - TILE_SIZE).coerceAtLeast(0)
        val first = desiredStart.coerceIn(0, maxOrigin)
        val last = (desiredEnd - TILE_SIZE).coerceIn(0, maxOrigin)
        if (last <= first) return listOf(first)

        val starts = mutableListOf(first)
        while (starts.last() < last) {
            val next = (starts.last() + TILE_STRIDE).coerceAtMost(last)
            if (next == starts.last()) break
            starts += next
        }
        return starts
    }

    private fun mirrorIndex(index: Int, size: Int): Int {
        if (size <= 1) return 0
        val period = 2 * size - 2
        val wrapped = ((index % period) + period) % period
        return if (wrapped < size) wrapped else period - wrapped
    }

    private fun standardDeviation(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 255
        return values.sorted()[values.size / 2]
    }

    private fun android.graphics.RectF.toPixels(bitmap: Bitmap): Rect = Rect(
        (left * bitmap.width).toInt().coerceIn(0, bitmap.width),
        (top * bitmap.height).toInt().coerceIn(0, bitmap.height),
        (right * bitmap.width).toInt().coerceIn(0, bitmap.width),
        (bottom * bitmap.height).toInt().coerceIn(0, bitmap.height),
    )

    @Synchronized
    private fun getSession(): OrtSession {
        session?.let { return it }
        modelAssetManager.ensureModelsExtracted("lama-manga-fp16.onnx").getOrThrow()
        val modelFile = modelAssetManager.getModelFile("lama-manga-fp16.onnx")
        val newSession = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(true)
            options.setCPUArenaAllocator(true)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Phase-Major Unload closes the session immediately after Phase 3, so enable intra-op spinning
            // during active tile bursts to eliminate futex_wake latency across 120+ LaMa conv barriers on Cortex-A715.
            runCatching {
                options.addConfigEntry("session.intra_op.allow_spinning", "1")
            }
            environment.createSession(modelFile.absolutePath, options)
        }
        session = newSession
        return newSession
    }

    private fun tryGradientRowFill(
        bitmap: Bitmap,
        mask: Bitmap,
        rect: Rect,
        containerRect: Rect?,
        isModelDetectedBubble: Boolean,
    ): Boolean {
        // Invariant 1: Never gradient-fill free text or heuristic non-model containers
        if (!isModelDetectedBubble || containerRect == null) return false

        val maxW = min(bitmap.width, mask.width)
        val maxH = min(bitmap.height, mask.height)
        val clip = Rect(rect)
        if (!clip.intersect(containerRect)) return false

        val safeLeft = clip.left.coerceIn(0, maxW)
        val safeRight = clip.right.coerceIn(safeLeft, maxW)
        val safeTop = clip.top.coerceIn(0, maxH)
        val safeBottom = clip.bottom.coerceIn(safeTop, maxH)
        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        if (width < 12 || height < 12) return false

        // 2D 3px dilation of mask (swallows both horizontal and vertical glyph halos)
        val dilateRadius = 3
        val dilated = BooleanArray(width * height)
        val mskRow = IntArray(width)
        for (y in 0 until height) {
            mask.getPixels(mskRow, 0, width, safeLeft, safeTop + y, width, 1)
            for (x in 0 until width) {
                if (Color.alpha(mskRow[x]) > 0) {
                    val yMin = max(0, y - dilateRadius)
                    val yMax = min(height - 1, y + dilateRadius)
                    val xMin = max(0, x - dilateRadius)
                    val xMax = min(width - 1, x + dilateRadius)
                    for (ny in yMin..yMax) {
                        val rowOffset = ny * width
                        for (nx in xMin..xMax) {
                            dilated[rowOffset + nx] = true
                        }
                    }
                }
            }
        }

        val rowR = IntArray(height)
        val rowG = IntArray(height)
        val rowB = IntArray(height)
        val bmpRow = IntArray(width)
        val insetMargin = 6

        for (y in 0 until height) {
            val py = safeTop + y
            bitmap.getPixels(bmpRow, 0, width, safeLeft, py, width, 1)

            var count = 0
            var rSum = 0; var gSum = 0; var bSum = 0
            var rMin = 255; var rMax = 0
            var gMin = 255; var gMax = 0
            var bMin = 255; var bMax = 0

            // Invariant 2: Contiguous 1px stride (step = 1) to never alias over screentone dots
            for (x in 0 until width) {
                val px = safeLeft + x
                val nearBubbleBorder = px < containerRect.left + insetMargin ||
                    px > containerRect.right - insetMargin ||
                    py < containerRect.top + insetMargin ||
                    py > containerRect.bottom - insetMargin
                if (nearBubbleBorder || dilated[y * width + x]) continue

                val p = bmpRow[x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                // Skip isolated dark pixels (unmasked glyph specks) instead of aborting the whole row fill
                if (lum < 155) continue

                rSum += r; gSum += g; bSum += b
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
                count++
            }

            if (count < 8) return false

            // Invariant 3: Peak-to-Peak Range Gate (catches single screentone dots & subtle texture)
            if ((rMax - rMin) > 16 || (gMax - gMin) > 16 || (bMax - bMin) > 16) {
                return false
            }

            rowR[y] = rSum / count
            rowG[y] = gSum / count
            rowB[y] = bSum / count

            // Invariant 4: Vertical first-derivative continuity (max 3 RGB step per scanline)
            if (y > 0) {
                if (kotlin.math.abs(rowR[y] - rowR[y - 1]) > 3 ||
                    kotlin.math.abs(rowG[y] - rowG[y - 1]) > 3 ||
                    kotlin.math.abs(rowB[y] - rowB[y - 1]) > 3
                ) {
                    return false
                }
            }
        }

        // Smooth vertical profile with a 3-tap box filter and fill only dilated mask pixels
        for (y in 0 until height) {
            val py = safeTop + y
            val yPrev = max(0, y - 1)
            val yNext = min(height - 1, y + 1)
            val fillColor = Color.rgb(
                (rowR[yPrev] + rowR[y] + rowR[yNext]) / 3,
                (rowG[yPrev] + rowG[y] + rowG[yNext]) / 3,
                (rowB[yPrev] + rowB[y] + rowB[yNext]) / 3,
            )

            bitmap.getPixels(bmpRow, 0, width, safeLeft, py, width, 1)
            var changed = false
            for (x in 0 until width) {
                val px = safeLeft + x
                if (!containerRect.contains(px, py)) continue
                if (dilated[y * width + x]) {
                    val isNearBorder = px < containerRect.left + insetMargin ||
                        px > containerRect.right - insetMargin ||
                        py < containerRect.top + insetMargin ||
                        py > containerRect.bottom - insetMargin
                    if (isNearBorder) {
                        val p = bmpRow[x]
                        val pLum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                        if (pLum >= 120) {
                            bmpRow[x] = fillColor
                            changed = true
                        }
                    } else {
                        bmpRow[x] = fillColor
                        changed = true
                    }
                }
            }
            if (changed) bitmap.setPixels(bmpRow, 0, width, safeLeft, py, width, 1)
        }
        return true
    }

    override fun close() {
        session?.close()
        session = null
    }

    private fun findSpeechBubbleBounds(bitmap: Bitmap, textRect: Rect): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        val left = textRect.left.coerceIn(0, w - 1)
        val top = textRect.top.coerceIn(0, h - 1)
        val right = textRect.right.coerceIn(0, w - 1)
        val bottom = textRect.bottom.coerceIn(0, h - 1)
        val textWidth = maxOf(1, right - left)
        val textHeight = maxOf(1, bottom - top)

        val maxSearchX = (w * 0.45f).toInt()
        val maxSearchY = (h * 0.35f).toInt()

        fun isBorderOrDark(x: Int, y: Int): Boolean {
            if (x <= 1 || x >= w - 2 || y <= 1 || y >= h - 2) return true
            val p = bitmap.getPixel(x, y)
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            return lum < 105
        }

        val xSteps = listOf(
            left + textWidth / 4,
            left + textWidth / 2,
            left + 3 * textWidth / 4,
        ).filter { it in 0 until w }

        val ySteps = listOf(
            top + textHeight / 4,
            top + textHeight / 2,
            top + 3 * textHeight / 4,
        ).filter { it in 0 until h }

        var foundTop = top
        for (x in xSteps) {
            var y = top
            var passedLight = 0
            while (y > 0 && y > top - maxSearchY) {
                if (isBorderOrDark(x, y)) {
                    if (passedLight > 4) break
                } else {
                    passedLight++
                }
                y--
            }
            if (y < foundTop && passedLight > 4) foundTop = y
        }

        var foundBottom = bottom
        for (x in xSteps) {
            var y = bottom
            var passedLight = 0
            while (y < h - 1 && y < bottom + maxSearchY) {
                if (isBorderOrDark(x, y)) {
                    if (passedLight > 4) break
                } else {
                    passedLight++
                }
                y++
            }
            if (y > foundBottom && passedLight > 4) foundBottom = y
        }

        var foundLeft = left
        for (y in ySteps) {
            var x = left
            var passedLight = 0
            while (x > 0 && x > left - maxSearchX) {
                if (isBorderOrDark(x, y)) {
                    if (passedLight > 4) break
                } else {
                    passedLight++
                }
                x--
            }
            if (x < foundLeft && passedLight > 4) foundLeft = x
        }

        var foundRight = right
        for (y in ySteps) {
            var x = right
            var passedLight = 0
            while (x < w - 1 && x < right + maxSearchX) {
                if (isBorderOrDark(x, y)) {
                    if (passedLight > 4) break
                } else {
                    passedLight++
                }
                x++
            }
            if (x > foundRight && passedLight > 4) foundRight = x
        }

        val bubbleW = foundRight - foundLeft
        val bubbleH = foundBottom - foundTop
        if (bubbleW >= textWidth * 1.1f && bubbleH >= textHeight * 1.1f && bubbleW <= w * 0.95f && bubbleH <= h * 0.5f) {
            return Rect(foundLeft, foundTop, foundRight, foundBottom)
        }
        return null
    }

    companion object {
        private const val TILE_SIZE = 512
        private const val TILE_PIXELS = TILE_SIZE * TILE_SIZE
        private const val CONTEXT_PAD = 32
        private const val TILE_STRIDE = TILE_SIZE - 2 * CONTEXT_PAD
        private const val FLAT_BACKGROUND_MAX_STD_DEV = 7.5
        private const val SMOOTH_GRADIENT_BUBBLE_MAX_STD_DEV = 24.0
    }
}