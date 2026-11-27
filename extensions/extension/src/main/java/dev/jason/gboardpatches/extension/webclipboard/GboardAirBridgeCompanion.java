package dev.jason.gboardpatches.extension.webclipboard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Integrated AirBridge companion running directly inside Gboard's Web Clipboard service
 * (zero separate Android app, zero extra manifest permissions, ultra-low battery):
 * - Low-power UDP discovery beacon (:8772) for AirBridge macOS auto-reconnect
 * - Automatic Screenshot -> Mac Clipboard observer (MediaStore.Images.Media)
 * - Direct file receive from Mac Drop Shelf (POST /api/upload -> MediaStore.Downloads)
 *   with live progress & tap-to-open notification
 * - Media gallery & Downloads browsing/streaming (GET /api/files, GET /api/file)
 */
final class GboardAirBridgeCompanion {
    private static final String TAG = "GboardAirBridge";
    private static final int DISCOVERY_UDP_PORT = 8772;
    private static final String TRANSFER_CHANNEL_ID = "gboard_airbridge_transfer";
    private static final int TRANSFER_NOTI_ID = 8779;
    static final String PREFS_NAME = "gboard_airbridge_prefs";
    static final String KEY_LAST_MAC_IP = "last_mac_ip";
    static final String KEY_LAST_MAC_PORT = "last_mac_port";
    static final int DEFAULT_MAC_RECEIVER_PORT = 8773;

    interface ScreenshotCallback {
        void onScreenshotCaptured(byte[] imageBytes, String mimeType);
    }

    private final Context appContext;
    private final ScreenshotCallback screenshotCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioPool = Executors.newCachedThreadPool();
    private final Map<String, long[]> failedAttemptsByIp = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private volatile int portalPort = WebClipboardPreferences.DEFAULT_PORT;
    private Thread beaconThread;
    private volatile long lastSyncedScreenshotId = -1L;
    private Runnable dismissTransferPillRunnable;

