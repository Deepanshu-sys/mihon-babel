package mihon.feature.translation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.translation.model.TranslationPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationQueueTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `TranslationJob serializes and deserializes accurately`() {
        val job = TranslationJob(
            chapterId = 1012L,
            chapterName = "Chapter 1",
            chapterUrl = "https://example.com/ch1",
            mangaTitle = "Test Manga",
            scanlator = "ScanGroup",
            sourceId = 1L,
            forceReprocess = false,
        )

        val serialized = json.encodeToString(job)
        val deserialized = json.decodeFromString<TranslationJob>(serialized)

        assertEquals(job, deserialized)
    }

    @Test
    fun `Monotonic progress calculation never decreases across phases`() {
        var currentPercent = 0

        val phases = listOf(
            TranslationPhase.DOWNLOADING to listOf(0 to 1, 1 to 1),
            TranslationPhase.SCANNING to listOf(1 to 10, 5 to 10, 10 to 10),
            TranslationPhase.TRANSLATING to listOf(1 to 5, 3 to 5, 5 to 5),
            TranslationPhase.RENDERING to listOf(1 to 10, 8 to 10, 10 to 10),
            TranslationPhase.FINALIZING to listOf(10 to 10),
        )

        for ((phase, steps) in phases) {
            for ((current, total) in steps) {
                val unitProgress = (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                val baseAndSpan = when (phase) {
                    TranslationPhase.DOWNLOADING -> 0 to 5
                    TranslationPhase.SCANNING -> 5 to 25
                    TranslationPhase.TRANSLATING -> 30 to 30
                    TranslationPhase.RENDERING -> 60 to 38
                    TranslationPhase.FINALIZING -> 98 to 2
                }
                val computedPercent = (baseAndSpan.first + (unitProgress * baseAndSpan.second).toInt()).coerceIn(0, 100)
                val newPercent = maxOf(currentPercent, computedPercent)

                assertTrue(newPercent >= currentPercent, "Progress must only increase: previous=$currentPercent, new=$newPercent at $phase $current/$total")
                currentPercent = newPercent
            }
        }

        assertEquals(100, currentPercent)
    }
}
