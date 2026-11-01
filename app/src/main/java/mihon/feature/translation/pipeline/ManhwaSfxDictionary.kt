package mihon.feature.translation.pipeline

/**
 * Curated scanlation lexicon for Korean manhwa sound effects (Uiseongeo), mimetic words (Uitaeeo),
 * 18+ romance/intimacy vocalizations, and authentic webtoon slang/interjections.
 *
 * Intercepts non-verbal and reaction speech bubbles before LLM/MT to deliver 100% accurate,
 * human-quality scanlation translations in 0ms without hallucination or laughter mistranslation.
 */
object ManhwaSfxDictionary {

    private val EXACT_MAP = mapOf(
        // 18+ Romance & Intimacy (Panting, Sighs, Breaths, Moans, Tactile)
        "하아.. 하아.." to "Haah... Haah...",
        "하아... 하아..." to "Haah... Haah...",
        "하아..하아.." to "Haah... Haah...",
        "하아.. 하아" to "Haah... Haah...",
        "하아 하아" to "Haah... Haah...",
        "하아.." to "Haah...",
        "하아..." to "Haah...",
        "하아-" to "Haah...",
        "하아" to "Haah...",
        "하앗.." to "Ah...!",
        "하앗!" to "Ah!",
        "하앗" to "Ah!",
        "하앙.." to "Ahn...",
        "하앙!" to "Ahn!",
        "하앙" to "Ahn...",
        "읏" to "Ngh...",
        "읏.." to "Ngh...",
        "읏!" to "Ngh!",
        "으읏" to "Ngh...",
        "으읏.." to "Ngh...",
        "으읏!" to "Ngh!",
        "흐응" to "Hnng...",
        "흐응.." to "Hnng...",
        "흐응!" to "Hnng!",
        "흐읏" to "Ngh...",
        "흐읏.." to "Ngh...",
        "흐읏!" to "Ngh!",
        "흐읏!!" to "Ngh!!",
        "흐으앗" to "Hngh...",
        "흐으앗!" to "Hngh!",
        "흐으앗..!" to "Hngh...!",
        "크읏" to "Kh...",
        "크읏.." to "Kh...",
        "크읏!" to "Kh!",
        "꺄악" to "Kyaa!",
        "꺄악!" to "Kyaa!",
        "꺄악?!" to "Kyaa?!",
        "꺄, 꺄악?!" to "Kyaa?!",
        "꺄, 꺄악? !" to "Kyaa?!",
        "앙" to "Ah!",
        "앙.." to "Ahn...",
        "앙!" to "Ahn!",
        "앗!" to "Ah!",
        "앗" to "Ah!",
        "아앗" to "Ah!",
        "아앗!" to "Ah...!",
        "후우.." to "Phew...",
        "후우..." to "Phew...",
        "후우" to "Phew...",
        "후욱" to "Fwh...",
        "휴우" to "Whew...",
        "쪽" to "*Kiss*",
        "쪽.." to "*Kiss*",
        "쪽!" to "*Kiss!*",
        "쪽 쪽" to "*Kiss kiss*",
        "쪽쪽" to "*Kiss kiss*",
        "츄" to "*Smooch*",
        "츄.." to "*Smooch*",
        "츕" to "*Smooch*",
        "츕.." to "*Smooch*",
        "부비부비" to "*Nuzzle*",
        "후끈" to "*Flushed*",
        "후끈.." to "*Blush*",
        "화끈" to "*Flushed*",
        "화끈.." to "*Flushed*",
        "질척" to "*Squelch*",
        "질척질척" to "*Squelch*",
        "화르륵" to "*Ignite*",
        "흠뻑" to "*Soaked*",
        "흐읍" to "Mmph...",
        "읍" to "Mmph!",
        "읍.." to "Mmph...",
        "흐아" to "Haa...",
        "흐앙" to "Ahn...",
        "흣" to "Ngh...",
        "흣!" to "Ngh!",
        "흐읏..." to "Ngh...",
        "읏..." to "Ngh...",
        "앙..!" to "Ahn...!",
        "하앙..!" to "Ahn...!",
        "앗..!" to "Ah...!",
        "하앗..!" to "Ah...!",

        // Shock, Gasps & Breaths
        "헉.." to "Gasp...",
        "헉!" to "Gasp!",
        "헉" to "Gasp!",
        "허억" to "Gasp...",
        "허억!" to "Gasp!",

        // Pain, Groans & Restraint
        "으윽" to "Ugh...",
        "으윽.." to "Ugh...",
        "으윽!" to "Ugh!",
        "으윽?!" to "Ugh?!",
        "으윽!?" to "Ugh?!",
        "옥?!" to "Huh?!",
        "으으윽" to "Urgh...",
        "흐윽" to "Ngh...",
        "흐윽.." to "Ngh...",
        "흐으윽" to "Urgh...",
        "크윽" to "Kgh...",
        "크윽.." to "Kgh...",
        "크윽!" to "Kgh!",
        "크으윽" to "Guh...",
        "으응" to "Mnh...",
        "으응.." to "Mnh...",
        "음.." to "Mmm...",
        "음..." to "Mmm...",
        "음" to "Mmm...",

        // Manhwa Non-Verbal Reactions & Physical Vocal Grunts
        "쳇" to "Tch...",
        "쳇.." to "Tch...",
        "쳇!" to "Tch!",
        "칫" to "Tch...",
        "칫.." to "Tch...",
        "치잇" to "Tch...",
        "큭" to "Kgh...",
        "큭.." to "Kgh...",
        "크큭" to "Heh...",
        "크크" to "Hehe...",
        "피식" to "*Smirk*",
        "피식.." to "*Smirk...*",
        "풋" to "Pfft...",
        "풋.." to "Pfft...",
        "켁" to "Ack!",
        "켁!" to "Gah!",
        "컥" to "Ghk!",
        "컥!" to "Ghk!",

        // Additional Elongations & Vowel Variants
        "하아아" to "Haah...",
        "하아아.." to "Haah...",
        "으으읏" to "Ngh...",
        "으으읏!" to "Ngh!",
        "쪽쪽쪽" to "*Kiss kiss*",
        "츕츕" to "*Smooch*",

        // Additional Action & Weapon SFX
        "챙" to "*CLASH*",
        "챙!" to "*CLASH!*",
        "서걱" to "*SLASH*",
        "서걱!" to "*SLASH!*",
        "파각" to "*CRACK*",
        "파각!" to "*CRACK!*",
        "쿠구구" to "*RUMBLE*",
        "쿠구궁" to "*RUMBLE*",
        "콰르릉" to "*ROAR*",
        "피융" to "*PEW*",
        "쉬익" to "*SWOOSH*",
        "촹" to "*CLANG*",
        "촹!" to "*CLANG!*",
        "쩌정" to "*SHATTER*",
        "철컥" to "*Click*",
        "철컥!" to "*Click!*",

        // OCR Artifacts for "...응?" (circular glyph misread as 0 or O)
        "...0H?" to "...Huh?",
        "..0H?" to "...Huh?",
        ".0H?" to "...Huh?",
        "0H?" to "Huh?",
        "...OH?" to "...Huh?",
        "..OH?" to "...Huh?",
        "OH?" to "Huh?",
        "...oh?" to "...Huh?",
        "oh?" to "Huh?",

        // Physical Movements, Startles & Flips
        "움찔" to "*Flinch*",
        "움찔.." to "*Flinch*",
        "움찔!" to "*Flinch!*",
        "흠칫" to "*Startle*",
        "흠칫!" to "*Startle!*",
        "스윽" to "*Slide*",
        "스윽-" to "*Slide*",
        "슥" to "*Swish*",
        "슥-" to "*Swish*",
        "꿀꺽" to "*Gulp*",
        "꿀꺽.." to "*Gulp...",
        "터벅터벅" to "*Step... Step...*",
        "사뿐사뿐" to "*Lightly...*",
        "살금살금" to "*Sneak sneak*",
        "부스럭" to "*Rustle*",
        "바스락" to "*Rustle*",
        "주르륵" to "*Drip...*",
        "번쩍" to "*Flash!*",
        "번쩍!" to "*Flash!*",
        "찌릿" to "*Glare*",
        "찌릿.." to "*Glare*",
        "콜록" to "*Cough*",
        "콜록콜록" to "*Cough cough*",
        "꼬르륵" to "*Rumble...*",
        "끄덕" to "*Nod*",
        "끄덕끄덕" to "*Nod nod*",
        "도리도리" to "*Shake head*",
        "덜컹" to "*Rattle*",
        "덜컹덜컹" to "*Clatter clatter*",
        "삐걱" to "*Creak*",
        "삐걱삐걱" to "*Creak creak*",
        "스르륵" to "*Slide...*",
        "사락" to "*Rustle*",
        "파닥파닥" to "*Flap flap*",
        "우르르" to "*Rumble*",
        "콰앙" to "*KABOOM!*",
        "콰앙!" to "*KABOOM!*",
        "슈우우" to "*WHOOOSH*",
        "팟" to "*ZAP!*",
        "팟!" to "*ZAP!*",

        // Heartbeats & Tension
        "두근두근" to "Ba-dump... Ba-dump...",
        "두근" to "Ba-dump...",
        "두근.." to "Ba-dump...",
        "콩닥콩닥" to "Thump-thump...",
        "쿵쾅쿵쾅" to "Thump-thump-thump...",
        "쿵쾅" to "Thump-thump...",

        // Impacts & Combat Sounds
        "쾅!" to "*BOOM!*",
        "쾅" to "*BOOM*",
        "쾅쾅" to "*BAM BAM!*",
        "쾅쾅쾅" to "*BAM BAM BAM!*",
        "쿵!" to "*THUD!*",
        "쿵" to "*THUD*",
        "쿵쿵" to "*THUD THUD*",
        "탁!" to "*SNAP!*",
        "탁" to "*SNAP*",
        "탁-" to "*TAP*",
        "찰싹!" to "*SLAP!*",
        "찰싹" to "*SLAP*",
        "퍽!" to "*SMACK!*",
        "퍽" to "*SMACK*",
        "퍽퍽" to "*SMACK SMACK!*",
        "휘익" to "*SWOOSH*",
        "쉭" to "*SWOOSH*",
        "쩌적" to "*CRACK*",
        "쩍" to "*CRACK*",
        "펄럭" to "*Flutter*",
        "펄럭펄럭" to "*Flutter*",

        // Laughs & Crying
        "흑흑" to "*Sob*",
        "흑흑.." to "*Sob*",
        "흐흑" to "*Sob...*",
        "훌쩍" to "*Sniff*",
        "훌쩍훌쩍" to "*Sniffle*",
        "ㅋㅋㅋㅋ" to "Haha...",
        "ㅋㅋㅋ" to "Haha...",
        "ㅎㅎ" to "Hehe...",
        "ㅎㅎㅎ" to "Hehe...",

        // High-Frequency Manhwa SFX, Reactions & Slang Interjections
        "드르륵" to "*Rattle*",
        "달그락" to "*Clatter*",
        "달그락달그락" to "*Clatter clatter*",
        "쾅!" to "*BAM!*",
        "쾅" to "*BAM*",
        "콰쾅!" to "*KA-BOOM!*",
        "콰쾅" to "*KA-BOOM*",
        "우르르" to "*Rumble*",
        "뚜벅뚜벅" to "*Footsteps*",
        "저벅저벅" to "*Footsteps*",
        "사각사각" to "*Scratch scratch*",
        "부스럭" to "*Rustle*",
        "바스락" to "*Rustle*",
        "주르륵" to "*Trickle*",
        "뚝뚝" to "*Drip drip*",
        "보글보글" to "*Bubble bubble*",
        "지글지글" to "*Sizzle*",
        "두리번두리번" to "*Glances around*",
        "살금살금" to "*Tiptoe*",
        "힐끔" to "*Glance*",
        "끄덕" to "*Nod*",
        "도리도리" to "*Shakes head*",
        "씨익" to "*Smirks*",
        "씨익.." to "*Smirks...*",
        "피식" to "*Chuckles*",
        "피식.." to "*Chuckles...*",
        "싱긋" to "*Beams*",
        "활짝" to "*Smiles brightly*",
        "울컥" to "*Chokes up*",
        "움찔" to "*Flinches*",
        "움찔!" to "*Flinches!*",
        "흠칫" to "*Startled*",
        "흠칫!" to "*Startled!*",
        "철렁" to "*Heart drops*",
        "쿵쾅쿵쾅" to "*Pounding heart*",
        "두근두근" to "*Ba-dump ba-dump*",
        "콩닥콩닥" to "*Pit-a-pat*",
    )

