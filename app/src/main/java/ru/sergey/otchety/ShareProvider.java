package ru.sergey.otchety;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Отдаёт картинку отчёта другим приложениям (Telegram, WhatsApp) при «Поделиться». */
public class ShareProvider extends ContentProvider {

    public static final String AUTHORITY = "ru.sergey.otchety.share";
    private static String name(int n) {
        return "otchet" + n + ".png";
    }

    public static File shareFile(android.content.Context ctx, int n) {
        File dir = new File(ctx.getCacheDir(), "share");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, name(n));
    }

    public static Uri shareUri(int n) {
        return Uri.parse("content://" + AUTHORITY + "/" + name(n));
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String last = uri.getLastPathSegment();
        if (last == null || !last.matches("otchet[0-9]{1,2}\\.png")) throw new FileNotFoundException(uri.toString());
        return new File(new File(getContext().getCacheDir(), "share"), last);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
