package mihon.feature.translation.pipeline

import mihon.feature.translation.model.DetectedRegion
import mihon.feature.translation.model.OcrResult
import kotlin.math.abs
import kotlin.math.min

/**
 * Natural reading order sorter for webtoons and comics.
 * Groups speech bubbles into vertical reading bands when their vertical centers overlap,
 * then sorts:
 * - Korean ("ko"), Chinese ("zh"), English ("en"): Left-to-Right (LTR, centerX ascending)
 * - Japanese ("ja"): Right-to-Left (RTL, centerX descending)
 */
object ReadingOrderSorter {

    fun sortPairs(
        pairs: List<Pair<DetectedRegion, OcrResult>>,
        languageCode: String = "ko",
    ): List<Pair<DetectedRegion, OcrResult>> {
        if (pairs.size <= 1) return pairs

        val isRtl = languageCode.startsWith("ja", ignoreCase = true)

        // Step 1: Initial sort by vertical center
        val ySorted = pairs.sortedBy { it.first.boundingBox.centerY() }

        // Step 2: Group into vertical reading bands
        val bands = mutableListOf<MutableList<Pair<DetectedRegion, OcrResult>>>()
        for (item in ySorted) {
            val currentBand = bands.lastOrNull()
            if (currentBand == null) {
                bands.add(mutableListOf(item))
            } else {
                val bandAnchor = currentBand.first()
                val minH = min(bandAnchor.first.boundingBox.height(), item.first.boundingBox.height()).coerceAtLeast(0.02f)
                val verticalDelta = abs(item.first.boundingBox.centerY() - bandAnchor.first.boundingBox.centerY())

                // If vertical centers are within 45% of the bubble height, they belong to the same scene band
                if (verticalDelta <= (minH * 0.45f)) {
                    currentBand.add(item)
                } else {
                    bands.add(mutableListOf(item))
                }
            }
        }

        // Step 3: Sort within each band by horizontal center
        val ordered = mutableListOf<Pair<DetectedRegion, OcrResult>>()
        for (band in bands) {
            if (isRtl) {
                band.sortByDescending { it.first.boundingBox.centerX() }
            } else {
                band.sortBy { it.first.boundingBox.centerX() }
            }
            ordered.addAll(band)
        }

        return ordered.mapIndexed { index, (region, ocr) ->
            region.copy(readingOrder = index) to ocr
        }
    }
}
