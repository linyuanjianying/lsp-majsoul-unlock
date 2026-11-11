package android.widget;

import android.content.Context;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;

/**
 * Stub ScrollView: a child that is taller than the viewport is clipped to it (which is
 * exactly the height budget the probe asserts), a shorter child fills the viewport when
 * {@link #setFillViewport(boolean)} is on.
 */
public class ScrollView extends FrameLayout {
    private boolean fillViewport;

    public ScrollView(Context context) { super(context); }

    public void setFillViewport(boolean value) { fillViewport = value; }
    public void setOverScrollMode(int mode) {}

    public static class LayoutParams extends FrameLayout.LayoutParams {
        public LayoutParams(int width, int height) { super(width, height); }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int widthMode = MeasureSpec.getMode(widthSpec);
        int widthSize = MeasureSpec.getSize(widthSpec);
        int heightMode = MeasureSpec.getMode(heightSpec);
        int heightSize = MeasureSpec.getSize(heightSpec);
        int viewportH = Math.max(0, heightSize - getPaddingTop() - getPaddingBottom());
        int viewportW = Math.max(0, widthSize - getPaddingLeft() - getPaddingRight());

        View child = getChildCount() > 0 ? getChildAt(0) : null;
        int contentH = 0;
        if (child != null && child.getVisibility() != GONE) {
            ViewGroup.LayoutParams params = child.getLayoutParams();
            int cw = params == null ? LayoutParams.MATCH_PARENT : params.width;
            int specW = cw >= 0 ? MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
                : MeasureSpec.makeMeasureSpec(viewportW, MeasureSpec.AT_MOST);
            child.measure(specW, MeasureSpec.makeMeasureSpec(viewportH, MeasureSpec.AT_MOST));
            contentH = child.getMeasuredHeight();
            int childW = child.getMeasuredWidth();
            int finalChildH = fillViewport && heightMode == MeasureSpec.EXACTLY
                ? Math.max(contentH, viewportH) : contentH;
            child.layout(0, 0, childW, finalChildH);
        }

        int ownW = viewportW + getPaddingLeft() + getPaddingRight();
        int ownH = contentH + getPaddingTop() + getPaddingBottom();
        int finalW = widthMode == MeasureSpec.EXACTLY ? widthSize
            : widthMode == MeasureSpec.AT_MOST ? Math.min(ownW, widthSize) : ownW;
        int finalH;
        if (heightMode == MeasureSpec.EXACTLY) finalH = heightSize;
        else if (heightMode == MeasureSpec.AT_MOST) finalH = Math.min(ownH, heightSize);
        else finalH = ownH;
        setMeasuredDimension(finalW, finalH);
    }
}
