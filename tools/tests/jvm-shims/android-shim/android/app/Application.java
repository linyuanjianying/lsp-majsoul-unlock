package android.app;

import android.content.Context;

public class Application extends Context {
    public interface ActivityLifecycleCallbacks {
        void onActivityCreated(Activity activity, android.os.Bundle state);
        void onActivityStarted(Activity activity);
        void onActivityResumed(Activity activity);
        void onActivityPaused(Activity activity);
        void onActivityStopped(Activity activity);
        void onActivitySaveInstanceState(Activity activity, android.os.Bundle state);
        void onActivityDestroyed(Activity activity);
    }
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callbacks) {}
    @Override public Context getApplicationContext() { return this; }
}
