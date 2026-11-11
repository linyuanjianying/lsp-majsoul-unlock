package android.view.animation;

import java.util.ArrayList;
import java.util.List;

/** A group of animations played together; the page switch fits both into one run. */
public class AnimationSet extends Animation {
    public final List<Animation> children = new ArrayList<>();
    public AnimationSet(boolean shareInterpolator) {}
    public void addAnimation(Animation animation) { children.add(animation); }
}
