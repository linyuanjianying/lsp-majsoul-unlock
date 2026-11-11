@file:JvmName("Color")

package android.graphics

/**
 * Real (non-stub) `android.graphics.Color` for the JVM pure-logic probes.
 *
 * The SDK's `android.jar` ships method bodies that throw `RuntimeException("Stub!")`, so a
 * colour assertion cannot run against it. This shim reproduces the *exact* contract of the
 * framework class for the helpers the floating window uses — `argb`, `rgb`, `alpha`, `red`,
 * `green`, `blue` — as a class of static methods (same shape as the framework's static
 * methods), so the production formula itself is under test, not a copy of it.
 */
object ColorShimConstants {
    const val BLACK = -0x1000000            // 0xFF000000
    const val WHITE = -0x1                 // 0xFFFFFFFF
    const val TRANSPARENT = 0
    const val ALPHA = 24
}

private const val BLACK = -0x1000000
private const val WHITE = -0x1
private const val TRANSPARENT = 0
private const val ALPHA = 24

fun alpha(color: Int): Int = color ushr 24

fun red(color: Int): Int = color shr 16 and 0xFF

fun green(color: Int): Int = color shr 8 and 0xFF

fun blue(color: Int): Int = color and 0xFF

/** `argb(alpha, red, green, blue)`; every channel masked to 8 bits, as the framework does. */
fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    (alpha and 0xFF shl 24) or (red and 0xFF shl 16) or (green and 0xFF shl 8) or (blue and 0xFF)

/** Opaque colour from its three channels. */
fun rgb(red: Int, green: Int, blue: Int): Int = argb(0xFF, red, green, blue)
