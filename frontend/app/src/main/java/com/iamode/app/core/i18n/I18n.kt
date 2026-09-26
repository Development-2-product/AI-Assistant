package com.iamode.app.core.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Languages the app's screens are translated into. "" = follow the phone. */
enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", "System default"),
    ENGLISH("en", "English"),
    TELUGU("te", "తెలుగు"),
    HINDI("hi", "हिन्दी");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag.orEmpty() } ?: SYSTEM
    }
}

/**
 * App-language runtime. Android 13+: the system per-app language (also in Settings › Apps › IA Mode › Language).
 * Android 10–12: the same choice, stored here and applied to the app's contexts.
 * [tr] translates any English source text through the generated string resources and falls back to English.
 */
object I18n {
    private const val PREFS = "i18n"
    private const val KEY = "app_language"

    @Volatile private var resources: Resources? = null
    @Volatile var locale: Locale = Locale.getDefault(); private set

    fun init(context: Context) {
        val tag = current(context).tag
        val base = context.applicationContext ?: context
        locale = if (tag.isBlank()) systemLocale() else Locale.forLanguageTag(tag)
        resources = if (tag.isBlank()) base.resources else localizedContext(base, tag).resources
    }

    fun current(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = context.getSystemService(LocaleManager::class.java)
            val tag = lm?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.language
            if (tag != null) return AppLanguage.fromTag(tag)
            if (lm != null) return AppLanguage.SYSTEM
        }
        return AppLanguage.fromTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""))
    }

    /**
     * Saves the choice. On Android 13+ the system recreates the screens itself; on older versions the caller
     * recreates the activity.
     */
    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (language.tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        }
        init(context)
    }

    /** For attachBaseContext on Android 10–12 (13+ is handled by the system). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return if (tag.isBlank()) base else localizedContext(base, tag)
    }

    private fun localizedContext(base: Context, tag: String): Context {
        val config = Configuration(base.resources.configuration)
        val l = Locale.forLanguageTag(tag)
        config.setLocale(l)
        config.setLocales(LocaleList(l))
        return base.createConfigurationContext(config)
    }

    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()

    internal fun lookup(text: String): String {
        val id = I18N_KEYS[text] ?: return text
        val r = resources ?: return text
        return try { r.getString(id) } catch (e: Resources.NotFoundException) { text }
    }
}

/**
 * Translates an English source text (the same text written in the code) into the app language.
 * Placeholders follow Android's %1$s format; unknown texts are returned unchanged.
 */
fun tr(text: String, vararg args: Any?): String {
    val pattern = I18n.lookup(text)
    if (args.isEmpty()) return pattern
    return try {
        String.format(I18n.locale, pattern, *args)
    } catch (e: java.util.IllegalFormatException) {
        String.format(Locale.ROOT, text, *args)
    }
}
