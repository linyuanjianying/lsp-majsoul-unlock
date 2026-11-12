package android.widget;

import android.content.Context;

/**
 * Stub EditText: a TextView plus the input-type slot the in-game control sets.
 *
 * <p>The real class is a TextView with an editable buffer; the JVM probe never types, so only
 * the construction and the input-type assignment have to exist for
 * {@code GameTopControl.buildControlPage()} to build the custom-name editors. Focus is modelled
 * by {@link android.view.View#hasFocus()}, which stays false here: the probe asserts structure,
 * not typing.
 */
public class EditText extends TextView {
    private int inputType;

    public EditText(Context context) { super(context); }

    public void setInputType(int value) { inputType = value; }
    public int getInputType() { return inputType; }
    public void setSelection(int index) {}
    public void setImeOptions(int options) {}
    public void setSelectAllOnFocus(boolean value) {}
}
