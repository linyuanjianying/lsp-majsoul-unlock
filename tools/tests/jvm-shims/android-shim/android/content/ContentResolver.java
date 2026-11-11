package android.content;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public class ContentResolver {
    public Bundle call(Uri uri, String method, String arg, Bundle extras) { return null; }
    public Bundle call(String authority, String method, String arg, Bundle extras) { return null; }
    public final Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
}
