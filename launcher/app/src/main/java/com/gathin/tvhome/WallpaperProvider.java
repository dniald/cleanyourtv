package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Collections;
import java.util.List;

/**
 * Read-only view of the wallpaper collection Home is showing, for the Solat screensaver.
 *
 * <p>The theme files live in this app's external files directory, which Android 11+ hides from other apps,
 * and {@link ThemeProvider} is write-only for the adb shell. This provider lets the Solat dream use the same
 * pictures without copying them: no INTERNET, no storage permission, no new launcher permission. It can serve
 * only the files {@link Config#wallpapers} already lists, so no file outside the wallpaper collection is
 * reachable, and it never writes.
 *
 * <p>The contract, for {@code com.gathin.tv.solat.WallpaperSource}:
 * <pre>
 *   content://com.gathin.tvhome.wallpapers/wallpapers        one row per picture, in Home's sorted order:
 *                                                           _id (long), name (String), size (long)
 *   content://com.gathin.tvhome.wallpapers/state             one row: name (String, "" when there is none or
 *                                                           the theme turns wallpaper off), changed (long, when
 *                                                           Home switched to it), interval_minutes (int),
 *                                                           order ("sequential"/"shuffle"), enabled (int 0/1),
 *                                                           count (int)
 *   content://com.gathin.tvhome.wallpapers/image/&lt;name&gt;    the picture, read-only
 * </pre>
 *
 * <p>It is exported on purpose: the Solat app is a different package and the two apps do not share a
 * signature, so a permission would have to be defined and granted on both sides. The data is the theme's
 * public wallpaper, not user data.
 */
public class WallpaperProvider extends ContentProvider {

    /** Fixed, not {@code ${applicationId}}: the Solat app has to name one authority on every launcher build. */
    static final String AUTHORITY = "com.gathin.tvhome.wallpapers";

    private static final String PATH_WALLPAPERS = "wallpapers";
    private static final String PATH_STATE = "state";
    private static final String PATH_IMAGE = "image";

    /** WallpaperPlayer's playback state, which is how the dream learns what Home is showing. */
    private static final String PREFERENCES = "wallpaper_rotation";
    private static final String PREF_IMAGE = "image";
    private static final String PREF_CHANGED = "changed";

    private static final String TYPE_COLLECTION = "vnd.android.cursor.dir/vnd.com.gathin.tvhome.wallpaper";
    private static final String TYPE_STATE = "vnd.android.cursor.item/vnd.com.gathin.tvhome.wallpaper-state";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        Context context = getContext();
        List<String> segments = uri == null ? null : uri.getPathSegments();
        if (context == null || segments == null || segments.isEmpty()) return null;
        String path = segments.get(0);
        if (!PATH_WALLPAPERS.equals(path) && !PATH_STATE.equals(path)) return null;

        Config config = config(context);
        List<File> images = config == null ? Collections.emptyList() : config.wallpapers;

        if (PATH_WALLPAPERS.equals(path)) {
            MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "name", "size"});
            for (int i = 0; i < images.size(); i++) {
                File image = images.get(i);
                cursor.addRow(new Object[]{(long) i, image.getName(), image.length()});
            }
            return cursor;
        }

        // The state row: the picture Home last put on screen and how Home rotates the collection. A
        // filename that is no longer in the collection (the theme changed) falls back to the first
        // picture, which is what Home's next start would show, so the dream still starts on a real image.
        String current = "";
        long changed = 0;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            changed = prefs.getLong(PREF_CHANGED, 0);
            String saved = prefs.getString(PREF_IMAGE, "");
            if (saved != null && WallpaperRotation.find(images, saved) != null) current = saved;
        } catch (RuntimeException ignored) {
            // Unreadable preferences: the first picture below is still a usable answer.
        }
        if (current.isEmpty() && !images.isEmpty()) current = images.get(0).getName();

        MatrixCursor cursor = new MatrixCursor(new String[]{
                "name", "changed", "interval_minutes", "order", "enabled", "count"});
        cursor.addRow(new Object[]{
                current,
                Math.max(0, changed),
                config == null ? 0 : config.wallpaperRotationMinutes,
                config == null ? WallpaperRotation.ORDER_SEQUENTIAL : config.wallpaperOrder,
                images.isEmpty() ? 0 : 1,
                images.size()});
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> segments = uri == null ? null : uri.getPathSegments();
        if (segments == null || segments.size() != 2 || !PATH_IMAGE.equals(segments.get(0))) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        // Read-only provider: a caller that asks for write access gets nothing.
        if (mode == null || !mode.equals("r")) throw new FileNotFoundException("Read-only: " + mode);

        Context context = getContext();
        File file = null;
        if (context != null) {
            Config config = config(context);
            if (config != null) file = WallpaperRotation.find(config.wallpapers, segments.get(1));
        }
        if (file == null || !file.isFile() || file.length() <= 0) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        List<String> segments = uri == null ? null : uri.getPathSegments();
        if (segments == null || segments.isEmpty()) return null;
        if (PATH_WALLPAPERS.equals(segments.get(0))) return TYPE_COLLECTION;
        if (PATH_STATE.equals(segments.get(0))) return TYPE_STATE;
        return null;
    }

    /** The same resolved collection Home draws from, or null when the theme cannot be read. */
    private static Config config(Context context) {
        try {
            return Config.load(context);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    // Read-only: Home owns the files and the rotation state.
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
