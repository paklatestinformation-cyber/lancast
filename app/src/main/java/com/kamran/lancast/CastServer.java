package com.kamran.lancast;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny HTTP server: serves the PC receiver page, video file with Range, status API and MJPEG screen stream. */
public class CastServer {
    private static final String TAG = "CastServer";
    private final Context context;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private byte[] receiverPage;

    public CastServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (running) return;
        try {
            receiverPage = loadReceiverPage();
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", 8977));
            running = true;
            CastState.serverRunning = true;
            acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        pool.execute(() -> handle(client));
                    } catch (IOException e) {
                        if (running) { /* keep accepting */ }
                    }
                }
            }, "cast-accept");
            acceptThread.start();
        } catch (IOException e) {
            running = false;
            CastState.serverRunning = false;
        }
    }

    public void stop() {
        running = false;
        CastState.serverRunning = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private byte[] loadReceiverPage() {
        try (InputStream in = context.getAssets().open("receiver.html");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return "<h1>Receiver page missing</h1>".getBytes(StandardCharsets.UTF_8);
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }

            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";

            if ("/".equals(path) || "/index.html".equals(path) || "/receiver.html".equals(path)) {
                sendBytes(client, 200, "text/html; charset=utf-8", receiverPage);
            } else if ("/favicon.ico".equals(path)) {
                sendBytes(client, 204, "image/x-icon", new byte[0]);
            } else if ("/status".equals(path)) {
                sendBytes(client, 200, "application/json; charset=utf-8", statusJson().getBytes(StandardCharsets.UTF_8));
            } else if ("/control".equals(path)) {
                handleControl(query);
                sendBytes(client, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            } else if ("/screen".equals(path)) {
                streamScreen(client);
            } else if ("/screen.jpg".equals(path)) {
                byte[] frame = CastState.latestFrame();
                if (frame == null) sendBytes(client, 204, "image/jpeg", new byte[0]);
                else sendBytes(client, 200, "image/jpeg", frame);
            } else if ("/video".equals(path)) {
                streamVideo(client, method, headers.get("range"));
            } else {
                sendBytes(client, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    private void handleControl(String query) {
        Map<String, String> q = parseQuery(query);
        String action = q.get("action");
        if (action == null) return;
        if ("seek".equals(action) && q.containsKey("pos")) {
            CastState.controlCommand = "seek:" + q.get("pos");
        } else if ("play".equals(action) || "pause".equals(action)) {
            CastState.controlCommand = action;
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return map;
    }

    private String statusJson() {
        String name = CastState.videoName == null ? "" : CastState.videoName.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"mode\":" + CastState.mode
                + ",\"videoReady\":" + (CastState.videoPath != null)
                + ",\"videoPlaying\":" + CastState.videoPlaying
                + ",\"videoPosition\":" + CastState.videoPositionMs
                + ",\"videoDuration\":" + CastState.videoDurationMs
                + ",\"videoName\":\"" + name + "\""
                + ",\"screenActive\":" + CastState.screenActive
                + ",\"screenWidth\":" + CastState.screenWidth
                + ",\"screenHeight\":" + CastState.screenHeight
                + ",\"frameSeq\":" + CastState.frameSeq() + "}";
    }

    private void streamVideo(Socket client, String method, String range) throws IOException {
        String path = CastState.videoPath;
        if (path == null) {
            sendBytes(client, 404, "text/plain", "No video selected".getBytes(StandardCharsets.UTF_8));
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            sendBytes(client, 404, "text/plain", "Video not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        long size = file.length();
        long start = 0, end = size - 1;
        boolean partial = false;
        if (range != null && range.toLowerCase().startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    if (dash > 0) start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) end = Long.parseLong(spec.substring(dash + 1));
                    else end = size - 1;
                    if (start < 0 || start >= size || end >= size || end < start) {
                        sendBytes(client, 416, "text/plain", "Invalid range".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    partial = true;
                } catch (NumberFormatException e) { start = 0; end = size - 1; }
            }
        }
        long length = end - start + 1;
        String type = contentType(path);
        OutputStream out = client.getOutputStream();
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Connection: close\r\n");
        if (partial) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        head.append("Cache-Control: no-store\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
        if ("HEAD".equals(method)) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            if (start > 0) skipFully(fis, start);
            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = fis.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (read < 0) break;
                out.write(buf, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    private void streamScreen(Socket client) throws IOException {
        OutputStream out = client.getOutputStream();
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        long lastSeq = -1;
        long lastSent = 0;
        while (running && !client.isClosed()) {
            byte[] frame = CastState.latestFrame();
            long seq = CastState.frameSeq();
            long now = System.currentTimeMillis();
            if (frame != null && seq != lastSeq && now - lastSent >= 50) {
                String part = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
                out.write(part.getBytes(StandardCharsets.US_ASCII));
                out.write(frame);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                lastSeq = seq;
                lastSent = now;
            } else {
                try { Thread.sleep(30); } catch (InterruptedException e) { break; }
            }
        }
    }

    private static void skipFully(FileInputStream fis, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = fis.skip(left);
            if (skipped <= 0) { if (fis.read() < 0) break; left--; }
            else left -= skipped;
        }
    }

    private static String contentType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".mkv")) return "video/x-matroska";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".avi")) return "video/x-msvideo";
        if (p.endsWith(".mov")) return "video/quicktime";
        if (p.endsWith(".3gp")) return "video/3gpp";
        return "video/mp4";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 8192) break;
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    private static void sendBytes(Socket client, int code, String type, byte[] body) throws IOException {
        OutputStream out = client.getOutputStream();
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