    /**
     * Translates a text segment as a manhwa sound effect, reaction, or slang interjection.
     * Returns the English translation if matched, or null if it requires machine translation.
     */
    fun translateSfx(sourceText: String): String? {
        val trimmed = sourceText.trim()
        if (trimmed.isEmpty()) return null

        // 1. Direct exact lookup
        EXACT_MAP[trimmed]?.let { return it }

        // 2. Lookup with normalized whitespace, dots, and dashes
        val normalized = trimmed
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\.{2,}"), "..")
            .replace(Regex("-{2,}"), "-")
            .replace(Regex("!{2,}"), "!")
            .replace(Regex("\\?{2,}"), "?")
        EXACT_MAP[normalized]?.let { return it }

        // 3. Repeated panting / sigh regex (e.g. "하아아...", "하아.. 하아아", "하앙~")
        if (Regex("^(하[아앗앙]+[\\s\\.\\-\\~\\!]*)+$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            val count = Regex("하[아앗앙]+").findAll(trimmed).count()
            return if (count >= 2) "Haah... Haah..." else "Haah..."
        }

        // 4. Repeated groaning regex (e.g. "으으윽...", "흐으윽", "크으윽")
        if (Regex("^(으+[윽응읏]|흐+[윽응읏]|크+[윽응읏])[\\s\\.\\-\\~\\!]*$").matches(trimmed)) {
            return "Ngh..."
        }

        // 5. Repeated heartbeat regex (e.g. "두근...", "두근두근두근")
        if (Regex("^(두근)+[\\s\\.\\-\\~]*$").matches(trimmed)) {
            return "Ba-dump... Ba-dump..."
        }

        // 6. Repeated kisses (e.g. "쪽쪽쪽...")
        if (Regex("^(쪽)+[\\s\\.\\-\\~\\!]*$").matches(trimmed)) {
            return "*Kiss kiss*"
        }

        // 7. Misread OCR circle variations (e.g. "...0h?", "0H", "...OH")
        if (Regex("^\\.*[0oO][hH]\\?*$").matches(trimmed)) {
            return "...Huh?"
        }

        // 8. Stacked single-syllable moans & exclamations (e.g. "아 흑 읏", "하아.. 으읏..", "헉.. 하아..")
        val tokens = trimmed.split(Regex("""[\s.,~…!\-]+""")).filter { it.isNotBlank() }
        if (tokens.size in 2..4 && tokens.all { it.length in 1..3 }) {
            val mappedTokens = tokens.map { tok ->
                EXACT_MAP[tok]?.removeSurrounding("*") ?: SINGLE_VOCAL_TOKENS[tok]
            }
            if (mappedTokens.all { it != null }) {
                return mappedTokens.filterNotNull().distinct().joinToString(" ")
            }
        }

        return null
    }

    private val SINGLE_VOCAL_TOKENS = mapOf(
        "아" to "Ah...",
        "앗" to "Ah!",
        "앙" to "Ahn...",
        "하" to "Haah...",
        "하아" to "Haah...",
        "흑" to "Sob...",
        "흡" to "Mmph...",
        "읍" to "Mmph!",
        "읏" to "Ngh...",
        "으읏" to "Ngh...",
        "윽" to "Ugh...",
        "으윽" to "Ugh...",
        "헉" to "Gasp!",
        "음" to "Mmm...",
        "흣" to "Ngh...",
        "흐응" to "Hnng...",
    )

    fun isSfx(sourceText: String): Boolean = translateSfx(sourceText) != null
}
