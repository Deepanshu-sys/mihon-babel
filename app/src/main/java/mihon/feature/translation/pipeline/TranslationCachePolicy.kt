package mihon.feature.translation.pipeline

import mihon.feature.translation.model.ChapterTranslationManifest

/** Reuse page output only when its page count and every rendering model match the current job. */
internal object TranslationCachePolicy {
    fun canReuse(
        manifest: ChapterTranslationManifest?,
        totalPages: Int,
        detectorModelId: String,
        ocrModelId: String,
        translatorModelId: String,
        inpainterModelId: String,
        forceReprocess: Boolean,
    ): Boolean = manifest != null &&
        !forceReprocess &&
        manifest.totalPages == totalPages &&
        manifest.detectorModelId == detectorModelId &&
        manifest.ocrModelId == ocrModelId &&
        manifest.translatorModelId == translatorModelId &&
        manifest.inpainterModelId == inpainterModelId
}
