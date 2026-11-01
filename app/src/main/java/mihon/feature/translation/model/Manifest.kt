package mihon.feature.translation.model

import kotlinx.serialization.Serializable

/**
 * Origin and validity classification of a translated dialogue record.
 */
enum class TranslationDisposition {
    MODEL_TRANSLATION,
    APPROVED_SFX_LEXICON,
    PASSTHROUGH,
    IGNORED,
    PASSTHROUGH_REVIEW,
    ERROR,
}

/**
 * Processing state of a translated page.
 */
enum class PageStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    INCOMPLETE_TRANSLATION,
    NO_TEXT_ORIGINAL,
    UNVERIFIED_NO_TEXT,
    FALLBACK_ORIGINAL,
    FAILED,
}

/**
 * Serializable record of a single detector candidate's coverage/recovery outcome for export and diagnostics.
 */
@Serializable
data class CandidateOutcomeRecord(
    val candidateId: String,
    val type: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val assessment: String,
    val error: String? = null,
)

/**
 * Diagnostic metrics recorded per page for benchmarking.
 */
@Serializable
data class PageDiagnostics(
    val pageIndex: Int,
    val detectionDurationMs: Long = 0,
    val ocrDurationMs: Long = 0,
    val translationDurationMs: Long = 0,
    val inpaintingDurationMs: Long = 0,
    val typesettingDurationMs: Long = 0,
    val totalPageDurationMs: Long = 0,
    val javaHeapAllocatedBytes: Long = 0,
    val nativeHeapAllocatedBytes: Long = 0,
    val regionCount: Int = 0,
    val averageOcrConfidence: Float = 0f,
    val wasClampedToMinFontSize: Boolean = false,
    val candidateCount: Int = 0,
    val sliceCount: Int = 0,
    val failedSliceCount: Int = 0,
    val cropOcrCount: Int = 0,
    val mergedObservationsCount: Int = 0,
    val unresolvedCandidateCount: Int = 0,
    val unresolvedCandidateIds: List<String> = emptyList(),
    val candidateOutcomes: List<CandidateOutcomeRecord> = emptyList(),
    val errorReason: String? = null,
)

/**
 * Serializable record of a single translated region for persistence and diagnostics.
 */
@Serializable
data class RegionRecord(
    val regionId: String,
    val type: String,
    val sourceText: String,
    val translatedText: String,
    val ocrConfidence: Float,
    val fontSizeBytesUsed: Float? = null,
    val disposition: String = "MODEL_TRANSLATION",
    val speakerTag: String? = null,
)

/**
 * Phase of translation execution.
 */
enum class TranslationPhase {
    DOWNLOADING,
    SCANNING,
    TRANSLATING,
    RENDERING,
    FINALIZING,
}

/**
 * Manifest stored alongside translated chapters for checkpointing, resume, and diagnostics.
 */
@Serializable
data class ChapterTranslationManifest(
    val chapterId: Long,
    val sourceLanguage: String = "ko",
    val targetLanguage: String = "en",
    val detectorModelId: String = "",
    val ocrModelId: String = "",
    val translatorModelId: String = "",
    val translatorSelectionReason: String = "",
    val inpainterModelId: String = "",
    val totalPages: Int = 0,
    val completedPages: Set<Int> = emptySet(),
    val pageStatuses: Map<Int, String> = emptyMap(),
    val reviewPages: Set<Int> = emptySet(),
    val regionRecords: Map<Int, List<RegionRecord>> = emptyMap(),
    val diagnostics: List<PageDiagnostics> = emptyList(),
    val isComplete: Boolean = false,
)
