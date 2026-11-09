package android.os

/**
 * JVM stand-in for android.jar's stub SystemClock.
 *
 * AutoDiscardController declares `now: () -> Long = SystemClock::elapsedRealtime`.
 * Tests always inject their own clock, but the *default* argument still has to
 * resolve when the controller is constructed through a path that does not pass one,
 * and android.jar's stub throws `RuntimeException("Stub!")`. This shim is placed
 * ahead of android.jar on the probe's classpath and is only compiled into the
 * tools/tests output — it never reaches the APK.
 */
object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
    @JvmStatic fun uptimeMillis(): Long = elapsedRealtime()
    @JvmStatic fun currentThreadTimeMillis(): Long = System.nanoTime() / 1_000_000L
    @JvmStatic fun sleep(ms: Long) = Thread.sleep(ms)
}
