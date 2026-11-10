package com.yefeng.majmax.hookprobe.manager

import android.content.Context

/**
 * The manager's language, exposed for callers that cannot use Kotlin `internal` members.
 *
 * This is the stable boundary other components (LSPosed entry points, Java hooks, the
 * assistant's own code) are expected to call, so the signatures here — and the fact that it is
 * a **public object with `@JvmStatic` members** — must not change. From Java:
 *
 * ```java
 * UiLanguage.text("停止助手", "Stop assistant", "アシストを停止")
 * UiLanguage.applyTag("ja")
 * String tag = UiLanguage.currentTag()
 * UiLanguage.refresh(context)
 * ```
 *
 * The object deliberately holds no state of its own: the stored choice and the effective
 * language live in [ManagerLanguageSettings] and [ManagerLanguageState], so `UiLanguage` can
 * never disagree with what the Compose screens render.
 *
 * Scope of [applyTag]: it updates **this process** only. `refresh(context)` reads the stored
 * option and the system locales, so it stays correct across processes — but it still only
 * republishes inside the process that calls it. A game process that inherits this class cannot
 * see a selection made in the manager process without being told; that is why callers in the
 * game are expected to pass an explicit tag through [applyTag] (from the packet they already
 * exchange) and to re-read [currentTag] after doing so.
 *
 * None of these methods write preferences: [text] and [currentTag] are pure reads, [applyTag]
 * is a render-time override, and only the manager's own language menu (through
 * [ManagerLanguageState.select]) stores the user's choice.
 */
object UiLanguage {
    /**
     * Picks one of the three pre-translated strings for the language in force right now.
     *
     * Chinese is the source text, so it is returned whenever the effective language is Chinese
     * — including when a "follow system" resolution lands on Chinese.
     */
    @JvmStatic
    fun text(zh: String, en: String, ja: String): String =
        ManagerLanguageState.current.tr(zh, en, ja)

    /**
     * Sets the effective language of this process from a BCP-47-ish tag.
     *
     * Accepts `zh-Hans`, `zh`, `zh-CN`, `zh-TW`, `zh-Hant`, `en`, `en-US`, `ja`, `ja-JP`
     * (case-insensitive). A region or script suffix is ignored: the manager has exactly three
     * renderings, and simplified Chinese is the only Chinese it can draw.
     *
     * An unrecognised tag is not a language switch — it is more likely a platform other than
     * the three supported ones — so the current value is kept rather than silently falling
     * back to Chinese. Preferences are never written; the stored user choice is untouched, and
     * the next [refresh] (or the next manager start) restores it.
     */
    @JvmStatic
    fun applyTag(tag: String) {
        val target = explicitLanguageOf(tag) ?: return
        ManagerLanguageState.applySystemLanguageOverride(target)
    }

    /**
     * The effective language of this process as a resource tag: `zh-Hans`, `en` or `ja`.
     *
     * Never `system`: the answer is always something that can actually be rendered.
     */
    @JvmStatic
    fun currentTag(): String = runCatching { ManagerLanguageState.current.tag }
        // Callers embed the value into JSON snapshots; an exception here would take down the
        // whole snapshot, so a failure degrades to the source language instead.
        .getOrDefault(ManagerLanguage.SIMPLIFIED.tag)

    /**
     * Re-derives the effective language from the stored option + `Resources.getSystem()`.
     *
     * With an explicit stored choice the effective language is that choice; with "follow
     * system" it is the device language (Japanese, then Chinese, then English). Call this after
     * the caller knows the user may have changed the setting — the manager app calls it from
     * `MainActivity.onCreate`. It does not write preferences and does not throw.
     */
    @JvmStatic
    fun refresh(context: Context) {
        runCatching {
            ManagerLanguageState.load(context)
            ManagerLanguageState.syncSystemLanguage()
        }
    }

    /**
     * Best-effort translation of a *stored* message whose source text is Chinese.
     *
     * Mirrors `localizedTrimmed` for callers outside a composition: known manager status lines
     * (see `ManagerLanguageText`) are translated, and anything else — engine errors, tool
     * output, unknown diagnostics — is returned verbatim, exactly as the UI would show it.
     */
    @JvmStatic
    fun localizeKnown(raw: String): String =
        ManagerLanguageText.trim(raw, ManagerLanguageState.current)

    /** The tag [applyTag] would resolve [tag] to, or `null` when the tag is not supported. */
    @JvmStatic
    fun resolveTag(tag: String): String? = explicitLanguageOf(tag)?.tag

    /** `true` while the stored choice is "follow system" (the device drives the language). */
    @JvmStatic
    fun followsSystem(): Boolean = ManagerLanguageState.followsSystem

    /**
     * Tag parser: delegates to [ManagerLanguageChoice.fromTag] so it cannot drift from the
     * preferences reader. `system` and unknown tags resolve to null (unsupported), never to a
     * silent fallback.
     */
    private fun explicitLanguageOf(tag: String?): ManagerLanguage? =
        ManagerLanguageChoice.fromTag(tag).explicitOrNull()
}
