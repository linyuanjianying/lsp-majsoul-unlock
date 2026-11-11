package android.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;

/**
 * Stub FrameLayout with a real measure pass: MATCH_PARENT children take the content box,
 * weighted height takes the remaining space (the in-game panel pages), and everything else
 * is wrapped. The tallest child decides the height; gravity placement is not modelled,
 * because it does not change any measured size.
 */
public class FrameLayout extends ViewGroup {
    public FrameLayout(Context context) { super(context); }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public int gravity = -1;
        public LayoutParams(int width, int height) { super(width, height); }
        public LayoutParams(int width, int height, int gravity) {
            super(width, height);
            this.gravity = gravity;
        }
        public LayoutParams(ViewGroup.LayoutParams source) { super(source); }
        public LayoutParams(ViewGroup.MarginLayoutParams source) { super(source); }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int widthMode = MeasureSpec.getMode(widthSpec);
        int widthSize = MeasureSpec.getSize(widthSpec);
        int heightMode = MeasureSpec.getMode(heightSpec);
        int heightSize = MeasureSpec.getSize(heightSpec);

        int innerW = Math.max(0, widthSize - getPaddingLeft() - getPaddingRight());
        int innerH = Math.max(0, heightSize - getPaddingTop() - getPaddingBottom());

        int maxW = 0;
        int maxH = 0;
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = paramsOf(child);
            int specW = params.width == LayoutParams.MATCH_PARENT
                ? MeasureSpec.makeMeasureSpec(Math.max(0, innerW - params.leftMargin - params.rightMargin), MeasureSpec.EXACTLY)
                : params.width >= 0
                    ? MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY)
                    : MeasureSpec.makeMeasureSpec(Math.max(0, innerW - params.leftMargin - params.rightMargin), MeasureSpec.AT_MOST);
            int specH = params.height == LayoutParams.MATCH_PARENT
                ? MeasureSpec.makeMeasureSpec(Math.max(0, innerH - params.topMargin - params.bottomMargin), MeasureSpec.EXACTLY)
                : params.height >= 0
                    ? MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY)
                    : MeasureSpec.makeMeasureSpec(Math.max(0, innerH - params.topMargin - params.bottomMargin), MeasureSpec.AT_MOST);
            child.measure(specW, specH);
            maxW = Math.max(maxW, child.getMeasuredWidth() + params.leftMargin + params.rightMargin);
            maxH = Math.max(maxH, child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
        }

        int ownW = getPaddingLeft() + getPaddingRight() + maxW;
        int ownH = getPaddingTop() + getPaddingBottom() + maxH;
        int finalW = widthMode == MeasureSpec.EXACTLY ? widthSize
            : widthMode == MeasureSpec.AT_MOST ? Math.min(ownW, widthSize) : ownW;
        int finalH = heightMode == MeasureSpec.EXACTLY ? heightSize
            : heightMode == MeasureSpec.AT_MOST ? Math.min(ownH, heightSize) : ownH;
        setMeasuredDimension(finalW, finalH);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = paramsOf(child);
            int x = getPaddingLeft() + params.leftMargin;
            int y = getPaddingTop() + params.topMargin;
            Gravity.apply(params.gravity, child.getMeasuredWidth(), child.getMeasuredHeight(),
                x, y, x + Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight()),
                y + Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom()), 0);
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
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
