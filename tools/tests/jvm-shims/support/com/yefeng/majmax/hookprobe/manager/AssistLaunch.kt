package com.yefeng.majmax.hookprobe.manager

/**
 * Stand-in for the real launch-state object (AiEndpointProvider.kt), reduced to the API the
 * in-game control reads: the state words it compares against and the foreground hand-off
 * it calls. The probe never launches anything, so every call is inert.
 */
object AssistLaunch {

    const val RUNNING = "running"
    const val STARTED = "started"
    const val RESTRICTED = "restricted"
    const val FORBIDDEN = "forbidden"
    const val UNAVAILABLE = "unavailable"

    @JvmStatic fun consumeToken(token: String?): Boolean = true
    @JvmStatic fun startFromForeground(context: android.content.Context) {}
    @JvmStatic fun isForegroundReady(): Boolean = true
    @JvmStatic fun isStartBlocked(): Boolean = false
}
