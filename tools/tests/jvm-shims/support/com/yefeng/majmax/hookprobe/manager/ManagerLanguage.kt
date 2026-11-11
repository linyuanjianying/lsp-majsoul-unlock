package com.yefeng.majmax.hookprobe.manager

/**
 * Stand-in for the real manager's language enum, compiled into the probe's classpath.
 *
 * The probe runs GameTopControl/GameTopUi/InGameLocalizer, and those only need the enum
 * and the two lookups used by InGameLocalizer.currentLanguage(): the stored choice and the
 * system language. The real ManagerLanguage.kt also carries Compose and coroutine state and
 * a full translation table used by the manager UI, none of which the in-game control calls.
 * Keeping this here means the probe does not depend on any Gradle output.
 */
enum class ManagerLanguage(val tag: String) {
    SIMPLIFIED("zh-Hans"),
    TRADITIONAL("zh-Hant"),
    ENGLISH("en"),
    JAPANESE("ja");

    fun effective(system: ManagerLanguage): ManagerLanguage = this
}

object ManagerLanguageSettings {
    fun readChoice(context: android.content.Context): ManagerLanguage = ManagerLanguage.SIMPLIFIED
}

object ManagerSystemLanguage {
    fun current(): ManagerLanguage = ManagerLanguage.SIMPLIFIED
}
