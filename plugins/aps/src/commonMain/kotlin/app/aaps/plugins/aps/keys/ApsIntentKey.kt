package app.aaps.plugins.aps.keys

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

enum class ApsIntentKey(
    override val key: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.URL,
    // urlRef rather than urlResId: a resource id is an Android Int and means nothing off Android.
    override val urlRef: TextRef? = null,
    override val defaultedBySM: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val exportable: Boolean = false
) : IntentPreferenceKey {

    LinkToDocs(
        key = "aps_link_to_docs",
        title = ApsStrings.openapsama_link_to_preference_json_doc_txt,
        preferenceType = PreferenceType.URL,
        urlRef = ApsStrings.openapsama_link_to_preference_json_doc
    ),

    // Disclaimer rows above the advanced Tsunami / Wave settings. They do nothing when clicked (the plugin attaches an
    // empty click handler, without one the row is not drawn). Hidden in simple mode, like the settings they introduce.
    TsunamiAdvancedDisclaimer(
        key = "tsunami_advanced_disclaimer",
        title = ApsStrings.advanced_tsunami_title,
        summary = ApsStrings.advanced_tsu_wave_disclaimer,
        preferenceType = PreferenceType.CLICK,
        defaultedBySM = true
    ),
    WaveAdvancedDisclaimer(
        key = "wave_advanced_disclaimer",
        title = ApsStrings.advanced_wave_title,
        summary = ApsStrings.advanced_tsu_wave_disclaimer,
        preferenceType = PreferenceType.CLICK,
        defaultedBySM = true,
        dependency = BooleanKey.EnableWave
    )
}
