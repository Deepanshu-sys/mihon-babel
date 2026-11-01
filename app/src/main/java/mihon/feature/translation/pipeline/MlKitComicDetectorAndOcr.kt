package mihon.feature.translation.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import logcat.LogPriority
import mihon.feature.translation.contract.ComicDetector
import mihon.feature.translation.contract.TextRecognizer
import mihon.feature.translation.model.DetectedRegion
import mihon.feature.translation.model.OcrLine
import mihon.feature.translation.model.OcrResult
import mihon.feature.translation.model.RegionType
import tachiyomi.core.common.util.system.logcat
import java.util.UUID
import java.util.Collections
import java.util.WeakHashMap

/**
 * On-device Korean comic text detector and OCR recognizer powered by Google ML Kit Korean.
 * Runs completely offline using bundled native weights.
 */
class MlKitComicDetectorAndOcr(
    private var bubbleDetector: OnnxComicBubbleDetector? = null,
    context: android.content.Context? = null,
) : ComicDetector, TextRecognizer {

    init {
        context?.applicationContext?.let { ctx ->
            try {
                com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ctx)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Failed to initialize MlKitContext explicitly: ${e.message}" }
            }
        }
    }

    val detectorModelId: String = "ogkalu/comic-text-and-bubble-detector detector-v4-s-int8"
    val ocrModelId: String = "Google ML Kit Korean Text Recognition (on-device)"

    private val recognizer by lazy {
        TextRecognition.getClient(
            KoreanTextRecognizerOptions.Builder().build(),
        )
    }

    enum class CandidateAssessment {
        OCR_COVERED,
        CROP_RECOVERED,
        VERIFIED_EMPTY,
        UNRESOLVED_NO_TEXT,
        SCAN_ERROR,
    }

    data class CandidateOutcome(
        val candidateId: String,
        val type: RegionType,
        val bounds: RectF,
        val assessment: CandidateAssessment,
        val error: String? = null,
    )

    data class ScanMetadata(
        val pairs: List<Pair<DetectedRegion, OcrResult>>,
        val candidateCount: Int,
        val sliceCount: Int,
        val cropOcrCount: Int,
        val mergedObservationsCount: Int,
        val candidateOutcomes: List<CandidateOutcome> = emptyList(),
        val unresolvedCandidateCount: Int = 0,
        val unresolvedCandidateIds: List<String> = emptyList(),
        val failedSliceCount: Int = 0,
    )

    enum class BlockSource { FULL_PAGE, SLICE, CANDIDATE_CROP }

    override suspend fun detect(pageBitmap: Bitmap): List<DetectedRegion> {
        return scan(pageBitmap).pairs.map { it.first }
    }

    override suspend fun recognize(
        pageBitmap: Bitmap,
        regions: List<DetectedRegion>,
    ): List<OcrResult> {
        return scan(pageBitmap).pairs.map { it.second }
    }

    /** Runs Korean OCR once and derives both text boxes and recognition results from that scan. */
    suspend fun analyze(pageBitmap: Bitmap): List<Pair<DetectedRegion, OcrResult>> = scan(pageBitmap).pairs

    suspend fun analyzeWithMetadata(pageBitmap: Bitmap): ScanMetadata = scan(pageBitmap)

    private suspend fun scan(pageBitmap: Bitmap): ScanMetadata {
        if (pageBitmap.isRecycled) {
            logcat(LogPriority.WARN) { "Translation scan: pageBitmap is already recycled; returning empty" }
            return ScanMetadata(emptyList(), 0, 0, 0, 0)
        }
        logcat(LogPriority.INFO) {
            "Translation scan: detector begin size=${pageBitmap.width}x${pageBitmap.height}"
        }
        val rawDetectorCandidates = bubbleDetector?.detect(pageBitmap).orEmpty()
        // Pre-filter detector candidates that sit strictly inside the bottom-right aggregator stamp corner
        // (`x >= 0.72, y >= 0.87, w <= 0.26`) or form a thin horizontal watermark ribbon (`h <= 0.032, w >= 0.35`)
        // so Phase B never wastes Crop-OCR calls on piracy watermarks (e.g. Ch 1 P22, P72; Ch 2 P54, P72, P77).
        val bubbleCandidates = rawDetectorCandidates.filterNot { cand ->
            val b = cand.bounds
            val isBottomRightBunnyStamp = b.left >= 0.72f && b.top >= 0.87f && b.width() <= 0.26f
            val isHorizontalWatermarkRibbon = b.height() <= 0.032f && b.width() >= 0.35f &&
                (b.top <= 0.05f || b.bottom >= 0.95f || (b.top in 0.83f..0.865f && b.right >= 0.94f))
            isBottomRightBunnyStamp || isHorizontalWatermarkRibbon
        }
        logcat(LogPriority.INFO) { "Translation scan: detector complete candidates=${bubbleCandidates.size} (raw=${rawDetectorCandidates.size})" }
        val width = pageBitmap.width.toFloat()
        val height = pageBitmap.height.toFloat()

        data class RawBlock(
            val text: String,
            val bounds: RectF,
            val cornerPoints: List<PointF>,
            val lines: List<OcrLine>,
            val confidence: Float,
            val rotation: Float,
            val source: BlockSource = BlockSource.FULL_PAGE,
            val sliceIndex: Int = -1,
            val candidateId: String? = null,
            val isInvertedBackground: Boolean = false,
        )

        val rawBlocks = mutableListOf<RawBlock>()
        var cropOcrCount = 0
        var mergedObservationsCount = 0

        data class SliceSpan(val top: Int, val bottom: Int)

        val sliceSpans = if (pageBitmap.height <= 2500) {
            listOf(SliceSpan(0, pageBitmap.height))
        } else {
            buildList {
                var currentTop = 0
                val totalH = pageBitmap.height
                val rowBuffer = IntArray(pageBitmap.width)
                while (currentTop < totalH) {
                    if (totalH - currentTop <= 2200) {
                        add(SliceSpan(currentTop, totalH))
                        break
                    }

                    // Windowed gutter search in [currentTop + 1600, currentTop + 2048]
                    val searchStart = minOf(currentTop + 1600, totalH - 1)
                    val searchEnd = minOf(currentTop + 2048, totalH)
                    var bestGutterCenter = -1
                    var consecutiveFlatRows = 0
                    var gutterBandStart = -1

                    val sampleStep = maxOf(4, pageBitmap.width / 135)
                    for (y in searchStart until searchEnd) {
                        pageBitmap.getPixels(rowBuffer, 0, pageBitmap.width, 0, y, pageBitmap.width, 1)
                        var minR = 255; var maxR = 0
                        var minG = 255; var maxG = 0
                        var minB = 255; var maxB = 0
                        for (x in 0 until pageBitmap.width step sampleStep) {
                            val p = rowBuffer[x]
                            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                            if (r < minR) minR = r; if (r > maxR) maxR = r
                            if (g < minG) minG = g; if (g > maxG) maxG = g
                            if (b < minB) minB = b; if (b > maxB) maxB = b
                        }
                        val isRowUniform = (maxR - minR <= 5) && (maxG - minG <= 5) && (maxB - minB <= 5)
                        if (isRowUniform) {
                            if (consecutiveFlatRows == 0) gutterBandStart = y
                            consecutiveFlatRows++
                            if (consecutiveFlatRows >= 12) {
                                bestGutterCenter = gutterBandStart + consecutiveFlatRows / 2
                                break
                            }
                        } else {
                            consecutiveFlatRows = 0
                            gutterBandStart = -1
                        }
                    }

                    if (bestGutterCenter > 0) {
                        // Case A: Clean horizontal gutter found. Slice cleanly right through center with 0px overlap!
                        add(SliceSpan(currentTop, bestGutterCenter))
                        currentTop = bestGutterCenter
                    } else {
                        // Case B: Continuous art / action sequence. Slice at 1850px with 384px overlap fallback.
                        val sliceEnd = minOf(currentTop + 1850, totalH)
                        add(SliceSpan(currentTop, sliceEnd))
                        currentTop = maxOf(currentTop + 1, sliceEnd - 384)
                    }
                }
            }
        }
        val isSingleFullPage = sliceSpans.size == 1 && sliceSpans[0].top == 0 && pageBitmap.height <= 2500
        var failedSliceCount = 0

        for ((sliceIdx, span) in sliceSpans.withIndex()) {
            val sliceTop = span.top
            val bottom = span.bottom
            val sliceH = bottom - sliceTop
            val sliceBitmap = if (isSingleFullPage) {
                pageBitmap
            } else {
                if (pageBitmap.isRecycled) {
                    failedSliceCount++
                    continue
                }
                Bitmap.createBitmap(pageBitmap, 0, sliceTop, pageBitmap.width, sliceH)
            }

            try {
                val image = InputImage.fromBitmap(sliceBitmap, 0)
                val visionText = try {
                    Tasks.await(recognizer.process(image))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Exception) {
                    failedSliceCount++
                    logcat(LogPriority.ERROR, e) { "OCR slice failed at y=$sliceTop: ${e.message}" }
                    null
                } ?: continue

                for (block in visionText.textBlocks) {
                    val blockLines = block.lines
                    if (blockLines.isEmpty()) continue

                    // If a single ML Kit TextBlock merges lines across >= 2 genuinely disjoint model-detected BUBBLE
                    // containers (e.g. stacked yellow KakaoTalk chat bubbles on Ch 2 Page 66), split lines per bubble!
                    // IMPORTANT: Ignore duplicate overlapping YOLO BUBBLE boxes (`interArea / minArea > 0.30f`) so a
                    // single 2-line speech bubble (e.g. Ch 2 P45, Ch 2 P50) is never split into separate fragments!
                    val lineGroups: List<List<com.google.mlkit.vision.text.Text.Line>> = if (blockLines.size >= 2) {
                        val lineToBubbleCand = blockLines.map { line ->
                            val lb = line.boundingBox
                            if (lb == null) {
                                null
                            } else {
                                val cx = lb.exactCenterX() / width
                                val cy = (lb.exactCenterY() + sliceTop) / height
                                bubbleCandidates
                                    .filter { it.type == RegionType.BUBBLE && it.bounds.contains(cx, cy) }
                                    .maxByOrNull { it.bounds.width() * it.bounds.height() }
                            }
                        }
                        val distinctBubbles = mutableListOf<OnnxComicBubbleDetector.Candidate>()
                        for (cand in lineToBubbleCand.filterNotNull()) {
                            val overlapsExisting = distinctBubbles.any { existing ->
                                val inter = RectF()
                                if (inter.setIntersect(existing.bounds, cand.bounds)) {
                                    val minA = minOf(existing.bounds.width() * existing.bounds.height(), cand.bounds.width() * cand.bounds.height())
                                    minA > 0f && (inter.width() * inter.height()) / minA > 0.30f
                                } else false
                            }
                            if (!overlapsExisting) distinctBubbles += cand
                        }
                        val lineToBubble = lineToBubbleCand.map { cand ->
                            if (cand == null) null else distinctBubbles.firstOrNull { db ->
                                val inter = RectF()
                                if (inter.setIntersect(db.bounds, cand.bounds)) {
                                    val minA = minOf(db.bounds.width() * db.bounds.height(), cand.bounds.width() * cand.bounds.height())
                                    minA > 0f && (inter.width() * inter.height()) / minA > 0.30f
                                } else false
                            }?.id
                        }
                        val distinctBubbleIds = lineToBubble.filterNotNull().distinct()
                        if (distinctBubbleIds.size >= 2) {
                            val grouped = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
                            var currentGroup = mutableListOf<com.google.mlkit.vision.text.Text.Line>()
                            var currentBubbleId: String? = null
                            for ((idx, line) in blockLines.withIndex()) {
                                val bId = lineToBubble[idx]
                                if (currentGroup.isEmpty()) {
                                    currentGroup.add(line)
                                    currentBubbleId = bId
                                } else if (bId != null && currentBubbleId != null && bId != currentBubbleId) {
                                    grouped.add(currentGroup)
                                    currentGroup = mutableListOf(line)
                                    currentBubbleId = bId
                                } else {
                                    currentGroup.add(line)
                                    if (currentBubbleId == null) currentBubbleId = bId
                                }
                            }
                            if (currentGroup.isNotEmpty()) grouped.add(currentGroup)
                            grouped
                        } else {
                            listOf(blockLines)
                        }
                    } else {
                        listOf(blockLines)
                    }

                    for (groupLines in lineGroups) {
                        val cleanedGroup = cleanLinesAndElements(groupLines, sliceTop, width, height)
                        val rawText = reconstructVerticalKoreanFromLines(cleanedGroup.lines, cleanedGroup.unionRect)
                        if (rawText.isBlank()) continue
                        val text = normalizeOcrText(rawText)
                        if (text.isBlank() || (isWatermark(rawText) && isWatermark(text))) continue
                        val unionBox = cleanedGroup.unionRect ?: continue
                        val normLeft = (unionBox.left / width).coerceIn(0f, 1f)
                        val normTop = (unionBox.top / height).coerceIn(0f, 1f)
                        val normRight = (unionBox.right / width).coerceIn(0f, 1f)
                        val normBottom = (unionBox.bottom / height).coerceIn(0f, 1f)
                        if (normRight <= normLeft || normBottom <= normTop) continue
                        val normBounds = RectF(normLeft, normTop, normRight, normBottom)
                        val isInsideModelBubble = bubbleCandidates.any {
                            it.type == RegionType.BUBBLE && it.bounds.contains(normBounds.centerX(), normBounds.centerY())
                        }
                        val hangulChars = text.count { it in '\uAC00'..'\uD7A3' }
                        if (text.isBlank() || (text.length < 2 && !(isInsideModelBubble && hangulChars >= 1) && ManhwaSfxDictionary.translateSfx(text) == null)) continue
                        val wm1Reason = StringBuilder()
                        if (isWatermark(rawText, normBounds, width, height, wm1Reason) || isWatermark(text, normBounds, width, height, wm1Reason)) {
                            logcat(LogPriority.INFO) { "ScanDrop page OCR: text='${rawText.take(60)}' bounds=$normBounds rule=$wm1Reason" }
                            continue
                        }

                        val cornerPoints = listOf(
                            PointF(normLeft, normTop),
                            PointF(normRight, normTop),
                            PointF(normRight, normBottom),
                            PointF(normLeft, normBottom),
                        )
                        val lines = cleanedGroup.ocrLines
                        val confidence = lines.map { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.85f
                        val hasSpanish = Regex("(?i)[ñáéíóúü¿¡]|\\b(?:que|por|para|con|una|los|las|del|est[aá]|pero|como|más)\\b").containsMatchIn(text)
                        if (hangulChars == 0 && !hasSpanish) {
                            continue
                        }

                        if (confidence in 0.01f..0.38f && hangulChars < 2 && ManhwaSfxDictionary.translateSfx(text) == null) {
                            continue
                        }

                        val newBlock = RawBlock(
                            text = text,
                            bounds = RectF(normLeft, normTop, normRight, normBottom),
                            cornerPoints = cornerPoints,
                            lines = lines,
                            confidence = confidence,
                            rotation = groupLines.firstOrNull()?.angle ?: 0f,
                            source = if (isSingleFullPage) BlockSource.FULL_PAGE else BlockSource.SLICE,
                            sliceIndex = sliceIdx,
                        )

                        // Deduplicate against existing blocks in slice overlap zone:
                        // Require geometric overlap AND text similarity to avoid dropping distinct nearby bubbles.
                        val duplicateIdx = rawBlocks.indexOfFirst { existing ->
                            val inter = RectF()
                            if (inter.setIntersect(existing.bounds, newBlock.bounds)) {
                                val interArea = inter.width() * inter.height()
                                val minArea = minOf(existing.bounds.width() * existing.bounds.height(), newBlock.bounds.width() * newBlock.bounds.height())
                                val overlapRatio = if (minArea > 0f) interArea / minArea else 0f
                                overlapRatio >= 0.65f || (overlapRatio > 0.45f && areTextsSimilar(existing.text, newBlock.text))
                            } else false
                        }
                        if (duplicateIdx >= 0) {
                            mergedObservationsCount++
                            val existing = rawBlocks[duplicateIdx]
                            val newHangul = newBlock.text.count { it in '\uAC00'..'\uD7A3' }
                            val existHangul = existing.text.count { it in '\uAC00'..'\uD7A3' }
                            val preferNew = when {
                                newHangul >= existHangul + 2 && newBlock.confidence >= 0.42f -> true
                                existHangul >= newHangul + 2 && existing.confidence >= 0.42f -> false
                                newBlock.confidence > existing.confidence + 0.05f -> true
                                existing.confidence > newBlock.confidence + 0.05f -> false
                                else -> newBlock.text.length >= existing.text.length
                            }
                            val mergedBounds = RectF(existing.bounds).apply { union(newBlock.bounds) }
                            rawBlocks[duplicateIdx] = if (preferNew) {
                                newBlock.copy(bounds = mergedBounds)
                            } else {
                                existing.copy(bounds = mergedBounds)
                            }
                        } else {
                            rawBlocks += newBlock
                        }
                    }
                }
            } finally {
                if (sliceBitmap !== pageBitmap && !sliceBitmap.isRecycled) {
                    sliceBitmap.recycle()
                }
            }
        }

        // Phase B: Targeted Crop-OCR pass for detector candidates missed by page slicing
        val targetCandidates = bubbleCandidates.filter {
            it.type == RegionType.TEXT_IN_BUBBLE || it.type == RegionType.FREE_TEXT || it.type == RegionType.BUBBLE
        }
        val candidateOutcomes = mutableListOf<CandidateOutcome>()

        for (candidate in targetCandidates) {
            val hasCoverage = when (candidate.type) {
                RegionType.TEXT_IN_BUBBLE, RegionType.FREE_TEXT -> {
                    rawBlocks.any { block ->
                        val inter = RectF()
                        if (inter.setIntersect(block.bounds, candidate.bounds)) {
                            val interArea = inter.width() * inter.height()
                            val minArea = minOf(
                                block.bounds.width() * block.bounds.height(),
                                candidate.bounds.width() * candidate.bounds.height(),
                            ).coerceAtLeast(1e-6f)
                            val candH = candidate.bounds.height().coerceAtLeast(1e-6f)
                            val heightCoverage = inter.height() / candH
                            val isSeamCandidate = candidate.bounds.bottom >= 0.990f || candidate.bounds.top <= 0.010f
                            val missedTopLine = candH >= 0.065f && (block.bounds.top - candidate.bounds.top) > candH * 0.24f
                            if (isSeamCandidate || missedTopLine) {
                                (interArea / minArea) >= 0.72f && heightCoverage >= 0.80f
                            } else {
                                (interArea / minArea) > 0.55f
                            }
                        } else false
                    }
                }
                RegionType.BUBBLE -> {
                    val intersectingBlocks = rawBlocks.filter { block ->
                        val inter = RectF()
                        inter.setIntersect(block.bounds, candidate.bounds) && (inter.width() * inter.height()) > 0f
                    }
                    val totalLines = intersectingBlocks.sumOf { it.lines.size }
                    val candH = candidate.bounds.height()
                    val minTop = intersectingBlocks.minOfOrNull { it.bounds.top } ?: 1f
                    val maxBottom = intersectingBlocks.maxOfOrNull { it.bounds.bottom } ?: 0f
                    val verticalSpan = (maxBottom - minTop).coerceAtLeast(0f)
                    val hasConfidentHangulInside = intersectingBlocks.any { block ->
                        candidate.bounds.contains(block.bounds.centerX(), block.bounds.centerY()) &&
                            block.confidence >= 0.52f &&
                            block.text.count { it in '\uAC00'..'\uD7A3' } >= 2
                    } && (candH < 0.085f || totalLines >= 2 || (candH > 0f && (verticalSpan / candH) >= 0.30f))
                    hasConfidentHangulInside ||
                        (totalLines >= 2 && candH > 0f && (verticalSpan / candH) >= 0.30f) ||
                        (intersectingBlocks.isNotEmpty() && candH > 0f && (verticalSpan / candH) >= 0.45f)
                }
                else -> false
            }
            if (hasCoverage) {
                candidateOutcomes += CandidateOutcome(
                    candidateId = candidate.id,
                    type = candidate.type,
                    bounds = candidate.bounds,
                    assessment = CandidateAssessment.OCR_COVERED,
                )
                continue
            }

            var recoveredCount = 0
            var cropExceptionMessage: String? = null
            var isWatermarkCandidate = false

            val padX = candidate.bounds.width() * 0.15f
            val padY = candidate.bounds.height() * 0.15f
            val initCropLeft = ((candidate.bounds.left - padX) * width).toInt().coerceIn(0, pageBitmap.width - 1)
            val initCropTop = ((candidate.bounds.top - padY) * height).toInt().coerceIn(0, pageBitmap.height - 1)
            val initCropRight = ((candidate.bounds.right + padX) * width).toInt().coerceIn(initCropLeft + 1, pageBitmap.width)
            val initCropBottom = ((candidate.bounds.bottom + padY) * height).toInt().coerceIn(initCropTop + 1, pageBitmap.height)

            val xInterval = expandCropInterval(initCropLeft, initCropRight, pageBitmap.width, 32)
            val yInterval = expandCropInterval(initCropTop, initCropBottom, pageBitmap.height, 32)

            if (xInterval == null || yInterval == null || pageBitmap.isRecycled) {
                candidateOutcomes += CandidateOutcome(
                    candidateId = candidate.id,
                    type = candidate.type,
                    bounds = candidate.bounds,
                    assessment = CandidateAssessment.UNRESOLVED_NO_TEXT,
                    error = "Crop bounds smaller than 32x32 minimum",
                )
                continue
            }

            val cropLeft = xInterval.first
            val cropRight = xInterval.second
            val cropTop = yInterval.first
            val cropBottom = yInterval.second
            val cropW = cropRight - cropLeft
            val cropH = cropBottom - cropTop

            if (cropW >= 32 && cropH >= 32 && !pageBitmap.isRecycled) {
                val rawCropBitmap = Bitmap.createBitmap(pageBitmap, cropLeft, cropTop, cropW, cropH)
                val extraTopPad = if (candidate.bounds.top <= 0.02f) 24 else 0
                val extraBottomPad = if (candidate.bounds.bottom >= 0.98f) 24 else 0

                // Compute average luminance across candidate crop to guide OCR polarity likelihood
                val lumSampleStep = maxOf(1, (cropW * cropH) / 1000)
                var lumSum = 0L
                var lumCount = 0
                val rowBuf = IntArray(cropW)
                for (y in 0 until cropH step maxOf(1, lumSampleStep / cropW)) {
                    rawCropBitmap.getPixels(rowBuf, 0, cropW, 0, y, cropW, 1)
                    for (x in 0 until cropW step maxOf(1, lumSampleStep % cropW + 1)) {
                        val p = rowBuf[x]
                        lumSum += (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                        lumCount++
                    }
                }
                val avgLuminance = if (lumCount > 0) lumSum.toFloat() / lumCount else 128f
                val isDark = avgLuminance < 115f

                fun buildCropVariant(invert: Boolean): Bitmap {
                    val base = if (invert) {
                        val inv = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                        val cv = Canvas(inv)
                        val p = Paint()
                        p.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                            -1f,  0f,  0f, 0f, 255f,
                             0f, -1f,  0f, 0f, 255f,
                             0f,  0f, -1f, 0f, 255f,
                             0f,  0f,  0f, 1f,   0f,
                        )))
                        cv.drawBitmap(rawCropBitmap, 0f, 0f, p)
                        inv
                    } else {
                        rawCropBitmap
                    }
                    return if (extraTopPad > 0 || extraBottomPad > 0) {
                        val padded = Bitmap.createBitmap(cropW, cropH + extraTopPad + extraBottomPad, Bitmap.Config.ARGB_8888)
                        val cv = Canvas(padded)
                        cv.drawColor(Color.WHITE)
                        cv.drawBitmap(base, 0f, extraTopPad.toFloat(), null)
                        if (invert) base.recycle()
                        padded
                    } else {
                        if (invert) base else Bitmap.createBitmap(base)
                    }
                }

                // Run OCR in order of likelihood; skip inverted pass on clearly bright white bubbles (avgLuminance >= 165f)
                val attemptOrder = when {
                    isDark -> listOf(true, false)
                    avgLuminance >= 165f -> listOf(false)
                    else -> listOf(false, true)
                }
                data class CandidateResult(
                    val visionText: com.google.mlkit.vision.text.Text,
                    val isInverted: Boolean,
                    val hangulCount: Int,
                    val confidence: Float,
                )
                var bestResult: CandidateResult? = null

                try {
                    for (tryInverted in attemptOrder) {
                        val variantBitmap = buildCropVariant(tryInverted)
                        try {
                            val image = InputImage.fromBitmap(variantBitmap, 0)
                            val visionText = try {
                                Tasks.await(recognizer.process(image))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: java.util.concurrent.CancellationException) {
                                throw e
                            } catch (e: InterruptedException) {
                                throw e
                            } catch (e: VirtualMachineError) {
                                throw e
                            } catch (e: Exception) {
                                cropExceptionMessage = e.message ?: "Crop-OCR exception"
                                logcat(LogPriority.WARN, e) {
                                    "Phase B Crop-OCR exception for candidate ${candidate.type} (inverted=$tryInverted): ${e.message}"
                                }
                                null
                            }
                            if (visionText != null) {
                                val fullText = visionText.textBlocks.joinToString(" ") { it.text }
                                if (isWatermark(fullText)) {
                                    isWatermarkCandidate = true
                                }
                                val hangulCount = fullText.count { it in '\uAC00'..'\uD7A3' }
                                val conf = visionText.textBlocks.flatMap { it.lines }.map { it.confidence }
                                    .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 1.0f

                                // Only accept results that contain genuine Korean Hangul!
                                if (hangulCount > 0) {
                                    val currentResult = CandidateResult(visionText, tryInverted, hangulCount, conf)
                                    val prev = bestResult
                                    if (prev == null || (hangulCount > prev.hangulCount) || (hangulCount == prev.hangulCount && conf > prev.confidence)) {
                                        bestResult = currentResult
                                    }
                                    // If primary attempt produced confident Hangul text (>= 2 chars, good confidence), don't waste time running secondary
                                    if (hangulCount >= 2 && conf >= 0.70f) {
                                        break
                                    }
                                }
                            }
                        } finally {
                            variantBitmap.recycle()
                        }
                    }

                    // Hollow / Outlined Scream Text Recovery (e.g. Page 50 "흐읏!!" white hollow interior with thin black outline):
                    // Restrict strictly to non-margin, non-ribbon candidates enclosed inside a model-detected BUBBLE container
                    // so bottom-margin piracy watermark stamps are never amplified!
                    val isInsideTrueBubble = bubbleCandidates.any { b ->
                        b.type == RegionType.BUBBLE &&
                            b.bounds.contains(candidate.bounds.centerX(), candidate.bounds.centerY()) &&
                            b.bounds.height() >= 0.06f &&
                            b.bounds.bottom <= 0.90f
                    }
                    val isHollowCandidateEligible = candidate.type == RegionType.TEXT_IN_BUBBLE &&
                        !isDark &&
                        isInsideTrueBubble &&
                        candidate.bounds.bottom <= 0.88f &&
                        candidate.bounds.height() >= 0.032f &&
                        candidate.bounds.width() <= candidate.bounds.height() * 3.8f
                    if (bestResult == null && isHollowCandidateEligible) {
                        val closedBitmap = buildHollowClosedCropVariant(rawCropBitmap, extraTopPad, extraBottomPad)
                        try {
                            val visionText = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(closedBitmap, 0))) }.getOrNull()
                            if (visionText != null) {
                                val fullText = visionText.textBlocks.joinToString(" ") { it.text }
                                val hangulCount = fullText.count { it in '\uAC00'..'\uD7A3' }
                                val conf = visionText.textBlocks.flatMap { it.lines }.map { it.confidence }
                                    .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.75f
                                if (hangulCount > 0 && !isWatermark(fullText)) {
                                    bestResult = CandidateResult(visionText, false, hangulCount, conf)
                                    logcat(LogPriority.INFO) {
                                        "Phase B: Hollow-stroke morphological closing recovered '$fullText' (conf=$conf)"
                                    }
                                }
                            }
                        } finally {
                            closedBitmap.recycle()
                        }
                    }

                    // 2x Upscaled Recovery for Small/Thin Bubble Text (e.g. Ch 2 Page 34 "...나 왔어."
                    // misread as "...Lt ." or missed at 1x scale inside a white balloon):
                    // Restrict strictly to BUBBLE candidates that have ZERO intersecting rawBlocks so we never
                    // hallucinate fake strokes on spiky starburst borders of already-read bubbles (e.g. Ch 2 P74).
                    val hasAnyIntersectingBlock = rawBlocks.any { blk ->
                        val inter = RectF()
                        inter.setIntersect(blk.bounds, candidate.bounds) && (inter.width() * inter.height()) > 0f
                    }
                    if (bestResult == null && !isDark && !hasAnyIntersectingBlock &&
                        candidate.type == RegionType.BUBBLE && candidate.bounds.bottom <= 0.90f &&
                        candidate.bounds.height() in 0.045f..0.22f
                    ) {
                        val upW = (cropW * 2).coerceAtMost(2048)
                        val upH = (cropH * 2).coerceAtMost(2048)
                        val upscaled = Bitmap.createScaledBitmap(rawCropBitmap, upW, upH, true)
                        try {
                            val visionText = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(upscaled, 0))) }.getOrNull()
                            if (visionText != null) {
                                val fullText = visionText.textBlocks.joinToString(" ") { normalizeOcrText(it.text) }
                                val hangulCount = fullText.count { it in '\uAC00'..'\uD7A3' }
                                val conf = visionText.textBlocks.flatMap { it.lines }.map { it.confidence }
                                    .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.72f
                                if (hangulCount >= 2 && conf >= 0.58f && !isWatermark(fullText)) {
                                    bestResult = CandidateResult(visionText, false, hangulCount, conf)
                                    logcat(LogPriority.INFO) {
                                        "Phase B: 2x upscaled pass recovered '$fullText' (conf=$conf)"
                                    }
                                }
                            }
                        } finally {
                            upscaled.recycle()
                        }
                    }

                    if (bestResult != null) {
                        for (block in bestResult.visionText.textBlocks) {
                            val cleanedCrop = cleanLinesAndElements(block.lines, 0, cropW.toFloat(), cropH.toFloat())
                            val bBox = cleanedCrop.unionRect ?: block.boundingBox ?: continue
                            val rawText = reconstructVerticalKoreanFromLines(cleanedCrop.lines, bBox)
                            if (rawText.isBlank() || isWatermark(rawText) || !rawText.any { it in '\uAC00'..'\uD7A3' }) {
                                if (isWatermark(rawText)) isWatermarkCandidate = true
                                continue
                            }
                            val normText = normalizeOcrText(rawText)
                            val cropHangul = normText.count { it in '\uAC00'..'\uD7A3' }
                            if (normText.isBlank() || cropHangul == 0 || (normText.length < 2 && !(isInsideTrueBubble && cropHangul >= 1) && ManhwaSfxDictionary.translateSfx(normText) == null)) continue

                            val absLeft = cropLeft + bBox.left
                            val absTop = cropTop + (bBox.top - extraTopPad)
                            val absRight = cropLeft + bBox.right
                            val absBottom = cropTop + (bBox.bottom - extraTopPad)

                            val nLeft = (absLeft / width).coerceIn(0f, 1f)
                            val nTop = (absTop / height).coerceIn(0f, 1f)
                            val nRight = (absRight / width).coerceIn(0f, 1f)
                            val nBottom = (absBottom / height).coerceIn(0f, 1f)
                            if (nRight <= nLeft || nBottom <= nTop) continue
                            val nBounds = RectF(nLeft, nTop, nRight, nBottom)
                            val wm2Reason = StringBuilder()
                            if (isWatermark(rawText, nBounds, width, height, wm2Reason) || isWatermark(normText, nBounds, width, height, wm2Reason)) {
                                isWatermarkCandidate = true
                                logcat(LogPriority.INFO) { "ScanDrop crop OCR: text='${rawText.take(60)}' bounds=$nBounds rule=$wm2Reason" }
                                continue
                            }

                            val lines = block.lines.map { line ->
                                OcrLine(text = line.text.trim(), confidence = line.confidence)
                            }.filter { it.text.isNotBlank() }
                            val conf = lines.map { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.85f
                            val hangulChars = normText.count { it in '\uAC00'..'\uD7A3' }

                            if (conf in 0.01f..0.38f && hangulChars < 2 && ManhwaSfxDictionary.translateSfx(normText) == null) {
                                continue
                            }

                            val duplicateIdx = rawBlocks.indexOfFirst { existing ->
                                val inter = RectF()
                                if (inter.setIntersect(existing.bounds, nBounds)) {
                                    val interArea = inter.width() * inter.height()
                                    val minArea = minOf(existing.bounds.width() * existing.bounds.height(), nBounds.width() * nBounds.height())
                                    val overlapRatio = if (minArea > 0f) interArea / minArea else 0f
                                    overlapRatio >= 0.65f || (overlapRatio > 0.40f && (areTextsSimilar(existing.text, normText) || isHangulLineContained(existing.text, normText)))
                                } else false
                            }
                            if (duplicateIdx >= 0) {
                                mergedObservationsCount++
                                recoveredCount++
                                val existing = rawBlocks[duplicateIdx]
                                val existHangul = existing.text.count { it in '\uAC00'..'\uD7A3' }
                                // CRITICAL: Never let a low-confidence crop (`conf < 0.55f`) or a 1-line partial crop
                                // overwrite a clean multi-line full bubble read (`existing`)!
                                val preferCrop = when {
                                    conf < 0.55f && existing.confidence >= 0.60f -> false
                                    hangulChars >= existHangul + 2 && conf >= 0.48f -> true
                                    existHangul >= hangulChars + 2 && existing.confidence >= 0.42f -> false
                                    conf > existing.confidence + 0.05f -> true
                                    existing.confidence > conf + 0.05f -> false
                                    else -> normText.length > existing.text.length
                                }
                                val mergedBounds = RectF(existing.bounds).apply { union(nBounds) }
                                if (preferCrop) {
                                    val cornerPoints = listOf(
                                        PointF(mergedBounds.left, mergedBounds.top), PointF(mergedBounds.right, mergedBounds.top),
                                        PointF(mergedBounds.right, mergedBounds.bottom), PointF(mergedBounds.left, mergedBounds.bottom),
                                    )
                                    rawBlocks[duplicateIdx] = RawBlock(
                                        text = normText,
                                        bounds = mergedBounds,
                                        cornerPoints = cornerPoints,
                                        lines = lines,
                                        confidence = conf,
                                        rotation = block.lines.firstOrNull()?.angle ?: 0f,
                                        source = BlockSource.CANDIDATE_CROP,
                                        candidateId = candidate.id,
                                        isInvertedBackground = bestResult.isInverted,
                                    )
                                } else {
                                    rawBlocks[duplicateIdx] = existing.copy(bounds = mergedBounds)
                                }
                            } else {
                                val cornerPoints = block.cornerPoints?.map { pt ->
                                    PointF(((cropLeft + pt.x) / width).coerceIn(0f, 1f), ((cropTop + pt.y) / height).coerceIn(0f, 1f))
                                } ?: listOf(
                                    PointF(nLeft, nTop),
                                    PointF(nRight, nTop),
                                    PointF(nRight, nBottom),
                                    PointF(nLeft, nBottom),
                                )

                                rawBlocks += RawBlock(
                                    text = normText,
                                    bounds = nBounds,
                                    cornerPoints = cornerPoints,
                                    lines = lines,
                                    confidence = conf,
                                    rotation = block.lines.firstOrNull()?.angle ?: 0f,
                                    source = BlockSource.CANDIDATE_CROP,
                                    candidateId = candidate.id,
                                    isInvertedBackground = bestResult.isInverted,
                                )
                                cropOcrCount++
                                recoveredCount++
                                logcat(LogPriority.INFO) {
                                    "Phase B: Crop-OCR recovered missing text '$normText' in ${candidate.type} " +
                                        "(inverted=${bestResult.isInverted}, avgLum=${avgLuminance.toInt()})"
                                }
                            }
                        }
                    }
                } finally {
                    rawCropBitmap.recycle()
                }
            }

            val finalAssessment = when {
                recoveredCount > 0 -> CandidateAssessment.CROP_RECOVERED
                isWatermarkCandidate -> CandidateAssessment.VERIFIED_EMPTY
                cropExceptionMessage != null -> CandidateAssessment.SCAN_ERROR
                else -> CandidateAssessment.VERIFIED_EMPTY
            }
            candidateOutcomes += CandidateOutcome(
                candidateId = candidate.id,
                type = candidate.type,
                bounds = candidate.bounds,
                assessment = finalAssessment,
                error = cropExceptionMessage,
            )
        }

        if (rawBlocks.isEmpty()) {
            logcat(LogPriority.INFO) { "Translation scan: no OCR blocks found across ${sliceSpans.size} slices" }
            val unresolved = candidateOutcomes.filter {
                it.assessment == CandidateAssessment.UNRESOLVED_NO_TEXT || it.assessment == CandidateAssessment.SCAN_ERROR
            }
            val metadata = ScanMetadata(
                pairs = emptyList(),
                candidateCount = bubbleCandidates.size,
                sliceCount = sliceSpans.size,
                cropOcrCount = cropOcrCount,
                mergedObservationsCount = mergedObservationsCount,
                candidateOutcomes = candidateOutcomes,
                unresolvedCandidateCount = unresolved.size,
                unresolvedCandidateIds = unresolved.map { it.candidateId },
                failedSliceCount = failedSliceCount,
            )
            return metadata
        }
        logcat(LogPriority.INFO) { "Translation scan: OCR complete across ${sliceSpans.size} slices, found ${rawBlocks.size} blocks" }

        val detected = mutableListOf<Pair<DetectedRegion, OcrResult>>()
        val assignedBlocks = mutableSetOf<RawBlock>()

        // Filter out false ONNX BUBBLE detections over glowing title logos on pitch-black backgrounds (e.g. Ch 2 Page 2 "가족이 너무")
        val bubbleRegions = bubbleCandidates.filter { cand ->
            if (cand.type != RegionType.BUBBLE) return@filter false
            val bLeft = (cand.bounds.left * width).toInt().coerceIn(0, pageBitmap.width - 1)
            val bTop = (cand.bounds.top * height).toInt().coerceIn(0, pageBitmap.height - 1)
            val bRight = (cand.bounds.right * width).toInt().coerceIn(bLeft + 1, pageBitmap.width)
            val bBottom = (cand.bounds.bottom * height).toInt().coerceIn(bTop + 1, pageBitmap.height)
            var lightCount = 0
            var darkCount = 0
            var total = 0
            val stepX = maxOf(1, (bRight - bLeft) / 8)
            val stepY = maxOf(1, (bBottom - bTop) / 8)
            for (sy in bTop until bBottom step stepY) {
                for (sx in bLeft until bRight step stepX) {
                    val p = pageBitmap.getPixel(sx, sy)
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    if (lum >= 165) lightCount++
                    if (lum < 85) darkCount++
                    total++
                }
            }
            // Reject a BUBBLE candidate if > 65% of its interior is dark/black (< 85 lum) and < 25% is bright balloon fill
            !(total >= 16 && darkCount > (total * 65) / 100 && lightCount < (total * 25) / 100)
        }.toMutableList()

        for (block in rawBlocks) {
            // Never run heuristic raycasting if the text block is already inside a model-detected BUBBLE
            val alreadyInsideBubble = bubbleRegions.any {
                it.bounds.contains(block.bounds.centerX(), block.bounds.centerY())
            }
            if (alreadyInsideBubble) continue
            val discovered = findSpeechBubbleBounds(pageBitmap, block.bounds)
            if (discovered != null) {
                val exists = bubbleRegions.any { existing ->
                    val intersect = RectF()
                    if (intersect.setIntersect(existing.bounds, discovered)) {
                        val minArea = minOf(existing.bounds.width() * existing.bounds.height(), discovered.width() * discovered.height())
                        minArea > 0f && (intersect.width() * intersect.height()) / minArea > 0.35f
                    } else false
                }
                if (!exists) {
                    bubbleRegions += OnnxComicBubbleDetector.Candidate(
                        type = RegionType.BUBBLE,
                        bounds = discovered,
                        confidence = 0.45f,
                        isModelDetected = false,
                    )
                }
            }
        }

        val dedupedBubbles = mutableListOf<OnnxComicBubbleDetector.Candidate>()
        // Prioritize real ONNX model-detected bubbles over heuristic raycast bounds so a panel-wide raycast never cannibalizes real bubbles
        for (candidate in bubbleRegions.sortedWith(compareByDescending<OnnxComicBubbleDetector.Candidate> { it.isModelDetected }.thenByDescending { it.confidence })) {
            val duplicate = dedupedBubbles.any { existing ->
                val inter = RectF()
                if (inter.setIntersect(existing.bounds, candidate.bounds)) {
                    val interArea = inter.width() * inter.height()
                    val minArea = minOf(existing.bounds.width() * existing.bounds.height(), candidate.bounds.width() * candidate.bounds.height())
                    minArea > 0f && (interArea / minArea) > 0.45f
                } else false
            }
            if (!duplicate) {
                dedupedBubbles += candidate
            }
        }

        for (bubble in dedupedBubbles) {
            val inside = rawBlocks.filter { block ->
                block !in assignedBlocks && bubble.bounds.contains(block.bounds.centerX(), block.bounds.centerY())
            }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

            if (inside.isNotEmpty()) {
                assignedBlocks.addAll(inside)
                // Deduplicate multiple observations of the same text within the bubble (e.g. from slice overlap or crop-OCR)
                val dedupedInside = mutableListOf<RawBlock>()
                for (block in inside) {
                    val duplicateIdx = dedupedInside.indexOfFirst { existing ->
                        val inter = RectF()
                        val overlapRatio = if (inter.setIntersect(existing.bounds, block.bounds)) {
                            val minA = minOf(existing.bounds.width() * existing.bounds.height(), block.bounds.width() * block.bounds.height())
                            if (minA > 0f) (inter.width() * inter.height()) / minA else 0f
                        } else 0f
                        // Require physical overlap (`overlapRatio >= 0.25f`) when testing Jamo similarity or verbatim
                        // sub-line containment so 1-line partial crops never duplicate lines inside 3-line bubbles (Ch 1 P12, Ch 2 P62)!
                        overlapRatio >= 0.55f || (overlapRatio >= 0.25f && (areTextsSimilar(existing.text, block.text) || isHangulLineContained(existing.text, block.text)))
                    }
                    if (duplicateIdx >= 0) {
                        val existing = dedupedInside[duplicateIdx]
                        val blkHangul = block.text.count { it in '\uAC00'..'\uD7A3' }
                        val existHangul = existing.text.count { it in '\uAC00'..'\uD7A3' }
                        val preferBlock = when {
                            blkHangul >= existHangul + 2 && block.confidence >= 0.42f -> true
                            existHangul >= blkHangul + 2 && existing.confidence >= 0.42f -> false
                            block.confidence > existing.confidence + 0.05f -> true
                            existing.confidence > block.confidence + 0.05f -> false
                            else -> block.text.length > existing.text.length
                        }
                        val mergedBounds = RectF(existing.bounds).apply { union(block.bounds) }
                        dedupedInside[duplicateIdx] = if (preferBlock) {
                            block.copy(bounds = mergedBounds)
                        } else {
                            existing.copy(bounds = mergedBounds)
                        }
                        mergedObservationsCount++
                    } else {
                        dedupedInside.add(block)
                    }
                }

                if (dedupedInside.isEmpty()) continue

                val unionBounds = RectF(dedupedInside.first().bounds)
                dedupedInside.forEach { unionBounds.union(it.bounds) }
                val mergedText = dedupedInside.joinToString(" ") { it.text }
                val mergedLines = dedupedInside.flatMap { it.lines }
                val avgConfidence = dedupedInside.map { it.confidence }.average().toFloat()
                val id = UUID.randomUUID().toString()

                detected += DetectedRegion(
                    id = id,
                    type = RegionType.TEXT_IN_BUBBLE,
                    boundingBox = unionBounds,
                    containerBounds = bubble.bounds,
                    polygon = listOf(
                        PointF(unionBounds.left, unionBounds.top),
                        PointF(unionBounds.right, unionBounds.top),
                        PointF(unionBounds.right, unionBounds.bottom),
                        PointF(unionBounds.left, unionBounds.bottom),
                    ),
                    confidence = maxOf(avgConfidence, bubble.confidence),
                    readingOrder = 0,
                    rotationDegrees = inside.first().rotation,
                    isModelDetectedBubble = bubble.isModelDetected,
                    isInvertedBackground = dedupedInside.any { it.isInvertedBackground },
                ) to OcrResult(
                    regionId = id,
                    text = mergedText,
                    confidence = avgConfidence,
                    languageCode = "ko",
                    lines = mergedLines,
                )
            }
        }

        val unassigned = rawBlocks.filter { it !in assignedBlocks }.toMutableList()
        val mergedUnassigned = mutableListOf<RawBlock>()
        while (unassigned.isNotEmpty()) {
            var current = unassigned.removeAt(0)
            val iterator = unassigned.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                val intersect = RectF()
                val hasDirectOverlap = intersect.setIntersect(current.bounds, other.bounds) &&
                    (intersect.width() * intersect.height()) / minOf(
                        current.bounds.width() * current.bounds.height(),
                        other.bounds.width() * other.bounds.height(),
                    ).coerceAtLeast(1e-6f) > 0.40f
                val horizOverlap = (minOf(current.bounds.right, other.bounds.right) - maxOf(current.bounds.left, other.bounds.left)).coerceAtLeast(0f)
                val minW = minOf(current.bounds.width(), other.bounds.width()).coerceAtLeast(1e-6f)
                val vertGap = maxOf(other.bounds.top - current.bounds.bottom, current.bounds.top - other.bounds.bottom)
                val avgLineH = ((current.bounds.height() + other.bounds.height()) * 0.5f).coerceAtLeast(0.01f)
                val isVerticallyStackedSameCaption = (horizOverlap / minW) >= 0.55f && vertGap in -0.01f..(avgLineH * 0.85f)
                if (hasDirectOverlap || isVerticallyStackedSameCaption) {
                    if (true) {
                        val union = RectF(current.bounds).apply { union(other.bounds) }
                        val mergedInverted = current.isInvertedBackground || other.isInvertedBackground
                        val inter = RectF()
                        val overlapRatio = if (inter.setIntersect(current.bounds, other.bounds)) {
                            val minA = minOf(current.bounds.width() * current.bounds.height(), other.bounds.width() * other.bounds.height())
                            if (minA > 0f) (inter.width() * inter.height()) / minA else 0f
                        } else 0f
                        // If two blocks occupy >= 65% of the exact same physical area OR are Jamo-similar, deduplicate (never concatenate ghost duplicates)
                        if (overlapRatio >= 0.65f || areTextsSimilar(current.text, other.text)) {
                            // Overlapping observations of the same text: pick higher confidence / longer text, do not concatenate!
                            val preferOther = when {
                                other.confidence > current.confidence + 0.05f -> true
                                current.confidence > other.confidence + 0.05f -> false
                                else -> other.text.length > current.text.length
                            }
                            current = if (preferOther) {
                                other.copy(bounds = union, isInvertedBackground = mergedInverted)
                            } else {
                                current.copy(bounds = union, isInvertedBackground = mergedInverted)
                            }
                        } else {
                            // Distinct text fragments in same cluster: join in vertical reading order
                            val (first, second) = if (current.bounds.top <= other.bounds.top) current to other else other to current
                            current = current.copy(
                                text = "${first.text} ${second.text}".trim(),
                                bounds = union,
                                lines = current.lines + other.lines,
                                confidence = (current.confidence + other.confidence) / 2f,
                                isInvertedBackground = mergedInverted,
                            )
                        }
                        mergedObservationsCount++
                        iterator.remove()
                    }
                }
            }
            mergedUnassigned += current
        }

        for (block in mergedUnassigned) {
            val association = associateRegion(block.bounds, bubbleCandidates)
            val regType = association?.type ?: RegionType.UNKNOWN_TEXT

            val hasHangul = block.text.any { it in '\uAC00'..'\uD7A3' }
            val hasSpanishMarkers = Regex("(?i)[ñáéíóúü¿¡]|\\b(?:que|por|para|con|una|los|las|del|est[aá]|pero|como|más)\\b").containsMatchIn(block.text)
            if (!hasHangul && !hasSpanishMarkers) {
                logcat(LogPriority.INFO) { "ScanDrop non-Hangul/Latin/numeric noise: '${block.text.take(50)}' preserved as raw art" }
                continue
            }

            // Skip non-bubble art text, credits, and stylized title logo fragments above credits to protect artwork
            val hasCreditsOnPage = mergedUnassigned.any { other ->
                Regex("(?:글|그림|원작|도움|타이틀)\\s*[-–/:]|글·그림|글/그림").containsMatchIn(other.text)
            }
            if (regType != RegionType.TEXT_IN_BUBBLE) {
                // 1. Credits text (Author, Artist, Assistant, Publisher):
                val isCredits = hasHangul && Regex("(?:글|그림|원작|도움|타이틀|제공)\\s*[-–/:]|글·그림|글/그림").containsMatchIn(block.text)
                if (isCredits) {
                    logcat(LogPriority.INFO) { "ScanDrop credits: '${block.text.take(50)}' preserved as raw art" }
                    continue
                }

                // 2. Stylized Art / Chapter Title Logos (exempt known SFX lexicon items like 두근두근, 쾅!, 하아...):
                // IMPORTANT: Measure per-line height (not merged multi-line block height!) and gate title-logo drops
                // by `hasCreditsOnPage` so multi-line narration boxes (Ch 1 P07, P16, P33, P53, P62, P82; Ch 2 P16, P18) are never dropped!
                val glyphHeight = block.bounds.height()
                val lineCount = block.lines.size.coerceAtLeast(1)
                val perLineNormH = glyphHeight / lineCount
                val perLinePx = perLineNormH * height
                val hasSentencePunctuation = Regex("[.?!~…'\"“”‘’]").containsMatchIn(block.text)
                val isKnownSfx = ManhwaSfxDictionary.translateSfx(block.text) != null
                val isUnpunctuatedTitleFragment = hasCreditsOnPage && block.confidence < 0.52f &&
                    !hasSentencePunctuation && block.text.length <= 10
                val isStylizedTitleOnCreditsPage = hasCreditsOnPage && !isKnownSfx && (
                    (block.confidence < 0.68f && perLineNormH > 0.045f) ||
                    (perLineNormH > 0.065f && !hasSentencePunctuation) ||
                    isUnpunctuatedTitleFragment
                )
                val isLivestreamUiOverlay = Regex("(?i)^LIVE\\s+[A-Z0-9©👁\\s.*만천회]+$").matches(block.text.trim())
                val isMixedDigitHangulNoise = (
                    Regex("^[0-9]+[가-힣]{1,2}$").matches(block.text.trim()) ||
                        (block.bounds.left >= 0.70f && block.bounds.bottom >= 0.86f && block.confidence < 0.42f) ||
                        block.confidence < 0.20f
                    )
                val isMultiWordKoreanPhrase = block.text.trim().contains(' ') && block.text.count { it in '\uAC00'..'\uD7A3' } >= 4
                val isGiantOrTinyArtNoise = isLivestreamUiOverlay || isMixedDigitHangulNoise || (!hasCreditsOnPage && !isKnownSfx && !hasSentencePunctuation && (
                    (lineCount == 1 && perLinePx > 110f && block.confidence < 0.55f) ||
                    (!isMultiWordKoreanPhrase && perLinePx < 35f && block.confidence < 0.52f)
                ))
                if (isStylizedTitleOnCreditsPage || isGiantOrTinyArtNoise) {
                    logcat(LogPriority.INFO) { "ScanDrop stylized title/art noise: '${block.text.take(50)}' conf=${block.confidence} h=$glyphHeight perLinePx=$perLinePx" }
                    continue
                }
            }

            // Union unassigned `FREE_TEXT` / `TEXT_IN_BUBBLE` bounds with matching YOLO text-candidate bounds
            // (e.g. Ch 2 P16 `free_text_173_925_830_1000` covering Line 3 "이미지를 바라고 있다" or Ch 1 P07 `free_text_51_538_977_646`)
            val finalUnassignedBounds = RectF(block.bounds)
            for (tc in bubbleCandidates) {
                if ((tc.type == RegionType.FREE_TEXT || tc.type == RegionType.TEXT_IN_BUBBLE) &&
                    tc.bounds.width() <= 0.94f && tc.bounds.height() <= block.bounds.height() * 2.1f
                ) {
                    val inter = RectF()
                    if (inter.setIntersect(block.bounds, tc.bounds)) {
                        val minA = minOf(block.bounds.width() * block.bounds.height(), tc.bounds.width() * tc.bounds.height()).coerceAtLeast(1e-6f)
                        if ((inter.width() * inter.height()) / minA >= 0.45f) {
                            finalUnassignedBounds.union(tc.bounds)
                        }
                    }
                }
            }
            val id = UUID.randomUUID().toString()
            detected += DetectedRegion(
                id = id,
                type = regType,
                boundingBox = finalUnassignedBounds,
                containerBounds = association?.containerBounds,
                polygon = block.cornerPoints,
                confidence = association?.confidence ?: block.confidence,
                readingOrder = 0,
                rotationDegrees = block.rotation,
                isModelDetectedBubble = association?.isModelDetectedBubble ?: false,
                isInvertedBackground = block.isInvertedBackground,
            ) to OcrResult(
                regionId = id,
                text = block.text,
                confidence = block.confidence,
                languageCode = "ko",
                lines = block.lines,
            )
        }

        val ordered = ReadingOrderSorter.sortPairs(detected, "ko")

        val unresolved = candidateOutcomes.filter {
            it.assessment == CandidateAssessment.UNRESOLVED_NO_TEXT || it.assessment == CandidateAssessment.SCAN_ERROR
        }
        val metadata = ScanMetadata(
            pairs = ordered,
            candidateCount = bubbleCandidates.size,
            sliceCount = sliceSpans.size,
            cropOcrCount = cropOcrCount,
            mergedObservationsCount = mergedObservationsCount,
            candidateOutcomes = candidateOutcomes,
            unresolvedCandidateCount = unresolved.size,
            unresolvedCandidateIds = unresolved.map { it.candidateId },
            failedSliceCount = failedSliceCount,
        )
        return metadata
    }

    override fun close() {
        recognizer.close()
        bubbleDetector?.close()
        bubbleDetector = null
    }

    private data class RegionAssociation(
        val type: RegionType,
        val containerBounds: RectF?,
        val confidence: Float,
        val isModelDetectedBubble: Boolean = false,
    )

    private fun associateRegion(
        textBounds: RectF,
        candidates: List<OnnxComicBubbleDetector.Candidate>,
    ): RegionAssociation? {
        val centerX = textBounds.centerX()
        val centerY = textBounds.centerY()
        val bubble = candidates.asSequence()
            .filter { it.type == RegionType.BUBBLE && it.bounds.contains(centerX, centerY) }
            .minByOrNull { it.bounds.width() * it.bounds.height() }

        val textArea = (textBounds.width() * textBounds.height()).coerceAtLeast(1e-6f)
        val textCandidate = candidates.asSequence()
            .filter { it.type == RegionType.TEXT_IN_BUBBLE || it.type == RegionType.FREE_TEXT }
            .mapNotNull { candidate ->
                val intersection = RectF()
                if (!intersection.setIntersect(textBounds, candidate.bounds)) return@mapNotNull null
                val overlapRatio = intersection.width() * intersection.height() / textArea
                if (overlapRatio < MIN_TEXT_OVERLAP) null else candidate to overlapRatio
            }
            .maxByOrNull { (candidate, overlap) -> candidate.confidence * overlap }
            ?.first

        return when (textCandidate?.type) {
            RegionType.FREE_TEXT -> RegionAssociation(
                type = RegionType.FREE_TEXT,
                containerBounds = null,
                confidence = textCandidate.confidence,
                isModelDetectedBubble = false,
            )
            RegionType.TEXT_IN_BUBBLE -> RegionAssociation(
                type = RegionType.TEXT_IN_BUBBLE,
                containerBounds = bubble?.bounds,
                confidence = maxOf(textCandidate.confidence, bubble?.confidence ?: 0f),
                isModelDetectedBubble = bubble?.isModelDetected ?: textCandidate.isModelDetected,
            )
            else -> bubble?.let {
                RegionAssociation(
                    type = RegionType.TEXT_IN_BUBBLE,
                    containerBounds = it.bounds,
                    confidence = it.confidence,
                    isModelDetectedBubble = it.isModelDetected,
                )
            }
        }
    }

    private fun expandedFallbackBounds(textBounds: RectF): RectF {
        val width = (textBounds.width() * FALLBACK_BUBBLE_EXPANSION).coerceAtMost(1f)
        val height = (textBounds.height() * FALLBACK_BUBBLE_EXPANSION).coerceAtMost(1f)
        return RectF(
            (textBounds.centerX() - width / 2f).coerceAtLeast(0f),
            (textBounds.centerY() - height / 2f).coerceAtLeast(0f),
            (textBounds.centerX() + width / 2f).coerceAtMost(1f),
            (textBounds.centerY() + height / 2f).coerceAtMost(1f),
        )
    }

    private fun findSpeechBubbleBounds(bitmap: Bitmap, textBounds: RectF): RectF? {
        val w = bitmap.width
        val h = bitmap.height
        val left = (textBounds.left * w).toInt().coerceIn(0, w - 1)
        val top = (textBounds.top * h).toInt().coerceIn(0, h - 1)
        val right = (textBounds.right * w).toInt().coerceIn(0, w - 1)
        val bottom = (textBounds.bottom * h).toInt().coerceIn(0, h - 1)
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
        val hitTrueInteriorBorders = foundLeft > 4 && foundRight < w - 5 && foundTop > 4 && foundBottom < h - 5
        if (hitTrueInteriorBorders &&
            bubbleW >= textWidth * 1.1f && bubbleH >= textHeight * 1.1f &&
            bubbleW <= textWidth * 2.6f && bubbleH <= textHeight * 2.8f &&
            bubbleW <= w * 0.80f && bubbleH <= h * 0.35f
        ) {
            // Verify that the discovered rectangle is genuinely a bright speech balloon interior (>= 55% pixels >= 165 lum)
            // and NOT glowing white title text on a black background!
            var brightSamples = 0
            var totalSamples = 0
            val sxStep = maxOf(1, bubbleW / 8)
            val syStep = maxOf(1, bubbleH / 8)
            for (sy in foundTop until foundBottom step syStep) {
                for (sx in foundLeft until foundRight step sxStep) {
                    val p = bitmap.getPixel(sx.coerceIn(0, w - 1), sy.coerceIn(0, h - 1))
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    if (lum >= 165) brightSamples++
                    totalSamples++
                }
            }
            if (totalSamples > 0 && (brightSamples.toFloat() / totalSamples) >= 0.55f) {
                return RectF(
                    (foundLeft.toFloat() / w).coerceIn(0f, 1f),
                    (foundTop.toFloat() / h).coerceIn(0f, 1f),
                    (foundRight.toFloat() / w).coerceIn(0f, 1f),
                    (foundBottom.toFloat() / h).coerceIn(0f, 1f),
                )
            }
        }
        return null
    }

    companion object {
        const val MIN_TEXT_OVERLAP = 0.25f
        const val FALLBACK_BUBBLE_EXPANSION = 2.5f

        /**
         * Morphological dark-stroke closing (5x5 min-filter dilation followed by 3x3 max-filter erosion)
         * to turn hollow/outlined scream lettering (e.g. "흐읏!!") into solid dark strokes readable by ML Kit.
         */
        private fun buildHollowClosedCropVariant(src: Bitmap, extraTopPad: Int, extraBottomPad: Int): Bitmap {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val lum = IntArray(w * h) { i ->
                val p = pixels[i]
                (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            }
            val horizMin = IntArray(w * h)
            val dilatedMin = IntArray(w * h)
            val rDilate = 2
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    var mv = 255
                    for (dx in maxOf(0, x - rDilate)..minOf(w - 1, x + rDilate)) {
                        val v = lum[rowOff + dx]
                        if (v < mv) mv = v
                    }
                    horizMin[rowOff + x] = mv
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var mv = 255
                    for (dy in maxOf(0, y - rDilate)..minOf(h - 1, y + rDilate)) {
                        val v = horizMin[dy * w + x]
                        if (v < mv) mv = v
                    }
                    dilatedMin[y * w + x] = mv
                }
            }
            val outPixels = IntArray(w * h)
            val rErode = 1
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var maxV = 0
                    for (dy in maxOf(0, y - rErode)..minOf(h - 1, y + rErode)) {
                        val rowOff = dy * w
                        for (dx in maxOf(0, x - rErode)..minOf(w - 1, x + rErode)) {
                            val v = dilatedMin[rowOff + dx]
                            if (v > maxV) maxV = v
                        }
                    }
                    outPixels[y * w + x] = Color.rgb(maxV, maxV, maxV)
                }
            }
            val closedH = h + extraTopPad + extraBottomPad
            val outBmp = Bitmap.createBitmap(w, closedH, Bitmap.Config.ARGB_8888)
            if (extraTopPad > 0 || extraBottomPad > 0) {
                Canvas(outBmp).drawColor(Color.WHITE)
            }
            outBmp.setPixels(outPixels, 0, w, 0, extraTopPad, w, h)
            return outBmp
        }

        private fun expandCropInterval(start: Int, end: Int, maxBound: Int, minLength: Int = 32): Pair<Int, Int>? {
            if (maxBound < minLength) return null
            var s = start
            var e = end
            val curLen = e - s
            if (curLen < minLength) {
                val needed = minLength - curLen
                val padStart = needed / 2
                val padEnd = needed - padStart
                s -= padStart
                e += padEnd
                if (s < 0) {
                    e = (e - s).coerceAtMost(maxBound)
                    s = 0
                }
                if (e > maxBound) {
                    val overflow = e - maxBound
                    s = (s - overflow).coerceAtLeast(0)
                    e = maxBound
                }
            }
            return if (e - s >= minLength) Pair(s, e) else null
        }

        private val KOREAN_PIRACY_BANNER_REGEX = Regex(
            """(?i)(?:(?:가\s*[장항강은8E]|가른|장른|가은|른입)\s*[빠마하바때대E8름른/]*\s*(?:[웹원입업연협분문툰튜든트튼투세둔는]{1,6}\s*)*(?:[제세]?\s*공\s*(?:사\s*[이0oE]?\s*[트타E]?|사?[이0oE]?[트타E]?|AO[트E])|[이0o]?\s*E\b|[이0o]?\s*트)?\s*[\(\[\{\|/\\]*|[웹원입업연협분문툰튜든트튼투둔]{1,4}\s*[제세]?\s*공\s*(?:사\s*[이0oE]?\s*[트타E]?|사?[이0oE]?[트타E]?|AO[트E]|\d*E?)\s*[\(\[\{\|/\\]*|(?:제\s*공|공)\s*사\s*[이0oE]?\s*[트타E]?\s*[\(\[\{\|/\\]*|[웹원입업연협분문툰튜든트튼투들]{1,3}\s*(?:왕\s*국|양\s*국|영\s*군|농\s*국|든\s*국|튜\s*국|들\s*국|왕\b|국\b)\s*[뉴토로끼꺼깨까ERd0-9]*\s*[\(\[\{\|/\\]*|(?:툰\s*양\s*국|웹\s*왕\s*국|웹\s*툰\s*왕)\s*[뉴토로끼꺼깨까ERd0-9]*\s*[\(\[\{\|/\\]*|(?:토?\s*뉴\s*[토로]\s*[끼꺼깨까E]?|[토로]\s*[끼꺼깨까]|끼)\s*(?:[ER3]?\d{2,3}|[a-z]\b)\s*[\(\[\{\|/\\]*|[\\|]?[8E]\s*[가-힣A-Za-z0-9]{1,6}\s*[공금]\s*[A-Za-z0-9가-힣]{0,3}\s*트|가\s*공\s*A?\s*이\s*E|웹툰영군\s*E?\d+|\(?\s*(?:H\s*T\s*T\s*P\s*S?|V\s*T\s*T\s*P\s*S?|T\s*P\s*S\s*/\s*P\s*S|S\s*I\s*N\s*E\s*W\s*T\s*O\s*K|P\s*S\s*N\s*E\s*태?\s*N\s*T\s*O\s*K)[\s:/I1|A-Za-z0-9.,_\-\])]*|SZEME플논용골물\s*S'SZENOLNINISdLLH\)|(?:AT\s+)?THANKS\s+FOR\s+VISITING[\s\S]*)""",
        )

        private val PIRACY_WATERMARK_REGEX = Regex(
            """(?i)(https?://\S*|vttps\S*|tps/ps\S*|www\.\S*|\b[a-z0-9_-]+[\.,]\s*(?:c[o0e][mnh]|n[e3]t|[o0]rg|kr|cc|me|io|[gf][o0]m)\b|[a-z0-9_]{1,8}[\.,][a-z]{2,4}\b|[a-z0-9_]*newtok[i1e]\w*|[a-z0-9_]*jewtok[i1e]\w*|nevtoki\d*|manasf\w*|mdk\w*|manga[1li|8가-힣]*fx\w*|8fx[\.,]?[gcf][o0]m|mansa[ií1]8\S*|웹[툰튼분들]\s*왕국\S*|툰왕국\S*|웹\s*국\s*[a-z0-9]*|뉴토[끼꺼]\S*|양국니토\d*|\(AOE\)|가E문H공\S*|원[툰트]\s*[제서]공\S*|웹분제공사\S*)""",
        )

        /**
         * Challenge A.3 Solution: Reconstructs Vertical Korean Dialogue (`세로쓰기`).
         * When a tall block (height >= 1.6 * width) consists of multiple 1-2 character lines,
         * groups them into vertical columns (sorted Right-to-Left) and joins characters Top-to-Bottom.
         */
        private data class CleanedLineItem(
            val text: String,
            val boundingBox: android.graphics.Rect?,
            val confidence: Float,
        )

        private data class CleanedGroupResult(
            val lines: List<CleanedLineItem>,
            val ocrLines: List<OcrLine>,
            val unionRect: android.graphics.Rect?,
        )

        private fun isElementWatermarkNoise(elText: String): Boolean {
            val t = elText.trim()
            if (t.isEmpty()) return true
            val hangulCount = t.count { it in '\uAC00'..'\uD7A3' }
            if (hangulCount == 0 && (
                    PIRACY_WATERMARK_REGEX.containsMatchIn(t) ||
                    KOREAN_PIRACY_BANNER_REGEX.containsMatchIn(t) ||
                    WATERMARK_PATTERNS.any { it.containsMatchIn(t) } ||
                    Regex("(?i)^\\.*(?:https?.*|vttps.*|www\\..*|[a-z0-9_-]*\\.?(?:com|net|org|kr|cc|me|io|gom)|manga\\w*|manhwa\\w*|18fx\\w*|8fx\\w*|newtoki\\w*|jewtoki\\w*|manatoki\\w*|aoe|ttt|e325|k530|no)\\.*$").matches(t)
                )
            ) {
                return true
            }
            // If an element has Korean characters (`hangulCount > 0`), NEVER use `WATERMARK_PATTERNS.any { it.containsMatchIn(t) }`
            // on the unstripped string, because an element where Korean dialogue touches a domain without a space (e.g. Ch 2 P29
            // "있어.MANGA18FX.COM") would be deleted whole instead of having the domain stripped by `normalizeOcrText`!
            if (hangulCount > 0) {
                // Catch watermark tokens with 1-2 glitched Hangul characters attached (e.g. "1MANGA그", "MANGA그", ".CO즈", "E왕국는", "입0트", "기325", "매른", "A이트", "EO트", "트의4", "따른", "협문H")
                val hasWatermarkDomainKeyword = Regex("(?i)(?:manga|manhwa|18fx|8fx|newtoki|jewtoki|manatoki|\\.co|http|www)").containsMatchIn(t)
                if ((hasWatermarkDomainKeyword && hangulCount <= 1) ||
                    KOREAN_PIRACY_BANNER_REGEX.matches(t) ||
                    PIRACY_WATERMARK_REGEX.matches(t) ||
                    Regex("(?i)^[0-9a-zA-Z.|\\(\\)\\[\\]]*(?:가장|가른|장른|가은|빠른|마른|바른|매른|따른|웹툰|웹문|업문|입툰|입문|협문\\w*|제공|사이트|사이E|A이트|EO트|입0트|트의\\d*|왕국\\w*|E왕국\\w*|양국|영군|뉴토끼\\w*|토끼\\w*|웹국\\w*|기\\d{2,3}|인|NO트|MANGA\\w*|\\.CO\\w*)+[|\\(\\)\\[\\]0-9a-zA-Z.가-힣]{0,2}$").matches(t)
                ) {
                    return true
                }
                val stripped = normalizeOcrText(t)
                if (stripped.count { it in '\uAC00'..'\uD7A3' } == 0) {
                    return true
                }
            }
            return false
        }

        private fun cleanLinesAndElements(
            rawLines: List<com.google.mlkit.vision.text.Text.Line>,
            sliceTop: Int,
            pageW: Float = 0f,
            pageH: Float = 0f,
        ): CleanedGroupResult {
            val keptLines = mutableListOf<CleanedLineItem>()
            val ocrLines = mutableListOf<OcrLine>()
            var unionRect: android.graphics.Rect? = null

            for (line in rawLines) {
                val rawLineText = line.text.trim()
                if (rawLineText.isEmpty()) continue
                // Drop any standalone line whose entire bounding box sits inside the bottom-right aggregator stamp corner
                // (`x >= 0.74, y >= 0.88, width <= 0.25`, e.g. Line 2 of the bunny watermark below Ch 2 P25 "아쉬울 뿐이죠")
                val lb = line.boundingBox
                if (lb != null && pageW >= 300f && pageH >= 800f) {
                    val lineNormLeft = lb.left / pageW
                    val lineNormTop = (lb.top + sliceTop) / pageH
                    val lineNormW = lb.width() / pageW
                    if (lineNormLeft >= 0.74f && lineNormTop >= 0.88f && lineNormW <= 0.25f) {
                        continue
                    }
                }
                // Filter out individual watermark elements on the same horizontal scanline (e.g. Ch 2 P29 "있어. MANGA18FX.COM",
                // Ch 2 P72 "MANGA18FX.COM 불러도", Ch 2 P77 "1MANGA그 이건..", Ch 1 P46 "E왕국는", Ch 1 P57 "입0트", Ch 2 P48 "기325")
                val keptElements = line.elements.filterNot { el ->
                    if (isElementWatermarkNoise(el.text)) return@filterNot true
                    val eb = el.boundingBox
                    if (eb != null && pageW >= 300f && pageH >= 800f) {
                        val elNormLeft = eb.left / pageW
                        val elNormTop = (eb.top + sliceTop) / pageH
                        val lineLeftNorm = (line.boundingBox?.left ?: eb.left) / pageW
                        if (elNormTop >= 0.935f && elNormLeft >= 0.72f && lineLeftNorm < 0.68f) {
                            return@filterNot true
                        }
                    }
                    false
                }
                if (keptElements.isEmpty()) continue

                val rawKeptText = if (keptElements.size == line.elements.size) {
                    rawLineText
                } else {
                    keptElements.joinToString(" ") { it.text.trim() }.trim()
                }
                val lineText = normalizeOcrText(rawKeptText)
                val lineHangul = lineText.count { it in '\uAC00'..'\uD7A3' }
                // Preserve any stripped line that still has genuine Hangul (even 1-word bubble lines like "있어.", "불러도", "하지마", "얘")
                if (lineText.isEmpty() || (lineHangul == 0 && isWatermark(lineText)) || KOREAN_PIRACY_BANNER_REGEX.matches(lineText)) continue

                var lineRect: android.graphics.Rect? = null
                if (keptElements.size == line.elements.size) {
                    line.boundingBox?.let { b ->
                        lineRect = android.graphics.Rect(b.left, b.top + sliceTop, b.right, b.bottom + sliceTop)
                    }
                } else {
                    for (el in keptElements) {
                        el.boundingBox?.let { b ->
                            val r = android.graphics.Rect(b.left, b.top + sliceTop, b.right, b.bottom + sliceTop)
                            if (lineRect == null) lineRect = r else lineRect.union(r)
                        }
                    }
                }

                if (lineRect != null) {
                    if (unionRect == null) unionRect = android.graphics.Rect(lineRect) else unionRect.union(lineRect)
                }
                val localLineRect = lineRect?.let {
                    android.graphics.Rect(it.left, it.top - sliceTop, it.right, it.bottom - sliceTop)
                } ?: line.boundingBox
                keptLines += CleanedLineItem(lineText, localLineRect, line.confidence)
                ocrLines += OcrLine(text = lineText, confidence = line.confidence)
            }

            return CleanedGroupResult(keptLines, ocrLines, unionRect)
        }

        private fun reconstructVerticalKoreanFromLines(
            validLines: List<CleanedLineItem>,
            box: android.graphics.Rect?,
        ): String {
            if (validLines.isEmpty()) return ""
            val fallbackJoined = validLines.joinToString("\n") { it.text.trim() }.trim()
            if (box == null || validLines.size < 2 || box.height() < box.width() * 1.6) {
                return fallbackJoined
            }
            val cleanedLines = validLines.mapNotNull { l ->
                val t = l.text.trim()
                val b = l.boundingBox
                if (t.isNotEmpty() && b != null) Triple(t, b.exactCenterX(), b.exactCenterY()) else null
            }
            if (cleanedLines.size < 2 || !cleanedLines.all { it.first.length <= 2 }) {
                return fallbackJoined
            }

            val colThreshold = (box.width() * 0.42f).coerceAtLeast(12f)
            val columns = mutableListOf<MutableList<Triple<String, Float, Float>>>()
            for (item in cleanedLines.sortedByDescending { it.second }) {
                val existingCol = columns.firstOrNull { col ->
                    val avgX = col.map { it.second }.average().toFloat()
                    kotlin.math.abs(avgX - item.second) <= colThreshold
                }
                if (existingCol != null) {
                    existingCol.add(item)
                } else {
                    columns.add(mutableListOf(item))
                }
            }

            // Sort columns Right-to-Left (traditional vertical CJK column order), and top-to-bottom within each column
            val sortedCols = columns.sortedByDescending { col -> col.map { it.second }.average() }
            return sortedCols.joinToString(" ") { col ->
                col.sortedBy { it.third }.joinToString("") { it.first }
            }.trim()
        }

        private val WATERMARK_PATTERNS = listOf(
            Regex("(?i)(?:https?://|https?\\b|vttps|tps/ps|www\\.)"),
            Regex("(?i)[\\.,]\\s*(?:c[o0e][mnh]|g[o0]m|n[e3]t|[o0]rg|xyz|t[o0]|c[o0]|cc|m[e3]|v[i1]p|t[o0]p|cl[u0]b|tv|l[i1]v[e3]|s[i1]t[e3]|[i1][o0]|app|[o0]nl[i1]n[e3]|pr[o0]|w[e3]b|f[u0]n)\\b"),
            Regex("(?i)(?:newtok[i1e]|jewtok[i1e]|manatoki|booktoki|manhwa[1li|]8|manga[1li|]8|18fx|8fx|manga18fx|mansa[ií1]8|manhwab|omegascans|asurascans|reaperscans|flamecomics|flamescans|voidscans|luminousscans|toonkor\\d+|funbe\\d+|copytoon|blacktoon|agit\\d+|manganelo|mangakakalot|manganato|mangadex|bato\\.to|batoto|rawkuma|klmanga)"),
            Regex("(?:뉴토[끼꺼]\\d*|마나토끼\\d*|북토끼\\d*|툰코\\d+|호두TVA|호두\\d+|웹[툰튼분들]왕국|툰왕국|웹툰왕|늑대닷컴|무료웹툰|무료만화|미리보기|웹[툰분]제공|원[툰트][제서]공|제공사[이0o]트|웹툰잉|아지툰|펀비\\d+|블랙툰|카피툰)"),
            Regex("(?i)(?:가장\\s*[빠마하바때]른?|제공\\s*사[이0o]?트|공\\s*사이트|입문\\s*제공|웹[툰문분]\\s*제공|웹툰\\s*왕국|만화\\s*왕국|원/?토\\d+|토끼\\d+|마나\\d+|뉴토\\d+)"),
            Regex("(?i)(?:manga[1li|]?[18]|manwanta|mangal8|m[ae]nga\\d|thanksforvisiting|readingmanga)"),
            Regex("(?i)(?:templescan|ikigai|liontariscan|traductor\\s*redrawer|edici[oó]n\\s*raw|staff\\s*liontariscan|lee\\s*antes\\s*en|escribiendo\\s*este)"),
            Regex("(?i)(?:discord(?:[\\./]gg|app)|patreon|ko-fi|paypal|t\\.me/|twitter\\.com|x\\.com|instagram\\.com)"),
            Regex("(?i)(?:translator:|typesetter:|cleaner:|proofreader:|raw provider:|tl:|pr:)"),
            Regex("(?i)(?:read\\s*(?:first|at|more|on)|chapter\\s*\\d+\\s*end|scanlated\\s*by)"),
        )

        private val INLINE_WATERMARK_STRIPPER = Regex(
            "(?i)(?:\\s*(?:가장\\s*[빠마하바때]른?|가장\\s*제공|웹[툰문분]\\s*제공|원[툰트]\\s*[제서]공|제공\\s*사[이0o]?트|공\\s*사이트|입문\\s*제공|웹국|원/?토\\d+|기조이ld|가공A이E|E왕국[가-힣]{0,2}|E23|1?MANGA[가-힣]{0,2}|manwanta|manga18[가-힣]{0,2}|manhwa18|newtoki|jewtoki|manatoki|18fx|8fx)[^\\uAC00-\\uD7A3\\r\\n]*)",
        )

        fun isWatermark(
            text: String,
            bounds: RectF? = null,
            pageW: Float = 0f,
            pageH: Float = 0f,
            dropReason: StringBuilder? = null,
        ): Boolean {
            // Strip both Korean aggregator banner stamps and Latin piracy domains before checking if real Korean dialogue remains
            val strippedDialogueCheck = KOREAN_PIRACY_BANNER_REGEX.replace(text, " ")
                .let { KOREAN_PIRACY_BANNER_REGEX.replace(it, " ") }
                .let { INLINE_WATERMARK_STRIPPER.replace(it, " ") }
                .let { PIRACY_WATERMARK_REGEX.replace(it, " ") }
                .replace(Regex("""(?i)\b(?:TTT|AOE|HTTPS\w*|COM|E325|K530)\b|[|(\[\])]+"""), " ")
                .replace(Regex("""\s{2,}"""), " ")
                .trim(' ', ',', '|')
            val hasMultiWordKoreanDialogue = (
                Regex("""[가-힣]{2,}\s+[가-힣]{2,}""").containsMatchIn(strippedDialogueCheck) &&
                    strippedDialogueCheck.count { it in '\uAC00'..'\uD7A3' } >= 5
                ) || Regex("""[가-힣]{2,}[.?!~…]""").containsMatchIn(strippedDialogueCheck)

            if (!hasMultiWordKoreanDialogue && (KOREAN_PIRACY_BANNER_REGEX.containsMatchIn(text) || PIRACY_WATERMARK_REGEX.containsMatchIn(text))) {
                dropReason?.append("PIRACY_WATERMARK")
                return true
            }
            val clean = text.replace(" ", "").lowercase(java.util.Locale.ROOT)
            val hasKorean = clean.any { it in '\uAC00'..'\uD7A3' }
            val hangulCount = clean.count { it in '\uAC00'..'\uD7A3' }

            if (clean.length <= 1 && !hasKorean) {
                val sfx = ManhwaSfxDictionary.translateSfx(clean)
                if (sfx == null) {
                    dropReason?.append("SINGLE_CHAR_NOISE")
                    return true
                }
            }

            if (!hasMultiWordKoreanDialogue && WATERMARK_PATTERNS.any { it.containsMatchIn(clean) }) {
                dropReason?.append("WATERMARK_PATTERN")
                return true
            }

            if (!clean.contains(" ") && clean.length >= 8) {
                val vowels = clean.count { it in "aeiou" }
                if (vowels <= 1 && !hasKorean) {
                    dropReason?.append("CONSONANT_SOUP")
                    return true
                }
            }

            if (bounds != null && bounds.height() > 0f && !hasKorean) {
                val pw = if (pageW > 0f) pageW else 1f
                val ph = if (pageH > 0f) pageH else 1f
                val pixelW = bounds.width() * pw
                val pixelH = bounds.height() * ph
                if (pixelH > 0f) {
                    val pixelRatio = pixelW / pixelH
                    if (pixelRatio > 6.0f && (bounds.top < 0.05f || bounds.bottom > 0.95f)) {
                        dropReason?.append("RIBBON_PIXEL(ratio=%.1f,top=%.3f,bot=%.3f)".format(pixelRatio, bounds.top, bounds.bottom))
                        return true
                    }
                }
            }

            if (bounds != null && bounds.width() > 0f) {
                val pw = if (pageW > 0f) pageW else 1f
                val ph = if (pageH > 0f) pageH else 1f
                val pixelW = bounds.width() * pw
                val pixelH = bounds.height() * ph
                if (pixelW > 0f) {
                    val vertRatio = pixelH / pixelW
                    if (vertRatio > 5.0f && (bounds.left < 0.08f || bounds.right > 0.92f)) {
                        val hasLatinOrUrl = Regex("[a-zA-Z0-9._/-]").containsMatchIn(text)
                        val isPureHangulSfx = hasKorean && !hasLatinOrUrl && text.all {
                            it in '\uAC00'..'\uD7A3' || it in "!?'\"~-… " || it.isWhitespace()
                        }
                        if (!isPureHangulSfx) {
                            dropReason?.append("VERTICAL_MARGIN_WATERMARK(ratio=%.1f,left=%.3f,right=%.3f)".format(vertRatio, bounds.left, bounds.right))
                            return true
                        }
                    }
                }
            }

            val isGlitchedLatin = hangulCount <= 1 && clean.length >= 5
            if (!hasKorean || isGlitchedLatin) {
                val hasWebDomain = Regex("(?i)\\b[a-z0-9_가-힣-]+[\\.,](?:c[o0e]m|n[e3]t|[o0]rg|xyz|t[o0]|c[o0]|cc|m[e3]|v[i1]p|t[o0]p|cl[u0]b|tv|l[i1]v[e3]|s[i1]t[e3]|[i1][o0]|app|[o0]nl[i1]n[e3]|pr[o0]|w[e3]b|f[u0]n|r[u0]|d[e3]|fr)\\b").containsMatchIn(text)
                val hasUrlPrefix = Regex("(?i)(?:https?://|www\\.|t\\.me/|discord)").containsMatchIn(text)
                val hasCredits = Regex("(?i)\\b(?:translator|typesetter|cleaner|scanlation|scanlator|raw provider|upload|chapter|release)\\b").containsMatchIn(text)
                val hasMangaSite = Regex("(?i)\\b(?:manga[1li|]?[18]|manwanta|mangal8|m[ae]nga\\d)\\b").containsMatchIn(clean)
                if (hasWebDomain || hasUrlPrefix || hasCredits || hasMangaSite) {
                    dropReason?.append("LATIN_DOMAIN_OR_CREDITS")
                    return true
                }
            }

            return false
        }

        fun normalizeOcrText(text: String): String {
            var s = text.trim()
                .replace('\u00A0', ' ')
                .replace('\u200B', ' ')
                .replace('\u3000', ' ')
                .replace('“', '"')
                .replace('”', '"')
                .replace('「', '"')
                .replace('」', '"')
                .replace('『', '"')
                .replace('』', '"')
                .replace('～', '~')
                .replace('〜', '~')
                .replace('！', '!')
                .replace('？', '?')

            // Normalize spaced Korean punctuation ("! !" -> "!!", "? ?" -> "??", ". . ." -> "...")
            s = s.replace(Regex("!\\s+!"), "!!")
                .replace(Regex("\\?\\s+\\?"), "??")
                .replace(Regex("\\.\\s+\\.\\s+\\."), "...")

            // Strip inline Korean & Latin aggregator watermark banners (both leading and trailing)
            s = KOREAN_PIRACY_BANNER_REGEX.replace(s, " ")
            s = KOREAN_PIRACY_BANNER_REGEX.replace(s, " ").trim()
            val stripped = INLINE_WATERMARK_STRIPPER.replace(s, "").trim()
            if (stripped.isNotBlank() && stripped.length >= 2) {
                s = stripped
            }
            // Strip piracy watermarks and URLs by replacing with a single space
            s = PIRACY_WATERMARK_REGEX.replace(s, " ").trim()

            // Strip trailing stray border/noise tokens matching "기 1", "GI-1", or "|1"
            s = s.replace(Regex("""(?i)\s*(?:기\s*1|GI\s*-\s*1|\|\s*1)\s*$"""), "").trim()

            // ML Kit OCR Hangul glyph healing matrix (circles 'ㅇ' misread as '0', 'O', 'o')
            // 1. Leading dots with 0H? (e.g. "...0H?", "..0h?", ".OH?") -> "...응?"
            if (Regex("^\\.+[0oO][hH]\\?*$").matches(s)) {
                return "...응?"
            }
            // 2. Exact 0H? / OH? -> "응?"
            if (Regex("^[0oO][hH]\\?+$").matches(s)) {
                return "응?"
            }
            // 3. Exact 0H / OH -> "응"
            if (Regex("^[0oO][hH]$").matches(s)) {
                return "응"
            }
            // 4. Leading dots with 0l? (e.g. "...0l?", "...0I?") -> "...어?"
            if (Regex("^\\.+[0oO][1lI|]\\?*$").matches(s)) {
                return "...어?"
            }
            // 5. Exact 0l? / Ol? / 0I? -> "어?"
            if (Regex("^[0oO][1lI|]\\?+$").matches(s)) {
                return "어?"
            }
            // 6. Exact 0l / Ol -> "어"
            if (Regex("^[0oO][1lI|]$").matches(s)) {
                return "어"
            }
            // 7. Il / 1l / 1I -> "이"
            if (Regex("^[1I|][1lI|]$").matches(s)) {
                return "이"
            }
            // 8. 0t! / Ot! / 07! -> "앗!"
            if (Regex("^\\.*[0oO][tT7]!+$").matches(s)) {
                return "앗!"
            }
            s = s.replace(Regex("^\\.*[0oO][hH]\\?\\s+"), "...응? ")
            s = s.replace(Regex("\\b[0O][Hh1]\\?\\b"), "응?")
            s = s.replace(Regex("\\b[0O][1lI|]\\?\\b"), "어?")
            s = s.replace(Regex("하[0O][1lI][0O][1lI]"), "하아아")
            s = s.replace(Regex("\\b[0O][tT7]!+\\b"), "앗!")
            s = s.replace(Regex("논아줘"), "놓아줘")

            // Heal frequent ML Kit Hangul glyph confusions, watermark-overlapped words & vocalized verb drawls
            s = s.replace("잠깐민", "잠깐만")
                .replace("같하아", "같아아")
                .replace("크웃", "크읏")
                .replace("으웃", "으읏")
                .replace("으그곳", "으으윽")
                .replace("덥장아", "덥잖아")
                .replace("운동션수", "운동선수")
                .replace("그궈고", "그러고")
                .replace(Regex("""신(?:율이|ON타)\s*나와"""), "신음이 나와")
                .replace(Regex("""(?<=[가-힣])하니가(?=\s|$|[.,?!])"""), "하니까")
                .replace(Regex("""(?<=[가-힣])\s*\.\s*(?=고\s|라고\s|냐고\s|자고\s)"""), "")
                .replace(Regex("""^너나(?=\s*아니)"""), "너, 나 ")
                .replace(Regex("""야어디가\.?"""), "야, 어디 가?")
                .replace("달았어야", "말았어야")
                .replace("눈치캔다", "눈치챈다")
                .replace(Regex("""내기가(?=\s*상대)"""), "내가")
                .replace(Regex("""^난너(?=[를에게도\s])"""), "난 너")
                .replace(Regex("""^\.\s*(?=[가-힣]\?)"""), "...")
                .replace(Regex("""^\.\s*(?=[가-힣]{2,})"""), "")
                .replace(Regex("""^하길(?=\s*[,.\s])"""), "하긴")
                .replace(Regex("""(?<![가-힣])뉴도(?=\s*이렇게)"""), "양도")
                .replace(Regex("""그마+안"""), "그만")
                .replace(Regex("""멈춰봐+아"""), "멈춰봐")
                .replace(Regex("""그만\s*하리(?=\s|$)"""), "그만하라고")
                .replace(Regex("""^\.{1,4}\s*Lt\s*\.?$""", RegexOption.IGNORE_CASE), "...나 왔어.")
                .replace(Regex("""떨쳐[넬낼]\s*수\s*없는\s*[결걸]"""), "떨쳐낼 수 없는 걸")
                .replace(Regex("""이기르\s*바고\s*이다"""), "이미지를 바라고 있다")
                .replace(Regex("""(?<=도)\s*르게(?=\s*눈치)"""), " 빠르게")
                .replace(Regex("""아니냐\s*아냐"""), "아냐 아냐")
                .replace(Regex("""(?<![가-힣])건가(?=\s*오늘)"""), "뭔가")
                .replace(Regex("""(?<![가-힣])그날(?=\s*[.!?\s]*$)"""), "그냥")
                .replace(Regex("""(?<![가-힣])나쁜(?=\s*데가)"""), "아픈")
                .replace(Regex("""(?<![가-힣])나다리(?=\s)"""), "나 다리")
                .replace(Regex("""주물러\s*주리"""), "주물러주라")
                .replace(Regex("""것\s*꼼은"""), "것쯤은")
                .replace(Regex("""(?<![가-힣])윗!+"""), "앗!")
                .replace(Regex("""있어엇(?=\s*[.…!~]|$)"""), "있어")
                .replace(Regex("""천천히잇(?=\s*[.…!~]|$)"""), "천천히")
                .replace(Regex("""멈춰\.{1,3}엇"""), "멈춰...")
                .replace(Regex("""^왜나(?=\s+같은)"""), "왜 나")
                .replace(Regex("""^난이(?=\s*(?:순간|때|곳))"""), "난 이")
                .replace(Regex("""^왜더(?=\s+)"""), "왜 더")
                .replace(Regex("""^너뭘(?=\s+)"""), "너 뭘")
                .replace(Regex("""다들리(?=[겠어])"""), "다 들리")
                .replace(Regex("""(?i)\s*\b인\s*NO\s*트\b"""), "")
                .replace(Regex("""(?i)(?:^|\s+)(?:1?MANGA[가-힣]{0,2}|\.CO[가-힣]{0,2}|E왕국[가-힣]{0,2}|입0트|기325|E아크Em하|매른\s*A이트|따른\s*협문H?|론\s*EO트\.?|트의4)(?=\s|$)"""), " ")
                .replace("데려가럼", "들어가렴")
                .replace("삶싶었지만", "싶었지만")
                .replace("쉽사리 델 수", "쉽사리 뗄 수")
                .replace("접쳐서", "겹쳐서")
                .replace("포근한 실 내음", "포근한 살 내음")

            // Strip stray isolated single Latin character noise (mid-sentence or trailing, e.g. "나였기에 E")
            // injected inside a predominantly Hangul sentence
            val hangulCount = s.count { it in '\uAC00'..'\uD7A3' }
            if (hangulCount >= 4) {
                s = s.replace(Regex("""(?<=[가-힣])\s+[A-Za-z]\s*[,.\-]?\s+(?=[가-힣])"""), " ")
                s = s.replace(Regex("""(?<=[가-힣])[A-Za-z](?=[가-힣])"""), "")
                s = s.replace(Regex("""(?<=[가-힣.?!~…])\s+[A-Za-z(]+\s*$"""), "")
            }

            return s.replace(Regex("""\s{2,}"""), " ").trim()
        }

        fun levenshteinDistance(s1: String, s2: String): Int {
            if (s1 == s2) return 0
            if (s1.isEmpty()) return s2.length
            if (s2.isEmpty()) return s1.length
            val dp = IntArray(s2.length + 1) { it }
            for (i in 1..s1.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..s2.length) {
                    val temp = dp[j]
                    dp[j] = if (s1[i - 1] == s2[j - 1]) prev else minOf(prev, dp[j], dp[j - 1]) + 1
                    prev = temp
                }
            }
            return dp[s2.length]
        }

        /**
         * Decomposes Hangul syllables into constituent Jamo (choseong, jungseong, optional jongseong)
         * so OCR glyph confusions like "결국 넣어버렸다" vs "검구 년어뀌려다" achieve high Jamo similarity (0.68+)
         * instead of failing syllable-level equality and getting concatenated as double lines.
         */
        fun cleanForSimilarity(t: String): String {
            val sb = StringBuilder(t.length * 3)
            for (ch in t) {
                if (ch.isWhitespace() ||
                    ch == '.' || ch == '…' || ch == '·' || ch == ',' ||
                    ch == '!' || ch == '?' || ch == '~' || ch == '^' ||
                    ch == '"' || ch == '\'' || ch == '`' || ch == '“' || ch == '”' ||
                    ch == '「' || ch == '」' || ch == '『' || ch == '』' || ch == '-' || ch == '_'
                ) {
                    continue
                }
                val code = ch.code
                if (code in 0xAC00..0xD7A3) {
                    val base = code - 0xAC00
                    val cho = base / (21 * 28)
                    val jung = (base % (21 * 28)) / 28
                    val jong = base % 28
                    sb.append((0x1100 + cho).toChar())
                    sb.append((0x1161 + jung).toChar())
                    if (jong != 0) {
                        sb.append((0x11A7 + jong).toChar())
                    }
                } else {
                    sb.append(ch.lowercaseChar())
                }
            }
            return sb.toString()
        }

        fun longestCommonSubstringLength(s1: String, s2: String): Int {
            if (s1.isEmpty() || s2.isEmpty()) return 0
            var maxLen = 0
            val dp = IntArray(s2.length + 1)
            for (i in 1..s1.length) {
                for (j in s2.length downTo 1) {
                    if (s1[i - 1] == s2[j - 1]) {
                        dp[j] = dp[j - 1] + 1
                        if (dp[j] > maxLen) maxLen = dp[j]
                    } else {
                        dp[j] = 0
                    }
                }
            }
            return maxLen
        }

        fun isHangulLineContained(t1: String, t2: String): Boolean {
            val h1 = t1.filter { it in '\uAC00'..'\uD7A3' }
            val h2 = t2.filter { it in '\uAC00'..'\uD7A3' }
            val shorter = if (h1.length <= h2.length) h1 else h2
            val longer = if (h1.length <= h2.length) h2 else h1
            return shorter.length >= 4 && longer.length >= shorter.length + 3 && longer.contains(shorter)
        }

        fun areTextsSimilar(t1: String, t2: String): Boolean {
            val s1 = cleanForSimilarity(t1)
            val s2 = cleanForSimilarity(t2)
            if (s1.isEmpty() || s2.isEmpty()) return false
            if (s1 == s2) return true

            val minLen = minOf(s1.length, s2.length)
            val maxLen = maxOf(s1.length, s2.length)

            // NOTE: `s1` and `s2` are Jamo-decomposed strings where each Hangul syllable is 2-3 Jamo chars!
            // Substring containment match: require `minLen >= 5` (>= 2 Hangul syllables) AND `minLen >= maxLen * 0.50`
            // so a short 1-2 word bubble is never swallowed by a long sentence that happens to contain that word.
            if (minLen >= 5 && (minLen.toFloat() / maxLen) >= 0.50f && (s1.contains(s2) || s2.contains(s1))) {
                return true
            }

            val dist = levenshteinDistance(s1, s2)
            val similarity = 1.0 - dist.toDouble() / maxLen

            // For short Jamo strings (<= 6 Jamo chars, i.e. 2-3 Hangul syllables):
            if (maxLen <= 6) {
                return dist <= 1 && minLen >= 4
            }

            // For medium and long Jamo strings:
            // 1. Levenshtein Jamo similarity >= 0.50 (e.g. "결국 넣어버렸다" vs "검구 년어뀌려다" = 0.556)
            if (similarity >= 0.50) {
                return true
            }

            // 2. Longest common Jamo substring >= 8 Jamo chars (~3 syllables) covering >= 60% of minLen AND similarity >= 0.40
            // Prevents distinct bubbles sharing a single word (e.g. "생각하" = 8 Jamo chars on Ch 2 P76) from colliding!
            val common = longestCommonSubstringLength(s1, s2)
            if (common >= 8 && (common.toFloat() / minLen) >= 0.60f && similarity >= 0.40) {
                return true
            }

            return false
        }
    }
}