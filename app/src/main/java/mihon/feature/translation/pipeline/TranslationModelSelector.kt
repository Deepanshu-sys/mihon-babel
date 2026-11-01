package mihon.feature.translation.pipeline

import mihon.feature.translation.TranslationEffortLevel

internal enum class TranslationEngineKind { INDEX_HIGH, INDEX_MID, INDEX_LOW }

internal data class TranslationModelSelection(
    val engine: TranslationEngineKind,
    val contextSize: Int,
    val reason: String,
)

/** Maps effort level to Index-Translate 2B engine configuration. */
internal object TranslationModelSelector {
    fun select(effortLevel: TranslationEffortLevel): TranslationModelSelection = when (effortLevel) {
        TranslationEffortLevel.LOW -> TranslationModelSelection(
            engine = TranslationEngineKind.INDEX_LOW,
            contextSize = 1536,
            reason = "Low Effort: Fast 12-line instTrans super-batches, 0 retries, deterministic honorific guard",
        )
        TranslationEffortLevel.MEDIUM -> TranslationModelSelection(
            engine = TranslationEngineKind.INDEX_MID,
            contextSize = 2048,
            reason = "Medium Effort: Balanced 8-line instTrans batches with 2-line rolling continuity & targeted rescue",
        )
        TranslationEffortLevel.HIGH -> TranslationModelSelection(
            engine = TranslationEngineKind.INDEX_HIGH,
            contextSize = 3072,
            reason = "High Effort: High-precision 6-line instTrans batches, 4-line rolling continuity & 4-point rescue",
        )
    }
}
