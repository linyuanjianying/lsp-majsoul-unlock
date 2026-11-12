package android.text;

/**
 * Stub InputType: only the constants the in-game naming editors assign.
 *
 * <p>The values match the platform ones; nothing on the JVM reads them, the editors just have to
 * be able to store an input type so {@code GameTopControl.buildControlPage()} can run.
 */
public final class InputType {
    public static final int TYPE_CLASS_TEXT = 0x00000001;
    public static final int TYPE_TEXT_VARIATION_NORMAL = 0x00000000;
    public static final int TYPE_TEXT_FLAG_CAP_SENTENCES = 0x00004000;

    private InputType() {}
}
