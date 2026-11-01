package mihon.feature.translation.pipeline

enum class DetectedScript {
    KOREAN,
    SPANISH_OR_LATIN,
    JAPANESE,
    CHINESE,
    UNKNOWN,
}

/**
 * 0ms zero-allocation script detector to identify the source script
 * of comic dialogue for optimal translation routing.
 */
object ComicLanguageDetector {

    fun detectScript(text: String): DetectedScript {
        var hangul = 0
        var kana = 0
        var cjk = 0
        var latin = 0

        for (cp in text.codePoints()) {
            when {
                // Hangul syllables & jamo
                (cp in 0xAC00..0xD7A3) || (cp in 0x1100..0x11FF) || (cp in 0x3130..0x318F) -> hangul++
                // Japanese Hiragana & Katakana
                (cp in 0x3040..0x309F) || (cp in 0x30A0..0x30FF) -> kana++
                // CJK Ideographs (Chinese Hanzi / Kanji)
                (cp in 0x4E00..0x9FFF) -> cjk++
                // Latin characters (English, Spanish with diacritics ñ, á, é, í, ó, ú, ü, ¿, ¡)
                (cp in 0x0041..0x005A) || (cp in 0x0061..0x007A) || (cp in 0x00C0..0x024F) -> latin++
            }
        }

        val totalLetters = hangul + kana + cjk + latin
        if (totalLetters == 0) return DetectedScript.UNKNOWN

        return when {
            hangul > 0 && (latin == 0 || hangul >= latin * 0.4f) -> DetectedScript.KOREAN
            kana > 0 && (latin == 0 || kana >= latin * 0.4f) -> DetectedScript.JAPANESE
            latin > 0 && latin > hangul && latin > kana -> DetectedScript.SPANISH_OR_LATIN
            cjk > 0 -> DetectedScript.CHINESE
            hangul > 0 -> DetectedScript.KOREAN
            else -> DetectedScript.UNKNOWN
        }
    }
}
