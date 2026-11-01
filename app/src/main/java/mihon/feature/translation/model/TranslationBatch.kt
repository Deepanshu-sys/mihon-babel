package mihon.feature.translation.model

/**
 * Global chapter-level context passed to translators for consistency across scenes.
 */
data class ChapterContext(
    val chapterId: Long,
    val seriesTitle: String = "",
    val glossary: Map<String, String> = emptyMap(),
    val characterHonorifics: Map<String, String> = emptyMap(),
    val previousChapterSummary: String? = null,
    val recentTranslations: List<TranslationMemoryEntry> = emptyList(),
)

data class TranslationMemoryEntry(
    val sourceText: String,
    val translatedText: String,
    val speakerTag: String? = null,
)

/**
 * Individual dialogue unit paired with local scene context.
 */
data class DialogueUnit(
    val regionId: String,
    val pageIndex: Int,
    val readingOrder: Int,
    val sourceText: String,
    val speakerHint: String? = null,
    val precedingDialogue: String? = null,
    val followingDialogue: String? = null,
    val precedingDialogues: List<String> = emptyList(),
    val followingDialogues: List<String> = emptyList(),
)

/**
 * A cohesive batch of dialogue units (e.g. all speech on a page or panel group).
 */
data class TranslationBatch(
    val pageIndex: Int,
    val dialogues: List<DialogueUnit>,
)

/**
 * Translation result for a single [DialogueUnit].
 */
data class TranslationResult(
    val regionId: String,
    val translatedText: String,
    val confidence: Float = 1.0f,
    val notes: String? = null,
    val disposition: TranslationDisposition = TranslationDisposition.PASSTHROUGH_REVIEW,
)
