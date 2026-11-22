# LAN Video Cast — BUILD PLAN

GOAL: Android app jo (1) phone par video play kare aur usi video ko local WiFi par PC ke browser mein live play kare, aur (2) Screen Cast mode mein poori phone screen ko PC par real-time mirror kare (MediaProjection + MJPEG). Phone = HTTP stream server, PC = receiver page.

PLAN: apk skill (GitHub Actions cloud build).

## Steps
- [x] 1. Requirements + architecture lock (native Java, embedded HTTP server, status API)
- [x] 2. Memory/project notes save
- [x] 3. Gradle scaffold + GitHub Actions workflow
- [x] 4. MainActivity + video picker + player + foreground service
- [x] 5. Embedded HTTP server (file streaming Range + /status + /control + MJPEG screen stream)
- [x] 6. MediaProjection screen capture service (permission dialog, virtual display, JPEG loop)
- [x] 7. PC receiver HTML page (2 tabs: synced video player + live screen MJPEG)
- [x] 8. UI design system (dark mode, accent #2563EB, adaptive icon, empty/loading/error states)
- [x] 9. Push to GitHub → watch build → fix if failed (4 builds; fixes: lambda final vars, release permissions)
- [x] 10. Deliver APK link + usage steps

## Key decisions (22 Nov 2026)
- Package: `com.kamran.lancast`, repo `lancast`, app name "LAN Video Cast".
- Port 8977, HTTP server binds 0.0.0.0. PC opens `http://<phone-ip>:8977`.
- Modes: (1) Video Cast — synced file playback; (2) Screen Cast — MediaProjection → JPEG frames → MJPEG stream.
- Single accent blue #2563EB, dark theme (media/connection mood).
- No third-party server lib needed — plain `ServerSocket` + threads (fewer deps, reliable).
- HTTP Range support required so PC `<video>` can seek.
- Screen Cast uses MediaProjection permission flow (API >= 29 requires foreground service with type mediaProjection).

## Result (22 Nov 2026)
- Release `apk-4`: https://github.com/paklatestinformation-cyber/lancast/releases/download/apk-4/app-debug.apk
- Green build run: 45039058987.

## NOW
- Delivered to user; awaiting their test feedback.
