package android.content;

public class ActivityNotFoundException extends RuntimeException {
    public ActivityNotFoundException() { super(); }
    public ActivityNotFoundException(String message) { super(message); }
}
