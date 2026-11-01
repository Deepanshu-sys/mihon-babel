package mihon.feature.translation.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import mihon.feature.translation.contract.PageTypesetter
import mihon.feature.translation.contract.TypesettingResult
import mihon.feature.translation.model.DetectedRegion
import mihon.feature.translation.model.RegionType
import mihon.feature.translation.model.TranslateMode
import mihon.feature.translation.model.TranslationDisposition
import mihon.feature.translation.model.TranslationResult
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Comic typography and lettering engine.
 * Binary-searches optimal font size within [MIN_FONT_SP, MAX_FONT_SP],
 * wraps dialogue centered, and vertically centers text within speech bubbles.
 */
class ComicPageTypesetter(
    private val context: Context,
    private val fontFileName: String = "animeace.ttf",
) : PageTypesetter {

    companion object {
        const val MIN_FONT_SP = 12f
        const val MAX_FONT_SP = 28f
        const val MAX_SHORT_DIALOGUE_SP = 33f
        const val MAX_EXCLAIM_FONT_SP = 38f
    }

    private var comicTypeface: Typeface = Typeface.DEFAULT_BOLD

    init {
        try {
            val safeFontName = fontFileName.ifBlank { "animeace.ttf" }
            val fontFile = File(context.filesDir, "translation_fonts/$safeFontName")
            if (!fontFile.exists()) {
                fontFile.parentFile?.mkdirs()
                context.assets.open("fonts/$safeFontName").use { input ->
                    fontFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (fontFile.exists()) {
                comicTypeface = Typeface.createFromFile(fontFile)
            }
        } catch (_: Throwable) {
            comicTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }

    override suspend fun typeset(
        cleanedBitmap: Bitmap,
        regions: List<DetectedRegion>,
        translations: List<TranslationResult>,
    ): Pair<Bitmap, List<TypesettingResult>> {
        val width = cleanedBitmap.width
        val height = cleanedBitmap.height

        val typesetBitmap = cleanedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(typesetBitmap)

        val translationMap = translations.associateBy { it.regionId }
        val layoutResults = mutableListOf<TypesettingResult>()

        // The output is a page bitmap, so scale lettering from page resolution rather
        // than from the phone's screen density.
        val pageScale = (width / 1080f).coerceIn(0.65f, 2f)

        for (region in regions) {
            if (region.translateMode == TranslateMode.KEEP) continue
            val trans = translationMap[region.id] ?: continue
            if (trans.disposition == TranslationDisposition.PASSTHROUGH ||
                trans.disposition == TranslationDisposition.IGNORED ||
                trans.disposition == TranslationDisposition.PASSTHROUGH_REVIEW) continue
            val rawText = trans.translatedText.trim()
            if (rawText.isBlank()) continue
            // Standard comic lettering is uppercase for crisp, readable dialogue.
            // Professional Manhwa Lettering Rules:
            // 1. ASCII Sanitizer for classic comic fonts (animeace.ttf):
            //    Replaces Unicode curly quotes, dashes, and ellipses with standard ASCII glyphs to prevent missing '□' tofu boxes.
            val sanitized = rawText
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace("…", "...")
                .replace("—", "--")
                .replace("–", "--")
                .replace("―", "--")
                .replace('～', '~')
                .replace('〜', '~')

            // 2. All-caps for dialogue lettering.
            // 3. Glue trailing punctuation (!, ?, ..., !?) to previous word with non-breaking space so punctuation is never orphaned on a new line.
            // 4. Bind short trailing words (<= 5 chars) with non-breaking space (\u00A0) to eliminate solitary dangling words.
            val formatted = if (sanitized.any { it.isLetter() }) {
                var upper = sanitized.uppercase()
                // Prevent orphan punctuation and eliminate unnatural space before punctuation (e.g. "WORD ?" -> "WORD?")
                upper = upper.replace(Regex("\\s+([!\\?\\.]{1,3})"), "$1")
                // Normalize 4+ dots to 3 dots and ensure breakable space after mid-sentence ellipsis ("WAIT...WHAT" -> "WAIT... WHAT")
                upper = upper.replace(Regex("\\.{4,}"), "...")
                upper = upper.replace(Regex("\\.\\.\\.(?=[A-Z])"), "... ")
                val wordTokens = upper.split(' ').filter { it.isNotEmpty() }
                if (wordTokens.size >= 4 && upper.length > 14) {
                    val lastSpace = upper.lastIndexOf(' ')
                    val beforeLast = upper.substring(0, lastSpace).trimEnd()
                    val lastWord = upper.substring(lastSpace + 1)
                    // Only bind the final two words when there are >= 4 words and no clause boundary (',', '.', '!', '?') right before the last word
                    if (lastWord.length <= 6 && beforeLast.isNotEmpty() && beforeLast.last() !in ",.!?;:") {
                        "$beforeLast\u00A0$lastWord"
                    } else {
                        upper
                    }
                } else {
                    upper
                }
            } else {
                sanitized
            }
            val placementBounds = region.containerBounds ?: region.boundingBox
            val boxLeft = (placementBounds.left * width).toInt().coerceIn(0, width)
            val boxTop = (placementBounds.top * height).toInt().coerceIn(0, height)
            val boxRight = (placementBounds.right * width).toInt().coerceIn(0, width)
            val boxBottom = (placementBounds.bottom * height).toInt().coerceIn(0, height)

            val boxWidth = max(20, boxRight - boxLeft)
            val boxHeight = max(20, boxBottom - boxTop)

            // For authentic manhwa vertical SFX strips (height >= 2.2 * width),
            // stack single-word sound effects vertically so they can typeset at full scale.
            // Strict Invariant: Dialogue (MODEL_TRANSLATION) and narration (FREE_TEXT) MUST wrap horizontally;
            // vertical character stacking applies ONLY to confirmed approved SFX sound effects.
            val isConfirmedSfx = trans.disposition == TranslationDisposition.APPROVED_SFX_LEXICON
            val isVerticalSfxStrip = isConfirmedSfx &&
                boxHeight >= boxWidth * 2.2f &&
                !formatted.contains(' ') &&
                formatted.length in 2..10
            val text = if (isVerticalSfxStrip) {
                formatted.toCharArray().joinToString("\n")
            } else {
                formatted
            }

            val isBubble = region.type == mihon.feature.translation.model.RegionType.TEXT_IN_BUBBLE
            val hasContainer = region.containerBounds != null
            val charCount = formatted.length

            // Challenge 3 Solution: Volume-Aware Width & Font Ceiling for Roomy Speech Bubbles.
            // Short utterances (<= 10 chars, e.g. "WHAT?!", "NOONA!", "HAAH...") use a narrower equator
            // (74% width) and higher font ceiling (up to 33sp, bounded by bubble geometry) so they stack
            // punchily in the center of large bubbles instead of floating as tiny 20sp text.
            val isRoomyOvalBubble = isBubble && hasContainer && boxHeight >= boxWidth * 0.42f
            val widthRatio = when {
                !isBubble -> 0.82f
                hasContainer && charCount <= 10 -> 0.72f
                isRoomyOvalBubble && charCount in 11..26 && formatted.contains(' ') -> 0.62f
                hasContainer && charCount <= 26 -> 0.78f
                else -> 0.88f
            }
            // Challenge D.2 Solution: Tilted / Angled Speech Bubble Geometry Compensation (5°..45°).
            // Shrinks target layout rectangle by cos(|theta|) so rotated text stays inside diagonal bubble walls.
            val absRot = abs(region.rotationDegrees)
            val tiltScale = if (absRot in 5f..45f) {
                kotlin.math.cos(Math.toRadians(absRot.toDouble())).toFloat().coerceIn(0.76f, 0.96f)
            } else {
                1.0f
            }
            val targetWidth = (boxWidth * widthRatio * tiltScale).toInt().coerceAtLeast(10)
            val targetHeight = (boxHeight * 0.82f * tiltScale).toInt().coerceAtLeast(10)

            val origHangulH = region.boundingBox.height() * height
            val numKoreanLines = maxOf(1, if (origHangulH > boxWidth * 0.85f && rawText.length > 20) 3 else if (rawText.length > 10) 2 else 1)
            val hangulLineH = origHangulH / (numKoreanLines + 0.35f * (numKoreanLines - 1))
            val targetCapHeightPx = hangulLineH * 0.85f

            val dynamicMaxSp = when {
                hasContainer && charCount <= 10 -> MAX_EXCLAIM_FONT_SP
                hasContainer && charCount <= 26 -> MAX_SHORT_DIALOGUE_SP
                else -> MAX_FONT_SP
            }
            val geometricMaxSp = (kotlin.math.min(boxWidth, boxHeight) * 0.26f / pageScale).coerceIn(16f, dynamicMaxSp)
            val targetSp = (targetCapHeightPx / pageScale).coerceIn(14f, dynamicMaxSp)
            val maxSpForBox = maxOf(targetSp, geometricMaxSp).coerceIn(MIN_FONT_SP + 2f, dynamicMaxSp)
            var low = MIN_FONT_SP
            var high = maxSpForBox
            var bestSize = low
            var bestLayout: StaticLayout? = null

            // Challenge D.1 Solution: 4-Mode Mood & Genre Typography Differentiation
            // 1. System / RPG Notification Window: [ ... ] or < ... > -> Bold Monospace with tech tracking
            // 2. Scream / Shout: !?, ?!, !!, or short ! -> Bold Italic with 1.04x horizontal punch
            // 3. Whisper / Hesitation / Thought: starts or ends with "..." without "!" -> Italic with softer charcoal ink
            // 4. Standard Spoken Dialogue -> Bold Comic Typeface
            val isSystemWindow = (rawText.startsWith("[") && rawText.endsWith("]")) ||
                (rawText.startsWith("<") && rawText.endsWith(">"))
            val isScreamOrShout = !isSystemWindow && (
                rawText.endsWith("!?") || rawText.endsWith("?!") ||
                rawText.endsWith("!!") || (rawText.endsWith("!") && rawText.length <= 15)
            )
            val isWhisperOrThought = !isSystemWindow && !isScreamOrShout && (
                rawText.startsWith("...") || rawText.startsWith("…") ||
                (rawText.endsWith("...") && !rawText.contains("!"))
            )

            val activeTypeface = when {
                isSystemWindow -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                isScreamOrShout -> Typeface.create(comicTypeface, Typeface.BOLD_ITALIC)
                isWhisperOrThought -> Typeface.create(comicTypeface, Typeface.ITALIC)
                else -> comicTypeface
            }

            val textPaint = TextPaint().apply {
                color = Color.BLACK
                typeface = activeTypeface
                textScaleX = if (isScreamOrShout) 1.04f else 1.0f
                letterSpacing = if (isSystemWindow) 0.04f else 0.0f
                fontFeatureSettings = "'calt' 1, 'liga' 1"
                isAntiAlias = true
            }

            val alignment = if (region.type == mihon.feature.translation.model.RegionType.TEXT_IN_BUBBLE) {
                Layout.Alignment.ALIGN_CENTER
            } else if (boxWidth > boxHeight * 1.8f) {
                Layout.Alignment.ALIGN_NORMAL
            } else {
                Layout.Alignment.ALIGN_CENTER
            }

            // Two-pass dynamic ellipse indents calculated inside the binary search per candidate size
            for (iter in 0 until 10) {
                val mid = (low + high) / 2f
                textPaint.textSize = mid * pageScale

                val unindented = buildLayout(text, textPaint, targetWidth, alignment, 1.10f)
                val candidateLayout = if (isBubble && unindented.lineCount > 1 && targetWidth >= 40) {
                    val indents = computeEllipseIndents(unindented.lineCount, targetWidth)
                    buildLayout(text, textPaint, targetWidth, alignment, 1.10f, indents)
                } else {
                    unindented
                }

                if (candidateLayout.height <= targetHeight) {
                    bestSize = mid
                    bestLayout = candidateLayout
                    low = mid + 0.25f // try larger
                } else {
                    high = mid - 0.25f // try smaller
                }
            }

            if (bestLayout == null) {
                // Clamped at MIN_FONT_SP: try standard 1.10f line spacing first
                textPaint.textSize = MIN_FONT_SP * pageScale
                val unindentedNormal = buildLayout(text, textPaint, targetWidth, alignment, 1.10f)
                val normalLayout = if (isBubble && unindentedNormal.lineCount > 1 && targetWidth >= 40) {
                    val indents = computeEllipseIndents(unindentedNormal.lineCount, targetWidth)
                    buildLayout(text, textPaint, targetWidth, alignment, 1.10f, indents)
                } else {
                    unindentedNormal
                }

                if (normalLayout.height <= targetHeight) {
                    bestLayout = normalLayout
                } else {
                    // Tighten line spacing to 0.92f only when clamped at MIN_FONT_SP and still overflowing
                    val unindentedTight = buildLayout(text, textPaint, targetWidth, alignment, 0.92f)
                    bestLayout = if (isBubble && unindentedTight.lineCount > 1 && targetWidth >= 40) {
                        val indents = computeEllipseIndents(unindentedTight.lineCount, targetWidth)
                        buildLayout(text, textPaint, targetWidth, alignment, 0.92f, indents)
                    } else {
                        unindentedTight
                    }
                }
                bestSize = MIN_FONT_SP
            }


            val wasClamped = bestSize <= MIN_FONT_SP
            val didOverflow = bestLayout.height > boxHeight

            // Center vertically within the speech bubble box
            val drawX = boxLeft + (boxWidth - targetWidth) / 2f
            val drawY = boxTop + (boxHeight - bestLayout.height) / 2f

            // Sample background luminance in cleanedBitmap around the box to determine contrast
            val centerX = (boxLeft + boxRight) / 2
            val centerY = (boxTop + boxBottom) / 2
            var totalLum = 0L
            var sampleCount = 0
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val sx = (centerX + dx * (boxWidth / 6)).coerceIn(0, width - 1)
                    val sy = (centerY + dy * (boxHeight / 6)).coerceIn(0, height - 1)
                    val p = cleanedBitmap.getPixel(sx, sy)
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    totalLum += lum
                    sampleCount++
                }
            }
            val avgLum = if (sampleCount > 0) totalLum / sampleCount else 255L
            val isDarkBackground = avgLum < 120

            canvas.save()
            if (abs(region.rotationDegrees) > 1f) {
                canvas.rotate(region.rotationDegrees, boxLeft + boxWidth / 2f, boxTop + boxHeight / 2f)
            }
            canvas.translate(drawX, drawY)

            if (isDarkBackground) {
                // Dark background (black panels, night scenes, dark narration box):
                // Draw bold black stroke first for halo contrast, then crisp white text
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeJoin = Paint.Join.ROUND
                textPaint.strokeWidth = (textPaint.textSize * 0.16f).coerceIn(2.5f, 6f)
                textPaint.color = Color.BLACK
                bestLayout.draw(canvas)

                textPaint.style = Paint.Style.FILL
                textPaint.color = Color.WHITE
                bestLayout.draw(canvas)
            } else {
                if (region.type == mihon.feature.translation.model.RegionType.FREE_TEXT || avgLum < 185L) {
                    // Free text on art OR shaded/grey speech bubble (avgLum 120..184):
                    // draw a crisp white halo outline behind dark lettering for scanlation contrast
                    textPaint.style = Paint.Style.STROKE
                    textPaint.strokeJoin = Paint.Join.ROUND
                    textPaint.strokeWidth = if (region.type == mihon.feature.translation.model.RegionType.FREE_TEXT) {
                        (textPaint.textSize * 0.14f).coerceIn(2f, 5f)
                    } else {
                        (textPaint.textSize * 0.10f).coerceIn(2f, 3.5f)
                    }
                    textPaint.color = Color.WHITE
                    bestLayout.draw(canvas)
                }
                textPaint.style = Paint.Style.FILL
                textPaint.color = if (isWhisperOrThought) Color.rgb(42, 42, 46) else Color.BLACK
                bestLayout.draw(canvas)
            }
            canvas.restore()

            layoutResults.add(
                TypesettingResult(
                    regionId = region.id,
                    fontSizeSp = bestSize,
                    wasClampedToMinSize = wasClamped,
                    didOverflowBounds = didOverflow,
                ),
            )
        }

        return Pair(typesetBitmap, layoutResults)
    }

    private fun computeEllipseIndents(lineCount: Int, targetWidth: Int): IntArray {
        val halfW = targetWidth / 2f
        val maxInset = (targetWidth * 0.25f).toInt()
        return IntArray(lineCount) { i ->
            val t = 2.0f * ((i + 0.5f) / lineCount - 0.5f)
            val sqrtTerm = kotlin.math.sqrt((1.0f - t * t).coerceAtLeast(0f))
            val rawInset = halfW * (1.0f - sqrtTerm)
            rawInset.toInt().coerceIn(0, maxInset)
        }
    }

    private fun buildLayout(
        text: CharSequence,
        textPaint: TextPaint,
        targetWidth: Int,
        alignment: Layout.Alignment,
        lineSpacingMultiplier: Float,
        indents: IntArray? = null,
    ): StaticLayout {
        val builder = StaticLayout.Builder.obtain(
            text,
            0,
            text.length,
            textPaint,
            targetWidth,
        )
            .setAlignment(alignment)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(false)

        if (indents != null) {
            builder.setIndents(indents, indents)
        }
        return builder.build()
    }

    override fun close() {
        // No native session to close
    }
}