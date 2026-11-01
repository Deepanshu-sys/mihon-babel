package mihon.feature.translation.pipeline

import android.content.Context
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages extraction and hash verification of bundled ML models from APK assets
 * into app-private storage.
 */
class ModelAssetManager(
    private val context: Context,
    private val onCopyProgress: (fileName: String, copiedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
) {

    data class ModelAsset(
        val assetPath: String,
        val targetFileName: String,
        val expectedSha256: String,
        val requiredBytes: Long,
    )

    companion object {
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val COPY_PROGRESS_INTERVAL_BYTES = 16L * 1024 * 1024

        val ASSETS = listOf(
            ModelAsset(
                assetPath = "models/encoder_model_quantized.onnx",
                targetFileName = "encoder_model_quantized.onnx",
                expectedSha256 = "210E1D2CB5B457CDEEFFE288099CC9D1DF9F13C35DC3D39FDE4BA252AF877380",
                requiredBytes = 52899741L,
            ),
            ModelAsset(
                assetPath = "models/decoder_model_merged_quantized.onnx",
                targetFileName = "decoder_model_merged_quantized.onnx",
                expectedSha256 = "E131732653A6D3CEC42A8B65F8844980042B036BDACA931446BC4B3530BC3506",
                requiredBytes = 60212803L,
            ),
            ModelAsset(
                assetPath = "models/source.spm",
                targetFileName = "source.spm",
                expectedSha256 = "C9496F7C2BE9AECB84C751AE9F35A875915DDE8E3892F652A5C76811AB2A0F49",
                requiredBytes = 841805L,
            ),
            ModelAsset(
                assetPath = "models/target.spm",
                targetFileName = "target.spm",
                expectedSha256 = "F1531AC8576FE64267DFCA0DC33950A6E9A2D3FD9E05346558AD7EA5EE0E65BF",
                requiredBytes = 813126L,
            ),
            ModelAsset(
                assetPath = "models/vocab.json",
                targetFileName = "vocab.json",
                expectedSha256 = "97197044D4803C664A37F8CA2CED9C3405B8A3DF6B262A12DA22D990B0D756CD",
                requiredBytes = 1849870L,
            ),
            ModelAsset(
                assetPath = "models/comic-text-bubble-detector.onnx",
                targetFileName = "comic-text-bubble-detector.onnx",
                expectedSha256 = "5FE9E4F576E49D4E7E8B0E029D6D3CDC252ABD4694113E1CAE120E62C931EA79",
                requiredBytes = 11120765L,
            ),
            ModelAsset(
                assetPath = "models/lama-manga-fp16.onnx",
                targetFileName = "lama-manga-fp16.onnx",
                expectedSha256 = "043946AFD8C66DB5680A76266A4FD5269149A7A91F6D45357B63FB429E340E1E",
                requiredBytes = 108818588L,
            ),
            ModelAsset(
                assetPath = "models/index_translate_2b.gguf",
                targetFileName = "index_translate_2b.gguf",
                expectedSha256 = "044B313D29342BD3B2C77CBB64023CA0D209BD9B3247763F9D162767EF2D746A",
                requiredBytes = 1312164352L,
            ),
        )

        private val extractionLocks = ConcurrentHashMap<String, Any>()
    }

    private val modelsDir: File
        get() = File(context.filesDir, "translation_models").apply { mkdirs() }

    private val externalModelDirs: List<File>
        get() = listOfNotNull(
            context.getExternalFilesDir("models"),
            File("/sdcard/Android/data/${context.packageName}/files/models"),
            File("/sdcard/Android/data/app.mihon.at/files/models"),
            File("/sdcard/MihonModels"),
            File("/sdcard/Download/MihonModels"),
        )

    private val assetsByFileName = ASSETS.associateBy(ModelAsset::targetFileName)

    fun findExternalModelFile(fileName: String): File? {
        for (dir in externalModelDirs) {
            val file = File(dir, fileName)
            if (file.isFile && file.length() > 0) {
                return file
            }
        }
        return null
    }

    fun getModelFile(fileName: String): File {
        val external = findExternalModelFile(fileName)
        if (external != null) {
            return external
        }
        return File(modelsDir, fileName)
    }

    /**
     * Extracts only the requested bundled models. A small app-private verification stamp
     * avoids hashing large weights again on every chapter translation. The model directory
     * is app-private; if the file size or stamp changes, the content is hashed again.
     */
    fun ensureModelsExtracted(vararg targetFileNames: String): Result<Unit> = runCatching {
        require(targetFileNames.isNotEmpty()) { "At least one translation model must be requested" }
        val requested = targetFileNames.toSet()

        for (fileName in requested) {
            val external = findExternalModelFile(fileName)
            if (external != null) {
                logcat(LogPriority.INFO) { "ModelAssetManager: using external model file for $fileName: ${external.absolutePath} (${external.length()} bytes)" }
                continue
            }

            val asset = assetsByFileName[fileName]
            if (asset == null) {
                logcat(LogPriority.WARN) { "ModelAssetManager: unlisted model requested: $fileName, checking file existence" }
                val targetFile = File(modelsDir, fileName)
                if (targetFile.isFile && targetFile.length() > 0) continue
                error("Translation model $fileName not found in external models or bundled assets")
            }

            val lock = extractionLocks.computeIfAbsent(fileName) { Any() }
            synchronized(lock) {
                ensureExtracted(asset)
            }
        }
    }

    private fun ensureExtracted(asset: ModelAsset) {
        val external = findExternalModelFile(asset.targetFileName)
        if (external != null) return

        val directory = modelsDir
        val targetFile = File(directory, asset.targetFileName)
        val stampFile = File(directory, "${asset.targetFileName}.verified")
        val expectedStamp = "${asset.expectedSha256.uppercase()}:${asset.requiredBytes}"

        if (targetFile.isFile && targetFile.length() == asset.requiredBytes) {
            if (stampFile.isFile && stampFile.readText(Charsets.US_ASCII) == expectedStamp) return

            // Existing files from an older build have no stamp; verify once, then cache it.
            if (computeSha256(targetFile).equals(asset.expectedSha256, ignoreCase = true)) {
                writeStampAtomically(stampFile, expectedStamp)
                return
            }
        }

        val tmpFile = File(directory, "${asset.targetFileName}.tmp")
        tmpFile.delete()
        try {
            val assetStream = try {
                context.assets.open(asset.assetPath)
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Asset ${asset.assetPath} not in APK; checking external storage again" }
                findExternalModelFile(asset.targetFileName)?.let { return }
                throw IllegalStateException("Model ${asset.targetFileName} not found in APK assets or external models folder (/sdcard/Android/data/app.mihon.babel/files/models/ or /sdcard/MihonModels/)", e)
            }

            assetStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copiedBytes = 0L
                    var lastReportedBytes = 0L
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        output.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                        if (copiedBytes - lastReportedBytes >= COPY_PROGRESS_INTERVAL_BYTES) {
                            reportCopyProgress(asset.targetFileName, copiedBytes, asset.requiredBytes)
                            lastReportedBytes = copiedBytes
                        }
                    }
                    if (copiedBytes != lastReportedBytes) {
                        reportCopyProgress(asset.targetFileName, copiedBytes, asset.requiredBytes)
                    }
                    output.fd.sync()
                }
            }

            val tmpHash = computeSha256(tmpFile)
            if (!tmpHash.equals(asset.expectedSha256, ignoreCase = true)) {
                error("Hash mismatch for asset ${asset.assetPath}: got $tmpHash, expected ${asset.expectedSha256}")
            }

            try {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmpFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            writeStampAtomically(stampFile, expectedStamp)
            logcat(LogPriority.INFO) { "Extracted and verified model ${asset.targetFileName}" }
        } finally {
            tmpFile.delete()
        }
    }

    private fun writeStampAtomically(stampFile: File, contents: String) {
        val tmpStamp = File(stampFile.parentFile, "${stampFile.name}.tmp")
        FileOutputStream(tmpStamp).use { output ->
            output.write(contents.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        try {
            Files.move(
                tmpStamp.toPath(),
                stampFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmpStamp.toPath(), stampFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmpStamp.delete()
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun reportCopyProgress(fileName: String, copiedBytes: Long, totalBytes: Long) {
        // Progress reporting is optional and must never make model extraction fail.
        runCatching { onCopyProgress(fileName, copiedBytes, totalBytes) }
    }

}
