package mihon.feature.translation.contract

import android.graphics.Bitmap
import mihon.feature.translation.model.ChapterTranslationManifest
import java.io.InputStream

/**
 * Storage contract for persisting translated pages, manifests, and diagnostics.
 * Keeps translated output isolated from raw downloaded files.
 */
interface TranslationStorage {
    /**
     * Atomically saves a finalized translated page image (e.g. WebP).
     */
    suspend fun savePageImage(
        chapterId: Long,
        pageIndex: Int,
        bitmap: Bitmap,
    ): Result<Unit>

    /**
     * Retrieves an [InputStream] for a completed translated page image, or null if not yet translated.
     */
    suspend fun getPageStream(
        chapterId: Long,
        pageIndex: Int,
    ): InputStream?

    /**
     * Checks if all pages for [chapterId] are completed and ready to be served.
     */
    suspend fun isChapterTranslationComplete(chapterId: Long): Boolean

    /**
     * Saves or updates the durable [ChapterTranslationManifest] for resumption and diagnostics.
     */
    suspend fun saveManifest(manifest: ChapterTranslationManifest): Result<Unit>

    /**
     * Loads the existing [ChapterTranslationManifest] for [chapterId], if present.
     */
    suspend fun getManifest(chapterId: Long): ChapterTranslationManifest?

    /**
     * Deletes all translated files and manifest for [chapterId].
     */
    suspend fun deleteChapterTranslation(chapterId: Long): Result<Unit>
}
