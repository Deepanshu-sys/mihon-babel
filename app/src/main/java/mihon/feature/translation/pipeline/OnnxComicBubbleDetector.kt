package mihon.feature.translation.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import mihon.feature.translation.model.RegionType
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Local RT-DETR comic detector for speech balloons and free lettering. */
class OnnxComicBubbleDetector(
    modelAssetManager: ModelAssetManager,
) : AutoCloseable {

    data class Candidate(
        val type: RegionType,
        val bounds: RectF,
        val confidence: Float,
        val isModelDetected: Boolean = true,
        val id: String = "${type.name.lowercase()}_${(bounds.left * 1000).toInt()}_${(bounds.top * 1000).toInt()}_${(bounds.right * 1000).toInt()}_${(bounds.bottom * 1000).toInt()}",
    )

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        modelAssetManager.ensureModelsExtracted("comic-text-bubble-detector.onnx").getOrThrow()
        val modelFile = modelAssetManager.getModelFile("comic-text-bubble-detector.onnx")
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(true)
            options.setCPUArenaAllocator(true)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Phase-Major Unload closes the detector at the end of Phase 1; enabling intra-op spinning
            // keeps the 4 Cortex-A715 performance cores at peak clock across RT-DETR transformer layers.
            runCatching { options.addConfigEntry("session.intra_op.allow_spinning", "1") }
            session = environment.createSession(modelFile.absolutePath, options)
        }
    }

    suspend fun detect(bitmap: Bitmap): List<Candidate> {
        if (bitmap.isRecycled) return emptyList()
        val candidates = buildList {
            for (tile in makeTiles(bitmap.width, bitmap.height)) {
                currentCoroutineContext().ensureActive()
                if (bitmap.isRecycled) break
                addAll(detectTile(bitmap, tile))
            }
        }
        return suppressOverlaps(candidates)
    }

    private suspend fun detectTile(page: Bitmap, tile: Rect): List<Candidate> {
        if (page.isRecycled) return emptyList()
        val crop = if (tile.left == 0 && tile.top == 0 && tile.width() == page.width && tile.height() == page.height) {
            page
        } else {
            Bitmap.createBitmap(page, tile.left, tile.top, tile.width(), tile.height())
        }
        val modelBitmap = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
        val pixelData = IntArray(INPUT_SIZE * INPUT_SIZE)
        val inputData = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        try {
            modelBitmap.getPixels(pixelData, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val planeSize = INPUT_SIZE * INPUT_SIZE
            for (index in pixelData.indices) {
                val pixel = pixelData[index]
                inputData[index] = android.graphics.Color.red(pixel) / 255f
                inputData[planeSize + index] = android.graphics.Color.green(pixel) / 255f
                inputData[planeSize * 2 + index] = android.graphics.Color.blue(pixel) / 255f
            }
        } finally {
            if (modelBitmap !== crop && !modelBitmap.isRecycled) modelBitmap.recycle()
            if (crop !== page && !crop.isRecycled) crop.recycle()
        }

        val imageTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
        val sizeTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(tile.width().toLong(), tile.height().toLong())),
            longArrayOf(1, 2),
        )

        try {
            logcat(LogPriority.INFO) {
                "Comic detector ONNX: run tile=${tile.width()}x${tile.height()} page=${page.width}x${page.height}"
            }
            session.run(mapOf("images" to imageTensor, "orig_target_sizes" to sizeTensor)).use { result ->
                logcat(LogPriority.INFO) { "Comic detector ONNX: output tensors received" }
                val labels = (result.get("labels").orElseThrow() as OnnxTensor).longBuffer
                val boxes = (result.get("boxes").orElseThrow() as OnnxTensor).floatBuffer
                val scores = (result.get("scores").orElseThrow() as OnnxTensor).floatBuffer
                val candidates = ArrayList<Candidate>()
                for (index in 0 until DETECTION_COUNT) {
                    val label = labels.get(index).toInt()
                    val score = scores.get(index)
                    if (score < MIN_SCORE) continue
                    val type = when (label) {
                        LABEL_BUBBLE -> RegionType.BUBBLE
                        LABEL_TEXT_IN_BUBBLE -> RegionType.TEXT_IN_BUBBLE
                        LABEL_TEXT_FREE -> RegionType.FREE_TEXT
                        else -> continue
                    }
                    val offset = index * 4
                    val left = boxes.get(offset).coerceIn(0f, tile.width().toFloat()) + tile.left
                    val top = boxes.get(offset + 1).coerceIn(0f, tile.height().toFloat()) + tile.top
                    val right = boxes.get(offset + 2).coerceIn(0f, tile.width().toFloat()) + tile.left
                    val bottom = boxes.get(offset + 3).coerceIn(0f, tile.height().toFloat()) + tile.top
                    if (right <= left || bottom <= top) continue
                    candidates += Candidate(
                        type = type,
                        bounds = RectF(
                            (left / page.width).coerceIn(0f, 1f),
                            (top / page.height).coerceIn(0f, 1f),
                            (right / page.width).coerceIn(0f, 1f),
                            (bottom / page.height).coerceIn(0f, 1f),
                        ),
                        confidence = score,
                    )
                }
                return candidates
            }
        } finally {
            imageTensor.close()
            sizeTensor.close()
        }
    }

    private fun makeTiles(width: Int, height: Int): List<Rect> {
        val shortSide = minOf(width, height)
        val longSide = maxOf(width, height)
        if (longSide <= shortSide * MAX_ASPECT_RATIO) return listOf(Rect(0, 0, width, height))

        // Keep the detector's input distortion bounded for long vertical webtoon pages.
        val window = (shortSide * MAX_ASPECT_RATIO).toInt().coerceAtLeast(1).coerceAtMost(longSide)
        val stride = (window * TILE_STRIDE_RATIO).toInt().coerceAtLeast(1)
        val starts = mutableListOf(0)
        while (starts.last() + window < longSide) {
            val next = (starts.last() + stride).coerceAtMost(longSide - window)
            if (next == starts.last()) break
            starts += next
        }
        return starts.map { start ->
            if (height >= width) Rect(0, start, width, (start + window).coerceAtMost(height))
            else Rect(start, 0, (start + window).coerceAtMost(width), height)
        }
    }

    private fun suppressOverlaps(candidates: List<Candidate>): List<Candidate> {
        val kept = mutableListOf<Candidate>()
        for (candidate in candidates.sortedByDescending(Candidate::confidence)) {
            val duplicate = kept.any { existing ->
                existing.type == candidate.type && intersectionOverUnion(existing.bounds, candidate.bounds) >= NMS_IOU
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private fun intersectionOverUnion(first: RectF, second: RectF): Float {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = first.width() * first.height() + second.width() * second.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() = session.close()

    private companion object {
        const val INPUT_SIZE = 640
        const val DETECTION_COUNT = 300
        const val MIN_SCORE = 0.30f
        const val LABEL_BUBBLE = 0
        const val LABEL_TEXT_IN_BUBBLE = 1
        const val LABEL_TEXT_FREE = 2
        const val MAX_ASPECT_RATIO = 2.0f
        const val TILE_STRIDE_RATIO = 0.75f
        const val NMS_IOU = 0.55f
    }
}
