package mihon.feature.translation.pipeline

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class LocalTranslationStorageTest {

    @Test
    fun `packChapterCbz packages all pages and produces valid cbz`(@TempDir tempDir: File) {
        val context = mockk<Context>()
        every { context.getExternalFilesDir(null) } returns tempDir
        every { context.filesDir } returns tempDir

        val storage = LocalTranslationStorage(context)
        val chapterId = 999L
        val chapterDir = storage.getChapterDir(chapterId)

        // Create 3 dummy page files
        for (i in 0 until 3) {
            val pageFile = File(chapterDir, "page_%03d.webp".format(i))
            pageFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }

        val cbz = storage.packChapterCbz(chapterId, 3)
        assertTrue(cbz.isFile)
        assertTrue(cbz.length() > 0)

        ZipFile(cbz).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf("page_000.webp", "page_001.webp", "page_002.webp"), entries)
        }
    }
}
