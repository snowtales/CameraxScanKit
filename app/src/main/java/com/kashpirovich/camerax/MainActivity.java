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
import com.google.android.material.switchmaterial.SwitchMaterial;
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
    private static final String TAG = "BitmapActivity";

    LinearLayout linearLayout;
    Button pickPhoto, makePhoto;
    private PreviewView previewView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private RemoteView remoteView;
    Bitmap bitmapOfPicture;
    ConstraintLayout previewFragment;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        linearLayout = findViewById(R.id.linear_root);
        pickPhoto = findViewById(R.id.pick_the_photo);
        makePhoto = findViewById(R.id.make_photo);
        previewView = findViewById(R.id.viewFinder);
        previewFragment = findViewById(R.id.camera_preview);

        int externalPermission = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
        int cameraPermission = checkSelfPermission(Manifest.permission.CAMERA);

        if ((externalPermission & cameraPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(new String[]
                    {
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.CAMERA,
                    });
        }

        pickPhoto.setOnClickListener(v -> {
                Intent pickPhoto = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                pickPhoto.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
                launcher.launch(pickPhoto);
        });

        makePhoto.setOnClickListener(v -> {
            startCamera();
            findViewById(R.id.camera_preview).setVisibility(View.VISIBLE);
        });

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        int mScreenWidth = getResources().getDisplayMetrics().widthPixels;
        int mScreenHeight = getResources().getDisplayMetrics().heightPixels;
        int scanFrameSize = (int) (SCAN_FRAME_SIZE * density);

        Rect rect = new Rect();
        rect.left = mScreenWidth / 2 - scanFrameSize / 2;
        rect.right = mScreenWidth / 2 + scanFrameSize / 2;
        rect.top = mScreenHeight / 2 - scanFrameSize / 2;
        rect.bottom = mScreenHeight / 2 + scanFrameSize / 2;

        remoteView = new RemoteView.Builder()
                .setContext(this)
                .setBoundingBox(rect)
                .setContinuouslyScan(true)
                .setFormat(HmsScan.QRCODE_SCAN_TYPE,
                        HmsScan.DATAMATRIX_SCAN_TYPE)
                .build();
        remoteView.onCreate(savedInstanceState);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        previewView.addView(remoteView, params);

    }

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), isGranted -> {
                isGranted.forEach((key, value) ->
                {
                    if (!value)
                        Snackbar.make(previewFragment, "\n we Need Access \n", Snackbar.LENGTH_LONG).show();
                });
            });

    /**
     * после выбора изображения в галерее, изображение конвертируем в Bitmap с помощью
     * MediaStore.Images.Media.getBitmap и передаем в {@link #successfulDecoding(Bitmap)}
     */

    private ActivityResultLauncher<Intent> launcher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent data = result.getData();
                    try {
                        Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), data.getData());
                        successfulDecoding(bitmap);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });

    /**
     * создаем отдельный поток для камеры {@link #cameraProviderFuture} и устанавливаем
     * настройки камеры
     */
    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                settingUpCamera(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e("CamX", "Use case binding failed" + e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     *  @see ImageAnalysis.Builder по умолчанию устанавливает YUV_420_888, подходящий для нас,
     *  и стратегию анализа полученных данных на STRATEGY_KEEP_ONLY_LATEST , которая берет
     *  только самое последнее изображение. Анализатор получает изображение и конвертирует в ImageProxy,
     *  его мы конвертируем в Bitmap {@link MainActivity#getBitmap(ImageProxy)}
     *  и передаем в {@link MainActivity#successfulDecoding(Bitmap)}
     */
    private void settingUpCamera(ProcessCameraProvider cameraProvider) {
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .build();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        CameraSelector camSel = CameraSelector.DEFAULT_BACK_CAMERA;
        analysis.setAnalyzer(getMainExecutor(), image -> {
            bitmapOfPicture = getBitmap(image);
            image.close();
            if(successfulDecoding(bitmapOfPicture)) {
                try{
                    Thread.sleep(2000);
                }catch (InterruptedException e){
                    Log.e(TAG, "Fail to Interrupt" + e);
                }
                startCamera();
            }
        });
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, camSel, preview, analysis);
    }


    /**
     * Конвертирует получаемый ImageProxy в Bitmap
     * @param image необходимо конвертировать в YuvImage, иначе {@link BitmapFactory} не сможет
     * сконвертировать в Bitmap
     */
    private Bitmap getBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        buffer.rewind();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        byte[] clonedBytes = bytes.clone();

        YuvImage yuvImage = new YuvImage(clonedBytes, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, output);

        return BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.toByteArray().length);
    }

    private boolean successfulDecoding(Bitmap bitmap) {
        HmsScanAnalyzerOptions options = new HmsScanAnalyzerOptions.Creator().setHmsScanTypes(HmsScan.QRCODE_SCAN_TYPE, HmsScan.DATAMATRIX_SCAN_TYPE).setPhotoMode(true).create();
        HmsScan[] result = ScanUtil.decodeWithBitmap(this, bitmap, options);
        if (result != null && result.length > 0 && !TextUtils.isEmpty(result[0].getOriginalValue())) {
            Log.i("got", result[0].getOriginalValue());
            Snackbar sn = Snackbar.make(linearLayout, result[0].getOriginalValue(), Snackbar.LENGTH_INDEFINITE);
            sn.setAction("\n dismiss \n", action -> sn.dismiss());
            sn.show();
            return true;
        }else{
            Log.i("Tag", "continue searchin");
            return false;
        }
    }


    @Override
    protected void onStart() {
        super.onStart();
        // Listen to the onStart method of the activity.
        remoteView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Listen to the onResume method of the activity.
        remoteView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Listen to the onPause method of the activity.
        remoteView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Listen to the onStop method of the activity.
        remoteView.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Listen to the onDestroy method of the activity.
        remoteView.onDestroy();
    }
}