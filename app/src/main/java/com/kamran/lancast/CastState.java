package com.kamran.lancast;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Shared, thread-safe state between Activity, services and HTTP server. */
public final class CastState {
    public static final int MODE_NONE = 0;
    public static final int MODE_VIDEO = 1;
    public static final int MODE_SCREEN = 2;

    public static volatile boolean serverRunning = false;
    public static volatile int mode = MODE_NONE;
    public static volatile String videoPath = null;
    public static volatile String videoName = "";
    public static volatile boolean videoPlaying = false;
    public static volatile long videoPositionMs = 0;
    public static volatile long videoDurationMs = 0;

    public static volatile boolean screenActive = false;
    public static volatile int screenWidth = 0;
    public static volatile int screenHeight = 0;

    private static final AtomicReference<byte[]> FRAME = new AtomicReference<>();
    private static final AtomicLong FRAME_SEQ = new AtomicLong(0);

    /** control command from PC: play, pause, seek:<ms> */
    public static volatile String controlCommand = null;

    public static void publishFrame(byte[] jpeg) {
        FRAME.set(jpeg);
        FRAME_SEQ.incrementAndGet();
    }

    public static byte[] latestFrame() {
        return FRAME.get();
    }

    public static long frameSeq() {
        return FRAME_SEQ.get();
    }

    private CastState() {}
}
