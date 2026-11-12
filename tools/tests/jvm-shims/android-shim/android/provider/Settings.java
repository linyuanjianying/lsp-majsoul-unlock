package android.provider;

import android.content.ContentResolver;

/**
 * Stub settings provider. {@link Global#getFloat} answers with the caller's default, which is
 * what a device without the setting stored does; the panel only asks for one value (the system
 * animator duration scale) and does not care where it came from.
 */
public class Settings {
    public static class Global {
        /** Name of the "Animation scale" developer setting the panel honours. */
        public static final String ANIMATOR_DURATION_SCALE = "animator_duration_scale";

        public static float getFloat(ContentResolver resolver, String name, float def) {
            return def;
        }
    }
}
