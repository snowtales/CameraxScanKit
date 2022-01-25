package com.kashpirovich.camerax;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;
import com.huawei.hms.hmsscankit.RemoteView;
import com.huawei.hms.hmsscankit.ScanUtil;
import com.huawei.hms.ml.scan.HmsScan;
import com.huawei.hms.ml.scan.HmsScanAnalyzerOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    public static final int SCAN_FRAME_SIZE = 200;
    public static final String TAG = "MainActivity";

    ConstraintLayout constraintLayout;
    Button pickPhoto, makePhoto;
    ListenableFuture<ProcessCameraProvider> listenableFuture;
    PreviewView previewView;
    RemoteView remoteView;
    Bitmap bitmap;
    LinearLayout linearLayout;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        constraintLayout = findViewById(R.id.camera_preview);
        listenableFuture = ProcessCameraProvider.getInstance(this);
        previewView = findViewById(R.id.viewFinder);
        linearLayout = findViewById(R.id.linear_root);
        pickPhoto = findViewById(R.id.pick_the_photo);
                pickPhoto.setOnClickListener(v ->
        {
            Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            pick.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            launcher.launch(pick);
        }
        );

        if(checkSelfPermission(Manifest.permission.CAMERA)!= PackageManager.PERMISSION_GRANTED){
            result.launch(Manifest.permission.CAMERA);
        }

        makePhoto = findViewById(R.id.make_photo);
        makePhoto.setOnClickListener(v -> {
            startCamera();
            findViewById(R.id.camera_preview).setVisibility(View.VISIBLE);
        });

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        int mScreenWidth = dm.widthPixels;
        int mScreenHeight = dm.heightPixels;
        int scaFrameSize = (int) (SCAN_FRAME_SIZE * density);

        Rect rect = new Rect();
        rect.left = mScreenWidth/2 - scaFrameSize/2;
        rect.right = mScreenWidth/2 + scaFrameSize/2;
        rect.top = mScreenHeight/2 - scaFrameSize/2;
        rect.bottom = mScreenHeight/2 + scaFrameSize/2;

        remoteView = new RemoteView
                .Builder()
                .setContext(this)
                .setBoundingBox(rect)
                .setContinuouslyScan(true)
                .setFormat(HmsScan.QRCODE_SCAN_TYPE, HmsScan.DATAMATRIX_SCAN_TYPE)
                .build();
        remoteView.onCreate(savedInstanceState);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        previewView.addView(remoteView, params);


    }

    private void startCamera() {
        listenableFuture = ProcessCameraProvider.getInstance(this);
        listenableFuture.addListener(()->{
            try{
                ProcessCameraProvider cameraProvider = listenableFuture.get();
                settingUpCamera(cameraProvider);
            }catch(ExecutionException | InterruptedException e){
                Log.e(TAG, "Binding Failed");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void settingUpCamera(ProcessCameraProvider cameraProvider) {
        ImageAnalysis analysis = new ImageAnalysis.Builder().build();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        CameraSelector camSel = CameraSelector.DEFAULT_BACK_CAMERA;
        analysis.setAnalyzer(getMainExecutor(), image -> {
            bitmap = getBitmap(image);
            image.close();
            if(successfulDecoding(bitmap)){
                try{
                    Thread.sleep(2000);
                }catch (InterruptedException e){
                    Log.e(TAG, "Fail to interrupt");
                }
                startCamera();
            }
        });
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, camSel, preview, analysis);
    }

    private Bitmap getBitmap(ImageProxy imageProxy) {
        ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        buffer.rewind();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        byte[] cloned = bytes.clone();

        YuvImage yuvImage = new YuvImage(cloned, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0,0, width, height), 100, outputStream);
        return BitmapFactory.decodeByteArray(outputStream.toByteArray(), 0, outputStream.toByteArray().length);
    }

    private final ActivityResultLauncher<String> result = registerForActivityResult(new ActivityResultContracts.RequestPermission(), denied -> {
        if(denied) Snackbar.make(constraintLayout, "\n please let Us make some photo \n", Snackbar.LENGTH_LONG).show();
    });

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
        if(result.getResultCode() == Activity.RESULT_OK){
            Intent data = result.getData();
            try{
                Bitmap bm = MediaStore.Images.Media.getBitmap(this.getContentResolver(), Objects.requireNonNull(data).getData());
                successfulDecoding(bm);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    });

    private boolean successfulDecoding(Bitmap bm) {
        HmsScanAnalyzerOptions options = new HmsScanAnalyzerOptions
                .Creator()
                .setHmsScanTypes(HmsScan.QRCODE_SCAN_TYPE, HmsScan.DATAMATRIX_SCAN_TYPE)
                .setPhotoMode(true)
                .create();
        HmsScan[] resultDecode = ScanUtil.decodeWithBitmap(this, bm, options);
        if(resultDecode != null && resultDecode.length > 0 && TextUtils.isEmpty(resultDecode[0].getOriginalValue()))
        {
            Snackbar sn = Snackbar.make(linearLayout, resultDecode[0].getOriginalValue(), Snackbar.LENGTH_INDEFINITE);
            sn.setAction("\n dismiss \n", action -> sn.dismiss());
            sn.show();
            return  true;
        } else {
        return false;
        }
    }

}