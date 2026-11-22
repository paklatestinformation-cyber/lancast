package com.kamran.lancast;

/** Thread-safe PCM ring buffer for screen audio, shared by capture service and HTTP server. */
public final class AudioBridge {
    public static final AudioBridge INSTANCE = new AudioBridge();
    private static final int CAPACITY = 44100 * 2 * 2; // ~2s of 16-bit mono @44.1kHz

    private final byte[] buf = new byte[CAPACITY];
    private int head = 0, tail = 0, size = 0;
    private final Object lock = new Object();

    public void write(byte[] data, int len) {
        synchronized (lock) {
            for (int i = 0; i < len; i++) {
                if (size >= CAPACITY) { head = (head + 1) % CAPACITY; size--; }
                buf[tail] = data[i];
                tail = (tail + 1) % CAPACITY;
                size++;
            }
            lock.notifyAll();
        }
    }

    public byte[] readBlocking(int max) {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 1500;
            while (size == 0 && System.currentTimeMillis() < deadline) {
                try { lock.wait(100); } catch (InterruptedException e) { break; }
            }
            int n = Math.min(size, max);
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                out[i] = buf[head];
                head = (head + 1) % CAPACITY;
                size--;
            }
            return out;
        }
    }

    public void clear() {
        synchronized (lock) { head = 0; tail = 0; size = 0; }
    }

    private AudioBridge() {}
}
