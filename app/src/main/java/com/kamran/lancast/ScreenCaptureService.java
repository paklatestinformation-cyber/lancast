package com.kamran.lancast;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Captures the whole phone screen via MediaProjection and publishes JPEG frames to CastState. */
public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "lancast_screen";
    private static final int NOTIF_ID = 77;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile boolean capturing = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(EXTRA_DATA)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNotification();
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null || data == null) { stopSelf(); return START_NOT_STICKY; }
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(Looper.getMainLooper()));

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm != null ? wm.getDefaultDisplay() : null;
        if (display != null) display.getRealMetrics(metrics);
        int w = metrics.widthPixels > 0 ? metrics.widthPixels : 1080;
        int h = metrics.heightPixels > 0 ? metrics.heightPixels : 1920;
        int dpi = metrics.densityDpi > 0 ? metrics.densityDpi : 320;

        // Downscale to <= 960px wide for smooth WiFi streaming.
        float scale = Math.min(1f, 960f / w);
        w = Math.max(2, (int) (w * scale) & ~1);
        h = Math.max(2, (int) (h * scale) & ~1);
        CastState.screenWidth = w;
        CastState.screenHeight = h;

        final int captureW = w, captureH = h;
        captureThread = new HandlerThread("screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                if (!capturing) return;
                Bitmap bitmap = imageToBitmap(image, captureW, captureH);
                if (bitmap != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 72, bos);
                    CastState.publishFrame(bos.toByteArray());
                    bitmap.recycle();
                }
            } finally {
                image.close();
            }
        }, captureHandler);

        virtualDisplay = projection.createVirtualDisplay("LANVideoCast", captureW, captureH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, captureHandler);
        capturing = true;
        CastState.screenActive = true;
        return START_STICKY;
    }

    @SuppressLint("NewApi")
    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Screen Cast", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Screen Cast chal raha hai")
                .setContentText("PC par screen dekhne ke liye browser kholein")
                .setContentIntent(pi)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, b.build());
        }
    }

    private static Bitmap imageToBitmap(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int stride = planes[0].getRowStride();
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        ByteBuffer copy = buffer.duplicate();
        if (stride == w * 4) {
            bitmap.copyPixelsFromBuffer(copy);
        } else {
            ByteBuffer packed = ByteBuffer.allocateDirect(w * h * 4);
            byte[] row = new byte[w * 4];
            for (int y = 0; y < h; y++) {
                copy.position(y * stride);
                copy.get(row);
                packed.put(row);
            }
            packed.position(0);
            bitmap.copyPixelsFromBuffer(packed);
        }
        return bitmap;
    }

    @Override
    public void onDestroy() {
        capturing = false;
        CastState.screenActive = false;
        CastState.publishFrame(null);
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} }
        if (imageReader != null) { try { imageReader.close(); } catch (Exception ignored) {} }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} }
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
