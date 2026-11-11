package com.yefeng.majmax.hookprobe.helper

import com.yefeng.majmax.hookprobe.manager.ManagerLanguage

/**
 * Stand-in for the real copy table (27KB of translations compiled into the app).
 *
 * The probe is about wiring, not wording: it only needs `text()` to return something stable
 * and `languageOf()` to resolve a tag the way the real table does. The real table is still
 * covered by run-top-control-copy-probe.sh, which compiles it from source.
 */
internal object GameMenuText {
    fun text(zh: String, language: ManagerLanguage): String =
        if (language == ManagerLanguage.SIMPLIFIED) zh else "[${language.tag}] $zh"

    fun languageOf(tag: String?, current: ManagerLanguage): ManagerLanguage {
        val normalized = tag?.trim().orEmpty()
        if (normalized.isEmpty()) return current
        return ManagerLanguage.entries.firstOrNull { it.tag.equals(normalized, ignoreCase = true) }
            ?: current
    }
}
