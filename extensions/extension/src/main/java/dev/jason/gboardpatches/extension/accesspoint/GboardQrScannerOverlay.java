package dev.jason.gboardpatches.extension.accesspoint;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.jason.gboardpatches.extension.webclipboard.ClipboardSyncService;
import dev.jason.gboardpatches.extension.webclipboard.WebClipboardPreferences;

/**
 * Full-screen Camera2 + ZXing QR scanner hosted inside GboardPatchesSettingsActivity
 * when launched from the "Quét QR" access point in Gboard's 4-squares drawer.
 */
public final class GboardQrScannerOverlay {
    private final Activity activity;
    private TextureView textureView;
    private TextView statusLabel;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private final AtomicBoolean handledScan = new AtomicBoolean(false);
    private long lastDecodeMs = 0L;
    private final MultiFormatReader qrReader = new MultiFormatReader();

    public GboardQrScannerOverlay(Activity activity) {
        this.activity = activity;
        qrReader.setHints(Collections.singletonMap(
                DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE)));
    }

    public void attach() {
        float density = activity.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(activity);
        root.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        int frameSize = Math.round(260 * density);
        FrameLayout scanBox = new FrameLayout(activity);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(Color.TRANSPARENT);
        boxBg.setCornerRadius(24 * density);
        boxBg.setStroke(Math.round(3 * density), Color.parseColor("#0A84FF"));
        scanBox.setBackground(boxBg);
        root.addView(scanBox, new FrameLayout.LayoutParams(frameSize, frameSize, Gravity.CENTER));

        statusLabel = new TextView(activity);
        statusLabel.setText("Đưa camera vào mã QR trên AirBridge Mac");
        statusLabel.setTextColor(Color.WHITE);
        statusLabel.setTextSize(15f);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setPadding(Math.round(20 * density), Math.round(12 * density),
                Math.round(20 * density), Math.round(12 * density));
        GradientDrawable lblBg = new GradientDrawable();
        lblBg.setColor(Color.argb(175, 0, 0, 0));
        lblBg.setCornerRadius(18 * density);
        statusLabel.setBackground(lblBg);
        FrameLayout.LayoutParams lblParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lblParams.bottomMargin = Math.round(72 * density);
        root.addView(statusLabel, lblParams);

        TextView closeBtn = new TextView(activity);
        closeBtn.setText("✕ Đóng");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(14f);
        closeBtn.setPadding(Math.round(16 * density), Math.round(8 * density),
                Math.round(16 * density), Math.round(8 * density));
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.argb(165, 0, 0, 0));
        closeBg.setCornerRadius(16 * density);
        closeBtn.setBackground(closeBg);
        closeBtn.setOnClickListener(v -> activity.finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        closeParams.topMargin = Math.round(48 * density);
        closeParams.rightMargin = Math.round(20 * density);
        root.addView(closeBtn, closeParams);

        activity.setContentView(root);

        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "Hãy cấp quyền Camera cho Gboard để quét QR", Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { openCamera(); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            bgThread = new HandlerThread("GboardQrCam");
            bgThread.start();
            bgHandler = new Handler(bgThread.getLooper());

            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String backId = null;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backId = id;
                    break;
                }
            }
            if (backId == null && cm.getCameraIdList().length > 0) {
                backId = cm.getCameraIdList()[0];
            }
            if (backId == null) return;

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img == null) return;
                try {
                    long now = System.currentTimeMillis();
                    if (handledScan.get() || now - lastDecodeMs < 120L) return;
                    lastDecodeMs = now;

                    Image.Plane yPlane = img.getPlanes()[0];
                    ByteBuffer yBuf = yPlane.getBuffer();
                    int rowStride = yPlane.getRowStride();
                    int w = img.getWidth();
                    int h = img.getHeight();
                    byte[] data = new byte[w * h];
                    if (rowStride == w) {
                        yBuf.get(data);
                    } else {
                        for (int row = 0; row < h; row++) {
                            yBuf.position(row * rowStride);
                            yBuf.get(data, row * w, w);
                        }
                    }
                    PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
                    BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
                    Result res = null;
                    try {
                        res = qrReader.decodeWithState(bmp);
                    } catch (Throwable ignored) {
                    } finally {
                        qrReader.reset();
                    }
                    if (res != null && res.getText() != null && res.getText().startsWith("airbridge://pair?")) {
                        if (handledScan.compareAndSet(false, true)) {
                            handleScannedQr(res.getText());
                        }
                    }
                } finally {
                    img.close();
                }
            }, bgHandler);

            cm.openCamera(backId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    SurfaceTexture st = textureView.getSurfaceTexture();
                    if (st == null) return;
                    st.setDefaultBufferSize(1280, 720);
                    Surface previewSurface = new Surface(st);
                    Surface readerSurface = imageReader.getSurface();
                    try {
                        CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        req.addTarget(previewSurface);
                        req.addTarget(readerSurface);
                        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        camera.createCaptureSession(
                                java.util.Arrays.asList(previewSurface, readerSurface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        captureSession = session;
                                        try {
                                            session.setRepeatingRequest(req.build(), null, bgHandler);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession session) {}
                                },
                                bgHandler);
                    } catch (Throwable ignored) {
                    }
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); }
            }, bgHandler);
        } catch (Throwable ignored) {
        }
    }

    private void handleScannedQr(String qrUri) {
        activity.runOnUiThread(() -> statusLabel.setText("⏳ Đang ghép nối vĩnh viễn với Mac..."));
        Uri uri = Uri.parse(qrUri);
        String clientName = uri.getQueryParameter("clientName");
        if (clientName == null || clientName.isEmpty()) clientName = "Mac";
        String macIp = uri.getQueryParameter("macIp");
        String portStr = uri.getQueryParameter("macPort");
        String secret = uri.getQueryParameter("secret");
        int macPort = 0;
        try {
            if (portStr != null) macPort = Integer.parseInt(portStr);
        } catch (Throwable ignored) {
        }
        if (macIp == null || macIp.isEmpty() || macPort <= 0 || secret == null || secret.isEmpty()) {
            handledScan.set(false);
            activity.runOnUiThread(() -> statusLabel.setText("Mã QR không hợp lệ, thử lại"));
            return;
        }

        final String finalMacIp = macIp;
        final int finalMacPort = macPort;
        final String finalSecret = secret;
        final String finalClientName = clientName;

        new Thread(() -> {
            try {
                Context appCtx = activity.getApplicationContext();
                ClipboardSyncService.startOrUpdate(appCtx);

                SharedPreferences abPrefs = appCtx.getSharedPreferences("gboard_airbridge_prefs", Context.MODE_PRIVATE);
                String deviceId = abPrefs.getString("device_id", null);
                if (deviceId == null || deviceId.isEmpty()) {
                    deviceId = "gb-" + UUID.randomUUID().toString();
                    abPrefs.edit().putString("device_id", deviceId).apply();
                }

                SharedPreferences wcPrefs = WebClipboardPreferences.preferences(appCtx);
                String pairingCode = WebClipboardPreferences.getPairingCode(wcPrefs);
                if (pairingCode == null) pairingCode = "";
                int webPort = WebClipboardPreferences.getPort(wcPrefs);

                String phoneIp = findLocalIpv4();
                String model = (Build.MANUFACTURER != null ? Build.MANUFACTURER : "Android")
                        + " " + (Build.MODEL != null ? Build.MODEL : "Phone");

                String payload = new JSONObject()
                        .put("secret", finalSecret)
                        .put("deviceId", deviceId)
                        .put("deviceName", Build.MODEL != null ? Build.MODEL : "Android")
                        .put("model", model)
                        .put("host", phoneIp != null ? phoneIp : "")
                        .put("webPort", webPort)
                        .put("tlsPort", 0)
                        .put("certSha256", "")
                        .put("token", pairingCode)
                        .put("pin", pairingCode)
                        .toString();

                byte[] bodyBytes = payload.getBytes(StandardCharsets.UTF_8);
                int code = 0;
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress(finalMacIp, finalMacPort), 5000);
                    sock.setSoTimeout(5000);
                    String req = "POST /qr-pair HTTP/1.1\r\n"
                            + "Host: " + finalMacIp + ":" + finalMacPort + "\r\n"
                            + "Content-Type: application/json; charset=utf-8\r\n"
                            + "Content-Length: " + bodyBytes.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    OutputStream os = sock.getOutputStream();
                    os.write(req.getBytes(StandardCharsets.US_ASCII));
                    os.write(bodyBytes);
                    os.flush();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
                    String statusLine = br.readLine();
                    if (statusLine != null && statusLine.contains(" 200")) {
                        code = 200;
                    }
                }

                final int finalCode = code;
                activity.runOnUiThread(() -> {
                    if (finalCode >= 200 && finalCode < 300) {
                        Toast.makeText(activity, "✅ Đã ghép nối vĩnh viễn với " + finalClientName + "!", Toast.LENGTH_LONG).show();
                        activity.finish();
                    } else {
                        handledScan.set(false);
                        statusLabel.setText("Ghép nối thất bại (HTTP " + finalCode + ") — thử lại");
                    }
                });
            } catch (Throwable t) {
                activity.runOnUiThread(() -> {
                    handledScan.set(false);
                    statusLabel.setText("Không nối được Mac (" + finalMacIp + ") — kiểm tra chung Wi-Fi");
                });
            }
        }).start();
    }

    private static String findLocalIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public void release() {
        try { if (captureSession != null) captureSession.close(); } catch (Throwable ignored) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Throwable ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Throwable ignored) {}
        try { if (bgThread != null) bgThread.quitSafely(); } catch (Throwable ignored) {}
    }
}
