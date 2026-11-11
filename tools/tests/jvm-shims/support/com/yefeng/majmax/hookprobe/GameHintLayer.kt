package com.yefeng.majmax.hookprobe

/**
 * Stand-in for the on-table hint layer (GameHintLayer.kt).
 *
 * The build regression only needs the control's calls to this collaborator to be inert: the
 * hint layer draws recommendations on the table and owns no view tag, so it takes no part in
 * the crash this probe guards.
 */
internal class GameHintLayer {
    fun setPresentationAllowed(allowed: () -> Boolean) {}
    fun setPresentationEnabled(enabled: Boolean) {}
    fun attach(activity: android.app.Activity) {}
    fun detach() {}
    fun hideNow() {}
}
