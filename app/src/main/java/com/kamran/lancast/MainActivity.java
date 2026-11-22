package com.kamran.lancast;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends AppCompatActivity {
    private VideoView videoView;
    private TextView videoName, timeCur, timeDur, screenStatus, linkText, ipText, statusChip;
    private SeekBar seekBar;
    private Button btnPlay, btnPick, btnScreenStart, btnScreenStop, btnCopy;
    private android.view.View videoPlaceholder;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean seeking = false;
    private String lanUrl = "http://<phone-ip>:8977";

    private final ActivityResultLauncher<Intent> pickLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri == null) return;
                copyVideo(uri);
            });

    private final ActivityResultLauncher<Intent> screenLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    Toast.makeText(this, "Screen cast permission nahi mila", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent svc = new Intent(this, ScreenCaptureService.class);
                svc.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.getResultCode());
                svc.putExtra(ScreenCaptureService.EXTRA_DATA, result.getData());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
                else startService(svc);
                CastState.mode = CastState.MODE_SCREEN;
                Toast.makeText(this, "Screen cast start ho gaya", Toast.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashHandler.install(this);
        setContentView(R.layout.activity_main);

        videoView = findViewById(R.id.videoView);
        videoPlaceholder = findViewById(R.id.videoPlaceholder);
        videoName = findViewById(R.id.videoName);
        timeCur = findViewById(R.id.timeCur);
        timeDur = findViewById(R.id.timeDur);
        seekBar = findViewById(R.id.seekBar);
        btnPlay = findViewById(R.id.btnPlay);
        btnPick = findViewById(R.id.btnPick);
        btnScreenStart = findViewById(R.id.btnScreenStart);
        btnScreenStop = findViewById(R.id.btnScreenStop);
        btnCopy = findViewById(R.id.btnCopy);
        screenStatus = findViewById(R.id.screenStatus);
        linkText = findViewById(R.id.linkText);
        ipText = findViewById(R.id.ipText);
        statusChip = findViewById(R.id.statusChip);

        String ip = getLanIp();
        lanUrl = "http://" + ip + ":8977";
        linkText.setText(lanUrl);
        ipText.setText("Phone IP: " + ip + "  |  Port: 8977");

        askNotificationPermission();

        Intent svc = new Intent(this, CastService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);

        btnPick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            pickLauncher.launch(intent);
        });

        btnPlay.setOnClickListener(v -> {
            if (!videoView.isPlaying()) {
                videoView.start();
                btnPlay.setText("Pause");
                CastState.sendCommand("play");
            } else {
                videoView.pause();
                btnPlay.setText("Play");
                CastState.sendCommand("pause");
            }
        });

        btnScreenStart.setOnClickListener(v -> {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm != null) screenLauncher.launch(mpm.createScreenCaptureIntent());
        });

        btnScreenStop.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            CastState.mode = CastState.MODE_NONE;
            Toast.makeText(this, "Screen cast band", Toast.LENGTH_SHORT).show();
        });

        btnCopy.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("LAN Video Cast", lanUrl));
                Toast.makeText(this, "Link copy ho gaya", Toast.LENGTH_SHORT).show();
            }
        });

        videoView.setOnPreparedListener(mp -> {
            videoPlaceholder.setVisibility(android.view.View.GONE);
            videoView.setVisibility(android.view.View.VISIBLE);
            btnPlay.setEnabled(true);
            CastState.videoDurationMs = videoView.getDuration();
            CastState.videoPlaying = true;
            videoView.start();
            btnPlay.setText("Pause");
            CastState.sendCommand("play");
        });

        videoView.setOnCompletionListener(mp -> {
            CastState.videoPlaying = false;
            btnPlay.setText("Play");
            CastState.sendCommand("pause");
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Video play nahi ho saka", Toast.LENGTH_SHORT).show();
            return false;
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (CastState.videoDurationMs > 0) {
                    int pos = (int) (CastState.videoDurationMs * s.getProgress() / 1000);
                    videoView.seekTo(pos);
                    CastState.sendCommand("seek:" + pos);
                }
                seeking = false;
            }
        });

        startUiLoop();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
            }
        }
    }

    private void copyVideo(Uri uri) {
        ContentResolver cr = getContentResolver();
        String name = "video.mp4";
        try (android.database.Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.getString(idx) != null) name = c.getString(idx);
            }
        } catch (Exception ignored) {}

        final String finalDisplayName = name;
        Toast.makeText(this, "Video load ho raha hai...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File out = new File(getCacheDir(), "cast_video_" + System.currentTimeMillis() + ".mp4");
            try (InputStream in = cr.openInputStream(uri); FileOutputStream fos = new FileOutputStream(out)) {
                if (in == null) throw new IllegalStateException("Cannot open video");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                final String finalName = finalDisplayName;
                final String path = out.getAbsolutePath();
                runOnUiThread(() -> {
                    CastState.videoPath = path;
                    CastState.videoName = finalName;
                    CastState.videoPlaying = false;
                    CastState.videoPositionMs = 0;
                    CastState.videoDurationMs = 0;
                    CastState.mode = CastState.MODE_VIDEO;
                    videoName.setText(finalName);
                    videoView.setVideoPath(path);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Video copy fail", Toast.LENGTH_SHORT).show());
            }
        }, "video-copy").start();
    }

    private void startUiLoop() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (videoView.isPlaying()) {
                    CastState.videoPlaying = true;
                    CastState.videoPositionMs = videoView.getCurrentPosition();
                    int dur = videoView.getDuration();
                    if (dur > 0) {
                        CastState.videoDurationMs = dur;
                        if (!seeking) seekBar.setProgress((int) (videoView.getCurrentPosition() * 1000L / dur));
                        timeDur.setText(fmt(dur));
                    }
                    timeCur.setText(fmt(videoView.getCurrentPosition()));
                } else if (CastState.videoPath != null) {
                    CastState.videoPlaying = false;
                }

                String cmd = CastState.controlCommand;
                if (cmd != null) {
                    CastState.controlCommand = null;
                    if ("play".equals(cmd) && !videoView.isPlaying()) { videoView.start(); btnPlay.setText("Pause"); }
                    else if ("pause".equals(cmd) && videoView.isPlaying()) { videoView.pause(); btnPlay.setText("Play"); }
                    else if (cmd.startsWith("seek:")) {
                        try { videoView.seekTo(Integer.parseInt(cmd.substring(5))); } catch (Exception ignored) {}
                    }
                    // PC se aaya command bhi phone state me publish karo, warna PC dobara seek karega
                    CastState.sendCommand(cmd);
                }

                boolean live = CastState.serverRunning;
                statusChip.setText(live ? "LIVE" : "OFFLINE");
                statusChip.setBackgroundResource(live ? R.drawable.chip_on : R.drawable.chip_off);
                if (CastState.screenActive) {
                    screenStatus.setText("Screen cast CHAL RAHA HAI • " + CastState.screenWidth + "x" + CastState.screenHeight);
                    btnScreenStart.setEnabled(false);
                    btnScreenStop.setEnabled(true);
                } else {
                    screenStatus.setText("Screen cast band hai");
                    btnScreenStart.setEnabled(true);
                    btnScreenStop.setEnabled(false);
                }
                handler.postDelayed(this, 300);
            }
        }, 300);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private String getLanIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.isSiteLocalAddress() && a.getHostAddress().indexOf(':') < 0)
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int i = wm.getConnectionInfo().getIpAddress();
                if (i != 0) return String.format("%d.%d.%d.%d", i & 0xff, (i >> 8) & 0xff, (i >> 16) & 0xff, (i >> 24) & 0xff);
            }
        } catch (Exception ignored) {}
        return "192.168.1.100";
    }
}
