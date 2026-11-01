package mihon.feature.translation.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoreanNameGlossaryExtractorTest {

    @Test
    fun `extracts 3-syllable full name with weak particles when repeated across chapter`() {
        val chapterTexts = listOf(
            "선후 넌 움직이지마",
            "진선후 너 뭔데..",
            "엄마랑 미소 위해서 억지로 하는 거니깐..!",
            "지, 진선후 주제에.",
        )

        val glossary = KoreanNameGlossaryExtractor.extractGlossary(chapterTexts)

        // 진선후 appears on 2 lines (너 and 주제에), so it meets the >= 2 occurrence threshold
        assertEquals("Jin Seon-hoo", glossary["진선후"])
        // And its given name part 선후 is automatically authorized
        assertEquals("Seon-hoo", glossary["선후"])
        // 미소 in "엄마랑 미소 위해서" is extracted via kinship conjunction rule
        assertEquals("Mi-so", glossary["미소"])
    }

    @Test
    fun `extracts 2-syllable given name with honorific addresses`() {
        val chapterTexts = listOf(
            "우리 집의 장녀 소영 누나",
            "소영 누나, 어디 가?",
        )

        val glossary = KoreanNameGlossaryExtractor.extractGlossary(chapterTexts)

        assertEquals("So-yeong", glossary["소영"])
    }

    @Test
    fun `does not extract common nouns or blacklisted words`() {
        val chapterTexts = listOf(
            "사람이 어떻게 그래?",
            "기분이 별로야.",
            "바보 주제에 어디서 까불어!",
            "일이 너무 많아서 힘들어.",
        )

        val glossary = KoreanNameGlossaryExtractor.extractGlossary(chapterTexts)

        assertTrue(glossary.isEmpty(), "Expected no false positive names, but found: $glossary")
    }

    @Test
    fun `unprovenanced honorific detection catches ungrounded terms`() {
        // Korean source has NO honorific term, English has "Oppa"
        assertTrue(LocalLlmDialogueTranslator.hasUnprovenancedHonorific("선후 넌 움직이지마", "Oppa, don't move."))
        // Korean source has NO honorific term, English has "Hyung"
        assertTrue(LocalLlmDialogueTranslator.hasUnprovenancedHonorific("우리 엄마 임신혜는", "Mom, it's Hyung-in."))
        // Korean source DOES have matching honorific term "누나", English has "Noona"
        assertFalse(LocalLlmDialogueTranslator.hasUnprovenancedHonorific("소영 누나 이리 와", "Soyeong Noona, come here."))
        // Korean source has "오빠", English has "Oppa"
        assertFalse(LocalLlmDialogueTranslator.hasUnprovenancedHonorific("오빠 어디 가?", "Oppa, where are you going?"))
    }

    @Test
    fun `stripUnprovenancedHonorifics removes ungrounded honorific cleanly`() {
        val cleaned = LocalLlmDialogueTranslator.stripUnprovenancedHonorifics("선후 넌 움직이지마", "Oppa, don't move.")
        assertEquals("Don't move.", cleaned)
    }

    @Test
    fun `garbled mixed script filter identifies corrupted watermark strings`() {
        assertTrue(LocalLlmDialogueTranslator.isGarbledMixedScript("자꾸 신ON타와8FX.gOM"))
        assertTrue(LocalLlmDialogueTranslator.isGarbledMixedScript("자꾸 신율이나와aX.fOM"))
        assertFalse(LocalLlmDialogueTranslator.isGarbledMixedScript("선후 넌 움직이지마"))
        assertFalse(LocalLlmDialogueTranslator.isGarbledMixedScript("Hello World!"))
    }

    @Test
    fun `stutter normalization never deletes standalone 1-syllable Korean words followed by space`() {
        val r1 = LocalLlmDialogueTranslator.normalizeKoreanStutter("안 아파?")
        assertFalse(r1.hadStutter)
        assertEquals("안 아파?", r1.normalizedText)

        val r2 = LocalLlmDialogueTranslator.normalizeKoreanStutter("못 믿어")
        assertFalse(r2.hadStutter)
        assertEquals("못 믿어", r2.normalizedText)

        val r3 = LocalLlmDialogueTranslator.normalizeKoreanStutter("왜 이래?")
        assertFalse(r3.hadStutter)
        assertEquals("왜 이래?", r3.normalizedText)

        val r4 = LocalLlmDialogueTranslator.normalizeKoreanStutter("넌 누구야")
        assertFalse(r4.hadStutter)
        assertEquals("넌 누구야", r4.normalizedText)

        val r5 = LocalLlmDialogueTranslator.normalizeKoreanStutter("내 눈")
        assertFalse(r5.hadStutter)
        assertEquals("내 눈", r5.normalizedText)
    }

    @Test
    fun `stutter normalization correctly normalizes true punctuated comic stutters`() {
        val r1 = LocalLlmDialogueTranslator.normalizeKoreanStutter("버, 벌리지마")
        assertTrue(r1.hadStutter)
        assertEquals("벌리지마", r1.normalizedText)

        val r2 = LocalLlmDialogueTranslator.normalizeKoreanStutter("괘, 괜찮아")
        assertTrue(r2.hadStutter)
        assertEquals("괜찮아", r2.normalizedText)

        val r3 = LocalLlmDialogueTranslator.normalizeKoreanStutter("지, 진선후")
        assertTrue(r3.hadStutter)
        assertEquals("진선후", r3.normalizedText)
    }

    @Test
    fun `stripInlineWatermarkGarbage removes leading pirate scan prefixes`() {
        val clean = LocalLlmDialogueTranslator.stripInlineWatermarkGarbage("서신경껴있FB.CON 이...이런 것 쯤은...")
        assertEquals("이...이런 것 쯤은...", clean)

        val dropped = LocalLlmDialogueTranslator.stripInlineWatermarkGarbage("자꾸 신율이나와aX.fOM")
        assertEquals("", dropped)
    }

    @Test
    fun `translateSfx resolves compound vocal moans`() {
        val sfx = ManhwaSfxDictionary.translateSfx("아 흑 읏")
        assertEquals("Ah... Sob... Ngh...", sfx)
    }

    @Test
    fun `areTextsSimilar correctly matches OCR double observations using Jamo decomposition`() {
        val s1 = "결국 넣어버렸다"
        val s2 = "검구 년어뀌려다"
        assertTrue(MlKitComicDetectorAndOcr.areTextsSimilar(s1, s2))

        // Distinct connected double-bubbles that share a common 2-3 syllable root ("생각하" on Ch 2 P76)
        // must NEVER be treated as duplicate OCR observations!
        assertFalse(MlKitComicDetectorAndOcr.areTextsSimilar("네가 생각하는\\n그런 거 아니야", "이상한 생각\\n하지마"))
        assertEquals("이건..", MlKitComicDetectorAndOcr.normalizeOcrText("1MANGA그 이건.."))
        assertEquals("다 봤으니까 똑바로 말해.", MlKitComicDetectorAndOcr.normalizeOcrText("다 봤으니까 똑바로 말해. E왕국는"))
        assertEquals("그런 건 아닌데..", MlKitComicDetectorAndOcr.normalizeOcrText("입0트 그런 건 아닌데.."))
        assertEquals("아쉬울 뿐이죠.", MlKitComicDetectorAndOcr.normalizeOcrText("아쉬울 뿐이죠. E아크Em하"))
        assertEquals("부드러운 피부 감촉과 포근한 살 내음", MlKitComicDetectorAndOcr.normalizeOcrText("부드러운 피부 감촉과 포근한 실 내음"))
        assertTrue(MlKitComicDetectorAndOcr.isHangulLineContained("내일 바쁘잖아요.\n그리고 그 말만 벌써\n세 번째에요.", "그리고 그 말만 벌써"))
        assertTrue(MlKitComicDetectorAndOcr.isHangulLineContained("그 그러니까 그만하라고\n몇 번을 말했는데!\n..화났어 ?", "몇번을 말했는데!"))
    }

    @Test
    fun `normalizeOcrText strips stray Latin noise and heals collapsed Hangul boundaries`() {
        val healed1 = MlKitComicDetectorAndOcr.normalizeOcrText("왜더 커지는 D, 건데?!")
        assertEquals("왜 더 커지는 건데?!", healed1)

        val healed2 = MlKitComicDetectorAndOcr.normalizeOcrText("잠깐민 기다려")
        assertEquals("잠깐만 기다려", healed2)

        val healed3 = MlKitComicDetectorAndOcr.normalizeOcrText("윗!")
        assertEquals("앗!", healed3)

        val healed4 = MlKitComicDetectorAndOcr.normalizeOcrText("왜나 같은 거랑")
        assertEquals("왜 나 같은 거랑", healed4)

        val healed5 = MlKitComicDetectorAndOcr.normalizeOcrText("다들리겠어")
        assertEquals("다 들리겠어", healed5)

        // Page 54 watermark-overlapped dialogue rescue ("자꾸 신율이나와aX.fOM" -> not dropped by isWatermark, healed to "자꾸 신음이 나와")
        assertFalse(MlKitComicDetectorAndOcr.isWatermark("자꾸 신율이나와aX.fOM"))
        val healedP54 = MlKitComicDetectorAndOcr.normalizeOcrText("자꾸 신율이나와aX.fOM")
        assertEquals("자꾸 신음이 나와", healedP54)

        // Page 40 groan & Page 12/44/58 vocalized verb drawl healing
        assertEquals("으으윽. 자, 잠깐만...", MlKitComicDetectorAndOcr.normalizeOcrText("으그곳. 자, 잠깐민..."))
        assertEquals("고개...숙이고 있어...", MlKitComicDetectorAndOcr.normalizeOcrText("고개...숙이고 있어엇..."))
    }

    @Test
    fun `translateSfx resolves startled screams and exclaimed hollow bubble moans`() {
        assertEquals("Ngh!!", ManhwaSfxDictionary.translateSfx("흐읏!!"))
        assertEquals("Kyaa?!", ManhwaSfxDictionary.translateSfx("꺄, 꺄악? !"))
        assertEquals("Hngh...!", ManhwaSfxDictionary.translateSfx("흐으앗..!"))
        assertEquals("Ugh?!", ManhwaSfxDictionary.translateSfx("으윽?!"))
    }

    @Test
    fun `isWatermark drops Korean piracy banners while preserving merged dialogue`() {
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("가장대문제공사이트\n웹문왕국토끼325|"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("가장마른웹문제공사이트\n웹툰양국 HTTPSUA"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("가른협문공이트\n웹든국뉴로끼325\n(HTTPS:"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("가장른 업문공사이E ("))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("입툰제공사이타\n웹튜국뉴토E25]\nHTTPS"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("가장은 업분공이트 TTT"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("토뉴토깨325"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("8E는공이트"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("8E인문금이트"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("원튼왕국끼R25"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("웹투왕국뉴토까d"))

        // Merged dialogue + watermark should NOT be dropped by isWatermark, and normalizeOcrText should strip the watermark cleanly
        val merged1 = "가장른 업문공사이트 ( 무심하긴. 다리나 좀주물러줘!"
        assertFalse(MlKitComicDetectorAndOcr.isWatermark(merged1))
        assertEquals("무심하긴. 다리나 좀주물러줘!", MlKitComicDetectorAndOcr.normalizeOcrText(merged1))

        val merged2 = "옛날부터 집에만 있던 나였기에 가장른 업문공사이E"
        assertFalse(MlKitComicDetectorAndOcr.isWatermark(merged2))
        assertEquals("옛날부터 집에만 있던 나였기에", MlKitComicDetectorAndOcr.normalizeOcrText(merged2))

        val merged3 = "다 봤으니까 똑바로 말해. 가공A이E"
        assertFalse(MlKitComicDetectorAndOcr.isWatermark(merged3))
        assertEquals("다 봤으니까 똑바로 말해.", MlKitComicDetectorAndOcr.normalizeOcrText(merged3))
    }

    @Test
    fun `normalizeOcrText heals Ch1 and Ch2 benchmark OCR typos`() {
        assertEquals("덥잖아~ 싫어~", MlKitComicDetectorAndOcr.normalizeOcrText("덥장아~ 싫어~"))
        assertEquals("자고 있다고 멋대로 만지려 드는 게 말이 돼?", MlKitComicDetectorAndOcr.normalizeOcrText("자고 있다.고 멋대로 만지려 드는 게 말이 돼?"))
        assertEquals("받아들이지 말았어야 했다.", MlKitComicDetectorAndOcr.normalizeOcrText("받아들이지 달았어야 했다."))
        // Also heals standalone "달았어야 했다." when OCR splits the sentence across two blocks before merging
        assertEquals("말았어야 했다.", MlKitComicDetectorAndOcr.normalizeOcrText("달았어야 했다."))
        assertEquals("오빠~ 나 다리 좀 주물러주라~", MlKitComicDetectorAndOcr.normalizeOcrText("오빠~ 나다리 좀 주물러주리~"))
        assertEquals("당분간 내가 상대해 줄테니까.", MlKitComicDetectorAndOcr.normalizeOcrText("당분간 내기가 상대해 줄테니까."))
        // rev15 elongated scream & glyph healing ("그마안" -> "그만", "멈춰봐아" -> "멈춰봐", "그만 하리" -> "그만하라고", "뉴도" -> "양도", ".어때?" -> "어때?")
        assertEquals("그, 그만...! 멈춰봐..!", MlKitComicDetectorAndOcr.normalizeOcrText("그, 그마안...! 멈춰봐아..!"))
        assertEquals("그만!", LocalLlmDialogueTranslator.normalizeKoreanStutter("그, 그만!").normalizedText)
        assertEquals("그 그러니까 그만하라고 몇 번을 말했는데!", MlKitComicDetectorAndOcr.normalizeOcrText("그 그러니까 그만 하리 몇 번을 말했는데!"))
        assertEquals("양도 이렇게 잔뜩 나올 정도면", MlKitComicDetectorAndOcr.normalizeOcrText("뉴도 이렇게 잔뜩 나올 정도면"))
        assertEquals("어때?", MlKitComicDetectorAndOcr.normalizeOcrText(".어때?"))
        assertTrue(MlKitComicDetectorAndOcr.isWatermark("\\E문H공N0트"))
        // rev16 multi-line stutter (?s), watermark-dialogue scanline collision, and OCR glyph healings
        assertEquals("괜찮으려나?\n아프진 않아?", LocalLlmDialogueTranslator.normalizeKoreanStutter("괘, 괜찮으려나?\n아프진 않아?").normalizedText)
        assertEquals("그만...!\n멈춰봐..!", LocalLlmDialogueTranslator.normalizeKoreanStutter("그, 그만...!\n멈춰봐..!").normalizedText)
        assertFalse(MlKitComicDetectorAndOcr.isWatermark("있어. MANGA18FX.COM"))
        assertEquals("있어.", MlKitComicDetectorAndOcr.normalizeOcrText("있어. MANGA18FX.COM"))
        assertEquals("이상한 생각 하지마", MlKitComicDetectorAndOcr.normalizeOcrText("8FX.COM\n이상한 생각 하지마"))
        assertEquals("뭔가 오늘 미소 기분이", MlKitComicDetectorAndOcr.normalizeOcrText("건가 오늘 미소 기분이"))
        assertEquals("아직 자고 있네. 그냥.", MlKitComicDetectorAndOcr.normalizeOcrText("아직 자고 있네. 그날."))
        assertEquals("아픈 데가 사라졌어요!", MlKitComicDetectorAndOcr.normalizeOcrText("나쁜 데가 사라졌어요!"))
        assertEquals("...지?", MlKitComicDetectorAndOcr.normalizeOcrText(".지?"))
    }

    @Test
    fun `TranslationModelSelector maps effort levels to optimized KV cache sizes`() {
        val low = TranslationModelSelector.select(mihon.feature.translation.TranslationEffortLevel.LOW)
        val mid = TranslationModelSelector.select(mihon.feature.translation.TranslationEffortLevel.MEDIUM)
        val high = TranslationModelSelector.select(mihon.feature.translation.TranslationEffortLevel.HIGH)

        assertEquals(1536, low.contextSize)
        assertEquals(2048, mid.contextSize)
        assertEquals(3072, high.contextSize)
    }

    @Test
    fun `extractGlossary never extracts kinship nouns like 여동생 or 남동생 as character names`() {
        val chapterTexts = listOf(
            "그런 여동생이니 더욱 민감하게 생각할 수 밖에 없겠지.",
            "그 후로 난 몇 주 동안 여동생을 피하고 있다.",
            "우리 남동생이 최고야.",
            "남동생은 집에 있어.",
        )
        val glossary = KoreanNameGlossaryExtractor.extractGlossary(chapterTexts)
        assertFalse(glossary.containsKey("여동생"), "여동생 must not be extracted as Yeo Dong-saeng")
        assertFalse(glossary.containsKey("남동생"), "남동생 must not be extracted as Nam Dong-saeng")
        assertFalse(glossary.containsKey("동생"), "동생 must not be extracted as Dong-saeng")
    }

    @Test
    fun `normalizeOcrText heals rev17 CBZ visual audit defects`() {
        assertEquals("...나 왔어.", MlKitComicDetectorAndOcr.normalizeOcrText("...Lt ."))
        assertEquals("떨쳐낼 수 없는 걸", MlKitComicDetectorAndOcr.normalizeOcrText("떨쳐넬 수 없는 결"))
        assertEquals("대중들은 미소에게 완벽한 아이돌의 이미지를 바라고 있다", MlKitComicDetectorAndOcr.normalizeOcrText("대중들은 미소에게 완벽한 아이돌의 이기르 바고 이다"))
        assertEquals("주변 사람들의 작은 변화도 빠르게 눈치챈다.", MlKitComicDetectorAndOcr.normalizeOcrText("주변 사람들의 작은 변화도 르게 눈치챈다."))
    }
}
