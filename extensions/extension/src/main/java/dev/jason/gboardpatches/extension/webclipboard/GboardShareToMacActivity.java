package dev.jason.gboardpatches.extension.webclipboard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Transparent Android Share Sheet target ("Gửi sang Mac") embedded inside Gboard.
 * Accepts single/multiple files, photos, videos, or text/links via ACTION_SEND / ACTION_SEND_MULTIPLE
 * and streams them directly over LAN TCP Socket to AirBridge macOS (~/Downloads/AirBridge).
 */
public final class GboardShareToMacActivity extends Activity {
    private static final String TAG = "GboardShareToMac";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.width = 1;
            lp.height = 1;
            lp.alpha = 0f;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {
        }

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        String action = intent.getAction();
        List<Uri> uris = extractUris(intent, action);
        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);

        if (uris.isEmpty() && (sharedText == null || sharedText.trim().isEmpty())) {
            showToast("Không có nội dung để gửi sang Mac");
            finish();
            return;
        }

        SharedPreferences prefs = getApplicationContext().getSharedPreferences(
                GboardAirBridgeCompanion.PREFS_NAME, Context.MODE_PRIVATE);
        String macIp = prefs.getString(GboardAirBridgeCompanion.KEY_LAST_MAC_IP, "");
        int macPort = prefs.getInt(GboardAirBridgeCompanion.KEY_LAST_MAC_PORT,
                GboardAirBridgeCompanion.DEFAULT_MAC_RECEIVER_PORT);
        if (macPort <= 0) {
            macPort = GboardAirBridgeCompanion.DEFAULT_MAC_RECEIVER_PORT;
        }

        if (macIp == null || macIp.trim().isEmpty()) {
            showToast("Chưa thấy Mac — hãy mở AirBridge trên Mac trước");
            finish();
            return;
        }

        final String targetIp = macIp.trim();
        final int targetPort = macPort;

        if (!uris.isEmpty()) {
            String firstLabel = uris.size() == 1
                    ? queryFileName(getContentResolver(), uris.get(0), intent.getType())
                    : (uris.size() + " tệp");
            showToast("Đang gửi " + firstLabel + " sang Mac...");
            Thread worker = new Thread(() -> sendFilesToMac(uris, intent.getType(), targetIp, targetPort),
                    "GboardShareToMacFiles");
            worker.setDaemon(true);
            worker.start();
        } else {
            final String textToSend = sharedText;
            showToast("Đang gửi văn bản sang Mac...");
            Thread worker = new Thread(() -> sendTextToMac(textToSend, targetIp, targetPort),
                    "GboardShareToMacText");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void sendFilesToMac(List<Uri> uris, String fallbackMime, String macIp, int macPort) {
        int sentCount = 0;
        String lastName = "";
        String lastError = null;
        ContentResolver resolver = getContentResolver();

        for (Uri uri : uris) {
            String name = queryFileName(resolver, uri, fallbackMime);
            long size = queryFileSize(resolver, uri);
            lastName = name;
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) {
                    lastError = "Không đọc được " + name;
                    continue;
                }
                int status = streamFileOverSocket(macIp, macPort, name, size, in);
                if (status >= 200 && status < 300) {
                    sentCount++;
                } else {
                    lastError = "Mac trả về lỗi (" + status + ")";
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed sending " + name + " to " + macIp + ":" + macPort, t);
                lastError = "Không nối được Mac (" + macIp + ")";
                break;
            }
        }

        final int finalSent = sentCount;
        final int total = uris.size();
        final String finalName = lastName;
        final String finalErr = lastError;
        mainHandler.post(() -> {
            if (finalSent == total && total > 0) {
                if (total == 1) {
                    showToast("✅ Đã gửi " + finalName + " sang Mac!");
                } else {
                    showToast("✅ Đã gửi " + finalSent + " tệp sang Mac!");
                }
            } else if (finalSent > 0) {
                showToast("Đã gửi " + finalSent + "/" + total + " tệp sang Mac");
            } else {
                showToast("❌ " + (finalErr != null ? finalErr : "Gửi sang Mac thất bại"));
            }
            finish();
        });
    }

    private void sendTextToMac(String text, String macIp, int macPort) {
        boolean ok = false;
        try {
            ClipboardSyncService.publishPhoneClipboardIfActive(text);
        } catch (Throwable ignored) {
        }
        try {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            try (Socket sock = new Socket()) {
                sock.setTcpNoDelay(true);
                sock.connect(new InetSocketAddress(macIp, macPort), 5000);
                sock.setSoTimeout(10_000);
                String hdr = "POST /receive-text HTTP/1.1\r\n"
                        + "Host: " + macIp + ":" + macPort + "\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream os = sock.getOutputStream();
                os.write(hdr.getBytes(StandardCharsets.US_ASCII));
                os.write(body);
                os.flush();
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
                String statusLine = br.readLine();
                ok = statusLine != null && statusLine.contains(" 200");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed sending text to " + macIp + ":" + macPort, t);
        }

        final boolean finalOk = ok;
        mainHandler.post(() -> {
            showToast(finalOk
                    ? "✅ Đã gửi văn bản vào Clipboard Mac!"
                    : "❌ Không nối được Mac (" + macIp + ")");
            finish();
        });
    }

    private static int streamFileOverSocket(String macIp, int macPort, String fileName,
            long knownSize, InputStream in) throws Exception {
        String encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        try (Socket sock = new Socket()) {
            sock.setTcpNoDelay(true);
            sock.connect(new InetSocketAddress(macIp, macPort), 6000);
            sock.setSoTimeout(120_000);

            StringBuilder hdr = new StringBuilder();
            hdr.append("POST /receive?name=").append(encodedName).append(" HTTP/1.1\r\n");
            hdr.append("Host: ").append(macIp).append(":").append(macPort).append("\r\n");
            hdr.append("Content-Type: application/octet-stream\r\n");
            if (knownSize >= 0L) {
                hdr.append("Content-Length: ").append(knownSize).append("\r\n");
            }
            hdr.append("Connection: close\r\n\r\n");

            OutputStream os = sock.getOutputStream();
            os.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
            if (knownSize < 0L) {
                try {
                    sock.shutdownOutput();
                } catch (Throwable ignored) {
                }
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = br.readLine();
            if (statusLine != null) {
                String[] parts = statusLine.split(" ");
                if (parts.length >= 2) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return 0;
        }
    }

    private static List<Uri> extractUris(Intent intent, String action) {
        List<Uri> result = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) {
                result.add((Uri) p);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Parcelable p : list) {
                    if (p instanceof Uri) {
                        result.add((Uri) p);
                    }
                }
            }
        }
        if (result.isEmpty()) {
            ClipData clip = intent.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) {
                        result.add(u);
                    }
                }
            }
        }
        return result;
    }

    private static String queryFileName(ContentResolver resolver, Uri uri, String fallbackMime) {
        String name = null;
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Throwable ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            String lastSeg = uri.getLastPathSegment();
            if (lastSeg != null && !lastSeg.trim().isEmpty()) {
                int slash = lastSeg.lastIndexOf('/');
                name = slash >= 0 ? lastSeg.substring(slash + 1) : lastSeg;
            }
        }
        if (name == null || name.trim().isEmpty()) {
            String mime = resolver.getType(uri);
            if (mime == null) mime = fallbackMime;
            String ext = mime != null ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null;
            name = "shared_" + System.currentTimeMillis() + (ext != null ? ("." + ext) : ".bin");
        }
        return name;
    }

    private static long queryFileSize(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show());
    }
}
