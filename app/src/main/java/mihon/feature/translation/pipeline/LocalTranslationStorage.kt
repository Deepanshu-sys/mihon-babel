package mihon.feature.translation.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.system.Os
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.translation.contract.TranslationStorage
import mihon.feature.translation.model.ChapterTranslationManifest
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Stores translated page files separately from Mihon's original chapter downloads. */
class LocalTranslationStorage(
    private val context: Context,
) : TranslationStorage {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val baseDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "translations").apply { mkdirs() }

    private val baseDirReadOnly: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "translations")

    fun getChapterRoot(chapterId: Long): File = File(baseDir, chapterId.toString()).apply { mkdirs() }

    fun getChapterRootReadOnly(chapterId: Long): File = File(baseDirReadOnly, chapterId.toString())

    fun getChapterDir(chapterId: Long): File {
        val root = getChapterRoot(chapterId)
        val currentFile = File(root, "CURRENT")
        if (currentFile.isFile && currentFile.length() > 0L) {
            val ver = runCatching { currentFile.readText(Charsets.UTF_8).trim() }.getOrNull()
            if (!ver.isNullOrBlank()) {
                val verDir = File(root, ver)
                if (verDir.isDirectory) return verDir
            }
        }
        return root
    }

    fun getChapterDirReadOnly(chapterId: Long): File {
        val root = getChapterRootReadOnly(chapterId)
        if (!root.isDirectory) return root
        val currentFile = File(root, "CURRENT")
        if (currentFile.isFile && currentFile.length() > 0L) {
            val ver = runCatching { currentFile.readText(Charsets.UTF_8).trim() }.getOrNull()
            if (!ver.isNullOrBlank()) {
                val verDir = File(root, ver)
                if (verDir.isDirectory) return verDir
            }
        }
        return root
    }

    fun getCustomFile(chapterId: Long, fileName: String): File = File(getChapterDir(chapterId), fileName)

    private fun getManifestFile(chapterId: Long): File = File(getChapterDir(chapterId), "manifest.json")

    private fun getManifestFileReadOnly(chapterId: Long): File = File(getChapterDirReadOnly(chapterId), "manifest.json")

    fun getPageFile(chapterId: Long, pageIndex: Int): File =
        File(getChapterDir(chapterId), "page_%03d.webp".format(pageIndex))

    fun getPageFileReadOnly(chapterId: Long, pageIndex: Int): File =
        File(getChapterDirReadOnly(chapterId), "page_%03d.webp".format(pageIndex))

    suspend fun savePageBytes(chapterId: Long, pageIndex: Int, bytes: ByteArray): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                require(pageIndex >= 0) { "Page index cannot be negative" }
                val targetFile = getPageFile(chapterId, pageIndex)
                val tmpFile = File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")

                try {
                    FileOutputStream(tmpFile).use { out ->
                        out.write(bytes)
                        out.flush()
                        try {
                            out.fd.sync()
                        } catch (_: Exception) {}
                    }
                    renameAtomic(tmpFile, targetFile)
                } catch (e: Exception) {
                    tmpFile.delete()
                    throw IllegalStateException("Failed to commit raw bytes for page $pageIndex", e)
                }
            }
        }

    override suspend fun savePageImage(chapterId: Long, pageIndex: Int, bitmap: Bitmap): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                require(pageIndex >= 0) { "Page index cannot be negative" }
                val targetFile = getPageFile(chapterId, pageIndex)
                val tmpFile = File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")

                try {
                    FileOutputStream(tmpFile).use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.WEBP, 95, out)) {
                            "Bitmap encoder failed for page $pageIndex"
                        }
                        out.fd.sync()
                    }
                    // Both files are in the same directory, so rename replaces the old file atomically.
                    renameAtomic(tmpFile, targetFile)
                } catch (e: Exception) {
                    tmpFile.delete()
                    throw IllegalStateException("Failed to commit translated page $pageIndex", e)
                }
            }
        }

    override suspend fun getPageStream(chapterId: Long, pageIndex: Int): InputStream? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getPageStreamSync(chapterId, pageIndex)
        }

    fun getPageStreamSync(chapterId: Long, pageIndex: Int): InputStream? {
        val file = getPageFileReadOnly(chapterId, pageIndex)
        return if (file.isFile && file.length() > 0L) file.inputStream() else null
    }

    override suspend fun isChapterTranslationComplete(chapterId: Long): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            isChapterTranslationCompleteSync(chapterId)
        }

    /** Completion means manifest is marked complete, totalPages > 0, and every expected page webp exists. */
    fun isChapterTranslationCompleteSync(chapterId: Long): Boolean {
        val manifest = getManifestSync(chapterId) ?: return false
        if (!manifest.isComplete || manifest.totalPages <= 0) return false
        for (pageIndex in 0 until manifest.totalPages) {
            val file = getPageFileReadOnly(chapterId, pageIndex)
            if (!file.isFile || file.length() == 0L) return false
        }
        return true
    }

    fun hasPageOutputSync(chapterId: Long, pageIndex: Int): Boolean {
        val file = getPageFileReadOnly(chapterId, pageIndex)
        return file.isFile && file.length() > 0L
    }

    fun hasAnyPageOutputSync(chapterId: Long): Boolean {
        val dir = getChapterDirReadOnly(chapterId)
        return dir.isDirectory && dir.listFiles { _, name -> name.startsWith("page_") && name.endsWith(".webp") }?.isNotEmpty() == true
    }

    override suspend fun saveManifest(manifest: ChapterTranslationManifest): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                require(manifest.totalPages >= 0) { "Page count cannot be negative" }
                val targetFile = getManifestFile(manifest.chapterId)
                val tmpFile = File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")
                try {
                    FileOutputStream(tmpFile).use { out ->
                        out.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                        out.fd.sync()
                    }
                    renameAtomic(tmpFile, targetFile)
                    if (manifest.isComplete) {
                        val targetParent = targetFile.parentFile ?: getChapterDir(manifest.chapterId)
                        val cbzFile = File(targetParent, "translated.cbz")
                        if (cbzFile.isFile && cbzFile.length() > 0L) {
                            promoteCompletedVersionInternal(manifest.chapterId, targetParent)
                        }
                    }
                } catch (e: Exception) {
                    tmpFile.delete()
                    throw IllegalStateException("Failed to atomically commit translation manifest", e)
                }
            }
        }

    fun promoteCompletedVersion(chapterId: Long) {
        val targetDir = getChapterDir(chapterId)
        val cbzFile = File(targetDir, "translated.cbz")
        if (cbzFile.isFile && cbzFile.length() > 0L) {
            promoteCompletedVersionInternal(chapterId, targetDir)
        }
    }

    private fun promoteCompletedVersionInternal(chapterId: Long, targetParent: File) {
        val root = getChapterRoot(chapterId)
        if (targetParent.absolutePath != root.absolutePath) {
            val currentPointer = File(root, "CURRENT")
            val currentTmp = File(root, "CURRENT.${System.nanoTime()}.tmp")
            FileOutputStream(currentTmp).use { out ->
                out.write(targetParent.name.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            renameAtomic(currentTmp, currentPointer)
            try {
                val dirFd = Os.open(root.absolutePath, android.system.OsConstants.O_RDONLY, 0)
                Os.fsync(dirFd)
                Os.close(dirFd)
            } catch (_: Throwable) {}
        }
    }

    fun packChapterCbz(
        chapterId: Long,
        totalPages: Int,
        customOutputFile: File? = null,
    ): File {
        require(totalPages > 0) { "Total pages must be > 0" }
        val chapterDir = getChapterDir(chapterId)
        val finalFile = customOutputFile ?: File(chapterDir, "translated.cbz")
        val parentDir = finalFile.parentFile ?: chapterDir
        val tmpFile = File(parentDir, "${finalFile.name}.${System.nanoTime()}.tmp")

        try {
            FileOutputStream(tmpFile).use { fos ->
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(fos)).use { zos ->
                    zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
                    for (i in 0 until totalPages) {
                        val pageFile = getPageFileReadOnly(chapterId, i)
                        check(pageFile.isFile && pageFile.length() > 0L) { "Missing page file $i" }
                        val entry = java.util.zip.ZipEntry("page_%03d.webp".format(i))
                        zos.putNextEntry(entry)
                        pageFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    zos.finish()
                    zos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Exception) {}
                }
            }

            check(tmpFile.length() > 0L) { "Temporary CBZ is empty" }

            java.util.zip.ZipFile(tmpFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                check(entries.size == totalPages) {
                    "Verification failed: expected $totalPages entries but got ${entries.size}"
                }
                for (i in 0 until totalPages) {
                    val expectedName = "page_%03d.webp".format(i)
                    val entry = entries[i]
                    check(entry.name == expectedName) {
                        "Verification failed: entry $i name is '${entry.name}', expected '$expectedName'"
                    }
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching {
                        zip.getInputStream(entry).use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                        }
                    }
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                        runCatching {
                            android.util.Log.w("LocalTranslationStorage", "Entry $i could not be decoded as image for bounds check (best-effort)")
                        }
                    }
                }
            }

            renameAtomic(tmpFile, finalFile)
            return finalFile
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    override suspend fun getManifest(chapterId: Long): ChapterTranslationManifest? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getManifestSync(chapterId)
        }

    private fun getManifestSync(chapterId: Long): ChapterTranslationManifest? {
        val file = getManifestFileReadOnly(chapterId)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            json.decodeFromString<ChapterTranslationManifest>(file.readText(Charsets.UTF_8))
        }.onFailure {
            android.util.Log.e("LocalTranslationStorage", "Failed to deserialize manifest for chapter $chapterId: ${it.message}", it)
        }.getOrNull()
    }

    private fun isManifestComplete(manifest: ChapterTranslationManifest): Boolean {
        return manifest.isComplete && manifest.totalPages > 0
    }

    override suspend fun deleteChapterTranslation(chapterId: Long): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dir = File(baseDir, chapterId.toString())
                if (dir.exists() && !dir.deleteRecursively()) {
                    error("Failed to delete translated chapter $chapterId")
                }
            }
        }

    fun getSeriesDir(mangaId: Long?, mangaTitle: String): File {
        val seriesRoot = File(baseDir, "series")
        val dirName = if (mangaId != null && mangaId > 0L) {
            mangaId.toString()
        } else {
            mangaTitle.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(64).ifBlank { "default" }
        }
        return File(seriesRoot, dirName).apply { mkdirs() }
    }

    fun getSeriesDirReadOnly(mangaId: Long?, mangaTitle: String): File {
        val seriesRoot = File(baseDirReadOnly, "series")
        val dirName = if (mangaId != null && mangaId > 0L) {
            mangaId.toString()
        } else {
            mangaTitle.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(64).ifBlank { "default" }
        }
        return File(seriesRoot, dirName)
    }

    fun getSeriesGlossary(mangaId: Long?, mangaTitle: String): Map<String, String> {
        val file = File(getSeriesDirReadOnly(mangaId, mangaTitle), "glossary.json")
        if (!file.isFile || file.length() == 0L) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyMap())
    }

    fun saveSeriesGlossary(mangaId: Long?, mangaTitle: String, glossary: Map<String, String>) {
        if (glossary.isEmpty()) return
        val dir = getSeriesDir(mangaId, mangaTitle)
        val file = File(dir, "glossary.json")
        val existing = getSeriesGlossary(mangaId, mangaTitle).toMutableMap()
        existing.putAll(glossary)
        runCatching {
            val text = json.encodeToString(existing)
            val tmp = File(dir, "glossary.json.${System.nanoTime()}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            renameAtomic(tmp, file)
        }
    }

    companion object {
        fun renameAtomic(src: File, dst: File) {
            try {
                Os.rename(src.absolutePath, dst.absolutePath)
            } catch (_: Throwable) {
                if (!src.renameTo(dst)) {
                    dst.delete()
                    if (!src.renameTo(dst)) {
                        error("Failed to rename ${src.absolutePath} to ${dst.absolutePath}")
                    }
                }
            }
        }
    }
}
