package com.kamran.lancast;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes uncaught-exception details so launch crashes can be diagnosed without adb. */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final Thread.UncaughtExceptionHandler defaultHandler;
    private final Context context;

    private CrashHandler(Context context) {
        this.context = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name = "crash-" + stamp + ".txt";
        StringBuilder body = new StringBuilder();
        body.append("when: ").append(new Date().toString()).append('\n');
        body.append("thread: ").append(thread.getName()).append('\n');
        body.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");
        java.io.StringWriter sw = new java.io.StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        body.append(sw.toString());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Lancast");
                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os != null) os.write(body.toString().getBytes("UTF-8"));
                    }
                }
            } catch (Exception ignored) {}
        } else {
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "Download/Lancast");
                if (!dir.exists()) dir.mkdirs();
                try (FileWriter fw = new FileWriter(new File(dir, name))) {
                    fw.write(body.toString());
                }
            } catch (Exception ignored) {}
        }
        if (defaultHandler != null) defaultHandler.uncaughtException(thread, ex);
        else System.exit(2);
    }
}
