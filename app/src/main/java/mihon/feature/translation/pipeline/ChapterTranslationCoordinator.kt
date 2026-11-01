package mihon.feature.translation.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import mihon.feature.translation.contract.DialogueTranslator
import mihon.feature.translation.model.CandidateOutcomeRecord
import mihon.feature.translation.model.ChapterContext
import mihon.feature.translation.model.ChapterTranslationManifest
import mihon.feature.translation.model.DetectedRegion
import mihon.feature.translation.model.DialogueUnit
import mihon.feature.translation.model.PageDiagnostics
import mihon.feature.translation.model.PageStatus
import mihon.feature.translation.model.RegionRecord
import mihon.feature.translation.model.RegionType
import mihon.feature.translation.model.TranslateMode
import mihon.feature.translation.model.TranslationBatch
import mihon.feature.translation.model.TranslationDisposition
import mihon.feature.translation.model.TranslationMemoryEntry
import mihon.feature.translation.model.TranslationResult
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Runs a resumable, chapter-wide local translation job. A page is committed only after
 * its rendered output and all of its diagnostics have been saved successfully.
 */
class ChapterTranslationCoordinator(
    private val storage: LocalTranslationStorage,
    private val detectorAndOcr: MlKitComicDetectorAndOcr,
    private val translator: DialogueTranslator,
    private val masker: StrokeTextMasker,
    private val inpainter: SmartPageInpainter,
    private val typesetter: ComicPageTypesetter,
) {

    companion object {
        const val MAX_RECENT_TRANSLATIONS = 24
        const val MAX_ENCODED_PAGE_BYTES = 64L * 1024L * 1024L

        private val KOREAN_GRAMMAR_PARTICLES = Regex(
            """(?:은|는|이|가|을|를|에|에서|으로|로|도|만|의|고|며|는데|지만|다|요|죠|네|까|자|군|지|어|아|야|거야|건가|걸|걸까|잖아|거든|텐데|던데|할까|볼까|래|대)[\s.?!~…]*$"""
        )

        private val STANDALONE_DIALOGUE_WORDS = setOf(
            "설마", "젠장", "제발", "진짜", "정말", "거짓말", "뭐야", "잠깐", "잠시만",
            "누구", "언제", "어디", "왜", "어떻게", "미안", "고마워", "됐어", "그만", "변태",
        )

        /**
         * Discriminates authentic speech bubbles, narration/caption boxes, and internal monologues
         * from floating, unboxed sound effects (SFX) over raw background artwork.
         */
        fun isNarrativeOrDialogueRegion(region: DetectedRegion, rawText: String): Boolean {
            // 1. Always keep model-detected speech bubbles, containers, or bubble-classified text
            if (region.type == RegionType.TEXT_IN_BUBBLE ||
                region.type == RegionType.BUBBLE ||
                region.isModelDetectedBubble ||
                region.containerBounds != null
            ) {
                return true
            }

            // 2. If it's FREE_TEXT / UNKNOWN_TEXT (on artwork without a closed bubble container):
            val clean = rawText.trim()
            if (clean.isBlank()) return false
            val boxArea = region.boundingBox.width() * region.boundingBox.height()
            // Allow curated SFX & vocalizations (두근두근, 하아..., 움찔, 쾅!) when compact (<= 12% of page area)
            // because StrokeTextMasker masks only tight glyph strokes and ComicPageTypesetter renders halo outlines!
            if (ManhwaSfxDictionary.translateSfx(clean) != null) {
                return boxArea <= 0.12f
            }

            // Keep any question (e.g. "자위?", "진짜?") or standalone monologue keyword ("설마")
            val strippedPunct = clean.trimEnd('?', '!', '.', '~', '…', ' ')
            if (clean.endsWith('?') || strippedPunct in STANDALONE_DIALOGUE_WORDS) {
                return true
            }

            // Keep 3-syllable Korean full names on character intro cards (e.g. "진미소", "진선후")
            if (strippedPunct.length == 3 && strippedPunct.all { it in '\uAC00'..'\uD7A3' } &&
                KoreanNameGlossaryExtractor.isValidNameCandidate(strippedPunct)) {
                return true
            }

            // Check location / character caption signatures: contains numbers or parentheses (e.g. "연구실 B", "김나혜 (26)")
            if (Regex("""\b[A-Za-z]\b|\d+|\(.*?\)|\[.*?\]""").containsMatchIn(clean)) {
                return true
            }

            // Check grammatical particles or narrative/spoken verb endings (e.g. "...했다", "...거든요", "...인데")
            if (KOREAN_GRAMMAR_PARTICLES.containsMatchIn(clean)) {
                return true
            }

            // Check multi-word phrase (>= 2 space-separated Hangul words, not repeated SFX like "쿵 쿵" or "두근 두근")
            val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (words.size >= 2) {
                val isRepeatedSfx = words.size == 2 && words[0] == words[1]
                if (!isRepeatedSfx) return true
            }

            // Single isolated token with no grammar endings (e.g. "콰아아앙", "스윽", "헉") -> Drop as in-art SFX
            return false
        }
    }

    private data class PageAnalysis(
        val bitmapDecodeFailed: Boolean = false,
        val pairs: List<Pair<mihon.feature.translation.model.DetectedRegion, mihon.feature.translation.model.OcrResult>> = emptyList(),
        val analysisMs: Long = 0L,
        val error: String? = null,
        val candidateCount: Int = 0,
        val sliceCount: Int = 0,
        val failedSliceCount: Int = 0,
        val cropOcrCount: Int = 0,
        val mergedObservationsCount: Int = 0,
        val unresolvedCandidateCount: Int = 0,
        val unresolvedCandidateIds: List<String> = emptyList(),
        val candidateOutcomes: List<CandidateOutcomeRecord> = emptyList(),
    )

    private data class PageTranslationRecord(
        val pairs: List<Pair<mihon.feature.translation.model.DetectedRegion, mihon.feature.translation.model.OcrResult>>,
        val dialogues: List<DialogueUnit>,
        val validatedTranslations: Map<String, TranslationResult>,
        val dispositions: Map<String, TranslationDisposition>,
        val translationMs: Long,
        val allDialogueHandled: Boolean,
    )

    suspend fun translateChapter(
        chapterId: Long,
        pageStreams: List<Pair<Int, () -> InputStream>>,
        mangaTitle: String,
        translatorSelectionReason: String,
        forceReprocess: Boolean = false,
        mangaId: Long? = null,
        progressCallback: suspend (phase: mihon.feature.translation.model.TranslationPhase, current: Int, total: Int, message: String) -> Unit,
    ): ChapterTranslationManifest {
        require(pageStreams.isNotEmpty()) { "Downloaded chapter contains no readable pages" }
            val totalPages = pageStreams.size
            val expectedIndices = (0 until totalPages).toSet()
            require(pageStreams.map { it.first }.toSet() == expectedIndices) {
                "Page stream indices must be contiguous and start at zero"
            }

            val oldManifest = storage.getManifest(chapterId)
            val currentInpainterModelId = "LaMa manga FP16-weight ONNX; low-variance balloon fill fallback"
            val compatibleManifest = oldManifest?.takeIf {
                TranslationCachePolicy.canReuse(
                    manifest = it,
                    totalPages = totalPages,
                    detectorModelId = detectorAndOcr.detectorModelId,
                    ocrModelId = detectorAndOcr.ocrModelId,
                    translatorModelId = translator.modelId,
                    inpainterModelId = currentInpainterModelId,
                    forceReprocess = forceReprocess,
                )
            }
            val reusablePages = if (compatibleManifest != null) {
                compatibleManifest.completedPages.filterTo(mutableSetOf()) { page ->
                    val status = compatibleManifest.pageStatuses[page]
                    (status == PageStatus.COMPLETED.name || status == PageStatus.NO_TEXT_ORIGINAL.name) &&
                        page in expectedIndices &&
                        storage.hasPageOutputSync(chapterId, page)
                }
            } else {
                mutableSetOf()
            }
            val reviewPages = compatibleManifest?.reviewPages?.toMutableSet() ?: mutableSetOf()

            var manifest = (compatibleManifest
                ?: ChapterTranslationManifest(chapterId = chapterId, totalPages = totalPages)).copy(
                totalPages = totalPages,
                completedPages = reusablePages,
                reviewPages = reviewPages.toSet(),
                isComplete = false,
                detectorModelId = detectorAndOcr.detectorModelId,
                ocrModelId = detectorAndOcr.ocrModelId,
                translatorModelId = translator.modelId,
                translatorSelectionReason = translatorSelectionReason,
                inpainterModelId = currentInpainterModelId,
            )
            val pageStatuses = manifest.pageStatuses.toMutableMap()
            val regionRecords = manifest.regionRecords.toMutableMap()
            val diagnostics = manifest.diagnostics.associateBy { it.pageIndex }.toMutableMap()

            fun commitPage(index: Int, status: String, pageDiagnostics: PageDiagnostics) {
                diagnostics[index] = pageDiagnostics
                pageStatuses[index] = status
                manifest = manifest.copy(
                    completedPages = reusablePages.toSet(),
                    pageStatuses = pageStatuses.toMap(),
                    reviewPages = reviewPages.toSet(),
                    regionRecords = regionRecords.toMap(),
                    diagnostics = diagnostics.values.sortedBy { it.pageIndex },
                    isComplete = false,
                )
            }

            storage.saveManifest(manifest).getOrThrow()

            // Pass 1: OCR the entire chapter before translating, so dialogue order and
            // repeated terms are available to a context-aware translator.
            val pageAnalysis = mutableMapOf<Int, PageAnalysis>()
            val allSourceText = mutableListOf<String>()
            for ((index, streamProvider) in pageStreams) {
                coroutineContext.ensureActive()
                if (index in reusablePages) continue
                progressCallback(mihon.feature.translation.model.TranslationPhase.SCANNING, index + 1, totalPages, "Reading Korean text on page ${index + 1}/$totalPages")

                logcat(LogPriority.INFO) { "Translation scan: decoding page=$index" }
                val bitmap = decodeStreamSafely(streamProvider)
                logcat(LogPriority.INFO) {
                    "Translation scan: decoded page=$index bitmap=${bitmap?.width}x${bitmap?.height}"
                }
                if (bitmap == null) {
                    pageAnalysis[index] = PageAnalysis(bitmapDecodeFailed = true, error = "Page image could not be decoded")
                    continue
                }

                try {
                    logcat(LogPriority.INFO) {
                        "Translation scan: begin page=$index size=${bitmap.width}x${bitmap.height} config=${bitmap.config}"
                    }
                    val start = SystemClock.elapsedRealtime()
                    val scanMeta = detectorAndOcr.analyzeWithMetadata(bitmap)
                    val rawPairs = scanMeta.pairs
                    val pairs = deduplicateConnectedPairs(rawPairs)
                    val deduplicatedCount = rawPairs.size - pairs.size
                    val duration = SystemClock.elapsedRealtime() - start
                    logcat(LogPriority.INFO) {
                        "Translation scan: complete page=$index regions=${pairs.size} elapsedMs=$duration"
                    }
                    for ((regionIdx, pair) in pairs.withIndex()) {
                        logcat(LogPriority.INFO) {
                            "Page $index OCR #$regionIdx [${pair.first.type}]: '${pair.second.text.replace("\n", " ")}' conf=${pair.second.confidence} box=${pair.first.boundingBox}"
                        }
                    }
                    val recordedOutcomes = scanMeta.candidateOutcomes.map { outcome ->
                        CandidateOutcomeRecord(
                            candidateId = outcome.candidateId,
                            type = outcome.type.name,
                            left = outcome.bounds.left,
                            top = outcome.bounds.top,
                            right = outcome.bounds.right,
                            bottom = outcome.bounds.bottom,
                            assessment = outcome.assessment.name,
                            error = outcome.error,
                        )
                    }
                    pageAnalysis[index] = PageAnalysis(
                        pairs = pairs,
                        analysisMs = duration,
                        candidateCount = scanMeta.candidateCount,
                        sliceCount = scanMeta.sliceCount,
                        failedSliceCount = scanMeta.failedSliceCount,
                        cropOcrCount = scanMeta.cropOcrCount,
                        mergedObservationsCount = scanMeta.mergedObservationsCount + deduplicatedCount,
                        unresolvedCandidateCount = scanMeta.unresolvedCandidateCount,
                        unresolvedCandidateIds = scanMeta.unresolvedCandidateIds,
                        candidateOutcomes = recordedOutcomes,
                    )
                    allSourceText += pairs.map { it.second.text }
                } catch (e: mihon.feature.translation.UserCancelledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Text scan failed on page $index of chapter $chapterId" }
                    pageAnalysis[index] = PageAnalysis(error = e.message ?: "Text scan failed")
                } finally {
                    bitmap.recycleIfNeeded()
                }
            }

            // Cross-Page Structural Watermark Filter: drops recurring non-bubble stamps across pages
            filterCrossPageRecurringWatermarks(pageAnalysis)

            // Cross-Page Split Bubble Stitching across vertical webtoon slices (runs before detector unload)
            val seamCrossings = stitchBoundarySplitRegions(pageAnalysis, totalPages, pageStreams)

            // Release ML Kit and YOLO detector resources before starting Pass 2 translation & inpainting
            try {
                (detectorAndOcr as? AutoCloseable)?.close()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to close detectorAndOcr after Pass 1" }
            }

            allSourceText.clear()
            allSourceText += pageAnalysis.values
                .flatMap { it.pairs }
                .map { it.second.text }
                .filter { it.isNotBlank() }

            val reusableSourceText = reusablePages
                .sorted()
                .flatMap { regionRecords[it].orEmpty() }
                .map { it.sourceText }
            val combinedSources = allSourceText + reusableSourceText
            val seriesGlossary = storage.getSeriesGlossary(mangaId, mangaTitle)
            val dynamicNameGlossary = KoreanNameGlossaryExtractor.extractGlossary(combinedSources)
            if (dynamicNameGlossary.isNotEmpty()) {
                storage.saveSeriesGlossary(mangaId, mangaTitle, dynamicNameGlossary)
            }
            val honorificGlossary = buildHonorificGlossary(combinedSources)
            // Series glossary comes last in map addition so established cross-chapter character spellings take precedence
            val fullGlossary = honorificGlossary + dynamicNameGlossary + seriesGlossary
            logcat(LogPriority.INFO) {
                "Dynamic Glossary: detected ${dynamicNameGlossary.size} names: $dynamicNameGlossary, series glossary size: ${seriesGlossary.size}"
            }
            val chapterContext = ChapterContext(
                chapterId = chapterId,
                seriesTitle = mangaTitle,
                glossary = fullGlossary,
            )

            data class DialogueRef(
                val pageIndex: Int,
                val regionId: String,
                val sourceText: String,
                val region: mihon.feature.translation.model.DetectedRegion? = null,
            )
            val chapterDialogueRefs = pageStreams.flatMap { (pageIndex, _) ->
                if (pageIndex in reusablePages) {
                    regionRecords[pageIndex].orEmpty().map { record ->
                        DialogueRef(pageIndex, record.regionId, record.sourceText, null)
                    }
                } else {
                    pageAnalysis[pageIndex]?.pairs.orEmpty()
                        .filter { (region, ocr) ->
                            ocr.text.isNotBlank() && isNarrativeOrDialogueRegion(region, ocr.text)
                        }
                        .map { (region, ocr) -> DialogueRef(pageIndex, ocr.regionId, ocr.text, region) }
                }
            }
            var prevTag: String? = null
            var prevY = 0f
            val chapterDialogueContexts = chapterDialogueRefs.mapIndexed { order, ref ->
                val preceding3 = (maxOf(0, order - 3) until order).map { chapterDialogueRefs[it].sourceText }
                val following3 = (order + 1..minOf(chapterDialogueRefs.size - 1, order + 3)).map { chapterDialogueRefs[it].sourceText }
                val hint = classifySpeakerTurn(ref.sourceText, ref.region, prevTag, prevY)
                prevTag = hint
                prevY = ref.region?.boundingBox?.top ?: 0f
                (ref.pageIndex to ref.regionId) to DialogueUnit(
                    regionId = ref.regionId,
                    pageIndex = ref.pageIndex,
                    readingOrder = order,
                    sourceText = ref.sourceText,
                    speakerHint = hint,
                    precedingDialogue = chapterDialogueRefs.getOrNull(order - 1)?.sourceText,
                    followingDialogue = chapterDialogueRefs.getOrNull(order + 1)?.sourceText,
                    precedingDialogues = preceding3,
                    followingDialogues = following3,
                )
            }.toMap()

            // =========================================================================
            // PHASE 2: Chapter-Wide Dialogue Translation (Pure Text Inference)
            // Evaluates all dialogue without holding bitmaps or inpainter in RAM.
            // =========================================================================
            val pageTranslations = mutableMapOf<Int, PageTranslationRecord>()
            val chapterRecentTranslations = mutableListOf<TranslationMemoryEntry>()
            chapterRecentTranslations += reusablePages
                .sorted()
                .flatMap { regionRecords[it].orEmpty() }
                .filter { record ->
                    (record.disposition == TranslationDisposition.MODEL_TRANSLATION.name ||
                     record.disposition == TranslationDisposition.APPROVED_SFX_LEXICON.name) &&
                    record.translatedText.isNotBlank() &&
                    record.translatedText != record.sourceText &&
                    record.translatedText.none { it in '\uAC00'..'\uD7A3' }
                }
                .map { record ->
                    TranslationMemoryEntry(record.sourceText, record.translatedText, record.speakerTag)
                }

            val pagesToTranslate = pageStreams.filter { (index, _) ->
                if (index in reusablePages) return@filter false
                val analysis = pageAnalysis[index]
                if (analysis == null || analysis.bitmapDecodeFailed || analysis.error != null) return@filter false
                val pairs = analysis.pairs.filter { (region, ocr) ->
                    ocr.text.isNotBlank() && isNarrativeOrDialogueRegion(region, ocr.text)
                }
                pairs.isNotEmpty()
            }

            // Group consecutive pages into Multi-Page Conversation Super-Batches (up to 8 dialogue lines per LLM call)
            // so 1-2 bubble webtoon slices don't pay ~1.8s of LLM prompt evaluation overhead 65 separate times per chapter,
            // AND sentences split across consecutive pages (e.g. Ch 2 P26->27, P40->41, P70->71) share the same `【源文】` block!
            data class PreparedPageInput(
                val tIdx: Int,
                val pageIndex: Int,
                val analysis: PageAnalysis,
                val pairs: List<Pair<DetectedRegion, mihon.feature.translation.model.OcrResult>>,
                val regions: List<DetectedRegion>,
                val ocrResults: List<mihon.feature.translation.model.OcrResult>,
                val dialogues: List<DialogueUnit>,
            )
            val preparedPages = pagesToTranslate.mapIndexedNotNull { tIdx, pageStream ->
                val index = pageStream.first
                val analysis = pageAnalysis[index] ?: return@mapIndexedNotNull null
                val pairs = analysis.pairs.filter { (region, ocr) ->
                    ocr.text.isNotBlank() && isNarrativeOrDialogueRegion(region, ocr.text)
                }
                if (pairs.isEmpty()) return@mapIndexedNotNull null
                val regions = pairs.map { it.first }
                val ocrResults = pairs.map { it.second }
                val dialogues = ocrResults.mapIndexed { order, ocr ->
                    val preceding3 = (maxOf(0, order - 3) until order).map { ocrResults[it].text }
                    val following3 = (order + 1..minOf(ocrResults.size - 1, order + 3)).map { ocrResults[it].text }
                    chapterDialogueContexts[index to ocr.regionId]
                        ?: DialogueUnit(
                            regionId = ocr.regionId,
                            pageIndex = index,
                            readingOrder = order,
                            sourceText = ocr.text,
                            speakerHint = regions.find { it.id == ocr.regionId }?.let { classifySpeakerTurn(ocr.text, it, null, 0f) },
                            precedingDialogue = ocrResults.getOrNull(order - 1)?.text,
                            followingDialogue = ocrResults.getOrNull(order + 1)?.text,
                            precedingDialogues = preceding3,
                            followingDialogues = following3,
                        )
                }
                PreparedPageInput(tIdx, index, analysis, pairs, regions, ocrResults, dialogues)
            }

            val superBatches = mutableListOf<List<PreparedPageInput>>()
            var currentSuperBatch = mutableListOf<PreparedPageInput>()
            var currentLineCount = 0
            for (prep in preparedPages) {
                if (currentSuperBatch.isNotEmpty() && currentLineCount + prep.dialogues.size > 8) {
                    superBatches += currentSuperBatch
                    currentSuperBatch = mutableListOf()
                    currentLineCount = 0
                }
                currentSuperBatch += prep
                currentLineCount += prep.dialogues.size
            }
            if (currentSuperBatch.isNotEmpty()) {
                superBatches += currentSuperBatch
            }

            for (superBatch in superBatches) {
                coroutineContext.ensureActive()
                val firstPageIdx = superBatch.first().pageIndex
                val lastPageIdx = superBatch.last().pageIndex
                val lastTIdx = superBatch.last().tIdx
                val combinedDialogues = superBatch.flatMap { it.dialogues }

                progressCallback(
                    mihon.feature.translation.model.TranslationPhase.TRANSLATING,
                    lastTIdx + 1,
                    pagesToTranslate.size,
                    "Translating dialogue script on pages ${firstPageIdx + 1}..${lastPageIdx + 1}/$totalPages (${lastTIdx + 1}/${pagesToTranslate.size})",
                )

                val translationStart = SystemClock.elapsedRealtime()
                val recentTranslations = chapterRecentTranslations.takeLast(MAX_RECENT_TRANSLATIONS)
                val combinedTranslations = try {
                    translator.translateBatch(
                        TranslationBatch(firstPageIdx, combinedDialogues),
                        chapterContext.copy(recentTranslations = recentTranslations),
                    )
                } catch (e: mihon.feature.translation.UserCancelledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "translateBatch failed on super-batch pages $firstPageIdx..$lastPageIdx; using safe fallback" }
                    combinedDialogues.map { dialogue ->
                        val sfx = ManhwaSfxDictionary.translateSfx(dialogue.sourceText)
                        if (sfx != null) {
                            TranslationResult(
                                regionId = dialogue.regionId,
                                translatedText = sfx,
                                confidence = 0.95f,
                                disposition = TranslationDisposition.APPROVED_SFX_LEXICON,
                            )
                        } else {
                            TranslationResult(
                                regionId = dialogue.regionId,
                                translatedText = dialogue.sourceText.trim().ifEmpty { "..." },
                                confidence = 0.1f,
                                disposition = TranslationDisposition.PASSTHROUGH_REVIEW,
                            )
                        }
                    }
                }
                val batchElapsedMs = SystemClock.elapsedRealtime() - translationStart
                val combinedTransById = combinedTranslations.associateBy { it.regionId }
                val perPageMs = (batchElapsedMs / superBatch.size.coerceAtLeast(1))

                for (prep in superBatch) {
                    val index = prep.pageIndex
                    val analysis = prep.analysis
                    val pairs = prep.pairs
                    val regions = prep.regions
                    val ocrResults = prep.ocrResults
                    val dialogues = prep.dialogues
                    val translations = dialogues.mapNotNull { combinedTransById[it.regionId] }
                    val translationMs = perPageMs

                // Multiset validation and disposition assigning
                val requestedIds = dialogues.map { it.regionId }
                val returnedIds = translations.map { it.regionId }
                val requestedSet = requestedIds.toSet()
                val returnedSet = returnedIds.toSet()
                val missingIds = requestedIds.filter { it !in returnedSet }
                val extraIds = returnedIds.filter { it !in requestedSet }
                val hasDuplicates = returnedIds.size != returnedSet.size
                val isMultisetIdentical = requestedIds.size == returnedIds.size &&
                    missingIds.isEmpty() &&
                    extraIds.isEmpty() &&
                    !hasDuplicates &&
                    requestedIds.sorted() == returnedIds.sorted()

                val returnedMap = translations.associateBy { it.regionId }
                val unresolvedCount = analysis.unresolvedCandidateCount
                val failedSliceCount = analysis.failedSliceCount
                var allDialogueHandled = isMultisetIdentical && failedSliceCount == 0
                val dispositions = mutableMapOf<String, TranslationDisposition>()
                val validatedTranslations = mutableMapOf<String, TranslationResult>()

                val regionById = regions.associateBy { it.id }
                for (dialogue in dialogues) {
                    val trans = returnedMap[dialogue.regionId]
                    val regionObj = regionById[dialogue.regionId]
                    val boxArea = regionObj?.let { it.boundingBox.width() * it.boundingBox.height() } ?: 0f
                    val isSafeSfxRegion = regionObj == null ||
                        regionObj.type == RegionType.TEXT_IN_BUBBLE ||
                        regionObj.type == RegionType.BUBBLE ||
                        regionObj.isModelDetectedBubble ||
                        regionObj.containerBounds != null ||
                        boxArea <= 0.12f
                    val sfxMapping = ManhwaSfxDictionary.translateSfx(dialogue.sourceText)?.removeSurrounding("*")
                    val hangulCount = dialogue.sourceText.count { it in '\uAC00'..'\uD7A3' }
                    val isShortInterjection = hangulCount <= 2
                    val isPunctuationOnly = trans != null && trans.translatedText.isNotBlank() &&
                        trans.translatedText.none { it.isLetter() } &&
                        trans.translatedText.all { it in "?!…—~. \t\n\r" }

                    when {
                        sfxMapping != null && isSafeSfxRegion -> {
                            dispositions[dialogue.regionId] = TranslationDisposition.APPROVED_SFX_LEXICON
                            validatedTranslations[dialogue.regionId] = TranslationResult(
                                regionId = dialogue.regionId,
                                translatedText = sfxMapping,
                                confidence = 0.95f,
                                disposition = TranslationDisposition.APPROVED_SFX_LEXICON,
                            )
                        }
                        sfxMapping != null && !isSafeSfxRegion -> {
                            dispositions[dialogue.regionId] = TranslationDisposition.IGNORED
                            validatedTranslations[dialogue.regionId] = TranslationResult(
                                regionId = dialogue.regionId,
                                translatedText = dialogue.sourceText.trim(),
                                confidence = 1.0f,
                                disposition = TranslationDisposition.IGNORED,
                            )
                        }
                        trans != null && trans.disposition == TranslationDisposition.APPROVED_SFX_LEXICON -> {
                            dispositions[dialogue.regionId] = TranslationDisposition.APPROVED_SFX_LEXICON
                            validatedTranslations[dialogue.regionId] = trans
                        }
                        trans != null && trans.disposition == TranslationDisposition.IGNORED -> {
                            dispositions[dialogue.regionId] = TranslationDisposition.IGNORED
                            validatedTranslations[dialogue.regionId] = trans
                        }
                        isPassThroughText(dialogue.sourceText) ||
                            (hangulCount == 0 && trans != null && trans.disposition != TranslationDisposition.PASSTHROUGH_REVIEW &&
                             trans.translatedText.trim().equals(dialogue.sourceText.trim(), ignoreCase = true)) -> {
                            dispositions[dialogue.regionId] = TranslationDisposition.PASSTHROUGH
                            validatedTranslations[dialogue.regionId] = TranslationResult(
                                regionId = dialogue.regionId,
                                translatedText = dialogue.sourceText.trim(),
                                confidence = 1.0f,
                                disposition = TranslationDisposition.PASSTHROUGH,
                            )
                        }
                        isPunctuationOnly && isShortInterjection -> {
                            dispositions[dialogue.regionId] = TranslationDisposition.PASSTHROUGH
                            validatedTranslations[dialogue.regionId] = trans!!
                        }
                        trans != null && trans.disposition == TranslationDisposition.MODEL_TRANSLATION &&
                            trans.translatedText.isNotBlank() && trans.translatedText != dialogue.sourceText.trim() &&
                            trans.translatedText.any { it.isLetter() } && trans.translatedText.trim() != "..." && trans.translatedText.trim() != "…" -> {
                            dispositions[dialogue.regionId] = TranslationDisposition.MODEL_TRANSLATION
                            validatedTranslations[dialogue.regionId] = trans
                        }
                        else -> {
                            allDialogueHandled = false
                            dispositions[dialogue.regionId] = TranslationDisposition.PASSTHROUGH_REVIEW
                            validatedTranslations[dialogue.regionId] = TranslationResult(
                                regionId = dialogue.regionId,
                                translatedText = dialogue.sourceText.trim().ifEmpty { "..." },
                                confidence = 0.1f,
                                disposition = TranslationDisposition.PASSTHROUGH_REVIEW,
                            )
                        }
                    }
                }

                if (!allDialogueHandled) {
                    val unhandledReason = when {
                        !isMultisetIdentical -> "Multiset validation note: requested ${requestedIds.size}, returned ${returnedIds.size}"
                        unresolvedCount > 0 -> "Non-text background candidates pruned ($unresolvedCount non-text shapes)"
                        else -> "Notice: some dialogues using fallback text"
                    }
                    logcat(LogPriority.INFO) { "Page $index note: $unhandledReason. Rendering all detected dialogues." }
                }

                val dialogueSourceMap = dialogues.associate { it.regionId to it.sourceText }
                val updatedPairs = pairs.map { pair ->
                    val region = pair.first
                    val disp = dispositions[region.id]
                    val trans = validatedTranslations[region.id]
                    val src = dialogueSourceMap[region.id] ?: pair.second.text
                    val isPassThrough = disp == TranslationDisposition.PASSTHROUGH ||
                        disp == TranslationDisposition.IGNORED ||
                        disp == TranslationDisposition.PASSTHROUGH_REVIEW ||
                        (trans != null && trans.translatedText.trim().equals(src.trim(), ignoreCase = true))
                    if (isPassThrough) {
                        pair.copy(first = region.copy(translateMode = TranslateMode.KEEP))
                    } else {
                        pair
                    }
                }

                pageTranslations[index] = PageTranslationRecord(
                    pairs = updatedPairs,
                    dialogues = dialogues,
                    validatedTranslations = validatedTranslations,
                    dispositions = dispositions,
                    translationMs = translationMs,
                    allDialogueHandled = allDialogueHandled,
                )

                // Feed back into translation memory for the subsequent pages in chapter
                for (dialogue in dialogues) {
                    val result = validatedTranslations[dialogue.regionId]
                    if (result != null &&
                        (result.disposition == TranslationDisposition.MODEL_TRANSLATION || result.disposition == TranslationDisposition.APPROVED_SFX_LEXICON) &&
                        result.translatedText.isNotBlank() &&
                        result.translatedText != dialogue.sourceText &&
                        result.translatedText.none { it in '\uAC00'..'\uD7A3' }
                    ) {
                        chapterRecentTranslations += TranslationMemoryEntry(
                            dialogue.sourceText,
                            result.translatedText,
                            chapterDialogueContexts[index to dialogue.regionId]?.speakerHint,
                        )
                    }
                }
                }
            }

            // Phase-Major Unload: release Translator neural weights before inpainting phase
            try {
                (translator as? AutoCloseable)?.close()
                logcat(LogPriority.INFO) { "Phase-Major: Unloaded translator from RAM. Entering Inpainting & Typesetting phase." }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to close translator after Phase 2" }
            }

            // =========================================================================
            // PHASE 3: Inpainting, Typesetting & Atomic Page Commits
            // Only LaMa inpainter and typesetter occupy memory now.
            // =========================================================================
            // Phase 3a: Pre-render cross-page seam bubbles to memory patches
            val seamPatches = mutableMapOf<Int, MutableList<SeamPatch>>()
            val seamFontSizes = mutableMapOf<String, Float>()
            val seamRegionIds = seamCrossings.flatMap { listOf(it.topRegionId, it.bottomRegionId) }.toSet()

            for (seamInfo in seamCrossings) {
                coroutineContext.ensureActive()
                val trans = pageTranslations[seamInfo.topPageIndex]?.validatedTranslations?.get(seamInfo.topRegionId)
                    ?: continue
                if (trans.translatedText.isBlank() || trans.translatedText.all { it in '\uAC00'..'\uD7A3' }) continue

                val topStream = pageStreams.find { it.first == seamInfo.topPageIndex }?.second
                val bottomStream = pageStreams.find { it.first == seamInfo.bottomPageIndex }?.second
                if (topStream == null || bottomStream == null) continue

                try {
                    val topDecoder = BitmapRegionDecoder.newInstance(topStream(), false)
                    val bottomDecoder = BitmapRegionDecoder.newInstance(bottomStream(), false)
                    if (topDecoder != null && bottomDecoder != null) {
                        val tW = topDecoder.width; val tH = topDecoder.height
                        val bW = bottomDecoder.width; val bH = bottomDecoder.height
                        val stripHTop = minOf(seamInfo.stripHeightTop, tH / 2)
                        val stripHBottom = minOf(seamInfo.stripHeightBottom, bH / 2)
                        val topStrip = topDecoder.decodeRegion(Rect(0, tH - stripHTop, tW, tH), null)
                        val bottomStrip = bottomDecoder.decodeRegion(Rect(0, 0, bW, stripHBottom), null)
                        topDecoder.recycle()
                        bottomDecoder.recycle()

                        if (topStrip != null && bottomStrip != null) {
                            val totalStripH = stripHTop + stripHBottom
                            val seamStrip = Bitmap.createBitmap(tW, totalStripH, Bitmap.Config.ARGB_8888)
                            Canvas(seamStrip).apply {
                                drawBitmap(topStrip, 0f, 0f, null)
                                drawBitmap(bottomStrip, 0f, stripHTop.toFloat(), null)
                            }
                            topStrip.recycleIfNeeded()
                            bottomStrip.recycleIfNeeded()

                            val seamRegionList = listOf(seamInfo.seamRegion)
                            val maskSet = masker.generateMask(seamStrip, seamRegionList)
                            val cleanedStrip = maskSet.use { inpainter.inpaint(seamStrip, it, seamRegionList) }
                            seamStrip.recycleIfNeeded()

                            val (typesetStrip, seamLayouts) = typesetter.typeset(cleanedStrip, seamRegionList, listOf(trans))
                            cleanedStrip.recycleIfNeeded()
                            seamLayouts.firstOrNull()?.fontSizeSp?.let { seamFontSizes[seamInfo.topRegionId] = it }

                            // Crop horizontally to the seam bubble's X-span (+12px pad) so pasting the seam patch
                            // never overwrites unrelated translated bubbles on the left/right of the same page!
                            val sBox = seamInfo.seamRegion.containerBounds ?: seamInfo.seamRegion.boundingBox
                            val patchLeft = ((sBox.left * tW).toInt() - 12).coerceIn(0, tW - 1)
                            val patchRight = ((sBox.right * tW).toInt() + 12).coerceIn(patchLeft + 1, minOf(tW, bW))
                            val patchW = patchRight - patchLeft

                            val topPatchBitmap = Bitmap.createBitmap(typesetStrip, patchLeft, 0, patchW, stripHTop)
                            val bottomPatchBitmap = Bitmap.createBitmap(typesetStrip, patchLeft, stripHTop, patchW, stripHBottom)
                            typesetStrip.recycleIfNeeded()

                            seamPatches.getOrPut(seamInfo.topPageIndex) { mutableListOf() }.add(
                                SeamPatch(seamInfo.topPageIndex, topPatchBitmap, patchLeft, tH - stripHTop)
                            )
                            seamPatches.getOrPut(seamInfo.bottomPageIndex) { mutableListOf() }.add(
                                SeamPatch(seamInfo.bottomPageIndex, bottomPatchBitmap, patchLeft, 0)
                            )
                            logcat(LogPriority.INFO) {
                                "Phase 3a: Emitted seam patches for pages ${seamInfo.topPageIndex} & ${seamInfo.bottomPageIndex} (x=$patchLeft..$patchRight)"
                            }
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Phase 3a seam rendering failed for pages ${seamInfo.topPageIndex} & ${seamInfo.bottomPageIndex}" }
                }
            }

            // Phase 3b: Sequential per-slice rendering and patch compositing
            for ((index, streamProvider) in pageStreams) {
                coroutineContext.ensureActive()
                if (index in reusablePages) {
                    progressCallback(mihon.feature.translation.model.TranslationPhase.RENDERING, index + 1, totalPages, "Page ${index + 1}/$totalPages already saved")
                    continue
                }

                val analysis = pageAnalysis[index]
                val translationRecord = pageTranslations[index]
                var stage = "decoding page"
                var sourceBitmap: Bitmap? = null
                var cleanedBitmap: Bitmap? = null
                var typesetBitmap: Bitmap? = null
                val translationMs = translationRecord?.translationMs ?: 0L
                var inpaintingMs = 0L
                var typesettingMs = 0L
                val pageStart = SystemClock.elapsedRealtime()

                try {
                    sourceBitmap = decodeStreamSafely(streamProvider)
                    if (sourceBitmap == null || analysis?.error != null) {
                        val reason = analysis?.error ?: "Page image could not be decoded"
                        logcat(LogPriority.WARN) { "Page $index cannot be processed ($reason); preserving original fallback" }
                        stage = "preserving original fallback"
                        if (sourceBitmap != null) {
                            storage.savePageImage(chapterId, index, sourceBitmap).getOrThrow()
                        } else {
                            val rawBytes = streamProvider().use { it.readBytes() }
                            storage.savePageBytes(chapterId, index, rawBytes).getOrThrow()
                        }
                        regionRecords[index] = emptyList()
                        reusablePages += index
                        reviewPages += index
                        commitPage(
                            index = index,
                            status = PageStatus.FALLBACK_ORIGINAL.name,
                            pageDiagnostics = makeDiagnostics(
                                index = index,
                                analysisMs = analysis?.analysisMs ?: 0L,
                                totalMs = SystemClock.elapsedRealtime() - pageStart,
                                regionCount = 0,
                                candidateCount = analysis?.candidateCount ?: 0,
                                sliceCount = analysis?.sliceCount ?: 0,
                                failedSliceCount = analysis?.failedSliceCount ?: 0,
                                errorReason = reason,
                            ),
                        )
                        storage.saveManifest(manifest).getOrThrow()
                        progressCallback(mihon.feature.translation.model.TranslationPhase.RENDERING, index + 1, totalPages, "Page ${index + 1}/$totalPages preserved as original fallback")
                        continue
                    }

                    if (translationRecord == null) {
                        // Page had no narrative/dialogue regions
                        val hasCandidates = (analysis?.candidateCount ?: 0) > 0
                        val failedSlices = analysis?.failedSliceCount ?: 0
                        if (failedSlices > 0) {
                            stage = "unverified page due to failed OCR slices"
                            storage.savePageImage(chapterId, index, sourceBitmap).getOrThrow()
                            regionRecords[index] = emptyList()
                            reusablePages += index
                            reviewPages += index
                            commitPage(
                                index = index,
                                status = PageStatus.FALLBACK_ORIGINAL.name,
                                pageDiagnostics = makeDiagnostics(
                                    index = index,
                                    analysisMs = analysis?.analysisMs ?: 0L,
                                    totalMs = SystemClock.elapsedRealtime() - pageStart,
                                    regionCount = 0,
                                    candidateCount = analysis?.candidateCount ?: 0,
                                    sliceCount = analysis?.sliceCount ?: 0,
                                    failedSliceCount = failedSlices,
                                    cropOcrCount = analysis?.cropOcrCount ?: 0,
                                    mergedObservationsCount = analysis?.mergedObservationsCount ?: 0,
                                    unresolvedCandidateCount = analysis?.unresolvedCandidateCount ?: 0,
                                    unresolvedCandidateIds = analysis?.unresolvedCandidateIds.orEmpty(),
                                    candidateOutcomes = analysis?.candidateOutcomes.orEmpty(),
                                    errorReason = "$failedSlices OCR slices failed during page scan",
                                ),
                            )
                            storage.saveManifest(manifest).getOrThrow()
                            progressCallback(mihon.feature.translation.model.TranslationPhase.RENDERING, index + 1, totalPages, "Page ${index + 1}/$totalPages unverified ($failedSlices OCR slices failed)")
                            continue
                        } else {
                            // Even if a page has no standalone dialogue of its own, it may receive the bottom half
                            // of a cross-page stitched seam bubble (e.g. Ch 1 Page 7 "Our son.", Ch 2 Page 29)!
                            val patches = seamPatches[index].orEmpty()
                            if (patches.isNotEmpty()) {
                                stage = "compositing cross-page seam patch onto page"
                                val patchedBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                val canvas = Canvas(patchedBitmap)
                                for (p in patches) {
                                    canvas.drawBitmap(p.bitmap, p.destinationX.toFloat(), p.destinationY.toFloat(), null)
                                    p.bitmap.recycleIfNeeded()
                                }
                                storage.savePageImage(chapterId, index, patchedBitmap).getOrThrow()
                                patchedBitmap.recycleIfNeeded()
                            } else {
                                stage = "saving page with no recognized text"
                                storage.savePageImage(chapterId, index, sourceBitmap).getOrThrow()
                            }
                            val statusName = if (patches.isNotEmpty()) PageStatus.COMPLETED.name else PageStatus.NO_TEXT_ORIGINAL.name
                            regionRecords[index] = emptyList()
                            reusablePages += index
                            commitPage(
                                index = index,
                                status = statusName,
                                pageDiagnostics = makeDiagnostics(
                                    index = index,
                                    analysisMs = analysis?.analysisMs ?: 0L,
                                    totalMs = SystemClock.elapsedRealtime() - pageStart,
                                    regionCount = 0,
                                    candidateCount = analysis?.candidateCount ?: 0,
                                    sliceCount = analysis?.sliceCount ?: 0,
                                    failedSliceCount = 0,
                                    cropOcrCount = analysis?.cropOcrCount ?: 0,
                                    mergedObservationsCount = analysis?.mergedObservationsCount ?: 0,
                                    unresolvedCandidateCount = analysis?.unresolvedCandidateCount ?: 0,
                                    unresolvedCandidateIds = analysis?.unresolvedCandidateIds.orEmpty(),
                                    candidateOutcomes = analysis?.candidateOutcomes.orEmpty(),
                                    errorReason = if (hasCandidates && patches.isEmpty()) "Non-text art/bubble shape (no text tokens)" else null,
                                ),
                            )
                            storage.saveManifest(manifest).getOrThrow()
                            progressCallback(mihon.feature.translation.model.TranslationPhase.RENDERING, index + 1, totalPages, "Finished page ${index + 1}/$totalPages ($statusName)")
                            continue
                        }
                    }

                    // Process translated page
                    val pairs = translationRecord.pairs
                    val regions = pairs.map { it.first }
                    val ocrResults = pairs.map { it.second }
                    val validatedTranslations = translationRecord.validatedTranslations
                    val dispositions = translationRecord.dispositions

                    // Identify which regions have verified translations to render
                    val translatablePairs = pairs.filter { (region, ocr) ->
                        if (region.id in seamRegionIds) return@filter false
                        if (region.translateMode == TranslateMode.KEEP) return@filter false
                        val result = validatedTranslations[region.id]
                        result != null &&
                            (result.disposition == TranslationDisposition.MODEL_TRANSLATION ||
                             result.disposition == TranslationDisposition.APPROVED_SFX_LEXICON) &&
                            result.translatedText.isNotBlank() &&
                            result.translatedText != ocr.text.trim() &&
                            result.translatedText.none { it in '\uAC00'..'\uD7A3' }
                    }

                    val layoutResults = if (translatablePairs.isEmpty()) {
                        val patches = seamPatches[index].orEmpty()
                        coroutineContext.ensureActive()
                        if (patches.isNotEmpty()) {
                            stage = "compositing seam patches onto page"
                            val patchedBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            val canvas = Canvas(patchedBitmap)
                            for (p in patches) {
                                canvas.drawBitmap(p.bitmap, p.destinationX.toFloat(), p.destinationY.toFloat(), null)
                                p.bitmap.recycleIfNeeded()
                            }
                            storage.savePageImage(chapterId, index, patchedBitmap).getOrThrow()
                            patchedBitmap.recycleIfNeeded()
                        } else {
                            // Not a single dialogue was translated on this page.
                            // Do NOT inpaint or typeset: preserve the original artist's image without destructive blanking.
                            stage = "saving original page for untranslated content"
                            storage.savePageImage(chapterId, index, sourceBitmap).getOrThrow()
                        }
                        emptyList()
                    } else {
                        val regionsToRender = translatablePairs.map { it.first }
                        val translationsToRender = translatablePairs.map { validatedTranslations.getValue(it.first.id) }

                        coroutineContext.ensureActive()
                        stage = "removing source lettering"
                        val maskSet = masker.generateMask(sourceBitmap, regionsToRender)
                        val erasedRegionIds = maskSet.maskedRegionIds
                        val inpaintStart = SystemClock.elapsedRealtime()
                        try {
                            cleanedBitmap = maskSet.use { inpainter.inpaint(sourceBitmap, it, regionsToRender) }
                        } finally {
                            inpaintingMs = SystemClock.elapsedRealtime() - inpaintStart
                        }

                        coroutineContext.ensureActive()
                        stage = "fitting translated text"
                        val (actuallyErasedRegions, actuallyErasedTranslations) = regionsToRender
                            .zip(translationsToRender)
                            .filter { (region, _) ->
                                val erased = region.id in erasedRegionIds
                                if (!erased) {
                                    logcat(LogPriority.WARN) {
                                        "Translation coordinator: skipping typesetting for region ${region.id} " +
                                            "because it was not erased; preventing English-over-Korean mashup"
                                    }
                                }
                                erased
                            }
                            .let { prs -> prs.map { it.first } to prs.map { it.second } }

                        val typesettingStart = SystemClock.elapsedRealtime()
                        val (rendered, results) = typesetter.typeset(cleanedBitmap, actuallyErasedRegions, actuallyErasedTranslations)
                        typesettingMs = SystemClock.elapsedRealtime() - typesettingStart
                        typesetBitmap = rendered
                        if (results.any { it.didOverflowBounds }) {
                            logcat(LogPriority.WARN) { "Page $index had translated text slightly overflowing speech bubble bounds" }
                        }

                        coroutineContext.ensureActive()
                        stage = "saving rendered page"
                        val patches = seamPatches[index].orEmpty()
                        if (patches.isNotEmpty()) {
                            val canvas = Canvas(typesetBitmap)
                            for (p in patches) {
                                canvas.drawBitmap(p.bitmap, p.destinationX.toFloat(), p.destinationY.toFloat(), null)
                                p.bitmap.recycleIfNeeded()
                            }
                        }
                        storage.savePageImage(chapterId, index, typesetBitmap).getOrThrow()
                        results
                    }

                    regionRecords[index] = pairs.map { (region, ocr) ->
                        val result = validatedTranslations.getValue(ocr.regionId)
                        val layout = layoutResults.firstOrNull { it.regionId == region.id }
                        val disp = dispositions[ocr.regionId] ?: dispositions[region.id] ?: TranslationDisposition.MODEL_TRANSLATION
                        val fontSize = if (disp == TranslationDisposition.PASSTHROUGH || disp == TranslationDisposition.IGNORED || region.translateMode == TranslateMode.KEEP) {
                            0f
                        } else {
                            layout?.fontSizeSp ?: seamFontSizes[region.id]
                        }
                        RegionRecord(
                            regionId = region.id,
                            type = region.type.name,
                            sourceText = ocr.text,
                            translatedText = result.translatedText,
                            ocrConfidence = ocr.confidence,
                            fontSizeBytesUsed = fontSize,
                            disposition = disp.name,
                            speakerTag = chapterDialogueContexts[index to ocr.regionId]?.speakerHint,
                        )
                    }

                    val pageStatus = PageStatus.COMPLETED.name
                    reusablePages += index
                    if (!translationRecord.allDialogueHandled) {
                        reviewPages += index
                    }

                    commitPage(
                        index = index,
                        status = pageStatus,
                        pageDiagnostics = makeDiagnostics(
                            index = index,
                            analysisMs = analysis?.analysisMs ?: 0L,
                            translationMs = translationMs,
                            inpaintingMs = inpaintingMs,
                            typesettingMs = typesettingMs,
                            totalMs = SystemClock.elapsedRealtime() - pageStart,
                            regionCount = regions.size,
                            averageOcrConfidence = ocrResults.map { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f,
                            wasClampedToMinFontSize = layoutResults.any { it.wasClampedToMinSize },
                            candidateCount = analysis?.candidateCount ?: 0,
                            sliceCount = analysis?.sliceCount ?: 0,
                            cropOcrCount = analysis?.cropOcrCount ?: 0,
                            mergedObservationsCount = analysis?.mergedObservationsCount ?: 0,
                            unresolvedCandidateCount = analysis?.unresolvedCandidateCount ?: 0,
                            unresolvedCandidateIds = analysis?.unresolvedCandidateIds.orEmpty(),
                            candidateOutcomes = analysis?.candidateOutcomes.orEmpty(),
                        ),
                    )
                    storage.saveManifest(manifest).getOrThrow()
                    progressCallback(mihon.feature.translation.model.TranslationPhase.RENDERING, index + 1, totalPages, "Finished page ${index + 1}/$totalPages ($pageStatus)")
                } catch (e: mihon.feature.translation.UserCancelledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed while $stage on page $index of chapter $chapterId" }
                    val analysisFailure = analysis?.error?.let { "; scan: $it" }.orEmpty()
                    regionRecords.remove(index)

                    try {
                        sourceBitmap?.let { src ->
                            if (!src.isRecycled) {
                                storage.savePageImage(chapterId, index, src).getOrThrow()
                                reusablePages += index
                                reviewPages += index
                            }
                        } ?: run {
                            val rawBytes = runCatching { streamProvider().use { it.readBytes() } }.getOrNull()
                            if (rawBytes != null && rawBytes.isNotEmpty()) {
                                storage.savePageBytes(chapterId, index, rawBytes).getOrThrow()
                                reusablePages += index
                                reviewPages += index
                            }
                        }
                    } catch (fallbackError: Exception) {
                        logcat(LogPriority.ERROR, fallbackError) { "Failsafe: could not save fallback source image for page $index" }
                    }

                    commitPage(
                        index = index,
                        status = PageStatus.FALLBACK_ORIGINAL.name,
                        pageDiagnostics = makeDiagnostics(
                            index = index,
                            analysisMs = analysis?.analysisMs ?: 0L,
                            translationMs = translationMs,
                            inpaintingMs = inpaintingMs,
                            typesettingMs = typesettingMs,
                            totalMs = SystemClock.elapsedRealtime() - pageStart,
                            regionCount = analysis?.pairs?.size ?: 0,
                            unresolvedCandidateCount = analysis?.unresolvedCandidateCount ?: 0,
                            unresolvedCandidateIds = analysis?.unresolvedCandidateIds.orEmpty(),
                            candidateOutcomes = analysis?.candidateOutcomes.orEmpty(),
                            errorReason = "$stage: ${e.message ?: e.javaClass.simpleName}$analysisFailure",
                        ),
                    )
                    storage.saveManifest(manifest).getOrThrow()
                    progressCallback(mihon.feature.translation.model.TranslationPhase.RENDERING, index + 1, totalPages, "Page ${index + 1}/$totalPages fallback: ${e.message ?: stage}")
                } finally {
                    typesetBitmap.recycleIfNeeded()
                    if (cleanedBitmap !== typesetBitmap) cleanedBitmap.recycleIfNeeded()
                    sourceBitmap.recycleIfNeeded()
                }
            }

            // Phase-Major Unload: release Inpainter & Typesetter resources once Phase 3 concludes
            try {
                inpainter.close()
                typesetter.close()
                logcat(LogPriority.INFO) { "Phase-Major: Unloaded inpainter and typesetter from RAM. Phase 3 complete." }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to close inpainter/typesetter after Phase 3" }
            }

            val strictlyCompletedPages = expectedIndices.filter { index ->
                val st = pageStatuses[index]
                (st == PageStatus.COMPLETED.name || st == PageStatus.NO_TEXT_ORIGINAL.name) &&
                    storage.hasPageOutputSync(chapterId, index)
            }.toSet()

            val allPagesComplete = expectedIndices.isNotEmpty() && expectedIndices.all { it in strictlyCompletedPages }

            manifest = manifest.copy(
                completedPages = strictlyCompletedPages,
                pageStatuses = pageStatuses.toMap(),
                reviewPages = reviewPages.toSet(),
                regionRecords = regionRecords.toMap(),
                diagnostics = diagnostics.values.sortedBy { it.pageIndex },
                translatorModelId = translator.modelId,
                isComplete = allPagesComplete,
            )
            storage.saveManifest(manifest).getOrThrow()

            val fallbackCount = expectedIndices.count { pageStatuses[it] == PageStatus.FALLBACK_ORIGINAL.name }
            logcat(LogPriority.INFO) {
                "Chapter translation finished: ${strictlyCompletedPages.size}/$totalPages pages saved ($fallbackCount pages preserved as original fallback, ${reviewPages.size} review pages)"
            }
            progressCallback(mihon.feature.translation.model.TranslationPhase.FINALIZING, totalPages, totalPages, "Offline chapter translation complete (${strictlyCompletedPages.size}/$totalPages ready)")
            return manifest
    }

    private fun decodeStreamSafely(streamProvider: () -> InputStream): Bitmap? = try {
        val encodedPage = streamProvider().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var totalBytes = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                totalBytes += count
                require(totalBytes <= MAX_ENCODED_PAGE_BYTES) {
                    "Encoded page is larger than ${MAX_ENCODED_PAGE_BYTES / (1024 * 1024)} MiB"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(encodedPage, 0, encodedPage.size, options)
        if (bmp != null) {
            bmp
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(encodedPage))
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Error decoding a downloaded page image: ${e.message}" }
        null
    }

    private val HONORIFIC_PATTERNS: List<Pair<Regex, Pair<String, String>>> = listOf(
        Regex("형님") to ("형님" to "Hyung-nim"),
        Regex("(?<![가-힣])형(?=아|이|은|는|을|도|만|한테|에게|께서|\\s|[!?.,~…\"']|$)") to ("형" to "Hyung"),
        Regex("오빠") to ("오빠" to "Oppa"),
        Regex("누나") to ("누나" to "Noona"),
        Regex("언니") to ("언니" to "Unnie"),
        Regex("선배님") to ("선배님" to "Sunbae"),
        Regex("선배") to ("선배" to "Sunbae"),
        Regex("후배") to ("후배" to "Hoobae"),
        Regex("아줌마") to ("아줌마" to "Ajumma"),
        Regex("아저씨") to ("아저씨" to "Ajeossi"),
        Regex("막내") to ("막내" to "Maknae"),
        Regex("아가씨") to ("아가씨" to "Agassi"),
        Regex("도련님") to ("도련님" to "Young Master"),
    )

    private fun buildHonorificGlossary(sourceTexts: List<String>): Map<String, String> {
        val fullText = sourceTexts.joinToString("\n")
        val matched = linkedMapOf<String, String>()
        for ((regex, entry) in HONORIFIC_PATTERNS) {
            if (regex.containsMatchIn(fullText)) {
                matched[entry.first] = entry.second
            }
        }
        return matched
    }

    private fun makeDiagnostics(
        index: Int,
        analysisMs: Long,
        translationMs: Long = 0L,
        inpaintingMs: Long = 0L,
        typesettingMs: Long = 0L,
        totalMs: Long,
        regionCount: Int,
        averageOcrConfidence: Float = 0f,
        wasClampedToMinFontSize: Boolean = false,
        candidateCount: Int = 0,
        sliceCount: Int = 0,
        failedSliceCount: Int = 0,
        cropOcrCount: Int = 0,
        mergedObservationsCount: Int = 0,
        unresolvedCandidateCount: Int = 0,
        unresolvedCandidateIds: List<String> = emptyList(),
        candidateOutcomes: List<CandidateOutcomeRecord> = emptyList(),
        errorReason: String? = null,
    ) = PageDiagnostics(
        pageIndex = index,
        ocrDurationMs = analysisMs,
        translationDurationMs = translationMs,
        inpaintingDurationMs = inpaintingMs,
        typesettingDurationMs = typesettingMs,
        totalPageDurationMs = totalMs,
        javaHeapAllocatedBytes = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() },
        nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
        regionCount = regionCount,
        averageOcrConfidence = averageOcrConfidence,
        wasClampedToMinFontSize = wasClampedToMinFontSize,
        candidateCount = candidateCount,
        sliceCount = sliceCount,
        failedSliceCount = failedSliceCount,
        cropOcrCount = cropOcrCount,
        mergedObservationsCount = mergedObservationsCount,
        unresolvedCandidateCount = unresolvedCandidateCount,
        unresolvedCandidateIds = unresolvedCandidateIds,
        candidateOutcomes = candidateOutcomes,
        errorReason = errorReason,
    )

    data class SeamCrossingInfo(
        val topPageIndex: Int,
        val bottomPageIndex: Int,
        val topRegionId: String,
        val bottomRegionId: String,
        val stripHeightTop: Int,
        val stripHeightBottom: Int,
        val seamRegion: DetectedRegion,
    )

    data class SeamPatch(
        val pageIndex: Int,
        val bitmap: Bitmap,
        val destinationX: Int,
        val destinationY: Int,
    )

    private suspend fun stitchBoundarySplitRegions(
        pageAnalysis: MutableMap<Int, PageAnalysis>,
        totalPages: Int,
        pageStreams: List<Pair<Int, () -> InputStream>>,
    ): List<SeamCrossingInfo> {
        // Require regions to genuinely touch the slice cut (within 1.5% of top/bottom page edge)
        // so separate bubbles sitting 100-150px away from the seam are never falsely merged.
        val boundaryBottomThreshold = 0.985f
        val boundaryTopThreshold = 0.015f
        val minHorizontalOverlap = 0.50f
        val seamCrossings = mutableListOf<SeamCrossingInfo>()

        for (pageIndex in 0 until totalPages - 1) {
            val currAnalysis = pageAnalysis[pageIndex] ?: continue
            val nextAnalysis = pageAnalysis[pageIndex + 1] ?: continue

            val currPairs = currAnalysis.pairs.toMutableList()
            val nextPairs = nextAnalysis.pairs.toMutableList()
            val stitchedBottomIndices = mutableSetOf<Int>()
            var modified = false

            for (i in currPairs.indices) {
                val (topRegion, topOcr) = currPairs[i]
                if (topOcr.text.isNotBlank() && MlKitComicDetectorAndOcr.isWatermark(topOcr.text)) continue
                val topBox = topRegion.containerBounds ?: topRegion.boundingBox
                if (topBox.bottom < boundaryBottomThreshold) continue

                for (j in nextPairs.indices) {
                    if (j in stitchedBottomIndices) continue
                    val (bottomRegion, bottomOcr) = nextPairs[j]
                    if (bottomOcr.text.isNotBlank() && MlKitComicDetectorAndOcr.isWatermark(bottomOcr.text)) continue
                    val bottomBox = bottomRegion.containerBounds ?: bottomRegion.boundingBox
                    if (bottomBox.top > boundaryTopThreshold) continue

                    val hasTopText = topOcr.text.isNotBlank()
                    val hasBottomText = bottomOcr.text.isNotBlank()
                    if (!hasTopText && !hasBottomText) continue

                    // Check horizontal overlap and geometric alignment
                    val overlapLeft = maxOf(topBox.left, bottomBox.left)
                    val overlapRight = minOf(topBox.right, bottomBox.right)
                    val overlapWidth = maxOf(0f, overlapRight - overlapLeft)
                    val minWidth = minOf(topBox.width(), bottomBox.width())
                    val maxWidth = maxOf(topBox.width(), bottomBox.width())

                    val isHorizontallyAligned = minWidth > 0f &&
                        (overlapWidth / minWidth) >= minHorizontalOverlap &&
                        (maxWidth == 0f || (minWidth / maxWidth) >= 0.50f)

                    if (isHorizontallyAligned) {
                        var unseveredText: String? = null
                        var unseveredConfidence = maxOf(topOcr.confidence, bottomOcr.confidence)
                        var unseveredRegion: DetectedRegion? = null
                        val stripH = 400

                        // Attempt unsevered seam band OCR
                        val topStream = pageStreams.find { it.first == pageIndex }?.second
                        val bottomStream = pageStreams.find { it.first == pageIndex + 1 }?.second
                        if (topStream != null && bottomStream != null) {
                            try {
                                val topDecoder = BitmapRegionDecoder.newInstance(topStream(), false)
                                val bottomDecoder = BitmapRegionDecoder.newInstance(bottomStream(), false)
                                if (topDecoder != null && bottomDecoder != null) {
                                    val tW = topDecoder.width; val tH = topDecoder.height
                                    val bW = bottomDecoder.width; val bH = bottomDecoder.height
                                    val actualTopStripH = minOf(stripH, tH / 2)
                                    val actualBottomStripH = minOf(stripH, bH / 2)
                                    val topStrip = topDecoder.decodeRegion(Rect(0, tH - actualTopStripH, tW, tH), null)
                                    val bottomStrip = bottomDecoder.decodeRegion(Rect(0, 0, bW, actualBottomStripH), null)
                                    topDecoder.recycle()
                                    bottomDecoder.recycle()

                                    if (topStrip != null && bottomStrip != null) {
                                        val totalStripH = actualTopStripH + actualBottomStripH
                                        val seamStrip = Bitmap.createBitmap(tW, totalStripH, Bitmap.Config.ARGB_8888)
                                        Canvas(seamStrip).apply {
                                            drawBitmap(topStrip, 0f, 0f, null)
                                            drawBitmap(bottomStrip, 0f, actualTopStripH.toFloat(), null)
                                        }
                                        topStrip.recycleIfNeeded()
                                        bottomStrip.recycleIfNeeded()

                                        val seamMeta = detectorAndOcr.analyzeWithMetadata(seamStrip)
                                        val seamCandidate = seamMeta.pairs.find { (r, ocr) ->
                                            val b = r.containerBounds ?: r.boundingBox
                                            val bTopPx = b.top * totalStripH
                                            val bBottomPx = b.bottom * totalStripH
                                            val hOverlap = maxOf(0f, minOf(topBox.right, b.right) - maxOf(topBox.left, b.left))
                                            val minW = minOf(topBox.width(), b.width()).coerceAtLeast(0.01f)
                                            bTopPx < actualTopStripH && bBottomPx > actualTopStripH &&
                                                (hOverlap / minW) >= 0.40f && ocr.text.isNotBlank()
                                        }
                                        if (seamCandidate != null) {
                                            unseveredText = seamCandidate.second.text
                                            unseveredConfidence = seamCandidate.second.confidence
                                            unseveredRegion = seamCandidate.first
                                            logcat(LogPriority.INFO) {
                                                "Seam band OCR successfully recovered unsevered text across pages $pageIndex & ${pageIndex + 1}: '$unseveredText' (conf=$unseveredConfidence)"
                                            }
                                        }
                                        seamStrip.recycleIfNeeded()
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "Seam band OCR failed across pages $pageIndex & ${pageIndex + 1}" }
                            }
                        }

                        val rawMergedText = unseveredText ?: when {
                            hasTopText && hasBottomText -> {
                                if (MlKitComicDetectorAndOcr.areTextsSimilar(topOcr.text, bottomOcr.text)) {
                                    if (topOcr.text.length >= bottomOcr.text.length) topOcr.text.trim() else bottomOcr.text.trim()
                                } else {
                                    "${topOcr.text.trim()} ${bottomOcr.text.trim()}".trim()
                                }
                            }
                            hasTopText -> topOcr.text.trim()
                            else -> bottomOcr.text.trim()
                        }
                        val mergedText = MlKitComicDetectorAndOcr.normalizeOcrText(rawMergedText).ifBlank { rawMergedText }

                        logcat(LogPriority.INFO) {
                            "Cross-page split bubble stitched across pages $pageIndex and ${pageIndex + 1}: " +
                                "top='${topOcr.text}' bottom='${bottomOcr.text}' -> final='$mergedText'"
                        }

                        currPairs[i] = topRegion to topOcr.copy(text = mergedText, confidence = unseveredConfidence)
                        nextPairs[j] = bottomRegion.copy(translateMode = TranslateMode.KEEP) to bottomOcr.copy(text = "")
                        stitchedBottomIndices += j

                        // Ensure seamRegion ALWAYS retains topRegion.id so Phase 3a typesetter matches trans.regionId!
                        val finalRegion = (unseveredRegion ?: topRegion.copy(
                            boundingBox = RectF(topBox.left, 0.20f, topBox.right, 0.80f),
                            containerBounds = RectF(topBox.left, 0.15f, topBox.right, 0.85f),
                        )).copy(id = topRegion.id)
                        seamCrossings.add(
                            SeamCrossingInfo(
                                topPageIndex = pageIndex,
                                bottomPageIndex = pageIndex + 1,
                                topRegionId = topRegion.id,
                                bottomRegionId = bottomRegion.id,
                                stripHeightTop = minOf(stripH, 2500),
                                stripHeightBottom = minOf(stripH, 2500),
                                seamRegion = finalRegion,
                            )
                        )
                        modified = true
                        break
                    }
                }

                // Single-sided seam recovery: when a region on `pageIndex` has its text `boundingBox.bottom >= 0.992f`
                // (or `nextAnalysis.candidateOutcomes` has an aligned top candidate at `top <= 0.015f`) and the severed
                // bottom glyph tips on `pageIndex + 1` were too short to form a standalone OCR pair in `nextPairs`
                // (e.g. Ch 1 P42+P43, Ch 2 P16+P17, Ch 2 P26+P27, Ch 2 P76+P77), stitch across the seam so `seamStrip`
                // recovers unsevered OCR and Phase 3a erases the severed glyph tips on `pageIndex + 1`!
                val alreadyStitchedTop = seamCrossings.any { it.topPageIndex == pageIndex && it.topRegionId == topRegion.id }
                val hasAlignedBottomCandidate = nextAnalysis.candidateOutcomes.any { cand ->
                    cand.top <= boundaryTopThreshold &&
                        (minOf(topBox.right, cand.right) - maxOf(topBox.left, cand.left)) /
                        minOf(topBox.width(), (cand.right - cand.left).coerceAtLeast(0.01f)).coerceAtLeast(0.01f) >= minHorizontalOverlap
                }
                if (!alreadyStitchedTop && topOcr.text.isNotBlank() && (topRegion.boundingBox.bottom >= 0.992f || hasAlignedBottomCandidate)) {
                    var unseveredText: String? = null
                    var unseveredConfidence = topOcr.confidence
                    var unseveredRegion: DetectedRegion? = null
                    val stripH = 400
                    val topStream = pageStreams.find { it.first == pageIndex }?.second
                    val bottomStream = pageStreams.find { it.first == pageIndex + 1 }?.second
                    if (topStream != null && bottomStream != null) {
                        try {
                            val topDecoder = BitmapRegionDecoder.newInstance(topStream(), false)
                            val bottomDecoder = BitmapRegionDecoder.newInstance(bottomStream(), false)
                            if (topDecoder != null && bottomDecoder != null) {
                                val tW = topDecoder.width; val tH = topDecoder.height
                                val bW = bottomDecoder.width; val bH = bottomDecoder.height
                                val actualTopStripH = minOf(stripH, tH / 2)
                                val actualBottomStripH = minOf(stripH, bH / 2)
                                val topStrip = topDecoder.decodeRegion(Rect(0, tH - actualTopStripH, tW, tH), null)
                                val bottomStrip = bottomDecoder.decodeRegion(Rect(0, 0, bW, actualBottomStripH), null)
                                topDecoder.recycle()
                                bottomDecoder.recycle()
                                if (topStrip != null && bottomStrip != null) {
                                    val totalStripH = actualTopStripH + actualBottomStripH
                                    val seamStrip = Bitmap.createBitmap(tW, totalStripH, Bitmap.Config.ARGB_8888)
                                    Canvas(seamStrip).apply {
                                        drawBitmap(topStrip, 0f, 0f, null)
                                        drawBitmap(bottomStrip, 0f, actualTopStripH.toFloat(), null)
                                    }
                                    topStrip.recycleIfNeeded()
                                    bottomStrip.recycleIfNeeded()
                                    val seamMeta = detectorAndOcr.analyzeWithMetadata(seamStrip)
                                    val seamCandidate = seamMeta.pairs.find { (r, ocr) ->
                                        val b = r.containerBounds ?: r.boundingBox
                                        val bTopPx = b.top * totalStripH
                                        val bBottomPx = b.bottom * totalStripH
                                        val hOverlap = maxOf(0f, minOf(topBox.right, b.right) - maxOf(topBox.left, b.left))
                                        val minW = minOf(topBox.width(), b.width()).coerceAtLeast(0.01f)
                                        bTopPx < actualTopStripH && bBottomPx > actualTopStripH - 12 &&
                                            (hOverlap / minW) >= 0.40f && ocr.text.isNotBlank()
                                    }
                                    if (seamCandidate != null) {
                                        unseveredText = seamCandidate.second.text
                                        unseveredConfidence = seamCandidate.second.confidence
                                        unseveredRegion = seamCandidate.first
                                    }
                                    seamStrip.recycleIfNeeded()
                                }
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "Single-sided seam band OCR failed across pages $pageIndex & ${pageIndex + 1}" }
                        }
                    }
                    val rawMerged = unseveredText ?: topOcr.text.trim()
                    val mergedText = MlKitComicDetectorAndOcr.normalizeOcrText(rawMerged).ifBlank { rawMerged }
                    currPairs[i] = topRegion to topOcr.copy(text = mergedText, confidence = unseveredConfidence)
                    val finalRegion = (unseveredRegion ?: topRegion.copy(
                        boundingBox = RectF(topBox.left, 0.20f, topBox.right, 0.85f),
                        containerBounds = topRegion.containerBounds?.let { RectF(topBox.left, 0.15f, topBox.right, 0.90f) },
                    )).copy(id = topRegion.id)
                    seamCrossings.add(
                        SeamCrossingInfo(
                            topPageIndex = pageIndex,
                            bottomPageIndex = pageIndex + 1,
                            topRegionId = topRegion.id,
                            bottomRegionId = "seam_bottom_${topRegion.id}",
                            stripHeightTop = minOf(stripH, 2500),
                            stripHeightBottom = minOf(stripH, 2500),
                            seamRegion = finalRegion,
                        )
                    )
                    modified = true
                }
            }

            if (modified) {
                pageAnalysis[pageIndex] = currAnalysis.copy(pairs = deduplicateConnectedPairs(currPairs))
                pageAnalysis[pageIndex + 1] = nextAnalysis.copy(pairs = nextPairs)
            }
        }
        return seamCrossings
    }

    private fun classifySpeakerTurn(
        text: String,
        region: mihon.feature.translation.model.DetectedRegion?,
        prevTag: String?,
        prevY: Float,
    ): String {
        val isNarration = region?.type != mihon.feature.translation.model.RegionType.TEXT_IN_BUBBLE
        val xCenter = region?.boundingBox?.let { (it.left + it.right) / 2f } ?: 0.5f
        val yTop = region?.boundingBox?.top ?: 0.0f
        val deltaY = kotlin.math.abs(yTop - prevY)

        if (isNarration || text.contains(Regex("""\(\d+\)|연구실|대학\s*조교"""))) {
            return "[Narration / Title Box]"
        }

        val hasPolitePronoun = text.contains(Regex("""(저[는도희를에게]|제[가]?|드리[다겠는어]|말씀|님|시[었겠는])"""))
        val hasCasualPronoun = text.contains(Regex("""(나[는도를에게]|내[가]?|너[는도를에게]|네[가]?|잖아|거든)"""))
        val hasPoliteEnding = text.contains(Regex("""(요|니다|니까|세요|셨네요|시지|군요|네요|까요)[\?.!~…\s]*$"""))
        val hasCasualEnding = text.contains(Regex("""(해|게|네|야|어|아|지|군|다|자|마|임|냐)[\?.!~…\s]*$"""))

        val benefactiveHint = if (text.contains(Regex("""(해\s*준|해\s*주신|정리\s*해준|도와준)"""))) {
            " (Note: 해준 = action done by other party for speaker)"
        } else ""

        val level = when {
            hasPoliteEnding || (hasPolitePronoun && !hasCasualEnding) -> "Polite/Junior"
            hasCasualEnding || hasCasualPronoun -> "Casual/Senior"
            deltaY < 0.035f && prevTag != null && prevTag.contains("Polite") -> "Polite/Junior (Connected)"
            deltaY < 0.035f && prevTag != null && prevTag.contains("Casual") -> "Casual/Senior (Connected)"
            else -> "Casual/Senior"
        }

        val lane = when {
            xCenter < 0.42f -> "Left Bubble"
            xCenter > 0.58f -> "Right Bubble"
            else -> "Center Bubble"
        }
        return "$lane | $level$benefactiveHint"
    }

    private fun deduplicateConnectedPairs(
        pairs: List<Pair<DetectedRegion, mihon.feature.translation.model.OcrResult>>,
    ): List<Pair<DetectedRegion, mihon.feature.translation.model.OcrResult>> {
        if (pairs.size <= 1) return pairs
        val retained = mutableListOf<Pair<DetectedRegion, mihon.feature.translation.model.OcrResult>>()
        val gapTolerance = 0.015f

        for (candidate in pairs) {
            val (cRegion, cOcr) = candidate
            val cTextNorm = java.text.Normalizer.normalize(cOcr.text, java.text.Normalizer.Form.NFKC)
                .lowercase(java.util.Locale.ROOT)
                .replace(Regex("\\s+"), " ")
                .trim()
            val cBox = cRegion.containerBounds ?: cRegion.boundingBox

            var duplicateIndex = -1
            for (i in retained.indices) {
                val (rRegion, rOcr) = retained[i]
                val rTextNorm = java.text.Normalizer.normalize(rOcr.text, java.text.Normalizer.Form.NFKC)
                    .lowercase(java.util.Locale.ROOT)
                    .replace(Regex("\\s+"), " ")
                    .trim()

                if (cTextNorm.isNotEmpty() && rTextNorm.isNotEmpty() &&
                    (cTextNorm == rTextNorm || MlKitComicDetectorAndOcr.areTextsSimilar(cOcr.text, rOcr.text))
                ) {
                    // Require physical overlap between the two text boundingBoxes (`textOverlapRatio >= 0.25f`),
                    // NOT just touching outer bubble containers! Connected double bubbles (`연결 말풍선`, e.g. Ch 2 P76
                    // "네가 생각하는 그런 거 아니야" and "이상한 생각 하지마") have touching containers but disjoint
                    // text boxes and must never delete one another.
                    val tBoxA = cRegion.boundingBox
                    val tBoxB = rRegion.boundingBox
                    val interLeft = maxOf(tBoxA.left, tBoxB.left)
                    val interTop = maxOf(tBoxA.top, tBoxB.top)
                    val interRight = minOf(tBoxA.right, tBoxB.right)
                    val interBottom = minOf(tBoxA.bottom, tBoxB.bottom)
                    val interArea = if (interRight > interLeft && interBottom > interTop) {
                        (interRight - interLeft) * (interBottom - interTop)
                    } else 0f
                    val minTextArea = minOf(tBoxA.width() * tBoxA.height(), tBoxB.width() * tBoxB.height()).coerceAtLeast(1e-6f)
                    val textOverlapRatio = interArea / minTextArea

                    if (textOverlapRatio >= 0.25f) {
                        duplicateIndex = i
                        break
                    }
                }
            }

            if (duplicateIndex != -1) {
                val existing = retained[duplicateIndex]
                val existingConf = existing.second.confidence
                val candidateConf = cOcr.confidence
                val existingArea = (existing.first.containerBounds ?: existing.first.boundingBox).let { it.width() * it.height() }
                val candidateArea = cBox.width() * cBox.height()

                if (candidateConf > existingConf || (candidateConf == existingConf && candidateArea > existingArea)) {
                    retained[duplicateIndex] = candidate
                }
            } else {
                retained.add(candidate)
            }
        }
        return retained
    }

    private fun isPassThroughText(sourceText: String): Boolean {
        val trimmed = sourceText.trim()
        if (trimmed.isEmpty()) return false
        if (Regex("""^[#№]?\s*\d{1,4}[A-Za-z]?$""").matches(trimmed)) {
            return true
        }
        val lower = trimmed.lowercase(java.util.Locale.ROOT)
        val creditPrefixes = listOf(
            "scanlated by", "translated by", "typeset by", "cleaned by", "proofread by",
            "raw provider", "scanlator:", "translator:", "typesetter:", "cleaner:", "proofreader:",
            "thanks for visiting", "reading manga", "번역:", "식자:", "스캔:"
        )
        if (creditPrefixes.any { lower.startsWith(it) }) {
            return true
        }
        if (Regex("(?:글|그림|원작|도움|타이틀)\\s*[-–/:]|글·그림|글/그림").containsMatchIn(trimmed)) {
            return true
        }
        return false
    }

    /**
     * Challenge 5 Solution: Structural Cross-Page Watermark Detector.
     * Identifies non-bubble text blocks (`FREE_TEXT` / `UNKNOWN_TEXT` without a model-detected bubble)
     * whose normalized character 3-grams repeat across >= 3 distinct pages in the chapter
     * (excluding legitimate SFX in ManhwaSfxDictionary) and removes them before translation/inpainting.
     */
    private fun filterCrossPageRecurringWatermarks(pageAnalysis: MutableMap<Int, PageAnalysis>) {
        if (pageAnalysis.size < 4) return
        val ngramPageMap = mutableMapOf<String, MutableSet<Int>>()

        fun extractNgrams(text: String): Set<String> {
            val clean = text.replace(Regex("\\s+"), "").lowercase(java.util.Locale.ROOT)
            if (clean.length < 4 || ManhwaSfxDictionary.translateSfx(text) != null) return emptySet()
            val ngrams = mutableSetOf<String>()
            for (i in 0..clean.length - 3) {
                ngrams += clean.substring(i, i + 3)
            }
            return ngrams
        }

        for ((pageIdx, analysis) in pageAnalysis) {
            for ((region, ocr) in analysis.pairs) {
                val isUnboxed = !region.isModelDetectedBubble && region.containerBounds == null && region.type != RegionType.TEXT_IN_BUBBLE
                if (!isUnboxed) continue
                for (ng in extractNgrams(ocr.text)) {
                    ngramPageMap.getOrPut(ng) { mutableSetOf() }.add(pageIdx)
                }
            }
        }

        val recurringWatermarkNgrams = ngramPageMap.filterValues { it.size >= 3 }.keys
        if (recurringWatermarkNgrams.isEmpty()) return

        for ((pageIdx, analysis) in pageAnalysis.entries.toList()) {
            val filtered = analysis.pairs.filterNot { (region, ocr) ->
                val isUnboxed = !region.isModelDetectedBubble && region.containerBounds == null && region.type != RegionType.TEXT_IN_BUBBLE
                val isCornerOrMargin = region.boundingBox.top < 0.08f || region.boundingBox.bottom > 0.92f ||
                    region.boundingBox.left < 0.08f || region.boundingBox.right > 0.92f
                if (isUnboxed && isCornerOrMargin && ManhwaSfxDictionary.translateSfx(ocr.text) == null) {
                    val ngrams = extractNgrams(ocr.text)
                    val matched = ngrams.count { it in recurringWatermarkNgrams }
                    if (matched >= 2) {
                        logcat(LogPriority.INFO) {
                            "Cross-page structural watermark dropped on page $pageIdx: '${ocr.text.take(40)}'"
                        }
                        true
                    } else false
                } else false
            }
            if (filtered.size != analysis.pairs.size) {
                pageAnalysis[pageIdx] = analysis.copy(pairs = filtered)
            }
        }
    }

    private fun Bitmap?.recycleIfNeeded() {
        if (this != null && !isRecycled) recycle()
    }
}