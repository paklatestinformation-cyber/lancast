package com.kamran.lancast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;

/** Keeps the HTTP server alive as a foreground service. */
public class CastService extends Service {
    private static final String CHANNEL_ID = "lancast_server";
    private static final int NOTIF_ID = 88;
    private CastServer server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();
        if (server == null) {
            server = new CastServer(this);
            server.start();
            // black placeholder frame so /screen.jpg never 204s before a real capture
            Bitmap bm = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.BLACK);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 50, bos);
            CastState.publishFrame(bos.toByteArray());
            bm.recycle();
        }
        return START_STICKY;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Cast Server", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("LAN Video Cast")
                .setContentText("Server PC ke liye chal raha hai")
                .setContentIntent(pi)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, b.build());
        }
    }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
