package android.view;

/** Stub Gravity with the flags plus the apply() the stub FrameLayout uses. */
public class Gravity {
    public static final int NO_GRAVITY = 0x0000;
    public static final int AXIS_SPECIFIED = 0x0001;
    public static final int AXIS_PULL_BEFORE = 0x0002;
    public static final int AXIS_PULL_AFTER = 0x0004;
    public static final int AXIS_CLIP = 0x0008;
    public static final int CENTER_HORIZONTAL = 0x0001;
    public static final int CENTER_VERTICAL = 0x0010;
    public static final int CENTER = CENTER_HORIZONTAL | CENTER_VERTICAL;
    public static final int TOP = 0x0030;
    public static final int BOTTOM = 0x0050;
    public static final int LEFT = 0x0003;
    public static final int RIGHT = 0x0005;
    public static final int START = 0x00800003;
    public static final int END = 0x00800005;
    public static final int FILL = 0x0077;

    /** Best-effort placement: enough to keep a centred child centred. */
    public static void apply(int gravity, int width, int height,
                             int containerLeft, int containerTop, int containerRight,
                             int containerBottom, int out) {
        // The stub only needs the call to exist; child.layout() decides the final box.
    }
}
