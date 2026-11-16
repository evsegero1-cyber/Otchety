package ru.sergey.otchety;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1;
    private static final String LAST_FILE = "last_report.bin";

    private WebView web;
    private boolean pageReady = false;
    private Intent pendingIntent = null;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("otchety", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (pageReady) return;
                pageReady = true;
                handleIntent(pendingIntent != null ? pendingIntent : getIntent());
                pendingIntent = null;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (pageReady) handleIntent(intent);
        else pendingIntent = intent;
    }

    private void handleIntent(Intent intent) {
        Uri uri = null;
        if (intent != null) {
            String action = intent.getAction();
            if (Intent.ACTION_VIEW.equals(action)) {
                uri = intent.getData();
            } else if (Intent.ACTION_SEND.equals(action)) {
                Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (extra instanceof Uri) uri = (Uri) extra;
            }
        }
        if (uri != null) {
            // чтобы при повторном показе не открывать тот же файл заново
            intent.setAction(Intent.ACTION_MAIN);
            loadUri(uri);
        } else {
            notifyJs();
        }
    }

    private void loadUri(final Uri uri) {
        io.execute(() -> {
            try {
                runOnUiThread(() -> web.evaluateJavascript("window.onLoading && window.onLoading()", null));
                String name = queryName(uri);
                File incoming = new File(getCacheDir(), "incoming.bin");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(incoming)) {
                    if (in == null) throw new Exception("Файл не открылся");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                File last = new File(getFilesDir(), LAST_FILE);
                try {
                    slimCopy(incoming, last);
                } catch (Exception notZip) {
                    // старый формат .xls (не zip) — берём как есть
                    copyFile(incoming, last);
                }
                //noinspection ResultOfMethodCallIgnored
                incoming.delete();
                prefs.edit().putString("name", name).apply();
                runOnUiThread(this::notifyJs);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Не удалось открыть файл: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    notifyJs();
                });
            }
        });
    }

    // Тяжёлые части xlsx, которые не нужны для просмотра: кэши сводных таблиц (десятки МБ),
    // графики, срезы, настройки печати. Без них файл открывается быстро и не съедает память.
    private static final String[] SKIP = {
            "xl/pivotCache/", "xl/pivotTables/", "xl/charts/", "xl/drawings/", "xl/slicers/",
            "xl/slicerCaches/", "xl/printerSettings/", "xl/calcChain.xml", "xl/media/",
            "xl/timelines/", "xl/timelineCaches/", "xl/externalLinks/", "xl/worksheets/_rels/",
            "docProps/thumbnail"};

    private static final String TOO_BIG_SHEET =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
            + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Этот лист слишком большой для просмотра на телефоне</t></is></c></row>"
            + "</sheetData></worksheet>";

    private static void slimCopy(File src, File dst) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(src);
             java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new FileOutputStream(dst))) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            byte[] buf = new byte[65536];
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                boolean skip = false;
                for (String p : SKIP) if (name.startsWith(p)) { skip = true; break; }
                if (skip) continue;
                zo.putNextEntry(new java.util.zip.ZipEntry(name));
                if (name.startsWith("xl/worksheets/sheet") && e.getSize() > 8_000_000L) {
                    zo.write(TOO_BIG_SHEET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    try (InputStream in = zf.getInputStream(e)) {
                        int n;
                        while ((n = in.read(buf)) > 0) zo.write(buf, 0, n);
                    }
                }
                zo.closeEntry();
            }
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private String queryName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "Отчёт" : name;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private void notifyJs() {
        web.evaluateJavascript("window.onFileReady && window.onFileReady()", null);
    }

    private void applyFullscreen(boolean on) {
        // «Фото» — всегда альбомная ориентация, при выходе — как было (по датчику)
        setRequestedOrientation(on
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = on
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (on) {
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.systemBars());
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            int flags = on
                    ? (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                    : 0;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadUri(data.getData());
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Сначала спрашиваем страницу: если открыт режим «фото» — она сама его закроет
        web.evaluateJavascript("window.onBack ? window.onBack() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    /** Методы, которые вызывает страница index.html */
    private class Bridge {
        @JavascriptInterface
        public String getFile() {
            File f = new File(getFilesDir(), LAST_FILE);
            if (!f.exists()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                return Base64.encodeToString(readAll(in), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void shareImages(String joined, String caption) {
            final java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            try {
                String[] parts = joined.split(",");
                for (int i = 0; i < parts.length; i++) {
                    byte[] png = Base64.decode(parts[i], Base64.DEFAULT);
                    try (FileOutputStream out = new FileOutputStream(ShareProvider.shareFile(MainActivity.this, i + 1))) {
                        out.write(png);
                    }
                    uris.add(ShareProvider.shareUri(i + 1));
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Не удалось сохранить картинку: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> {
                Intent send;
                if (uris.size() == 1) {
                    send = new Intent(Intent.ACTION_SEND);
                    send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                } else {
                    send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                }
                send.setType("image/png");
                send.putExtra(Intent.EXTRA_TEXT, caption);   // подпись «Отчет за …»
                ClipData clip = ClipData.newRawUri("", uris.get(0));
                for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                send.setClipData(clip);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(send, "Отправить отчёт"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Нет приложения, чтобы отправить", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void closeFile() {
            new File(getFilesDir(), LAST_FILE).delete();
            prefs.edit().remove("name").apply();
        }

        @JavascriptInterface
        public String getFileName() {
            return prefs.getString("name", "");
        }

        @JavascriptInterface
        public void openPicker() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel",
                        "application/octet-stream"});
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Нет приложения для выбора файлов", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void setFullscreen(boolean on) {
            runOnUiThread(() -> applyFullscreen(on));
        }
    }
}
