package com.yefeng.majmax.hookprobe.helper

import android.content.Context
import android.text.TextUtils
import android.widget.TextView
import com.yefeng.majmax.hookprobe.manager.ManagerLanguage
import com.yefeng.majmax.hookprobe.manager.ManagerLanguageSettings
import com.yefeng.majmax.hookprobe.manager.ManagerSystemLanguage

/**
 * Copy plumbing shared by the in-game menu, the recommendation window and the system
 * floating window.
 *
 * Everything here is copy-only. A call either returns the string/state to draw or
 * adjusts how an *existing* [TextView] lays that text out (wrap, alignment, ellipsis).
 * No width, padding, minimum size, position or view tree is ever created or changed, so
 * a translated label can grow to a second line but can never move the surface.
 */
internal object InGameLocalizer {

    /**
     * Language a surface currently draws in.
     *
     * It changes only when the manager reports a different tag; `null`/blank means "no
     * news" and keeps the current value, so a failed or older reply can never flip the
     * copy back mid-session.
     */
    class State(initial: ManagerLanguage = ManagerLanguage.SIMPLIFIED) {
        /** The language to draw in; read from Kotlin and from the Java control alike. */
        @JvmField
        var language: ManagerLanguage = initial

        /** The last tag this surface adopted; empty until a reply carried one. */
        @JvmField
        var tag: String = ""

        /** True when [raw] named another known language and the state moved to it. */
        fun apply(raw: String?): Boolean {
            val next = GameMenuText.languageOf(raw, language)
            tag = raw?.trim().orEmpty()
            if (next == language) return false
            language = next
            return true
        }

        /**
         * Seeds a language without claiming a tag: [tag] stays empty, so the first reply
         * is still authoritative and may move the copy even if the seed was stale.
         */
        fun force(next: ManagerLanguage) {
            language = next
            tag = ""
        }
    }

    /**
     * The language the manager page currently shows.
     *
     * A Traditional selection resolves to [ManagerLanguage.TRADITIONAL], so the tag the provider
     * sends to the game is `zh-Hant` and every in-game surface follows it.
     *
     * The stored *choice* with "follow the system" resolved, i.e. exactly the value the
     * manager renders in. Read-only: a failure keeps Chinese, and the manager process
     * itself is the writer.
     */
    @JvmStatic
    fun currentLanguage(context: Context): ManagerLanguage = runCatching {
        ManagerLanguageSettings.readChoice(context).effective(ManagerSystemLanguage.current())
    }.getOrDefault(ManagerLanguage.SIMPLIFIED)

    /**
     * [currentLanguage] as the tag the provider sends to the game, or `null` when it could
     * not be read at all (in which case nothing should be sent rather than a guess).
     *
     * Same resolver as the game-side reads, so the overlay, the provider and the control all
     * derive the tag from the one stored choice.
     */
    @JvmStatic
    fun currentTag(context: Context): String? = runCatching {
        ManagerLanguageSettings.readChoice(context).effective(ManagerSystemLanguage.current()).tag
    }.getOrNull()

    /** Horizontal alignment of a cell, applied only when it is already different. */
    object Align {
        const val CENTER = 17 // Gravity.CENTER
        const val START = 3 // Gravity.START | Gravity.TOP
    }

    /**
     * Translation entry point: [zh] is the source of truth.
     *
     * Declared in the companion so Java reads it as the plain static
     * `InGameLocalizer.t(language, zh)` the in-game control calls.
     */
    @JvmStatic
    fun t(language: ManagerLanguage, zh: String): String = GameMenuText.text(zh, language)

    /**
     * Applies the layout a translated label needs on a view that already exists.
     *
     * - `wrapLines = 1` keeps a one-line chip (capsule, switch state) single-line and
     *   trims it with an ellipsis, exactly like the Chinese copy did.
     * - `wrapLines > 1` lets a longer note occupy at most that many lines inside the
     *   same measured box.
     * - [align] re-centres a cell so a wrapped label reads evenly.
     *
     * Idempotent and cheap: it is called on every repaint, so the Chinese layout comes
     * back when the language switches back. It never adds views or listeners.
     */
    @JvmStatic
    @JvmOverloads
    fun layout(view: TextView?, wrapLines: Int, align: Int? = null) {
        if (view == null) return
        if (align != null && view.gravity != align) view.gravity = align
        if (wrapLines <= 1) {
            if (!view.isSingleLine) view.isSingleLine = true
            val trimmed = view.ellipsize !== TextUtils.TruncateAt.END
            if (view.maxLines != 1) view.maxLines = 1
            if (trimmed) view.ellipsize = TextUtils.TruncateAt.END
        } else {
            if (view.isSingleLine) view.isSingleLine = false
            if (view.maxLines != wrapLines) view.maxLines = wrapLines
            if (view.ellipsize != null) view.ellipsize = null
        }
    }
}
