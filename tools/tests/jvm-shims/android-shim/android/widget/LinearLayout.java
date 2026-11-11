package android.widget;

import android.content.Context;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;

/**
 * Stub LinearLayout with a real vertical/horizontal measure pass.
 *
 * <p>It mirrors the platform for the cases the in-game control uses: MATCH_PARENT children,
 * weighted children with remaining space, wrap-content children, paddings and margins.
 * It does not model baseline alignment (the control never relies on it) and it does not
 * apply gravity offsets in {@link #onLayout}, which only matters for where a child is drawn
 * inside a taller box, not for how tall the box is.
 */
public class LinearLayout extends ViewGroup {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    private int orientation = HORIZONTAL;

    public LinearLayout(Context context) { super(context); }

    public void setOrientation(int orientation) { this.orientation = orientation; }
    public int getOrientation() { return orientation; }
    public void setGravity(int gravity) {}

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public static final float FILL = -1f;
        public float weight;
        public int gravity = -1;
        public LayoutParams(int width, int height) { super(width, height); }
        public LayoutParams(int width, int height, float weight) {
            super(width, height);
            this.weight = weight;
        }
        public LayoutParams(ViewGroup.LayoutParams source) { super(source); }
        public LayoutParams(ViewGroup.MarginLayoutParams source) { super(source); }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int widthMode = MeasureSpec.getMode(widthSpec);
        int widthSize = MeasureSpec.getSize(widthSpec);
        int heightMode = MeasureSpec.getMode(heightSpec);
        int heightSize = MeasureSpec.getSize(heightSpec);

        int innerWidthSpec = MeasureSpec.makeMeasureSpec(
            Math.max(0, widthSize - getPaddingLeft() - getPaddingRight()),
            widthMode == MeasureSpec.UNSPECIFIED ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST);
        int innerHeightSpec = MeasureSpec.makeMeasureSpec(
            Math.max(0, heightSize - getPaddingTop() - getPaddingBottom()),
            heightMode == MeasureSpec.UNSPECIFIED ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST);

        int totalWidth = 0;
        int totalHeight = 0;
        float totalWeight = 0f;
        int matchCount = 0;
        boolean vertical = orientation == VERTICAL;
        int count = getChildCount();
        int[] heights = new int[count];

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = paramsOf(child);
            int cw = params.width;
            int ch = params.height;
            if (params.weight > 0f) totalWeight += params.weight;
            if ((vertical ? ch : cw) == LayoutParams.MATCH_PARENT) matchCount++;

            int specW = cw == LayoutParams.MATCH_PARENT
                ? MeasureSpec.makeMeasureSpec(Math.max(0, MeasureSpec.getSize(innerWidthSpec)
                    - params.leftMargin - params.rightMargin), MeasureSpec.EXACTLY)
                : cw == 0 && !vertical
                    ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
                    : cw >= 0
                        ? MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(Math.max(0, MeasureSpec.getSize(innerWidthSpec)
                            - params.leftMargin - params.rightMargin), MeasureSpec.AT_MOST);
            int specH = ch == LayoutParams.MATCH_PARENT
                ? MeasureSpec.makeMeasureSpec(Math.max(0, MeasureSpec.getSize(innerHeightSpec)
                    - params.topMargin - params.bottomMargin), MeasureSpec.EXACTLY)
                : ch >= 0
                    ? MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
                    : MeasureSpec.makeMeasureSpec(Math.max(0, MeasureSpec.getSize(innerHeightSpec)
                        - params.topMargin - params.bottomMargin), MeasureSpec.AT_MOST);
            child.measure(specW, specH);

            int childW = child.getMeasuredWidth() + params.leftMargin + params.rightMargin;
            int childH = child.getMeasuredHeight() + params.topMargin + params.bottomMargin;
            heights[i] = childH;
            if (vertical) {
                totalHeight += childH;
                totalWidth = Math.max(totalWidth, childW);
            } else {
                totalWidth += childW;
                totalHeight = Math.max(totalHeight, childH);
            }
        }

        int ownW = getPaddingLeft() + getPaddingRight() + totalWidth;
        int ownH = getPaddingTop() + getPaddingBottom() + totalHeight;

        if (vertical) {
            if (heightMode == MeasureSpec.EXACTLY && totalWeight > 0f && matchCount < count) {
                int border = heightSize - getPaddingTop() - getPaddingBottom();
                int remaining = Math.max(0, border - totalHeight);
                for (int i = 0; i < count; i++) {
                    View child = getChildAt(i);
                    if (child.getVisibility() == GONE) continue;
                    LayoutParams params = paramsOf(child);
                    if (params.weight <= 0f) continue;
                    int add = (int) (remaining * params.weight / totalWeight);
                    int newHeight = child.getMeasuredHeight() + add;
                    child.measure(
                        MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY));
                }
            }
            ownH = Math.max(ownH,
                heightMode == MeasureSpec.EXACTLY && totalWeight > 0f ? heightSize : 0);
        } else if (widthMode == MeasureSpec.EXACTLY && totalWeight > 0f) {
            int border = widthSize - getPaddingLeft() - getPaddingRight();
            int remaining = Math.max(0, border - totalWidth);
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                LayoutParams params = paramsOf(child);
                if (params.weight <= 0f) continue;
                int add = (int) (remaining * params.weight / totalWeight);
                child.measure(
                    MeasureSpec.makeMeasureSpec(child.getMeasuredWidth() + add, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(child.getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
        }

        int finalW = widthMode == MeasureSpec.UNSPECIFIED ? ownW : widthSize;
        int finalH = heightMode == MeasureSpec.UNSPECIFIED ? ownH : heightSize;
        if (heightMode == MeasureSpec.AT_MOST) finalH = Math.min(ownH, heightSize);
        if (widthMode == MeasureSpec.AT_MOST) finalW = Math.min(ownW, widthSize);
        setMeasuredDimension(finalW, finalH);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean vertical = orientation == VERTICAL;
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = paramsOf(child);
            int childW = child.getMeasuredWidth();
            int childH = child.getMeasuredHeight();
            int x = left + params.leftMargin;
            int y = top + params.topMargin;
            child.layout(x, y, x + childW, y + childH);
            if (vertical) top += params.topMargin + params.bottomMargin + childH;
            else left += params.leftMargin + params.rightMargin + childW;
        }
    }

    static LayoutParams paramsOf(View child) {
        ViewGroup.LayoutParams raw = child.getLayoutParams();
        if (raw instanceof LayoutParams) return (LayoutParams) raw;
        LayoutParams converted = raw == null
            ? new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            : new LayoutParams(raw);
        child.setLayoutParams(converted);
        return converted;
    }
}
