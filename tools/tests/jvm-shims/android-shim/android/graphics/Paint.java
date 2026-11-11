package android.graphics;

public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
    public enum Style { FILL, STROKE, FILL_AND_STROKE }

    private int color;
    private Style style = Style.FILL;
    private float strokeWidth;
    private Cap cap;
    private Join join;
    private Typeface typeface;
    private float textSize;

    public Paint() {}
    public Paint(int flags) {}
    public void setColor(int value) { color = value; }
    public int getColor() { return color; }
    public void setStyle(Style value) { style = value; }
    public void setStrokeWidth(float value) { strokeWidth = value; }
    public void setStrokeCap(Cap value) { cap = value; }
    public void setStrokeJoin(Join value) { join = value; }
    public void setAntiAlias(boolean value) {}
    public void setTypeface(Typeface value) { typeface = value; }
    public void setTextSize(float value) { textSize = value; }
    public static class FontMetrics {
        public float top, ascent, descent, bottom, leading;
    }
    public FontMetrics getFontMetrics() { return new FontMetrics(); }
    public float measureText(CharSequence text, int start, int end) {
        return (end - start) * (textSize <= 0f ? 10f : textSize * .55f);
    }
    public float measureText(String text) { return text == null ? 0f : text.length() * (textSize <= 0f ? 10f : textSize * .55f); }
    public void setShadowLayer(float radius, float dx, float dy, int color) {}
    public void clearShadowLayer() {}
    public void setAlpha(int value) {}
}
