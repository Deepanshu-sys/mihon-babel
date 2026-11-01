package mihon.feature.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    preferenceStore: PreferenceStore,
) {
    val showTranslations: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_show_translations_key",
        true,
    )

    val effortLevel: Preference<String> = preferenceStore.getString(
        "pref_translation_effort_level",
        TranslationEffortLevel.MEDIUM.key,
    )

    val comicFont: Preference<String> = preferenceStore.getString(
        "pref_translation_comic_font",
        "animeace.ttf",
    )

    val smartInpaintingFilter: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_translation_smart_inpainting_filter",
        true,
    )
}

enum class TranslationEffortLevel(val key: String, val label: String) {
    LOW("low", "Low (Fastest, No Context)"),
    MEDIUM("medium", "Medium (Rolling Context)"),
    HIGH("high", "High (Deep Chapter Context)");

    companion object {
        fun fromKey(key: String): TranslationEffortLevel = when (key.lowercase()) {
            "low", "fast" -> LOW
            "high", "deep" -> HIGH
            else -> MEDIUM
        }
    }
}

