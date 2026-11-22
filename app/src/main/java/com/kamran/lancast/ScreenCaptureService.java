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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
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

/** Captures phone screen (MJPEG frames) + app audio (PCM) via MediaProjection. */
public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "lancast_screen";
    private static final int NOTIF_ID = 77;
    private static final int MAX_W = 960;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile boolean capturing = false;

    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean audioRunning = false;
    private int captureW = 0, captureH = 0, dpi = 320;

    private final ImageReader.OnImageAvailableListener imageListener = reader -> {
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
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(EXTRA_DATA)) { stopSelf(); return START_NOT_STICKY; }
        try { startForegroundNotification(); } catch (Exception e) {
            stopSelf(); return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null || data == null) { stopSelf(); return START_NOT_STICKY; }
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(Looper.getMainLooper()));

        captureThread = new HandlerThread("screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        DisplayMetrics m = currentMetrics();
        setupDisplay(m.widthPixels, m.heightPixels, m.densityDpi);
        capturing = true;
        CastState.screenActive = true;
        startAudioCapture();
        captureHandler.post(rotationCheck);
        return START_STICKY;
    }

    private final Runnable rotationCheck = new Runnable() {
        @Override public void run() {
            if (!capturing) return;
            try {
                DisplayMetrics m = currentMetrics();
                int w = m.widthPixels, h = m.heightPixels;
                float scale = Math.min(1f, (float) MAX_W / w);
                int nw = Math.max(2, (int) (w * scale) & ~1);
                int nh = Math.max(2, (int) (h * scale) & ~1);
                if (nw != captureW || nh != captureH) setupDisplay(w, h, m.densityDpi);
            } catch (Exception ignored) {}
            captureHandler.postDelayed(this, 1000);
        }
    };

    private DisplayMetrics currentMetrics() {
        DisplayMetrics m = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm != null ? wm.getDefaultDisplay() : null;
        if (display != null) display.getRealMetrics(m);
        if (m.widthPixels <= 0) m.widthPixels = 1080;
        if (m.heightPixels <= 0) m.heightPixels = 1920;
        if (m.densityDpi <= 0) m.densityDpi = 320;
        return m;
    }

    /** Creates/recreates VirtualDisplay + ImageReader with current orientation dims. */
    private void setupDisplay(int rawW, int rawH, int rawDpi) {
        float scale = Math.min(1f, (float) MAX_W / rawW);
        int w = Math.max(2, (int) (rawW * scale) & ~1);
        int h = Math.max(2, (int) (rawH * scale) & ~1);
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} virtualDisplay = null; }
        if (imageReader != null) {
            try { imageReader.setOnImageAvailableListener(null, null); imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        captureW = w; captureH = h; dpi = rawDpi;
        CastState.screenWidth = w;
        CastState.screenHeight = h;
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(imageListener, captureHandler);
        virtualDisplay = projection.createVirtualDisplay("LANVideoCast", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, captureHandler);
    }

    private void startAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || projection == null) return;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            final int rate = 44100;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(min > 0 ? min : 4096, rate / 5);
            AudioRecord rec = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); return; }
            audioRecord = rec;
            CastState.audioRate = rate;
            CastState.audioActive = true;
            audioRunning = true;
            audioThread = new Thread(() -> {
                byte[] data = new byte[Math.max(2048, bufSize / 4)];
                while (audioRunning && audioRecord != null) {
                    int n = audioRecord.read(data, 0, data.length);
                    if (n > 0) AudioBridge.INSTANCE.write(data, n);
                    else if (n < 0) break;
                }
            }, "audio-capture");
            audioThread.start();
        } catch (Throwable t) {
            CastState.audioActive = false;
        }
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
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Screen Cast chal raha hai")
                .setContentText("PC par screen dekhne ke liye browser kholein")
                .setContentIntent(pi).setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else startForeground(NOTIF_ID, b.build());
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
        audioRunning = false;
        CastState.screenActive = false;
        CastState.audioActive = false;
        AudioBridge.INSTANCE.clear();
        CastState.publishFrame(null);
        if (audioRecord != null) { try { audioRecord.stop(); } catch (Exception ignored) {} try { audioRecord.release(); } catch (Exception ignored) {} }
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} }
        if (imageReader != null) { try { imageReader.close(); } catch (Exception ignored) {} }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} }
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
