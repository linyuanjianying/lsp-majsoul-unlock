package android.os;

public class SystemClock {
    public static long uptimeMillis() { return System.nanoTime() / 1_000_000L; }
    public static long elapsedRealtime() { return uptimeMillis(); }
    public static void sleep(long ms) {}
}
