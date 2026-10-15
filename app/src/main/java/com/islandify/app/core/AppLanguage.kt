package com.islandify.app.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app language choice: "" = follow the phone, "en" = English, "hi" = Hindi,
 * "pt-BR" = Brazilian Portuguese.
 * Stored on its own so it can be read in attachBaseContext, before IslandSettings is initialised.
 */
object AppLanguage {
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val HINDI = "hi"
    const val PORTUGUESE_BRAZIL = "pt-BR"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("islandify", Context.MODE_PRIVATE)

    // Service-hosted ComposeViews do not follow Activity.recreate(). This lightweight signal lets
    // the overlay rebuild its localized context immediately after an in-app language change.
    private val _changes = MutableStateFlow(0)
    val changes = _changes.asStateFlow()

    fun saved(ctx: Context): String = prefs(ctx).getString("lang", SYSTEM) ?: SYSTEM

    fun set(ctx: Context, code: String) {
        prefs(ctx).edit().putString("lang", code).apply()
        _changes.value++
    }

    /** Wraps [base] so every string resource resolves in the chosen language. */
    fun wrap(base: Context): Context {
        val code = prefs(base).getString("lang", SYSTEM) ?: SYSTEM
        if (code.isEmpty()) {
            Locale.setDefault(base.resources.configuration.locales[0])
            return base
        }
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }
}
