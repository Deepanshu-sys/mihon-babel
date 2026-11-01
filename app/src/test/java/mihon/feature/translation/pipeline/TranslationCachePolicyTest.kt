package mihon.feature.translation.pipeline

import mihon.feature.translation.model.ChapterTranslationManifest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationCachePolicyTest {

    private val manifest = ChapterTranslationManifest(
        chapterId = 10L,
        totalPages = 12,
        detectorModelId = "detector-v1",
        ocrModelId = "ocr-v1",
        translatorModelId = "gaon-v1",
        inpainterModelId = "lama-v1",
    )

    @Test
    fun `reuses outputs when page count and model pipeline match`() {
        assertTrue(
            TranslationCachePolicy.canReuse(
                manifest = manifest,
                totalPages = 12,
                detectorModelId = "detector-v1",
                ocrModelId = "ocr-v1",
                translatorModelId = "gaon-v1",
                inpainterModelId = "lama-v1",
                forceReprocess = false,
            ),
        )
    }

    @Test
    fun `invalidates cached pages when translator changes`() {
        assertFalse(
            TranslationCachePolicy.canReuse(
                manifest = manifest,
                totalPages = 12,
                detectorModelId = "detector-v1",
                ocrModelId = "ocr-v1",
                translatorModelId = "qwen-v1",
                inpainterModelId = "lama-v1",
                forceReprocess = false,
            ),
        )
    }

    @Test
    fun `force reprocess bypasses a matching cache`() {
        assertFalse(
            TranslationCachePolicy.canReuse(
                manifest = manifest,
                totalPages = 12,
                detectorModelId = "detector-v1",
                ocrModelId = "ocr-v1",
                translatorModelId = "gaon-v1",
                inpainterModelId = "lama-v1",
                forceReprocess = true,
            ),
        )
    }

    @Test
    fun `invalidates cached pages when chapter page count changes`() {
        assertFalse(
            TranslationCachePolicy.canReuse(
                manifest = manifest,
                totalPages = 13,
                detectorModelId = "detector-v1",
                ocrModelId = "ocr-v1",
                translatorModelId = "gaon-v1",
                inpainterModelId = "lama-v1",
                forceReprocess = false,
            ),
        )
    }
}
