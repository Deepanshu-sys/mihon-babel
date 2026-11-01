package mihon.feature.translation.pipeline

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.feature.translation.contract.DialogueTranslator
import mihon.feature.translation.model.ChapterContext
import mihon.feature.translation.model.DialogueUnit
import mihon.feature.translation.model.TranslationBatch
import mihon.feature.translation.model.TranslationResult
import tachiyomi.core.common.util.system.logcat

/**
 * Chapter-aware local Index-Translate 2B translator implementing:
 * 1. Whole-Page Batching (up to 8 lines in 1 LLM call per page)
 * 2. 100% Static System Prompt (for instant KV-cache prefix hits in translation_llm.cpp)
 * 3. Source-Side Korean-Only Rolling Context (zero English history to prevent context contagion/echo)
 * 4. Three Distinct Effort Modes (LOW: 1-pass direct, MEDIUM: instTrans + Hangul rescue, HIGH: full instTrans + 3-point verification)
 */
internal class LocalLlmDialogueTranslator(
    context: Context,
    assetManager: ModelAssetManager,
    modelFileName: String = INDEX_MODEL_FILE,
    override val modelId: String = INDEX_MODEL_ID,
    systemPrompt: String = INDEX_SYSTEM_PROMPT,
    private val deepContext: Boolean = false,
    private val enableContext: Boolean = true,
    temperature: Float = INDEX_TEMPERATURE,
    contextSize: Int = DEFAULT_CONTEXT_SIZE,
    threadCount: Int = DEFAULT_THREAD_COUNT,
) : DialogueTranslator {

    private val runtime: LocalLlmRuntime
    private val baseSystemPrompt: String = systemPrompt
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
    }

    init {
        assetManager.ensureModelsExtracted(modelFileName).getOrThrow()
        runtime = LocalLlmRuntime(
            context = context.applicationContext,
            modelFile = assetManager.getModelFile(modelFileName),
            systemPrompt = systemPrompt,
            temperature = temperature,
            contextSize = contextSize,
            threadCount = threadCount,
        )
        runtime.reset()
    }

    override suspend fun translateBatch(
        batch: TranslationBatch,
        context: ChapterContext,
    ): List<TranslationResult> {
        val dialogues = batch.dialogues.filter { it.sourceText.isNotBlank() }
        if (dialogues.isEmpty()) return emptyList()

        val results = ArrayList<TranslationResult>(dialogues.size)
        val pendingAiDialogues = mutableListOf<DialogueUnit>()

        // 1. Instant Human-Grade SFX & Bubble Vocalization check (0ms), plus garbled mixed-script filter
        for (dialogue in dialogues) {
            val sfx = ManhwaSfxDictionary.translateSfx(dialogue.sourceText)
            if (sfx != null) {
                val cleanBubbleSfx = sfx.removeSurrounding("*")
                logcat(LogPriority.INFO) { "SFX Lexicon [0ms]: '${dialogue.sourceText}' -> '$cleanBubbleSfx'" }
                results += TranslationResult(
                    regionId = dialogue.regionId,
                    translatedText = cleanBubbleSfx,
                    confidence = 0.95f,
                    disposition = mihon.feature.translation.model.TranslationDisposition.APPROVED_SFX_LEXICON,
                )
            } else if (isGarbledMixedScript(dialogue.sourceText)) {
                logcat(LogPriority.WARN) { "Dropping garbled mixed-script dialogue: '${dialogue.sourceText}'" }
                results += TranslationResult(
                    regionId = dialogue.regionId,
                    translatedText = dialogue.sourceText,
                    confidence = 0.0f,
                    disposition = mihon.feature.translation.model.TranslationDisposition.IGNORED,
                )
            } else {
                pendingAiDialogues += dialogue
            }
        }

        if (pendingAiDialogues.isEmpty()) {
            return results
        }

        // 2. Strip inline watermark fragments & pre-normalize true Korean stutters (0ms)
        val stutterMap = mutableMapOf<String, Boolean>()
        // 0ms Chapter-Wide Exact Dialogue Deduplication Cache: reuse verified translations from earlier in the chapter
        // for recurring short reactions/utterances (<= 16 chars) with matching punctuation!
        val chapterDedupCache = context.recentTranslations.associate { entry ->
            entry.sourceText.replace(Regex("""\s+"""), " ").trim() to entry.translatedText
        }.toMutableMap()

        val normalizedAiDialogues = pendingAiDialogues.mapNotNull { dialogue ->
            val deWatermarked = stripInlineWatermarkGarbage(dialogue.sourceText)
            if (deWatermarked.isBlank() || isGarbledMixedScript(deWatermarked)) {
                results += TranslationResult(
                    regionId = dialogue.regionId,
                    translatedText = dialogue.sourceText,
                    confidence = 0.0f,
                    disposition = mihon.feature.translation.model.TranslationDisposition.IGNORED,
                )
                return@mapNotNull null
            }
            val stutterInfo = normalizeKoreanStutter(deWatermarked)
            stutterMap[dialogue.regionId] = stutterInfo.hadStutter
            val normKey = stutterInfo.normalizedText.replace(Regex("""\s+"""), " ").trim()
            val cachedTrans = if (normKey.length in 2..16) chapterDedupCache[normKey] else null
            if (!cachedTrans.isNullOrBlank() && isCleanEnglishTranslation(cachedTrans, context.seriesTitle)) {
                val finalCached = if (stutterInfo.hadStutter) applyScanlationStutter(cachedTrans) else cachedTrans
                logcat(LogPriority.INFO) { "Chapter Dedup Cache [0ms]: '$normKey' -> '$finalCached'" }
                results += TranslationResult(
                    regionId = dialogue.regionId,
                    translatedText = finalCached,
                    confidence = 0.94f,
                    disposition = mihon.feature.translation.model.TranslationDisposition.MODEL_TRANSLATION,
                )
                return@mapNotNull null
            }
            dialogue.copy(sourceText = stutterInfo.normalizedText)
        }
        if (normalizedAiDialogues.isEmpty()) {
            val resultMapEarly = results.associateBy { it.regionId }
            return dialogues.mapNotNull { resultMapEarly[it.regionId] }
        }

        // 3. Whole-Page Batching (up to 8 lines per call = 1 LLM call per page on 98% of manhwa pages)
        val recentRolling = context.recentTranslations.toMutableList()
        val subBatches = groupWholePageBatches(normalizedAiDialogues)

        for (subBatch in subBatches) {
            currentCoroutineContext().ensureActive()
            val startMs = android.os.SystemClock.elapsedRealtime()

            val rollingCount = when {
                !enableContext -> 0 // LOW effort: 0 history (speed mode)
                deepContext -> 4    // HIGH effort: 4 Korean lines for deep cross-batch continuity
                else -> 2           // MEDIUM effort: 2 Korean lines for inter-batch continuity
            }
            val rollingEntries = if (rollingCount > 0) recentRolling.takeLast(rollingCount) else emptyList()
            val subContext = context.copy(recentTranslations = rollingEntries)

            // Keep systemPrompt 100% static (baseSystemPrompt) so llama_memory_seq_rm in translation_llm.cpp
            // reuses 100% of the static system prompt KV cache across every page/batch.
            val prompt = buildEffortDialoguePrompt(subBatch, subContext)
            val maxTok = (subBatch.sumOf { it.sourceText.length } * 4 + 96).coerceIn(128, 768)
            val generated = runtime.generate(
                prompt = prompt,
                maxTokens = maxTok,
                lineCount = subBatch.size,
                systemPromptOverride = baseSystemPrompt,
            )
            val elapsed = android.os.SystemClock.elapsedRealtime() - startMs
            logcat(LogPriority.INFO) { "Index-Translate 2B batch (${subBatch.size} lines) completed in ${elapsed}ms | Raw: '$generated'" }
            // Thermal pacing guard: tablet runs at 31.3-33.1C; brief 40ms breather only after very heavy >4.5s batches
            if (enableContext && elapsed > 4500L) {
                kotlinx.coroutines.delay(40L)
            }

            var parsedMap = parseNumberedDialogueResponse(generated, subBatch.size)

            // Effort-Gated Verification & Rescue:
            // - LOW effort (!enableContext): 0 LLM retries (Fast 1-pass) + 0ms deterministic honorific stripping
            // - MEDIUM effort (enableContext && !deepContext): Targeted single-line LLM rescue if blank, leaks Hangul/CJK, or hallucinates honorifics
            // - HIGH effort (deepContext): Strict 4-point verification (Hangul/CJK leaks, honorifics, glossary name match, and inter/intra-batch echo)
            if (!enableContext) {
                val mutableLow = parsedMap.toMutableMap()
                for (idx in subBatch.indices) {
                    val key = (idx + 1).toString()
                    val tr = mutableLow[key]
                    if (!tr.isNullOrBlank() && hasUnprovenancedHonorific(subBatch[idx].sourceText, tr)) {
                        mutableLow[key] = stripUnprovenancedHonorifics(subBatch[idx].sourceText, tr)
                    }
                }
                parsedMap = mutableLow
            } else {
                // First, deterministically strip any unprovenanced vocative honorifics in 0ms when the rest of the
                // English sentence is clean and non-empty—avoiding a 2.5-3.5s LLM retry and preserving KV cache prefix!
                val preStrippedMap = parsedMap.toMutableMap()
                for (idx in subBatch.indices) {
                    val key = (idx + 1).toString()
                    val tr = preStrippedMap[key]
                    if (!tr.isNullOrBlank() &&
                        isCleanEnglishTranslation(tr, context.seriesTitle) &&
                        hasUnprovenancedHonorific(subBatch[idx].sourceText, tr)
                    ) {
                        val stripped = stripUnprovenancedHonorifics(subBatch[idx].sourceText, tr)
                        if (stripped.isNotBlank() && stripped.any { it.isLetter() } && !hasUnprovenancedHonorific(subBatch[idx].sourceText, stripped)) {
                            preStrippedMap[key] = stripped
                        }
                    }
                }
                parsedMap = preStrippedMap

                val lastRecentTrans = recentRolling.lastOrNull()?.translatedText
                val badIndices = subBatch.indices.filter { idx ->
                    val key = (idx + 1).toString()
                    val tr = parsedMap[key]
                    if (tr.isNullOrBlank() || !isCleanEnglishTranslation(tr, context.seriesTitle)) {
                        return@filter true
                    }
                    if (hasUnprovenancedHonorific(subBatch[idx].sourceText, tr)) {
                        return@filter true
                    }
                    if (deepContext) {
                        val prevKey = idx.toString()
                        val prevTr = parsedMap[prevKey]
                        val isInterBatchEcho = idx == 0 && lastRecentTrans != null &&
                            tr.equals(lastRecentTrans, ignoreCase = true) &&
                            subBatch[idx].sourceText != recentRolling.lastOrNull()?.sourceText
                        val isIntraBatchEcho = idx > 0 && prevTr != null &&
                            tr.equals(prevTr, ignoreCase = true) &&
                            subBatch[idx].sourceText != subBatch[idx - 1].sourceText
                        val missingGlossaryName = context.glossary.any { (koTerm, enTerm) ->
                            koTerm.length >= 2 &&
                                subBatch[idx].sourceText.contains(koTerm) &&
                                !tr.contains(enTerm, ignoreCase = true) &&
                                !tr.contains(enTerm.replace("-", ""), ignoreCase = true) &&
                                !tr.contains(enTerm.split(" ").last(), ignoreCase = true)
                        }
                        val srcHangulLen = subBatch[idx].sourceText.count { it in '\uAC00'..'\uD7A3' }
                        val enWordCount = tr.trim().split(Regex("""\s+""")).count { it.isNotBlank() }
                        val isLengthMismatch = (srcHangulLen >= 10 && enWordCount <= 1) ||
                            (srcHangulLen in 1..2 && enWordCount > 12)
                        isInterBatchEcho || isIntraBatchEcho || missingGlossaryName || isLengthMismatch
                    } else {
                        false
                    }
                }

                if (badIndices.isNotEmpty()) {
                    val mutableParsed = parsedMap.toMutableMap()
                    logcat(LogPriority.WARN) { "Targeted single-line rescue for ${badIndices.size}/${subBatch.size} line(s)..." }
                    for (idx in badIndices) {
                        currentCoroutineContext().ensureActive()
                        val key = (idx + 1).toString()
                        val trBefore = parsedMap[key]
                        val isHonorificFail = trBefore != null && hasUnprovenancedHonorific(subBatch[idx].sourceText, trBefore)
                        val singlePrompt = buildRetryEnglishPrompt(
                            dialogues = listOf(subBatch[idx]),
                            glossary = context.glossary,
                            noHonorifics = isHonorificFail,
                        )
                        val singleGen = runtime.generate(
                            prompt = singlePrompt,
                            maxTokens = 128,
                            lineCount = 1,
                            systemPromptOverride = baseSystemPrompt,
                        )
                        val singleMap = parseNumberedDialogueResponse(singleGen, 1)
                        var rescued = singleMap["1"]
                        if (!rescued.isNullOrBlank() && isCleanEnglishTranslation(rescued, context.seriesTitle)) {
                            if (hasUnprovenancedHonorific(subBatch[idx].sourceText, rescued)) {
                                rescued = stripUnprovenancedHonorifics(subBatch[idx].sourceText, rescued)
                            }
                            mutableParsed[key] = rescued
                        } else if (isHonorificFail && !trBefore.isNullOrBlank()) {
                            mutableParsed[key] = stripUnprovenancedHonorifics(subBatch[idx].sourceText, trBefore)
                        }
                    }
                    parsedMap = mutableParsed
                }
            }

            for ((index, dialogue) in subBatch.withIndex()) {
                val key = (index + 1).toString()
                val translated = parsedMap[key]
                val isModelOutput = !translated.isNullOrBlank() &&
                    !isJsonGarbage(translated) &&
                    isCleanEnglishTranslation(translated, context.seriesTitle)

                val (finalClean, disp, conf) = if (isModelOutput) {
                    val rawCleaned = cleanTranslationText(translated)
                    val cleaned = if (stutterMap[dialogue.regionId] == true) {
                        applyScanlationStutter(rawCleaned)
                    } else {
                        rawCleaned
                    }
                    recentRolling += mihon.feature.translation.model.TranslationMemoryEntry(
                        sourceText = dialogue.sourceText,
                        translatedText = cleaned,
                        speakerTag = dialogue.speakerHint,
                    )
                    Triple(cleaned, mihon.feature.translation.model.TranslationDisposition.MODEL_TRANSLATION, 0.92f)
                } else {
                    val sfxFallback = ManhwaSfxDictionary.translateSfx(dialogue.sourceText)
                    if (sfxFallback != null) {
                        Triple(sfxFallback.removeSurrounding("*"), mihon.feature.translation.model.TranslationDisposition.APPROVED_SFX_LEXICON, 0.95f)
                    } else {
                        Triple(dialogue.sourceText, mihon.feature.translation.model.TranslationDisposition.PASSTHROUGH_REVIEW, 0.1f)
                    }
                }
                logcat(LogPriority.INFO) {
                    "Index-Translate 2B batch line ${index + 1} [${elapsed}ms]: '${dialogue.sourceText.replace("\n", " ")}' -> '$finalClean'"
                }
                results += TranslationResult(
                    regionId = dialogue.regionId,
                    translatedText = finalClean,
                    confidence = conf,
                    disposition = disp,
                )
            }
        }

        val resultMap = results.associateBy { it.regionId }
        return dialogues.mapNotNull { resultMap[it.regionId] }
    }

    private fun isCleanEnglishTranslation(text: String, seriesTitle: String? = null): Boolean {
        val trimmed = text.trim().removeSurrounding("\"").removeSurrounding("'")
        if (!seriesTitle.isNullOrBlank()) {
            val normTitle = seriesTitle.replace(Regex("(?i)\\s+(?:raw|chapter.*)$"), "").trim()
            if (trimmed.equals(seriesTitle.trim(), ignoreCase = true) || (normTitle.length >= 4 && trimmed.equals(normTitle, ignoreCase = true))) {
                return false
            }
        }
        if (trimmed.isBlank()) return false
        if (trimmed.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' || it in '\uf900'..'\ufaff' }) return false
        if (trimmed.any { it in '\uac00'..'\ud7a3' || it in '\u1100'..'\u11ff' || it in '\u3130'..'\u318f' }) return false
        if (trimmed.any { it in '\u3040'..'\u309f' || it in '\u30a0'..'\u30ff' }) return false
        val hasNonLatinLetters = trimmed.any { Character.isLetter(it) && it !in 'a'..'z' && it !in 'A'..'Z' && it !in '\u00C0'..'\u024F' }
        if (hasNonLatinLetters) return false
        return true
    }

    private fun formatRegisterCue(speakerHint: String?, sourceText: String): String? {
        val hasBenefactive = (speakerHint != null && (speakerHint.contains("해준") || speakerHint.contains("other party for speaker"))) ||
            Regex("""(해\s*준|해\s*주신|정리\s*해준|도와\s*준|챙겨\s*준)""").containsMatchIn(sourceText)
        val hasFirstPersonSubject = Regex("""(?:^|\s|[.,?!~…])(난|나는|내가|제가)(?=\s|[.,?!~…]|$)""").containsMatchIn(sourceText)
        val register = when {
            speakerHint == null -> null
            speakerHint.contains("Narration", ignoreCase = true) -> "Narration"
            speakerHint.contains("Polite", ignoreCase = true) -> "Polite"
            speakerHint.contains("Casual", ignoreCase = true) -> "Casual"
            else -> null
        }
        return when {
            hasBenefactive && register != null -> "$register, 'you did for me' (-해 준 = listener did it for speaker)"
            hasBenefactive -> "'you did for me' (-해 준 = listener did it for speaker)"
            hasFirstPersonSubject && register != null -> "$register, subject is 'I' (난/내가)"
            hasFirstPersonSubject -> "subject is 'I' (난/내가)"
            deepContext && register != null -> register
            else -> null
        }
    }

    /**
     * Builds the prompt according to the 3 Effort Levels:
     * - LOW (!enableContext): Clean Direct Translation + matched glossary (0 history)
     * - MEDIUM (enableContext && !deepContext): Canonical `instTrans` + matched glossary + last 3 Korean lines
     * - HIGH (enableContext && deepContext): Full `instTrans` + matched glossary + last 4 Korean lines + Speaker register cues
     */
    /**
     * Extracts conditional Korean grammar, perspective, and false-friend anchors (0ms)
     * ONLY for patterns actually present in the current dialogue batch.
     * Index-Translate-2B is trained to obey `1. 【硬性要求】 -> - Glossary:` mappings rather than chatty rules.
     */
    private fun buildContextualGrammarGlossary(dialogues: List<DialogueUnit>): Map<String, String> {
        // Collapse intra-bubble newlines to spaces so multi-line OCR bubbles (e.g. "고개...숙이고\n있어", "누가 멋대로\n움직여도 좋다 했어")
        // never fail regex matching due to '\n' line breaks!
        val batchText = dialogues.joinToString("\n") { it.sourceText.replace(Regex("""\s+"""), " ").trim() }
        val anchors = linkedMapOf<String, String>()
        for ((regex, mapping) in GRAMMAR_PERSPECTIVE_ANCHORS) {
            if (regex.containsMatchIn(batchText)) {
                anchors[mapping.first] = mapping.second
            }
        }
        return anchors
    }

    private fun buildEffortDialoguePrompt(dialogues: List<DialogueUnit>, context: ChapterContext): String = buildString {
        val matchedNames = context.glossary.filter { (k, _) ->
            dialogues.any { d -> d.sourceText.contains(k, ignoreCase = true) }
        }
        val grammarAnchors = buildContextualGrammarGlossary(dialogues)
        val combinedGlossary = matchedNames + grammarAnchors

        // ALL EFFORT LEVELS use Index-Translate-2B's native `instTrans` schema (`【源文】` + `1. 【硬性要求】`)
        // so even LOW effort stays in the model's fine-tuned instruction distribution.
        appendLine("【源文】")
        for ((index, d) in dialogues.withIndex()) {
            val clean = d.sourceText.replace("\r\n", " ").replace('\n', ' ').trim()
            appendLine("${index + 1}. $clean")
        }
        appendLine()
        appendLine("1. 【硬性要求】")
        appendLine("- Output strictly English translations numbered 1 to ${dialogues.size}.")
        if (combinedGlossary.isNotEmpty()) {
            val glossaryStr = combinedGlossary.entries.joinToString("; ") { "${it.key} = ${it.value}" }
            appendLine("- Glossary: $glossaryStr")
        }
        if (!enableContext) {
            return@buildString
        }

        appendLine()
        appendLine("2. 【注意】")
        appendLine("- Natural colloquial manhwa dialogue. Never invent honorifics not in the Korean source.")
        if (context.recentTranslations.isNotEmpty()) {
            // Inject 3rd-person subject gender continuity ONLY when the prior batch ended with an unfinished
            // clause (no terminal punctuation) or when the current batch contains 3rd-person Narration.
            // Never force "use she/her for omitted subjects" onto face-to-face speech bubbles where omitted subjects are "you" or "I"!
            val hasNarrationInBatch = dialogues.any { it.speakerHint?.contains("Narration", ignoreCase = true) == true }
            if (hasNarrationInBatch) {
                val recentKo = context.recentTranslations.joinToString(" ") { it.sourceText }
                val hasFemaleTopic = Regex("(엄마|어머니|누나|언니|여동생|여배우|걸그룹)").containsMatchIn(recentKo)
                val hasMaleTopic = Regex("(오빠|형|아빠|아버지|아들|남동생)").containsMatchIn(recentKo)
                when {
                    hasFemaleTopic && !hasMaleTopic -> appendLine("- For 3rd-person narration lines, the subject is female (she/her).")
                    hasMaleTopic && !hasFemaleTopic -> appendLine("- For 3rd-person narration lines, the subject is male (he/him).")
                }
            }
        }
        val registerNotes = dialogues.mapIndexedNotNull { idx, d ->
            val cue = formatRegisterCue(d.speakerHint, d.sourceText)
            if (cue != null) "Line ${idx + 1}: $cue" else null
        }
        if (registerNotes.isNotEmpty()) {
            appendLine("- Perspective: ${registerNotes.joinToString("; ")}")
        }
    }

    private fun buildRetryEnglishPrompt(
        dialogues: List<DialogueUnit>,
        glossary: Map<String, String> = emptyMap(),
        noHonorifics: Boolean = false,
    ): String = buildString {
        appendLine("【源文】")
        for ((index, d) in dialogues.withIndex()) {
            val clean = d.sourceText.replace("\r\n", " ").replace('\n', ' ').trim()
            appendLine("${index + 1}. $clean")
        }
        appendLine()
        appendLine("1. 【硬性要求】")
        appendLine("- Translate Korean dialogue to English. Output strictly numbered translations 1 to ${dialogues.size}.")
        appendLine("- Strictly English letters only. No Korean or Chinese characters.")
        if (noHonorifics) {
            appendLine("- Do NOT use any honorifics (no Oppa, Noona, Hyung, Unnie, Sunbae).")
        }
        val matchedGlossary = glossary.filter { (k, _) ->
            dialogues.any { d -> d.sourceText.contains(k, ignoreCase = true) }
        }
        if (matchedGlossary.isNotEmpty()) {
            appendLine("- Glossary: ${matchedGlossary.entries.joinToString("; ") { "${it.key} = ${it.value}" }}")
        }
        appendLine()
        append("2. 【注意】\n- Casual manhwa dialogue style.")
    }

    private fun parseNumberedDialogueResponse(output: String, expectedCount: Int): Map<String, String> {
        val cleanOutput = output
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)<think>.*$"), "")
            .replace("<think>", "")
            .replace("</think>", "")
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val deDuplicated = cleanOutput.replace(Regex("""^(1[:.]\s*)+"""), "1: ")
        val fullOutput = if (deDuplicated.startsWith("1:") || deDuplicated.startsWith("1.")) {
            deDuplicated
        } else {
            "1: $deDuplicated"
        }

        val result = mutableMapOf<String, String>()

        // 1. Check for bilingual format: 1: ... \n Translation: "..."
        val lines = fullOutput.lines()
        var currentId: String? = null
        for (line in lines) {
            val numMatch = Regex("""^\s*(\d+)[:\.\)]\s*(.+)$""").find(line.trim())
            if (numMatch != null) {
                currentId = numMatch.groupValues[1]
                val content = numMatch.groupValues[2].trim()
                if (content.isNotBlank() && isCleanEnglishTranslation(content)) {
                    result[currentId] = cleanTranslationText(content)
                }
            } else if (currentId != null) {
                val transMatch = Regex("""(?i)^\s*(?:translation|\*\*translation\*\*|english):\s*["']?([^"'\r\n]+)["']?""").find(line)
                if (transMatch != null) {
                    val english = transMatch.groupValues[1].trim()
                    if (isCleanEnglishTranslation(english)) {
                        result[currentId] = cleanTranslationText(english)
                    }
                }
            }
        }

        // 2. Standard single-line regex `^\s*(\d+)[.:]\s*(.+)$`
        val lineRegex = Regex("""(?m)^\s*(\d+)[:\.\)]\s*(.+)$""")
        for (match in lineRegex.findAll(fullOutput)) {
            val id = match.groupValues[1]
            if (result[id] != null && isCleanEnglishTranslation(result[id]!!)) continue
            val text = cleanTranslationText(match.groupValues[2])
            if (id.isNotBlank() && text.isNotBlank() && isCleanEnglishTranslation(text) && !isJsonGarbage(text)) {
                result[id] = text
            }
        }

        // 3. Secondary fallback: inline `"1": "text"`
        if (result.size < expectedCount) {
            val altRegex = Regex("""["']?(\d+)["']?\s*[:\-]\s*["']?([^"'\r\n]+)["']?""")
            for (match in altRegex.findAll(cleanOutput)) {
                val id = match.groupValues[1]
                if (result[id] != null && isCleanEnglishTranslation(result[id]!!)) continue
                val text = cleanTranslationText(match.groupValues[2])
                if (id.isNotBlank() && text.isNotBlank() && isCleanEnglishTranslation(text) && !isJsonGarbage(text)) {
                    result[id] = text
                }
            }
        }

        // 4. Tertiary fallback: line by line without leading numbers
        if (result.size < expectedCount) {
            val nonNumberedLines = cleanOutput.lines()
                .map { cleanTranslationText(it) }
                .filter { it.isNotBlank() && isCleanEnglishTranslation(it) && !isJsonBoilerplate(it) && !isJsonGarbage(it) }
            for ((idx, line) in nonNumberedLines.withIndex()) {
                val key = (idx + 1).toString()
                if (!result.containsKey(key)) {
                    result[key] = line
                }
            }
        }

        return result
    }

    private fun isJsonGarbage(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("\"id\"") || lower.contains("\"text\"") ||
            lower.contains("\"translations\"") || lower.startsWith("id\":") ||
            lower.startsWith("text\":") || lower.startsWith("id:") ||
            lower == "{" || lower == "}" || lower == "[" || lower == "]" ||
            lower == "\"\"" || lower == "''"
    }

    private fun isJsonBoilerplate(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("{") || trimmed.startsWith("}") ||
            trimmed.startsWith("[") || trimmed.startsWith("]") ||
            trimmed.contains("\"translations\"") || trimmed.contains("\"id\"") ||
            trimmed.contains("\"text\"")
    }

    private fun cleanTranslationText(output: String): String {
        var text = output
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace("<think>", "")
            .replace("</think>", "")
            .replace(Regex("<\\|[^>]+\\|>"), "")
            .replace(Regex("(?i)^sure,?\\s+(here('s|\\s+is)\\s+the\\s+translation:?|here\\s+you\\s+go:?)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)^here('s|\\s+is)\\s+the\\s+translation:?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)^\\[TARGET TO TRANSLATE\\]:?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)^\\[KOREAN DIALOGUE\\]:?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)^\\[DIALOGUE\\]:?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)^translation:?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\[+[^\]]+\]+\s*[:\-]?\s*"""), "")
            .replace(Regex("""(?i)^(?:(?:Left|Right|Center|Top|Bottom)\s+Bubble(?:\s*\|\s*[^:]+)?|Benefactive|Polite/Formal|Polite|Honorific|Casual/Senior|Casual|Line\s+\d+)\s*[:\-]\s*"""), "")
            .replace(Regex("""^\]+\s*"""), "")
            .replace(Regex("^\\d+[:\\.]\\s*"), "")
            .trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
            .trimStart('[', ']', ' ', '"', '\'')
            .trimEnd('"', '\'', ' ')
            .trim()

        text = text.replace(Regex("""\s*\([^)]*(?:AOE|NOTE|LIT|TRANSLAT|CONTEXT|REGISTER|HONORIFIC|SPEAKER)[^)]*\)""", RegexOption.IGNORE_CASE), "").trim()
        if (text.startsWith("(") && text.endsWith(")") && text.count { it == '(' } == 1) {
            text = text.substring(1, text.length - 1).trim()
        }

        val repetitionRegex = Regex("""(?i)(.{2,40}?)(?:[\s,\-]+)\1{2,}""")
        val repMatch = repetitionRegex.find(text)
        if (repMatch != null) {
            val phrase = repMatch.groupValues[1].trim().removeSuffix(",")
            text = text.substring(0, repMatch.range.first) + phrase
        }
        // Scanlation-standard kinship localization: never leave literal romanized "Yeo Dong-saeng" / "Nam Dong-saeng" / "Dong-saeng"
        text = text.replace(Regex("""(?i)\byeo[-\s]?dong[-\s]?saeng\b"""), "little sister")
            .replace(Regex("""(?i)\bnam[-\s]?dong[-\s]?saeng\b"""), "little brother")
            .replace(Regex("""(?i)\bdong[-\s]?saeng\b"""), "younger sibling")
        return text.trim()
    }

    override fun close() = runtime.close()

    @Serializable
    private data class TranslationInput(
        val series: String? = null,
        val context: List<String>? = null,
        val dialogue: List<DialogueLine>,
    )

    @Serializable
    private data class DialogueLine(
        val id: String,
        val text: String,
    )

    @Serializable
    private data class TranslationOutput(
        val translations: List<TranslatedLine> = emptyList(),
    )

    @Serializable
    private data class TranslatedLine(
        val id: String = "",
        val text: String = "",
    )

    /**
     * Whole-Page Batching: Keeps all bubbles on a page together (up to 8 lines per batch)
     * so 98% of pages require only 1 LLM call instead of 3-4 micro-batches.
     */
    private fun groupWholePageBatches(dialogues: List<DialogueUnit>): List<List<DialogueUnit>> {
        // Effort-tuned batch sizing:
        // - LOW (!enableContext): 12 lines per super-batch (~33% fewer LLM calls for maximum speed)
        // - MEDIUM (default): 8 lines per super-batch (balanced throughput and context)
        // - HIGH (deepContext): 6 lines per batch (sharper 2B per-line attention focus on dense pages)
        val maxBatchSize = when {
            !enableContext -> 12
            deepContext -> 6
            else -> 8
        }
        if (dialogues.size <= maxBatchSize) return listOf(dialogues)
        return dialogues.chunked(maxBatchSize)
    }

    companion object {
        const val PIPELINE_REVISION = 19

        /**
         * Generalized Korean grammar, pro-drop perspective, and lexical false-friend anchors.
         * Injected into `1. 【硬性要求】 -> - Glossary:` ONLY when the pattern appears in the current batch.
         */
        private val GRAMMAR_PERSPECTIVE_ANCHORS: List<Pair<Regex, Pair<String, String>>> = listOf(
            // 1. Pro-drop perspective & subject-complement idioms
            Regex("""내가\s*처음이야\s*\?""") to ("내가 처음이야?" to "Am I your first? / Is this your first time?"),
            Regex("""(?:누나|언니|형|오빠)\s*안에\s*넣은\s*거야""") to ("안에 넣은 거야?!" to "Did I just put it inside?!"),
            // 2. Rhetorical rebuttal & prohibition (`누가 ~좋다 했어`, `누가 ~하는 줄 알아`)
            Regex("""누가\s*.*?(?:좋다\s*했어|허락\s*했어|그랬어|움직이래)""") to ("누가 멋대로 움직여도 좋다 했어" to "Who said you could move around on your own?!"),
            Regex("""누가\s*.*?(?:하고\s*싶어서|좋아서).*?줄\s*알아""") to ("누가 너랑 하고 싶어서 하는 줄 알아?" to "You think I'm doing this because I want to do it with you?!"),
            // 3. Continuative imperatives (`-고 있어!` vs progressive declaration)
            Regex("""(?:고개|눈|입|몸|손|거기).*?(?:숙이고|감고|다물고|가만히)\s*있어""") to ("고개 숙이고 있어" to "Keep your head down! (command to listener)"),
            // 4. Derogatory status particle `~주제에`
            Regex("""[가-힣]+\s*주제에""") to ("주제에" to "for a mere nobody like / the nerve of"),
            // 5. Frequent manhwa lexical false friends & colloquial idioms in 2B models
            // IMPORTANT: Never include forbidden English words ("NOT ...", "NEVER ...") inside glossary values,
            // as negative tokens prime the 2B model's KV cache toward the forbidden word!
            Regex("""남매""") to ("남매" to "brother and sister / siblings"),
            Regex("""(?:너무|자꾸|몸이|손이)?\s*(?:떠는|떨고|떨리는|떨려)""") to ("떠는" to "trembling / shaking"),
            Regex("""벌리(?:지\s*마|지마|고|는|면)""") to ("벌리지마" to "don't spread it open"),
            Regex("""혼나(?:볼래|고\s*싶|겠|!|\.)|혼좀""") to ("혼나" to "gonna get punished / gonna get it"),
            Regex("""(?<![가-힣])얘(?:\s+|가|는|도|를|\?|\!)""") to ("얘" to "this guy / this kid"),
            Regex("""겹쳐\s*보(?:인다|여|였)""") to ("겹쳐 보인다" to "reminds me of / I see her in"),
            Regex("""놀랐잖아""") to ("놀랐잖아!" to "You startled me! / You scared me!"),
            Regex("""밖에까지\s*다\s*들리""") to ("밖에까지 다 들리겠어" to "They'll hear us all the way outside!"),
            Regex("""왜\s*나\s*같은\s*거랑""") to ("왜 나 같은 거랑" to "Why with someone like me...?"),
            Regex("""찐따(?:놈아)?""") to ("찐따놈아" to "you pathetic loser"),
            Regex("""(?<![가-힣])혹시[\s.?!…]*$""") to ("혹시" to "by any chance... / could it be..."),
            Regex("""이런\s*것\s*쯤은""") to ("이런 것쯤은" to "something like this is nothing / I can handle this"),
            // 5b. Universal Korean Grammar, Pro-Drop Perspective & Kinship Anchors (Zero Character Names)
            Regex("""꿈을\s*꾸고\s*있는\s*건가""") to ("꿈을 꾸고 있는 건가?" to "Am I dreaming? / Was I having a dream?"),
            Regex("""악역\s*전문\s*여배우""") to ("악역 전문 여배우" to "actress specializing in villain roles"),
            Regex("""유명한\s*국민\s*배우""") to ("유명한 국민 배우다" to "She's a famous national actress"),
            Regex("""스크린에서처럼\s*성격""") to ("실제로는 어떨까?" to "What is she really like in person?"),
            Regex("""마사지\s*해주는데.*잠들어서""") to ("마사지 해주는데, 중간에 잠들어서" to "I was giving a massage, and she fell asleep in the middle of it"),
            Regex("""자위(?:를)?\s*하고\s*있다""") to ("자위를 하고 있다" to "I was masturbating / I am masturbating"),
            Regex("""완벽한\s*아이돌의\s*이미지""") to ("완벽한 아이돌의 이미지를 바라고 있다" to "hoping for the image of a perfect idol"),
            Regex("""여동생""") to ("여동생" to "little sister / younger sister"),
            Regex("""남동생""") to ("남동생" to "little brother / younger brother"),
            Regex("""우리\s*동생이""") to ("우리 동생이" to "my younger sibling"),
            Regex("""동생까지""") to ("동생까지" to "even my younger sibling"),
            Regex("""떨쳐낼\s*수\s*없는\s*걸""") to ("떨쳐낼 수 없는 걸" to "I just can't shake this feeling off"),
            Regex("""(?m)^\.{0,3}\s*지\s*\?$""") to ("...지?" to "...right?"),
            Regex("""다른\s*사람들한텐\s*안\s*들켰고""") to ("다른 사람들한텐 안들켰고 ?" to "You didn't get caught by the others, right?"),
            Regex("""그만둬야\s*하는데""") to ("그만둬야 하는데" to "I should stop this"),
            Regex("""(?<![가-힣])난\s*도대체\s*뭘\s*하는\s*거야""") to ("난 도대체 뭘하는 거야" to "What on earth am I doing?"),
            Regex("""빠르게\s*눈치챈다""") to ("빠르게 눈치챈다" to "quickly notices"),
            Regex("""기특하니까""") to ("기특하니까" to "because you're such a good kid / so admirable"),
            Regex("""어리광쟁이""") to ("어리광쟁이" to "spoiled kid in the family"),
            Regex("""설득력""") to ("설득력" to "convincing"),
            Regex("""데리러\s*와준\s*거야""") to ("데리러 와준 거야?" to "You came to pick me up?"),
            Regex("""사오라고\s*한\s*건""") to ("사오라고 한건 여기 두고 갈게" to "I'll leave the things you asked me to buy right here"),
            Regex("""이번엔\s*똑바로\s*사왔지""") to ("이번엔 똑바로 사왔지?" to "This time you bought the right things, right?"),
            Regex("""뛰어난\s*실력의\s*소유자|운동선수라서""") to ("소유자로" to "talented athlete"),
            Regex("""오빠\s*기다렸잖아""") to ("오빠 기다렸잖아!" to "I was waiting for you, Oppa!"),
            Regex("""안\s*자고\s*기다렸다\s*했으니""") to ("안 자고 기다렸다 했으니" to "since they waited up without sleeping"),
            Regex("""들어가서\s*자지""") to ("들어가서 자지.." to "You should go inside your room and sleep..."),
            Regex("""(?<![가-힣])안\s*그래\s*\?""") to ("안 그래?" to "Isn't that right?"),
            Regex("""아프진\s*않아\s*\?""") to ("아프진 않아?" to "Does it hurt?"),
            Regex("""자는\s*거야\s*[가-힣]*\s*\?""") to ("자는 거야?" to "Are you asleep?"),
            Regex("""장난이\s*심하잖아""") to ("장난이 심하잖아" to "Your joke is going too far!"),
            Regex("""방금까지\s*입고\s*있던""") to ("자, 방금까지 입고 있던" to "Here, I was wearing this until just now"),
            Regex("""유독\s*이렇게\s*과민\s*반응하는\s*건""") to ("유독 이렇게 과민 반응하는건" to "The only reason for overreacting this strongly toward"),
            Regex("""다리\s*좀\s*주물러""") to ("나 다리 좀 주물러주라" to "Massage my legs for me please"),
            Regex("""받아들이지\s*말았어야""") to ("받아들이지 말았어야 했다" to "I never should have accepted it"),
            Regex("""또\s*언제\s*무슨\s*짓을\s*저지를지""") to ("또 언제 무슨 짓을 저지를지 모르니" to "You never know when you might pull something like this again"),
            Regex("""역시\s*도와주는\s*수밖에""") to ("역시 도와주는 수밖에 없잖아?" to "So you have no choice but to let me help, right?"),
            Regex("""어떤\s*애인지""") to ("어떤 애인지?" to "what kind of kid they really are?"),
            Regex("""(?m)^넣어줘[.?!~…]*$""") to ("넣어줘" to "Put it in me..."),
            Regex("""(?m)^라든지\s*\?$""") to ("라든지?" to "...Or something like that?"),
            Regex("""살결에\s*직접\s*비비고""") to ("살결에 직접 비비고 있는 거 같다" to "It feels like I'm rubbing directly against her skin"),
            Regex("""(?m)^\.{0,3}\s*상대방을$""") to ("..상대방을" to "...that you want to"),
            Regex("""임신시키고\s*싶다""") to ("임신시키고 싶다" to "impregnate your partner / get her pregnant"),
            Regex("""걸렸다면\s*지금쯤\s*난리가""") to ("걸렸다면 지금쯤 난리가 났겠지" to "if you'd been caught, there'd be a huge uproar by now"),
            Regex("""불러도\s*대답\s*안\s*하니까\s*들어온\s*건데""") to ("불러도 대답 안 하니까 들어온 건데" to "You didn't answer when I called, so I came in"),
            Regex("""순간\s*기뻐\s*보였던\s*것\s*같다""") to ("순간 기뻐 보였던 것 같다" to "she seemed happy for a moment"),
            Regex("""(?m)^말고\s*더\s*있어\s*\?$""") to ("말고 더 있어?" to "What else could it mean besides that?"),
            Regex("""그만하라고\s*몇\s*번을""") to ("그만하라고 몇 번을 말했는데" to "I told you to stop so many times!"),
            Regex("""아쉬울\s*뿐""") to ("아쉬울 뿐이죠" to "It's just a shame"),
            Regex("""다음\s*화에\s*계속""") to ("다음화에 계속" to "To be continued..."),
            Regex("""잘\s*하자\s*\?""") to ("잘하자?" to "Let's get along well, okay?"),
            Regex("""어떻게\s*설명할\s*거야""") to ("어떻게 설명할 거야?" to "How are you going to explain this?"),
            // 6. Murim / Cultivation & Hunter / System domain terminology (0ms conditional injection)
            Regex("""단전""") to ("단전" to "Dantian"),
            Regex("""내공""") to ("내공" to "Internal Energy"),
            Regex("""주화입마""") to ("주화입마" to "Qi Deviation"),
            Regex("""천마""") to ("천마" to "Heavenly Demon"),
            Regex("""마교""") to ("마교" to "Demonic Cult"),
            Regex("""무림""") to ("무림" to "Murim"),
            Regex("""각성자""") to ("각성자" to "Awakener"),
            Regex("""마석""") to ("마석" to "Mana Stone"),
            Regex("""상태창""") to ("상태창" to "Status Window"),
            Regex("""성좌""") to ("성좌" to "Constellation"),
        )
        const val INDEX_MODEL_FILE = "index_translate_2b.gguf"
        const val INDEX_MODEL_ID = "Index-Translate 2B Q4_K_M rev$PIPELINE_REVISION"
        // Concise static system prompt (saves prefill tokens on every batch; zero hardcoded series names)
        const val INDEX_SYSTEM_PROMPT =
            "Translate Korean manhwa dialogue into natural English. Output only numbered lines (1: ..., 2: ...)."
        const val DEFAULT_CONTEXT_SIZE = 2048
        const val DEFAULT_THREAD_COUNT = 4
        const val INDEX_TEMPERATURE = 0.0f

        // Backward-compatibility aliases
        const val QWEN_MODEL_FILE = INDEX_MODEL_FILE
        const val QWEN_MODEL_ID = INDEX_MODEL_ID
        const val QWEN_SYSTEM_PROMPT = INDEX_SYSTEM_PROMPT
        const val QWEN_TEMPERATURE = INDEX_TEMPERATURE

        private val CHOSEONG = charArrayOf(
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
        )

        fun getInitialConsonant(c: Char): Char? {
            if (c !in '\uAC00'..'\uD7A3') return null
            val choseongIndex = (c.code - 0xAC00) / (21 * 28)
            return CHOSEONG.getOrNull(choseongIndex)
        }

        data class StutterInfo(
            val normalizedText: String,
            val hadStutter: Boolean,
        )

        private val STANDALONE_ONE_SYLLABLE_WORDS = setOf(
            '안', '못', '왜', '뭐', '난', '넌', '내', '네', '날', '널', '다', '더', '잘', '좀', '이', '그', '저', '네', '응', '어', '아', '야',
        )

        fun getMedialVowelIndex(c: Char): Int {
            if (c !in '\uAC00'..'\uD7A3') return -1
            return ((c.code - 0xAC00) % (21 * 28)) / 28
        }

        fun getFinalConsonantIndex(c: Char): Int {
            if (c !in '\uAC00'..'\uD7A3') return -1
            return (c.code - 0xAC00) % 28
        }

        /**
         * Normalizes genuine Korean comic stutters (e.g. "버, 벌리지마", "괘, 괜찮아", "지, 진선후", "누... 누나")
         * Requires explicit stutter punctuation (comma, dot, ellipsis, hyphen—never a bare space!)
         * AND requires that the first syllable has no batchim (or is identical to the second syllable)
         * and shares BOTH initial consonant (choseong) AND medial vowel (jungseong) with the following word.
         */
        fun normalizeKoreanStutter(raw: String): StutterInfo {
            val trimmed = raw.trim()
            val match = Regex("""(?s)^([가-힣])\s*[,.\-…]+\s*([가-힣].*)$""").find(trimmed)
            if (match != null) {
                val firstChar = match.groupValues[1][0]
                val rest = match.groupValues[2]
                val secondChar = rest.firstOrNull { it in '\uAC00'..'\uD7A3' }
                if (secondChar != null) {
                    val sameSyllable = firstChar == secondChar
                    val openCutSyllable = getFinalConsonantIndex(firstChar) == 0 &&
                        firstChar !in STANDALONE_ONE_SYLLABLE_WORDS &&
                        getInitialConsonant(firstChar) == getInitialConsonant(secondChar) &&
                        getMedialVowelIndex(firstChar) == getMedialVowelIndex(secondChar)
                    if (sameSyllable || openCutSyllable) {
                        return StutterInfo(normalizedText = rest, hadStutter = true)
                    }
                }
            }
            return StutterInfo(normalizedText = raw, hadStutter = false)
        }

        private val PSEUDO_DOMAIN_OR_CAMEL_NOISE = Regex(
            """(?:[A-Za-z0-9]{1,10}[.,][A-Za-z]{2,4}\b|[a-z][A-Z][A-Za-z0-9]*|[A-Z][a-z]+[A-Z][A-Za-z0-9]*|[0-9][A-Za-z]{2,})"""
        )

        fun stripInlineWatermarkGarbage(raw: String): String {
            var s = raw.trim()
            // If line contains a pseudo-domain (e.g. "aX.fOM", "8Fx.Co") and has no valid Korean sentence punctuation, drop it completely
            if (PSEUDO_DOMAIN_OR_CAMEL_NOISE.containsMatchIn(s) && !Regex("""[?!~…]|\.{2,}|(?:다|요|어|아|지|냐|니|까|네|라|자)\.""").containsMatchIn(s)) {
                return ""
            }
            // Strip leading mixed alphanumeric/domain tokens attached before Korean dialogue (e.g. "서신경껴있FB.CON 이...이런")
            s = s.replace(Regex("""^(?:[가-힣]{0,6}[A-Za-z0-9_.\-/]{3,}[가-힣A-Za-z0-9_.\-/]*)\s+(?=[가-힣])"""), "")
            // Strip trailing or glued pseudo-domain/alphanumeric noise at the end of a Korean line (e.g. "...나와aX.fOM")
            s = s.replace(Regex("""[A-Za-z0-9_]{1,10}[.,][A-Za-z]{2,4}\s*$"""), "")
            s = s.replace(Regex("""\s+(?:[가-힣]{0,4}[A-Za-z0-9_.\-/]{4,}[가-힣A-Za-z0-9_.\-/]*)$"""), "")
            return s.trim()
        }

        fun applyScanlationStutter(english: String): String {
            val trimmed = english.trim()
            if (trimmed.isEmpty()) return english
            val firstWordMatch = Regex("""^([a-zA-Z])([a-zA-Z]*)(.*)$""").find(trimmed)
            if (firstWordMatch != null) {
                val firstLetter = firstWordMatch.groupValues[1]
                val restOfWord = firstWordMatch.groupValues[2]
                val tail = firstWordMatch.groupValues[3]
                if (restOfWord.startsWith("-")) return english
                return "$firstLetter-${firstLetter.uppercase()}$restOfWord$tail"
            }
            return english
        }

        val HONORIFIC_PROVENANCE_MAP = mapOf(
            "oppa" to listOf("오빠", "오라버니", "오라버님"),
            "noona" to listOf("누나", "누님", "누날"),
            "hyung" to listOf("형", "형님"),
            "unnie" to listOf("언니", "언닐"),
            "sunbae" to listOf("선배", "선배님"),
            "hoobae" to listOf("후배", "후배님"),
        )

        fun hasUnprovenancedHonorific(korean: String, english: String): Boolean {
            for ((enTerm, krVariants) in HONORIFIC_PROVENANCE_MAP) {
                val regex = Regex("""\b${Regex.escape(enTerm)}\b""", RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(english)) {
                    if (krVariants.none { korean.contains(it) }) {
                        return true
                    }
                }
            }
            return false
        }

        fun stripUnprovenancedHonorifics(korean: String, english: String): String {
            var result = english
            for ((enTerm, krVariants) in HONORIFIC_PROVENANCE_MAP) {
                if (krVariants.none { korean.contains(it) }) {
                    result = result.replace(Regex("""(?i)^\s*${Regex.escape(enTerm)}\s*[,!.\-—–]?\s*"""), "")
                        .replace(Regex("""(?i)\s*[,!.\-—–]?\s*${Regex.escape(enTerm)}\s*[!.\-—–]?$"""), "")
                        .replace(Regex("""(?i)\b${Regex.escape(enTerm)}\b"""), "")
                        .trim()
                        .replace(Regex("""\s{2,}"""), " ")
                        .replaceFirstChar { it.uppercase() }
                }
            }
            return result
        }

        fun isGarbledMixedScript(text: String): Boolean {
            val clean = text.replace(" ", "")
            if (clean.length < 3) return false
            val hangulCount = clean.count { it in '\uAC00'..'\uD7A3' }
            val latinDigitCount = clean.count { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') }
            if (hangulCount in 1..8 && latinDigitCount >= 4 && (hangulCount.toFloat() / clean.length) < 0.45f) {
                return true
            }
            // Catch OCR-merged watermark overlays like "자꾸 신율이나와aX.fOM" (pseudo-domain or camelCase noise glued to Hangul)
            if (PSEUDO_DOMAIN_OR_CAMEL_NOISE.containsMatchIn(text) && hangulCount in 1..10) {
                return true
            }
            return false
        }
    }
}