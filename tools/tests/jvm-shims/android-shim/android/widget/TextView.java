package android.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;

/**
 * Stub TextView: text, typeface and colour are remembered, and the measurement models a
 * one-line row instead of "zero".
 *
 * <p>No font is available on the JVM, so a text row is measured as its explicit padding
 * plus a per-line height, {@code 1.22 * 1.34 * textSizePx} (AOSP's 1.1 line gap factor on
 * top of a typical ascent+descent). That is what {@link android.widget.LinearLayout} uses
 * to size a label, so a card, a header or a tab row measures at close to its device height
 * without pretending to be exact: the layout regression asserts *structure* (caps, ratios,
 * spacing deltas), and the real px numbers are reported for review.
 */
public class TextView extends View {
    /** Assumed ascent+descent of "sans-serif" in ems. */
    public static final float LINE_HEIGHT_EM = 1.22f * 1.10f;

    private CharSequence text = "";
    private int textColor;
    private float textSize;
    private Typeface typeface;
    public int gravity;
    public int maxLines = Integer.MAX_VALUE;
    private boolean singleLine;
    public android.text.TextUtils.TruncateAt ellipsize;
    private CharSequence hint;

    public TextView(Context context) { super(context); }

    public CharSequence getText() { return text; }
    public void setText(CharSequence value) { text = value == null ? "" : value; }
    public void setText(int resId) {}
    public void setTextAppearance(int resId) {}
    public void setTypeface(Typeface value) { typeface = value; }
    public void setTypeface(Typeface value, int style) { typeface = value; }
    public void setTextColor(int color) { textColor = color; }
    public int getCurrentTextColor() { return textColor; }
    public void setTextSize(float size) { textSize = size; }
    public float getTextSize() { return textSize; }
    public void setGravity(int value) { gravity = value; }
    public void setMaxLines(int value) { maxLines = value; }
    public void setMinHeight(int value) { setMinimumHeight(value); }
    public void setMinWidth(int value) { setMinimumWidth(value); }
    public void setEms(int value) {}
    public void setShadowLayer(float r, float dx, float dy, int c) {}
    public void setAllCaps(boolean value) {}
    public void setSingleLine(boolean value) { singleLine = value; }
    public boolean isSingleLine() { return singleLine; }
    public void setEllipsize(android.text.TextUtils.TruncateAt value) { ellipsize = value; }
    public android.text.TextUtils.TruncateAt getEllipsize() { return ellipsize; }
    public void setLineSpacing(float add, float mult) {}
    public void setIncludeFontPadding(boolean value) {}
    public void setLetterSpacing(float value) {}
    public void setHint(CharSequence value) { hint = value; }

    /** One text line in pixels, for the current text size. */
    public int lineHeight() {
        return (int) Math.round(textSize * LINE_HEIGHT_EM);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Width: a wrap-content label still needs a box; the probe only asserts heights and
        // the wrapping behaviour of the real containers, so give it a nominal one line.
        int wantW = getPaddingLeft() + getPaddingRight() + Math.max(0, getSuggestedMinimumWidth());
        int wantH = getPaddingTop() + getPaddingBottom() + Math.max(lineHeight(), 0);
        setMeasuredDimension(resolveSize(wantW, widthSpec), resolveSize(wantH, heightSpec));
    }
}