    private final Runnable checkScreenshotRunnable = () -> {
        if (!running || ioPool.isShutdown()) {
            return;
        }
        try {
            ioPool.execute(this::checkLatestScreenshot);
        } catch (Throwable ignored) {
        }
    };
    private final ContentObserver screenshotObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            mainHandler.removeCallbacks(checkScreenshotRunnable);
            mainHandler.postDelayed(checkScreenshotRunnable, 450L);
        }
    };

    GboardAirBridgeCompanion(Context context, ScreenshotCallback screenshotCallback) {
        this.appContext = context.getApplicationContext();
        this.screenshotCallback = screenshotCallback;
    }

    void setPortalPort(int port) {
        if (port > 0) {
            this.portalPort = port;
        }
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        ensureTransferChannel();
        startScreenshotObserver();
        startUdpBeacon();
    }

    void stop() {
        running = false;
        mainHandler.removeCallbacks(checkScreenshotRunnable);
        try {
            appContext.getContentResolver().unregisterContentObserver(screenshotObserver);
        } catch (Throwable ignored) {
        }
        if (beaconThread != null) {
            beaconThread.interrupt();
            beaconThread = null;
        }
        ioPool.shutdownNow();
    }

    static void rememberMacEndpoint(Context ctx, String ip, int port) {
        if (ctx == null || ip == null || ip.isEmpty() || "unknown".equals(ip)
                || "127.0.0.1".equals(ip) || "::1".equals(ip) || port <= 0) {
            return;
        }
        try {
            SharedPreferences prefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String prevIp = prefs.getString(KEY_LAST_MAC_IP, "");
            int prevPort = prefs.getInt(KEY_LAST_MAC_PORT, 0);
            if (!ip.equals(prevIp) || port != prevPort) {
                prefs.edit()
                        .putString(KEY_LAST_MAC_IP, ip)
                        .putInt(KEY_LAST_MAC_PORT, port)
                        .apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private String getStableDeviceId() {
        SharedPreferences prefs = appContext.getSharedPreferences("gboard_airbridge_prefs", Context.MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null || id.isEmpty()) {
            id = "gb-" + UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private String getDeviceModel() {
        String m = Build.MANUFACTURER != null ? Build.MANUFACTURER : "Android";
        String model = Build.MODEL != null ? Build.MODEL : "Phone";
        return m + " " + model;
    }

    private void startScreenshotObserver() {
        ioPool.execute(() -> {
            long[] latest = queryLatestScreenshotMeta();
            if (latest != null) {
                lastSyncedScreenshotId = latest[0];
            }
        });
        mainHandler.post(() -> {
            try {
                appContext.getContentResolver().registerContentObserver(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        true,
                        screenshotObserver);
            } catch (Throwable t) {
                Log.w(TAG, "Screenshot observer registration failed", t);
            }
        });
    }

    private void checkLatestScreenshot() {
        if (!running) {
            return;
        }
        try {
            long[] meta = queryLatestScreenshotMeta();
            if (meta == null) {
                return;
            }
            long id = meta[0];
            if (id <= lastSyncedScreenshotId) {
                return;
            }
            lastSyncedScreenshotId = id;
            Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            ContentResolver resolver = appContext.getContentResolver();
            String mime = resolver.getType(uri);
            if (mime == null || !mime.startsWith("image/")) {
                mime = "image/png";
            }
            byte[] bytes = readLimitedStream(resolver.openInputStream(uri), 10 * 1024 * 1024);
            if (bytes != null && bytes.length > 0 && screenshotCallback != null) {
                screenshotCallback.onScreenshotCaptured(bytes, mime);
            }
        } catch (Throwable ignored) {
        }
    }

    private long[] queryLatestScreenshotMeta() {
        List<String> cols = new ArrayList<>();
        cols.add(MediaStore.Images.Media._ID);
        cols.add(MediaStore.Images.Media.DISPLAY_NAME);
        cols.add(MediaStore.Images.Media.DATE_ADDED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols.add(MediaStore.Images.Media.RELATIVE_PATH);
            cols.add(MediaStore.Images.Media.IS_PENDING);
        }
        try (Cursor c = appContext.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cols.toArray(new String[0]),
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null) {
                return null;
            }
            int idIdx = c.getColumnIndex(MediaStore.Images.Media._ID);
            int nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
            int pathIdx = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) : -1;
            int pendIdx = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? c.getColumnIndex(MediaStore.Images.Media.IS_PENDING) : -1;

            int checked = 0;
            while (c.moveToNext() && checked < 6) {
                checked++;
                if (pendIdx >= 0 && c.getInt(pendIdx) != 0) {
                    continue;
                }
                String name = nameIdx >= 0 ? safeLower(c.getString(nameIdx)) : "";
                String relPath = pathIdx >= 0 ? safeLower(c.getString(pathIdx)) : "";
                if (relPath.contains("airbridge") || relPath.contains("gboardsync")) {
                    continue;
                }
                if (!relPath.contains("screenshot") && !name.startsWith("screenshot")) {
                    continue;
                }
                long dateAdded = dateIdx >= 0 ? c.getLong(dateIdx) : 0L;
                long nowSec = System.currentTimeMillis() / 1000L;
                if (lastSyncedScreenshotId >= 0L && nowSec - dateAdded > 30L) {
                    return null;
                }
                return new long[]{c.getLong(idIdx), dateAdded};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void startUdpBeacon() {
        beaconThread = new Thread(() -> {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setBroadcast(true);
                int burst = 0;
                String deviceId = getStableDeviceId();
                String model = getDeviceModel();
                String name = Build.MODEL != null ? Build.MODEL : "Android";

                while (running && !Thread.currentThread().isInterrupted()) {
                    int boundPort = portalPort > 0
                            ? portalPort
                            : WebClipboardPreferences.getPort(WebClipboardPreferences.preferences(appContext));
                    byte[] payload = new JSONObject()
                            .put("app", "AirBridge")
                            .put("deviceId", deviceId)
                            .put("name", name)
                            .put("model", model)
                            .put("webPort", boundPort)
                            .put("tlsPort", 0)
                            .put("certSha256", "")
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);

                    for (InetAddress target : collectBroadcastAddresses()) {
                        try {
                            sock.send(new DatagramPacket(payload, payload.length, target, DISCOVERY_UDP_PORT));
                        } catch (Throwable ignored) {
                        }
                    }
                    long sleepMs = burst < 3 ? 2000L : 10000L;
                    if (burst < 3) {
                        burst++;
                    }
                    Thread.sleep(sleepMs);
                }
            } catch (Throwable ignored) {
            } finally {
                if (sock != null) {
                    try {
                        sock.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "GboardAirBridgeBeacon");
        beaconThread.setDaemon(true);
        beaconThread.start();
    }

    void handleHttpRequest(Socket socket, String reqLine, InputStream rawInput) {
        try (Socket conn = socket) {
            conn.setTcpNoDelay(true);
            conn.setSoTimeout(120_000);
            BufferedInputStream in = new BufferedInputStream(rawInput, 64 * 1024);
            OutputStream out = conn.getOutputStream();

            if (reqLine == null || reqLine.isEmpty()) {
                return;
            }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String rawTarget = parts[1];
            int qIdx = rawTarget.indexOf('?');
            String path = qIdx >= 0 ? rawTarget.substring(0, qIdx) : rawTarget;
            Map<String, String> query = qIdx >= 0 ? parseQuery(rawTarget.substring(qIdx + 1)) : Collections.emptyMap();

            Map<String, String> headers = new HashMap<>();
            while (true) {
                String line = readAsciiLine(in);
                if (line == null || line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }

            if ("OPTIONS".equals(method)) {
                writeResponse(out, 204, "No Content", "text/plain", new byte[0]);
                return;
            }

            // Optional rate-limit & pairing code check using Gboard Web Clipboard's pairing settings
            String remoteIp = conn.getInetAddress() != null ? conn.getInetAddress().getHostAddress() : "unknown";
            String macPortHeader = headers.get("x-airbridge-mac-port");
            if (macPortHeader != null && !macPortHeader.isEmpty()) {
                try {
                    int macPort = Integer.parseInt(macPortHeader.trim());
                    rememberMacEndpoint(appContext, remoteIp, macPort);
                } catch (Throwable ignored) {
                }
            }
            if (isLockedOut(remoteIp)) {
                writeJson(out, 429, new JSONObject().put("error", "TOO_MANY_ATTEMPTS"));
                return;
            }

            SharedPreferences prefs = WebClipboardPreferences.preferences(appContext);
            boolean pairingRequired = WebClipboardPreferences.isPairingRequired(prefs);
            String requiredCode = WebClipboardPreferences.getPairingCode(prefs);
            if (pairingRequired && requiredCode != null && !requiredCode.isEmpty()) {
                String provided = headers.get("x-airbridge-pin");
                if (provided == null) provided = headers.get("x-pairing-code");
                if (provided == null) provided = query.get("pin");
                if (provided == null) provided = query.get("token");
                if (!requiredCode.equals(provided)) {
                    recordFailure(remoteIp);
                    writeJson(out, 401, new JSONObject().put("error", "PIN_REQUIRED"));
                    return;
                }
                failedAttemptsByIp.remove(remoteIp);
            }

            if ("GET".equals(method) && "/api/status".equals(path)) {
                JSONObject json = new JSONObject()
                        .put("deviceName", Build.MODEL != null ? Build.MODEL : "Android")
                        .put("modelName", getDeviceModel())
                        .put("screenStreaming", false)
                        .put("remoteControlEnabled", false)
                        .put("storageAccessible", true)
                        .put("clipboardLatest", "");
                writeJson(out, 200, json);
                return;
            }
            if ("POST".equals(method) && "/api/pair".equals(path)) {
                writeJson(out, 200, new JSONObject()
                        .put("status", "APPROVED")
                        .put("token", requiredCode != null && !requiredCode.isEmpty() ? requiredCode : "gboard-ok"));
                return;
            }
            if ("POST".equals(method) && "/api/upload".equals(path)) {
                handleUpload(in, out, headers, query);
                return;
            }
            if ("GET".equals(method) && "/api/files".equals(path)) {
                handleListFiles(out, query.get("path"));
                return;
            }
            if ("GET".equals(method) && "/api/file".equals(path)) {
                handleGetFile(out, query.get("path"), headers.get("range"), "1".equals(query.get("download")));
                return;
            }
            if ("GET".equals(method) && "/api/clipboard".equals(path)) {
                writeJson(out, 200, new JSONObject().put("latest", "").put("history", new JSONArray()));
                return;
            }

            writeJson(out, 404, new JSONObject().put("error", "not_found"));
        } catch (Throwable ignored) {
        }
    }

    private void handleUpload(InputStream in, OutputStream out, Map<String, String> headers,
            Map<String, String> query) throws Exception {
        long contentLength = 0L;
        try {
            String cl = headers.get("content-length");
            if (cl != null) contentLength = Long.parseLong(cl.trim());
        } catch (Throwable ignored) {
        }
        String rawName = query.get("name");
        if (rawName == null || rawName.trim().isEmpty()) {
            rawName = "file_" + System.currentTimeMillis() + ".bin";
        }
        String safeName = rawName.replace('\\', '/');
        int slash = safeName.lastIndexOf('/');
        if (slash >= 0) safeName = safeName.substring(slash + 1);
        if (safeName.isEmpty()) safeName = "file.bin";

        String mime = guessMime(safeName);
        ContentResolver resolver = appContext.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirBridge");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
        }

        Uri targetUri = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        }
        if (targetUri == null) {
            writeJson(out, 500, new JSONObject().put("error", "cannot_create_download"));
            return;
        }

        long written = 0L;
        long lastNotify = 0L;
        showTransferNotification(safeName, 0, contentLength, false, null, mime);
        try (OutputStream fos = resolver.openOutputStream(targetUri)) {
            if (fos == null) {
                writeJson(out, 500, new JSONObject().put("error", "cannot_open_output"));
                return;
            }
            byte[] buf = new byte[64 * 1024];
            long remaining = contentLength;
            while (remaining > 0L) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, toRead);
                if (n <= 0) break;
                fos.write(buf, 0, n);
                remaining -= n;
                written += n;
                long now = System.currentTimeMillis();
                if (now - lastNotify >= 200L) {
                    lastNotify = now;
                    showTransferNotification(safeName, written, contentLength, false, null, mime);
                }
            }
            fos.flush();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(targetUri, done, null, null);
        }
        showTransferNotification(safeName, written, written, true, targetUri, mime);

        writeJson(out, 200, new JSONObject()
                .put("ok", true)
                .put("fileName", safeName)
                .put("size", written)
                .put("path", "Download/AirBridge/" + safeName));
    }

    private void handleListFiles(OutputStream out, String rawPath) throws Exception {
        String path = rawPath == null ? "" : rawPath.trim();
        JSONArray arr = new JSONArray();
        if (path.isEmpty()) {
            arr.put(folderJson("Ảnh & Camera", "DCIM/Camera"));
            arr.put(folderJson("Ảnh chụp màn hình", "Pictures"));
            arr.put(folderJson("Download / AirBridge", "Download/AirBridge"));
        } else if (path.startsWith("DCIM") || path.startsWith("Pictures")) {
            boolean screenshotsOnly = path.equals("Pictures");
            queryMediaImages(arr, screenshotsOnly);
        } else {
            queryMediaDownloads(arr);
        }
        writeJson(out, 200, new JSONObject().put("path", path).put("entries", arr));
    }

    private JSONObject folderJson(String name, String path) throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("path", path)
                .put("isDir", true)
                .put("size", 0)
                .put("modifiedMs", System.currentTimeMillis())
                .put("mime", "inode/directory");
    }

    private void queryMediaImages(JSONArray out, boolean screenshotsOnly) {
        String[] cols = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.MIME_TYPE,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DISPLAY_NAME
        };
        try (Cursor c = appContext.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cols,
                null,
                null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            int count = 0;
            while (c.moveToNext() && count < 120) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long size = c.getLong(2);
                long modMs = c.getLong(3) * 1000L;
                String mime = c.getString(4);
                String rel = safeLower(c.getString(5));
                if (screenshotsOnly && !rel.contains("screenshot") && !safeLower(name).startsWith("screenshot")) {
                    continue;
                }
                out.put(new JSONObject()
                        .put("name", name != null ? name : ("img_" + id + ".jpg"))
                        .put("path", "media_img/" + id + "/" + (name != null ? name : "image.jpg"))
                        .put("isDir", false)
                        .put("size", size)
                        .put("modifiedMs", modMs)
                        .put("mime", mime != null ? mime : "image/jpeg"));
                count++;
            }
        } catch (Throwable ignored) {
        }
    }

    private void queryMediaDownloads(JSONArray out) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        String[] cols = {
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
                MediaStore.Downloads.MIME_TYPE
        };
        try (Cursor c = appContext.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cols,
                null,
                null,
                MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            int count = 0;
            while (c.moveToNext() && count < 100) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long size = c.getLong(2);
                long modMs = c.getLong(3) * 1000L;
                String mime = c.getString(4);
                out.put(new JSONObject()
                        .put("name", name != null ? name : ("file_" + id))
                        .put("path", "media_dl/" + id + "/" + (name != null ? name : "file.bin"))
                        .put("isDir", false)
                        .put("size", size)
                        .put("modifiedMs", modMs)
                        .put("mime", mime != null ? mime : "application/octet-stream"));
                count++;
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleGetFile(OutputStream out, String path, String rangeHeader, boolean forceDownload) throws Exception {
        if (path == null) {
            writeJson(out, 404, new JSONObject().put("error", "not_found"));
            return;
        }
        Uri contentUri = null;
        String fileName = "file.bin";
        String[] segs = path.split("/", 3);
        if (segs.length >= 2) {
            long id = Long.parseLong(segs[1]);
            if (segs.length >= 3 && !segs[2].isEmpty()) fileName = segs[2];
            if ("media_img".equals(segs[0])) {
                contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            } else if ("media_dl".equals(segs[0]) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
            }
        }
        if (contentUri == null) {
            writeJson(out, 404, new JSONObject().put("error", "not_found"));
            return;
        }
        ContentResolver resolver = appContext.getContentResolver();
        String mime = resolver.getType(contentUri);
        if (mime == null) mime = guessMime(fileName);
        byte[] all = readLimitedStream(resolver.openInputStream(contentUri), 64 * 1024 * 1024);
        if (all == null) {
            writeJson(out, 404, new JSONObject().put("error", "unreadable"));
            return;
        }
        StringBuilder hdr = new StringBuilder();
        hdr.append("HTTP/1.1 200 OK\r\n");
        hdr.append("Content-Type: ").append(mime).append("\r\n");
        hdr.append("Content-Length: ").append(all.length).append("\r\n");
        if (forceDownload) {
            hdr.append("Content-Disposition: attachment; filename=\"")
                    .append(fileName.replace("\"", "_")).append("\"\r\n");
        }
        hdr.append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
        out.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(all);
        out.flush();
    }

    private void showTransferNotification(String fileName, long written, long total,
            boolean done, Uri openUri, String mime) {
        try {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (dismissTransferPillRunnable != null) {
                mainHandler.removeCallbacks(dismissTransferPillRunnable);
                dismissTransferPillRunnable = null;
            }

            int pct = total > 0 ? (int) Math.min(100, (written * 100L) / total) : (done ? 100 : 0);
            PendingIntent openPi = null;
            if (done && openUri != null) {
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(openUri, mime);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                openPi = PendingIntent.getActivity(
                        appContext, TRANSFER_NOTI_ID, view,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(appContext, TRANSFER_CHANNEL_ID)
                    : new Notification.Builder(appContext);
            b.setSmallIcon(done ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_sys_download)
                    .setContentTitle(done ? "Đã nhận từ Mac" : "Đang nhận từ Mac • " + pct + "%")
                    .setContentText(fileName + " (" + formatSize(done ? written : total) + ")")
                    .setOngoing(true)
                    .setOnlyAlertOnce(!done)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis());
            if (openPi != null) {
                b.setContentIntent(openPi);
            }

            boolean promotedStyle = applyLiveUpdatePromotion(
                    b,
                    done ? "Đã nhận" : (pct + "%"),
                    done ? 100 : pct,
                    done ? Color.parseColor("#34C759") : Color.parseColor("#0A84FF"));
            if (!promotedStyle && !done) {
                b.setProgress(100, pct, total <= 0);
            }
            Notification notification = b.build();
            logPromotionStatus(nm, done ? "completed" : ("progress(" + pct + "%)"), notification);
            nm.notify(TRANSFER_NOTI_ID, notification);

            if (done) {
                final PendingIntent finalOpenPi = openPi;
                final String finalSummary = "Lưu tại Download/AirBridge (" + formatSize(written) + ")";
                dismissTransferPillRunnable = () -> {
                    try {
                        Notification.Builder doneB = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? new Notification.Builder(appContext, TRANSFER_CHANNEL_ID)
                                : new Notification.Builder(appContext);
                        doneB.setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle("Đã nhận: " + fileName)
                                .setContentText(finalSummary)
                                .setOngoing(false)
                                .setOnlyAlertOnce(true)
                                .setAutoCancel(true);
                        if (finalOpenPi != null) {
                            doneB.setContentIntent(finalOpenPi);
                        }
                        nm.notify(TRANSFER_NOTI_ID, doneB.build());
                    } catch (Throwable ignored) {
                    }
                };
                mainHandler.postDelayed(dismissTransferPillRunnable, 5000L);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean applyLiveUpdatePromotion(Notification.Builder builder,
            String shortText, int progress, int accentColor) {
        if (Build.VERSION.SDK_INT < 35) {
            return false;
        }
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("android.requestPromotedOngoing", true);
            extras.putString("android.shortCriticalText", shortText);
            builder.addExtras(extras);
        } catch (Throwable ignored) {
        }
        try {
            Notification.Builder.class
                    .getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(builder, true);
        } catch (Throwable ignored) {
        }
        try {
            Notification.Builder.class
                    .getMethod("setShortCriticalText", String.class)
                    .invoke(builder, shortText);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> styleClass = Class.forName("android.app.Notification$ProgressStyle");
            Class<?> segmentClass = Class.forName("android.app.Notification$ProgressStyle$Segment");
            Object style = styleClass.getDeclaredConstructor().newInstance();
            Object segment = segmentClass.getDeclaredConstructor(int.class).newInstance(100);
            try {
                segmentClass.getMethod("setColor", int.class).invoke(segment, accentColor);
            } catch (Throwable ignored) {
            }
            styleClass.getMethod("addProgressSegment", segmentClass).invoke(style, segment);
            styleClass.getMethod("setProgress", int.class).invoke(style, progress);
            try {
                styleClass.getMethod("setStyledByProgress", boolean.class).invoke(style, true);
            } catch (Throwable ignored) {
            }
            builder.setStyle((Notification.Style) style);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void logPromotionStatus(NotificationManager nm, String stage, Notification notification) {
        try {
            Object canPost = NotificationManager.class.getMethod("canPostPromotedNotifications").invoke(nm);
            Object hasChars = Notification.class.getMethod("hasPromotableCharacteristics").invoke(notification);
            Log.i(TAG, "FluidCloud " + stage + " canPostPromoted=" + canPost + " hasPromotableChars=" + hasChars);
        } catch (Throwable ignored) {
        }
    }

    private void ensureTransferChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(TRANSFER_CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        TRANSFER_CHANNEL_ID,
                        "Gboard AirBridge Nhận tệp",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private boolean isLockedOut(String ip) {
        long[] rec = failedAttemptsByIp.get(ip);
        return rec != null && rec[1] > System.currentTimeMillis();
    }

    private void recordFailure(String ip) {
        long now = System.currentTimeMillis();
        long[] rec = failedAttemptsByIp.get(ip);
        if (rec == null) {
            rec = new long[]{0L, 0L};
            failedAttemptsByIp.put(ip, rec);
        }
        rec[0]++;
        if (rec[0] >= 5L) {
            rec[0] = 0L;
            rec[1] = now + 60_000L;
        }
    }

    private static List<InetAddress> collectBroadcastAddresses() {
        Set<InetAddress> set = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) set.add(ia.getBroadcast());
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            set.add(InetAddress.getByName("255.255.255.255"));
        } catch (Throwable ignored) {
        }
        return new ArrayList<>(set);
    }

    private static byte[] readLimitedStream(InputStream in, int maxBytes) {
        if (in == null) return null;
        try (InputStream stream = in; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int n;
            while ((n = stream.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) return null;
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readAsciiLine(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString(StandardCharsets.US_ASCII.name());
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq > 0) {
                    map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    private static void writeJson(OutputStream out, int code, JSONObject obj) throws Exception {
        byte[] body = obj.toString().getBytes(StandardCharsets.UTF_8);
        writeResponse(out, code, code == 200 ? "OK" : "Error", "application/json; charset=utf-8", body);
    }

    private static void writeResponse(OutputStream out, int code, String reason, String contentType, byte[] body) throws Exception {
        String hdr = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    private static String formatSize(long bytes) {
        double kb = bytes / 1024.0;
        double mb = kb / 1024.0;
        if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb);
        if (kb >= 1.0) return String.format(Locale.US, "%.0f KB", kb);
        return bytes + " B";
    }

    private static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "mp4": return "video/mp4";
            case "mov": return "video/quicktime";
            case "pdf": return "application/pdf";
            case "apk": return "application/vnd.android.package-archive";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m != null ? m : "application/octet-stream";
        }
    }
}
