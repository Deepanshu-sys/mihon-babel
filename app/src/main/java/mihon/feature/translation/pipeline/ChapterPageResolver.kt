package mihon.feature.translation.pipeline

import android.content.Context
import com.hippo.unifile.UniFile
import mihon.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class ResolvedPages(
    val pageStreams: List<Pair<Int, () -> InputStream>>,
    val cleanup: () -> Unit = {},
    val sourceFile: UniFile? = null,
    val sourcePhysicalFile: File? = null,
    val sourceResolutionTier: Int = 0,
    val sourceFingerprint: String = "",
    val replacementEligible: Boolean = false,
)

class ChapterPageResolver(
    private val context: Context,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val storageManager: StorageManager,
    private val chapterCache: ChapterCache,
) {

    suspend fun resolvePageStreams(
        chapterId: Long,
        chapterName: String,
        chapterUrl: String,
        mangaTitle: String,
        scanlator: String?,
        sourceId: Long,
        onProgress: suspend (current: Int, total: Int, message: String) -> Unit,
    ): ResolvedPages = withContext(Dispatchers.IO) {
        val source = sourceManager.get(sourceId) ?: sourceManager.getOrStub(sourceId)

        // Tier 1: Try exact DownloadProvider directory
        var chapterFile: UniFile? = downloadProvider.findChapterDir(
            chapterName = chapterName,
            chapterScanlator = scanlator,
            chapterUrl = chapterUrl,
            mangaTitle = mangaTitle,
            source = source,
        )
        var tier = if (chapterFile != null) 1 else 0

        // Tier 2: Fuzzy match within DownloadProvider's manga directory
        if (chapterFile == null) {
            val mangaDir = downloadProvider.findMangaDir(mangaTitle, source)
            if (mangaDir != null && mangaDir.exists()) {
                chapterFile = fuzzyFindChapterInDirectory(mangaDir, chapterName, scanlator, chapterUrl)
                if (chapterFile != null) tier = 2
            }
        }

        // Tier 3: Search all sources in storageManager downloads folder (SAF)
        if (chapterFile == null) {
            val downloadsDir = storageManager.getDownloadsDirectory()
            if (downloadsDir != null && downloadsDir.exists()) {
                chapterFile = findChapterInUniDirectory(downloadsDir, mangaTitle, chapterName, scanlator, chapterUrl)
                if (chapterFile != null) tier = 3
            }
        }

        // Tier 3b: Search filesystem directly (handles permission boundaries & direct external storage)
        if (chapterFile == null && eu.kanade.tachiyomi.BuildConfig.DEBUG) {
            val candidatePaths = listOf(
                File("/storage/emulated/0/Temp/downloads"),
                File("/sdcard/Temp/downloads"),
                File("/storage/emulated/0/Temp/downloads/Manga18fx (ALL)"),
                File("/sdcard/Temp/downloads/Manga18fx (ALL)"),
                File("/storage/emulated/0/Download"),
                File("/sdcard/Download"),
                File("/storage/emulated/0/Download/Manga18fx (ALL)"),
                File("/sdcard/Download/Manga18fx (ALL)"),
                File("/storage/emulated/0/Mihon/downloads"),
                File("/sdcard/Mihon/downloads"),
                File("/storage/emulated/0/Tachiyomi/downloads"),
                File("/sdcard/Tachiyomi/downloads"),
                File("/storage/emulated/0/Temp"),
                File("/sdcard/Temp"),
            )

            for (candDir in candidatePaths) {
                if (!candDir.exists() || !candDir.isDirectory) continue
                val matchedFile = findChapterInFileDirectory(candDir, mangaTitle, chapterName, scanlator, chapterUrl)
                if (matchedFile != null) {
                    val uni = UniFile.fromFile(matchedFile)
                    if (uni != null && uni.exists()) {
                        chapterFile = uni
                        tier = 3
                        logcat(LogPriority.INFO) { "Found chapter in external filesystem path: ${matchedFile.absolutePath}" }
                        break
                    }
                }
            }
        }

        // Tier 4: Local manga directory
        if (chapterFile == null) {
            val localDir = storageManager.getLocalSourceDirectory()
            if (localDir != null && localDir.exists()) {
                chapterFile = findChapterInUniDirectory(localDir, mangaTitle, chapterName, scanlator, chapterUrl)
                if (chapterFile != null) tier = 4
            }
        }

        // If found on disk (directory or archive)
        if (chapterFile != null && chapterFile.exists()) {
            return@withContext extractPagesFromUniFile(chapterId, chapterFile, onProgress, tier)
        }

        // Tier 5: On-demand online download / stream fetch
        if (source is HttpSource) {
            logcat(LogPriority.INFO) { "Chapter not downloaded locally; fetching online pages from ${source.name}" }
            return@withContext fetchOnlinePages(chapterId, chapterName, chapterUrl, scanlator, source, onProgress)
        }

        error("Could not find or fetch chapter '$chapterName' for '$mangaTitle'")
    }

    private fun findChapterInUniDirectory(
        root: UniFile,
        mangaTitle: String,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
    ): UniFile? {
        val normTarget = normalizeString(mangaTitle)
        val children = root.listFiles().orEmpty()

        // 1. Direct children: check if root directly contains manga folders
        for (child in children) {
            if (!child.isDirectory) continue
            val normChild = normalizeString(child.name.orEmpty())
            if (isMangaTitleMatch(normChild, normTarget)) {
                val found = fuzzyFindChapterInDirectory(child, chapterName, scanlator, chapterUrl)
                if (found != null) return found
            }
        }

        // 2. Nested source directories (e.g. Manga18fx (ALL)/MangaName/)
        for (sourceDir in children) {
            if (!sourceDir.isDirectory) continue
            for (mangaDir in sourceDir.listFiles().orEmpty()) {
                if (!mangaDir.isDirectory) continue
                val normChild = normalizeString(mangaDir.name.orEmpty())
                if (isMangaTitleMatch(normChild, normTarget)) {
                    val found = fuzzyFindChapterInDirectory(mangaDir, chapterName, scanlator, chapterUrl)
                    if (found != null) return found
                }
            }
        }

        return null
    }

    private fun findChapterInFileDirectory(
        root: File,
        mangaTitle: String,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
    ): File? {
        val normTarget = normalizeString(mangaTitle)
        val children = root.listFiles() ?: return null

        // 1. Check if root directly contains manga folders
        for (child in children) {
            if (!child.isDirectory) continue
            val normChild = normalizeString(child.name)
            if (isMangaTitleMatch(normChild, normTarget)) {
                val found = fuzzyFindChapterInFile(child, chapterName, scanlator, chapterUrl)
                if (found != null) return found
            }
        }

        // 2. Nested source directories (e.g. Manga18fx (ALL)/MangaName/)
        for (sourceDir in children) {
            if (!sourceDir.isDirectory) continue
            val subChildren = sourceDir.listFiles() ?: continue
            for (mangaDir in subChildren) {
                if (!mangaDir.isDirectory) continue
                val normChild = normalizeString(mangaDir.name)
                if (isMangaTitleMatch(normChild, normTarget)) {
                    val found = fuzzyFindChapterInFile(mangaDir, chapterName, scanlator, chapterUrl)
                    if (found != null) return found
                }
            }
        }

        return null
    }

    private fun isMangaTitleMatch(normA: String, normB: String): Boolean {
        if (normA.isEmpty() || normB.isEmpty()) return false
        if (normA == normB) return true
        val strippedA = normA.removeSuffix("raw")
        val strippedB = normB.removeSuffix("raw")
        if (strippedA == strippedB) return true
        if (strippedA.contains(strippedB) || strippedB.contains(strippedA)) return true
        return false
    }

    private fun fuzzyFindChapterInDirectory(
        directory: UniFile,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
    ): UniFile? {
        val files = directory.listFiles().orEmpty()
        val normTarget = normalizeString(chapterName)
        val targetNumber = extractChapterNumber(chapterName)

        // 1. Exact normalized name match
        files.firstOrNull { normalizeString(it.name.orEmpty()) == normTarget }?.let { return it }
        files.firstOrNull { normalizeString(it.name?.substringBeforeLast('.').orEmpty()) == normTarget }?.let { return it }

        // 2. Scanlator prefix match
        if (!scanlator.isNullOrBlank()) {
            val normWithScanlator = normalizeString("${scanlator}_$chapterName")
            files.firstOrNull { normalizeString(it.name.orEmpty()) == normWithScanlator }?.let { return it }
        }

        // 3. Chapter number match (e.g. "Chapter 1" matches "1.cbz", "ch1", "Chapter 01")
        if (targetNumber != null) {
            files.firstOrNull { file ->
                val num = extractChapterNumber(file.name.orEmpty())
                num != null && num == targetNumber
            }?.let { return it }
        }

        // 4. URL path ending match (strict equality on normalized basename to prevent "ch-1" matching "ch-121")
        val urlEnding = chapterUrl.trimEnd('/').substringAfterLast('/')
        if (urlEnding.isNotBlank()) {
            val normUrl = normalizeString(urlEnding)
            files.firstOrNull {
                val base = normalizeString(it.name?.substringBeforeLast('.').orEmpty())
                base == normUrl
            }?.let { return it }
        }

        return null
    }

    private fun fuzzyFindChapterInFile(
        directory: File,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
    ): File? {
        val files = directory.listFiles() ?: return null
        val normTarget = normalizeString(chapterName)
        val targetNumber = extractChapterNumber(chapterName)

        // 1. Exact normalized name match
        files.firstOrNull { normalizeString(it.name) == normTarget }?.let { return it }
        files.firstOrNull { normalizeString(it.name.substringBeforeLast('.')) == normTarget }?.let { return it }

        // 2. Scanlator prefix match
        if (!scanlator.isNullOrBlank()) {
            val normWithScanlator = normalizeString("${scanlator}_$chapterName")
            files.firstOrNull { normalizeString(it.name) == normWithScanlator }?.let { return it }
        }

        // 3. Chapter number match (e.g. "Chapter 1" matches "1.cbz", "ch1", "Chapter 01", "Chapter 1.cbz")
        if (targetNumber != null) {
            files.firstOrNull { file ->
                val num = extractChapterNumber(file.name)
                num != null && num == targetNumber
            }?.let { return it }
        }

        // 4. URL path ending match (strict equality on normalized basename to prevent "ch-1" matching "ch-121")
        val urlEnding = chapterUrl.trimEnd('/').substringAfterLast('/')
        if (urlEnding.isNotBlank()) {
            val normUrl = normalizeString(urlEnding)
            files.firstOrNull {
                val base = normalizeString(it.name.substringBeforeLast('.'))
                base == normUrl
            }?.let { return it }
        }

        return null
    }

    private suspend fun extractPagesFromUniFile(
        chapterId: Long,
        file: UniFile,
        onProgress: suspend (current: Int, total: Int, message: String) -> Unit,
        sourceResolutionTier: Int = 0,
    ): ResolvedPages {
        val physicalFile = file.filePath?.let { File(it) }?.takeIf { it.isFile }
        val fingerprint = if (physicalFile != null) {
            "${physicalFile.canonicalPath}:${physicalFile.length()}:${physicalFile.lastModified()}"
        } else ""
        val eligible = sourceResolutionTier == 1 &&
            file.isFile &&
            (file.name?.endsWith(".cbz", ignoreCase = true) == true) &&
            physicalFile != null &&
            physicalFile.isFile

        if (file.isFile) {
            // Unpack archive (CBZ/ZIP) into a staging directory for 100% reliable random-access I/O
            val stageDir = File(context.cacheDir, "stage_chapter_$chapterId")
            stageDir.deleteRecursively()
            stageDir.mkdirs()

            onProgress(0, 0, "Extracting chapter archive...")
            logcat(LogPriority.INFO) { "Extracting chapter archive ${file.name} to ${stageDir.absolutePath}" }

            val reader = file.archiveReader(context)
            val imageNames = reader.useEntries { entries ->
                entries.filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                    .map { it.name }
                    .toList()
            }

            imageNames.forEachIndexed { index, entryName ->
                val outFile = File(stageDir, "%04d_%s".format(index, entryName.substringAfterLast('/')))
                reader.getInputStream(entryName)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val extractedFiles = stageDir.listFiles()
                ?.filter { it.isFile && ImageUtil.isImage(it.name) { it.inputStream() } }
                ?.sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                .orEmpty()

            require(extractedFiles.isNotEmpty()) { "Archive ${file.name} contained no valid images" }

            val streams = extractedFiles.mapIndexed { index, imgFile ->
                Pair(index) { FileInputStream(imgFile) }
            }

            return ResolvedPages(
                pageStreams = streams,
                cleanup = { stageDir.deleteRecursively() },
                sourceFile = file,
                sourcePhysicalFile = physicalFile,
                sourceResolutionTier = sourceResolutionTier,
                sourceFingerprint = fingerprint,
                replacementEligible = eligible,
            )
        } else {
            // Unpacked directory of images
            val files = file.listFiles()
                ?.filter { !it.isDirectory && it.name != null && ImageUtil.isImage(it.name!!) { it.openInputStream() } }
                ?.sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
                ?: emptyList()

            require(files.isNotEmpty()) { "Directory ${file.name} contained no valid images" }

            val streams = files.mapIndexed { index, imgFile ->
                Pair(index) { imgFile.openInputStream() }
            }

            return ResolvedPages(
                pageStreams = streams,
                sourceFile = file,
                sourcePhysicalFile = null,
                sourceResolutionTier = sourceResolutionTier,
                sourceFingerprint = "",
                replacementEligible = false,
            )
        }
    }

    private suspend fun fetchOnlinePages(
        chapterId: Long,
        chapterName: String,
        chapterUrl: String,
        scanlator: String?,
        source: HttpSource,
        onProgress: suspend (current: Int, total: Int, message: String) -> Unit,
    ): ResolvedPages {
        val stageDir = File(context.cacheDir, "online_chapter_$chapterId")
        stageDir.deleteRecursively()
        stageDir.mkdirs()

        val sChapter = SChapter.create().apply {
            url = chapterUrl
            name = chapterName
            this.scanlator = scanlator
        }

        onProgress(0, 0, "Fetching page list from ${source.name}...")
        val pages = source.getPageList(sChapter)
        require(pages.isNotEmpty()) { "Source ${source.name} returned an empty page list" }

        val total = pages.size
        pages.forEachIndexed { index, page ->
            onProgress(index + 1, total, "Downloading page ${index + 1}/$total from ${source.name}...")
            val outFile = File(stageDir, "page_%04d.jpg".format(index))

            // Check if page already cached by reader
            val imageUrl = page.imageUrl ?: source.getImageUrl(page)
            page.imageUrl = imageUrl

            val cachedFile = chapterCache.getImageFile(imageUrl)
            if (cachedFile != null && cachedFile.exists()) {
                cachedFile.copyTo(outFile, overwrite = true)
            } else {
                val imageResponse = source.getImage(page)
                imageResponse.body.byteStream().use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        val downloadedFiles = stageDir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            .orEmpty()

        require(downloadedFiles.size == total) { "Failed to download all pages from online source" }

        val streams = downloadedFiles.mapIndexed { index, imgFile ->
            Pair(index) { FileInputStream(imgFile) }
        }

        return ResolvedPages(
            pageStreams = streams,
            cleanup = { stageDir.deleteRecursively() },
        )
    }

    private fun normalizeString(text: String): String {
        return text.lowercase()
            .replace("’", "")
            .replace("‘", "")
            .replace("“", "")
            .replace("”", "")
            .replace("'", "")
            .replace("\"", "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun extractChapterNumber(name: String): Float? {
        val base = name.substringBeforeLast('.')
        val explicit = Regex("""(?:ch(?:apter)?|ep(?:isode)?|#)\s*0*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
            .findAll(base)
            .lastOrNull()
        if (explicit != null) return explicit.groupValues[1].toFloatOrNull()
        val standalone = Regex("""^0*([0-9]+(?:\.[0-9]+)?)$""").find(base.trim())
        return standalone?.groupValues?.getOrNull(1)?.toFloatOrNull()
    }
}
