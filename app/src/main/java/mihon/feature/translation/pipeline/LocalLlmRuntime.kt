package mihon.feature.translation.pipeline

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small cancellable Kotlin wrapper around the bundled llama.cpp CPU runtime. */
internal class LocalLlmRuntime(
    context: Context,
    modelFile: File,
    private val systemPrompt: String,
    temperature: Float,
    contextSize: Int,
    threadCount: Int,
) : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            } catch (_: Throwable) {
            }
            runnable.run()
        }, "mihon-local-llm").apply { priority = Thread.MAX_PRIORITY }
    }
    @Volatile
    private var nativeHandle = 0L

    init {
        require(modelFile.isFile && modelFile.canRead()) { "Bundled local translation model is missing" }
        check(isSupported(context)) { "The embedded local language model is unavailable for this device ABI" }
        System.loadLibrary("mihon_llm")
        nativeHandle = nativeCreate(
            modelFile.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            temperature,
            contextSize,
            threadCount,
        )
        check(nativeHandle != 0L) { "The local translation model could not be loaded" }
        registerActive(this)
    }

    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        lineCount: Int = 0,
        systemPromptOverride: String = systemPrompt,
    ): String = suspendCancellableCoroutine { continuation ->
        check(nativeHandle != 0L) { "The local translation model is closed" }
        val task = executor.submit {
            try {
                val handle = nativeHandle
                if (handle == 0L) throw mihon.feature.translation.UserCancelledException("Cancelled by user")
                val output = nativeGenerate(handle, systemPromptOverride, prompt, maxTokens, lineCount)
                if (output == "__MIHON_CANCELLED__") {
                    throw mihon.feature.translation.UserCancelledException("Cancelled by user")
                }
                if (continuation.isActive) continuation.resume(output)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            cancel()
            task.cancel(false)
        }
    }

    fun cancel() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeCancel(handle)
        }
    }

    fun reset() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeReset(handle)
        }
    }

    override fun close() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            nativeCancel(handle)
            executor.shutdown()
            try {
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (_: InterruptedException) {
                executor.shutdownNow()
            }
            nativeDestroy(handle)
        } else {
            executor.shutdownNow()
        }
        if (activeRuntime == this) activeRuntime = null
    }

    private external fun nativeCreate(
        modelPath: String,
        nativeLibraryPath: String,
        temperature: Float,
        contextSize: Int,
        threadCount: Int,
    ): Long

    private external fun nativeGenerate(handle: Long, systemPrompt: String, prompt: String, maxTokens: Int, lineCount: Int): String

    private external fun nativeCancel(handle: Long)

    private external fun nativeReset(handle: Long)

    private external fun nativeDestroy(handle: Long)

    companion object {
        @Volatile
        private var activeRuntime: LocalLlmRuntime? = null

        fun registerActive(runtime: LocalLlmRuntime) {
            activeRuntime = runtime
        }

        fun cancelActive() {
            activeRuntime?.cancel()
        }

        fun isSupported(context: Context): Boolean = try {
            System.loadLibrary("mihon_llm")
            true
        } catch (_: Throwable) {
            context.applicationInfo.nativeLibraryDir
                ?.let { File(it, "libmihon_llm.so").isFile }
                ?: false
        }
    }
}
