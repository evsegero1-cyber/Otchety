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
    public static final String FILE_NAME = "otchet.png";

    public static File shareFile(android.content.Context ctx) {
        File dir = new File(ctx.getCacheDir(), "share");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, FILE_NAME);
    }

    public static Uri shareUri() {
        return Uri.parse("content://" + AUTHORITY + "/" + FILE_NAME);
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (!FILE_NAME.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        return shareFile(getContext());
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
        c.addRow(new Object[]{"Отчёт.png", f.length()});
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
