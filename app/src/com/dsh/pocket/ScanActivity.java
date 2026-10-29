package com.dsh.pocket;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Camera preview plus ZXing decode; returns the first http(s) URL it reads. */
public final class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int REQUEST_CAMERA = 7;

    private Camera camera;
    private SurfaceHolder holder;
    private MultiFormatReader reader;
    private volatile boolean handled;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        SurfaceView preview = new SurfaceView(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText("对准电脑上的配对二维码");
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(15);
        hint.setPadding(40, 70, 40, 0);
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader = new MultiFormatReader();
        reader.setHints(hints);

        holder = preview.getHolder();
        holder.addCallback(this);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_CAMERA && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            hint.setText("没有相机权限，返回上一页粘贴链接");
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        startPreview();
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
        if (camera != null) {
            Camera.Parameters params = camera.getParameters();
            Camera.Size size = chooseSize(params.getSupportedPreviewSizes());
            params.setPreviewSize(size.width, size.height);
            try {
                camera.setParameters(params);
            } catch (RuntimeException ignored) {
            }
            camera.startPreview();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        stopPreview();
    }

    private Camera.Size chooseSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        for (Camera.Size size : sizes) {
            if (size.width >= 640 && size.width < best.width) best = size;
        }
        return best;
    }

    private void startPreview() {
        if (camera != null) return;
        try {
            camera = Camera.open();
            camera.setPreviewDisplay(holder);
            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);
            Camera.Size size = chooseSize(params.getSupportedPreviewSizes());
            params.setPreviewSize(size.width, size.height);
            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            hint.setText("打不开相机：" + e.getMessage());
            camera = null;
        }
    }

    private void stopPreview() {
        if (camera == null) return;
        try {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
        } catch (Exception ignored) {
        }
        camera = null;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera source) {
        if (handled || data == null) return;
        Camera.Size size = source.getParameters().getPreviewSize();
        try {
            PlanarYUVLuminanceSource luminance = new PlanarYUVLuminanceSource(
                    data, size.width, size.height, 0, 0, size.width, size.height, false);
            Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(luminance)));
            String text = result == null ? null : result.getText();
            if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) {
                handled = true;
                Intent out = new Intent();
                out.putExtra("url", text);
                setResult(RESULT_OK, out);
                finish();
            }
        } catch (Exception ignored) {
            // no code in this frame
        }
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        super.onDestroy();
    }
}
