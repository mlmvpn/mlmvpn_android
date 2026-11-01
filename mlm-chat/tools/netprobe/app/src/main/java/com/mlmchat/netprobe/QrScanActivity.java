package com.mlmchat.netprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one QR code with the camera and returns its text ("text" extra). The old Camera API is
 * enough here and needs no library; frames are decoded with zxing off the UI thread.
 */
@SuppressWarnings("deprecation")
public class QrScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final int REQ_CAMERA = 7;
    private Camera camera;
    private SurfaceHolder holder;
    private HandlerThread worker;
    private Handler decodeHandler;
    private volatile boolean busy;
    private volatile boolean done;
    private final MultiFormatReader reader = new MultiFormatReader();
    private TextView hint;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        FrameLayout root = new FrameLayout(this);
        SurfaceView sv = new SurfaceView(this);
        root.addView(sv);
        hint = new TextView(this);
        hint.setText("کد QR را جلوی دوربین بگیرید. برای برگشت، دکمهٔ برگشت گوشی.");
        hint.setTextColor(Color.WHITE);
        hint.setBackgroundColor(0x99000000);
        hint.setPadding(24, 24, 24, 24);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(hint, lp);
        setContentView(root);
        holder = sv.getHolder();
        holder.addCallback(this);
        worker = new HandlerThread("qr");
        worker.start();
        decodeHandler = new Handler(worker.getLooper());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else if (holder.getSurface() != null && holder.getSurface().isValid()) {
            openCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_CAMERA && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)) {
            hint.setText("بدون اجازهٔ دوربین نمی‌شود QR را خواند. کارت را با «چسباندن» وارد کنید.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.quitSafely();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        closeCamera();
    }

    private void openCamera() {
        if (camera != null) return;
        try {
            camera = Camera.open();
            if (camera == null) {
                hint.setText("دوربین پشتی پیدا نشد.");
                return;
            }
            Camera.Parameters p = camera.getParameters();
            List<String> focus = p.getSupportedFocusModes();
            if (focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                int px = s.width * s.height;
                if (px <= 1920 * 1080 && (best == null || px > best.width * best.height)) best = s;
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            camera.setParameters(p);
            camera.setDisplayOrientation(displayRotation());
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            hint.setText("دوربین باز نشد: " + e.getMessage());
            closeCamera();
        }
    }

    private int displayRotation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(0, info);
        int degrees;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
            default: degrees = 0;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    private void closeCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (Exception ignored) { }
            camera.release();
            camera = null;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (busy || done || data == null) return;
        Camera.Size s;
        try {
            s = cam.getParameters().getPreviewSize();
        } catch (Exception e) {
            return;
        }
        busy = true;
        final byte[] frame = data.clone();
        final int w = s.width, h = s.height;
        decodeHandler.post(() -> {
            try {
                PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(frame, w, h, 0, 0, w, h, false);
                Result r = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
                if (r != null && r.getText() != null && !done) {
                    done = true;
                    final String text = r.getText();
                    runOnUiThread(() -> {
                        setResult(RESULT_OK, new Intent().putExtra("text", text));
                        finish();
                    });
                }
            } catch (Exception ignored) {
                // No code in this frame.
            } finally {
                reader.reset();
                busy = false;
            }
        });
    }
}
