package com.saba.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

enum class UiLang { HE, EN }

/** Resolves the setup-screen language. Grandpa's own screens are always Hebrew. */
fun Settings.uiLang(): UiLang = when (uiLanguage) {
    "he" -> UiLang.HE
    "en" -> UiLang.EN
    else -> if (Locale.getDefault().language in setOf("he", "iw")) UiLang.HE else UiLang.EN
}

val LocalUiLang = staticCompositionLocalOf { UiLang.HE }

/** Inline translation: every caregiver-facing string is written once in each language, side by side. */
@Composable
fun tr(en: String, he: String): String = if (LocalUiLang.current == UiLang.HE) he else en

fun MainActivity.tr(en: String, he: String): String = if (settings.uiLang() == UiLang.HE) he else en
