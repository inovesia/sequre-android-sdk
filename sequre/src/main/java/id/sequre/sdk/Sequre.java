package id.sequre.sdk;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Size;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.util.ArrayUtils;
import com.google.android.gms.tflite.client.TfLiteInitializationOptions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.common.HybridBinarizer;

import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.gms.vision.TfLiteVision;
import org.tensorflow.lite.task.gms.vision.classifier.Classifications;
import org.tensorflow.lite.task.gms.vision.classifier.ImageClassifier;
import org.tensorflow.lite.task.gms.vision.detector.Detection;
import org.tensorflow.lite.task.gms.vision.detector.ObjectDetector;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import id.sequre.sdk.databinding.SequreBinding;


public class Sequre extends AppCompatActivity {

    private static boolean FINISHED;
    private static Context CONTEXT;
    private static id.sequre.sdk.Callback CALLBACK;
    private boolean validated;
    private String message;
    private String applicationNumber;

    private static int PERMISSION_REQUEST = 0x01;
    private SequreBinding binding;
    private ImageAnalysis imageAnalysis;
    private ImageCapture imageCapture;
    private Camera camera;
    private boolean resized, torch;
    private double moveCloser = 0.6, moveFurther = 0.8, distancesLength = 3, distancesMax = 40, framePercentage = 0.8, frameRatio = 1.0 / 2.0, ratio, left, top, width, height, vertical, horizontal;
    private int eventColor = Color.GRAY;
    private int eventWidth = 10;
    private List<RectF> boundingBoxs = new ArrayList<>();

    private ObjectDetector objectDetector, objectDetectorV2;
    private ImageClassifier imageClassifier;
    private List<Double> distances;
    private Result result;

    private View mask;

    private Long processing;
    private Timer watcher;

    private MultiFormatReader reader;
    private SharedPreferences pref;
    private Long[] timelines = new Long[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            SharedPreferences pref = getSharedPreferences(getPackageName(), MODE_PRIVATE);
            Locale locale = new Locale(pref.getString("l", "en"));
            Locale.setDefault(locale);
            Resources resources = getResources();
            Configuration config = resources.getConfiguration();
            config.setLocale(locale);
            resources.updateConfiguration(config, resources.getDisplayMetrics());
        } catch (Exception e) {
            e.printStackTrace();
        }

        timelines[0] = System.currentTimeMillis();

        binding = SequreBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        distances = new ArrayList<>();
        result = new Result();


        if (!TfLiteVision.isInitialized()) {
            TfLiteInitializationOptions tfOptions = TfLiteInitializationOptions.builder().setEnableGpuDelegateSupport(true).build();
            TfLiteVision.initialize(Sequre.this, tfOptions)
                    .addOnSuccessListener(unused -> {
                        initTensorFlow();
                    })
                    .addOnFailureListener(e -> {
                        e.printStackTrace();
                        TfLiteVision.initialize(Sequre.this)
                                .addOnSuccessListener(unused -> {
                                    initTensorFlow();
                                })
                                .addOnFailureListener(e1 -> {
                                    e1.printStackTrace();
                                });
                    });
        } else {
            initTensorFlow();
        }

        reader = new MultiFormatReader();
        Map<DecodeHintType, List<BarcodeFormat>> hints = new HashMap<>();
        hints.put(DecodeHintType.POSSIBLE_FORMATS, ArrayUtils.toArrayList(new BarcodeFormat[]{BarcodeFormat.QR_CODE}));
        reader.setHints(hints);

        imageAnalysis = new ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
//                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
//                .setTargetRotation(Surface.ROTATION_0)
//                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(Sequre.this), imageProxy -> {
            if (!resized) {
                initMask(imageProxy);
            }
            new Thread() {
                @Override
                public void run() {
                    log("processing preview");
                    if (processing == null) {
                        processing = System.currentTimeMillis();
                        detect(imageProxy);
                    }
                    imageProxy.close();
                }
            }.run();
        });

        imageCapture = new ImageCapture.Builder()
//                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
//                .setTargetRotation(Surface.ROTATION_0)
                .build();

        binding.sequreBack.setOnClickListener(view -> finish());

        binding.sequreTorch.setOnClickListener(view -> {
            if (camera.getCameraInfo().hasFlashUnit()) {
                camera.getCameraControl().enableTorch((torch = !torch));
                binding.sequreTorch.setImageResource(torch ? R.mipmap.ic_torch : R.mipmap.ic_torch_off);
            }
        });

        binding.sequreZoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean b) {
                if (camera != null) {
                    camera.getCameraControl().setLinearZoom(value / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        watcher = new Timer();
        watcher.schedule(new TimerTask() {
            @Override
            public void run() {
                if (processing != null && System.currentTimeMillis() - processing > 2000) {
                    processing = null;
                }
            }
        }, 1000, 1000);

        requestPermissions();

        pref = getSharedPreferences(getPackageName(), MODE_PRIVATE);

        framePercentage = Double.parseDouble(pref.getString("framePercentage", "0.9"));
        frameRatio = Double.parseDouble(pref.getString("frameRatio", "0.5"));
        moveCloser = Double.parseDouble(pref.getString("moveCloser", "0.6"));
        moveFurther = Double.parseDouble(pref.getString("moveFurther", "0.8"));

        binding.sequreFramePercentage.setText("" + framePercentage);
        binding.sequreFrameRatio.setText("" + frameRatio);
        binding.sequreMoveCloser.setText("" + moveCloser);
        binding.sequreMoveFurther.setText("" + moveFurther);

        TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                try {
                    framePercentage = Double.parseDouble(binding.sequreFramePercentage.getText().toString());
                    pref.edit().putString("framePercentage", "" + framePercentage).apply();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    frameRatio = Double.parseDouble(binding.sequreFrameRatio.getText().toString());
                    pref.edit().putString("frameRatio", "" + frameRatio).apply();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    moveCloser = Double.parseDouble(binding.sequreMoveCloser.getText().toString());
                    pref.edit().putString("moveCloser", "" + moveCloser).apply();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    moveFurther = Double.parseDouble(binding.sequreMoveFurther.getText().toString());
                    pref.edit().putString("moveFurther", "" + moveFurther).apply();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                resized = false;
            }
        };
        binding.sequreFramePercentage.addTextChangedListener(textWatcher);
        binding.sequreFrameRatio.addTextChangedListener(textWatcher);
        binding.sequreMoveCloser.addTextChangedListener(textWatcher);
        binding.sequreMoveFurther.addTextChangedListener(textWatcher);
    }

    private void initTensorFlow() {
        ObjectDetector.ObjectDetectorOptions.Builder objectDetectorOptions = ObjectDetector.ObjectDetectorOptions.builder();
        objectDetectorOptions.setScoreThreshold(0.5f);
        objectDetectorOptions.setMaxResults(3);

//        try {
//            objectDetectorOptions.useGpu();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }

        try {
            objectDetector = ObjectDetector.createFromFileAndOptions(Sequre.this, "sequre.tflite", objectDetectorOptions.build());
            objectDetectorV2 = ObjectDetector.createFromFileAndOptions(Sequre.this, "sequre_v2.tflite", objectDetectorOptions.build());
        } catch (Exception e) {
            e.printStackTrace();
        }

        ImageClassifier.ImageClassifierOptions.Builder imageClassifierOptions = ImageClassifier.ImageClassifierOptions.builder();
        imageClassifierOptions.setScoreThreshold(0.5f);
        imageClassifierOptions.setMaxResults(3);

        try {
            imageClassifier = ImageClassifier.createFromFileAndOptions(Sequre.this, "classification.tflite", imageClassifierOptions.build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void detect(ImageProxy imageProxy) {
        if (objectDetector != null) {
            log("preparing image");
            ImageProcessor.Builder builder = new ImageProcessor.Builder();
            ImageProcessor imageProcessor = builder.build();

            Bitmap imageBuffer = Bitmap.createBitmap(imageProxy.getWidth(), imageProxy.getHeight(), Bitmap.Config.ARGB_8888);
            imageBuffer.copyPixelsFromBuffer(imageProxy.getPlanes()[0].getBuffer());

            // read qr
            log("extract qrcode");
            int[] intArray = new int[imageBuffer.getWidth() * imageBuffer.getHeight()];
            //copy pixel data from the Bitmap into the 'intArray' array
            imageBuffer.getPixels(intArray, 0, imageBuffer.getWidth(), 0, 0, imageBuffer.getWidth(), imageBuffer.getHeight());

            LuminanceSource source = new RGBLuminanceSource(imageBuffer.getWidth(), imageBuffer.getHeight(), intArray);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            Reader reader = new MultiFormatReader();// use this otherwise ChecksumException
            try {
                com.google.zxing.Result qr = reader.decode(bitmap);
                result.qr = qr.getText();
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (result.qr == null || !result.qr.toLowerCase().startsWith("http")) {
                processing = null;
                return;
            }
            log("qrcode: " + result.qr);
            if (!result.qr.startsWith("HTTP://QTRU.ST/")) {
                result.status = Status.Fake;
                result.message = "fake_qr";
                finish();
                return;
            }
            log("TensorFlow: detecting objects");
            TensorImage image = imageProcessor.process(TensorImage.fromBitmap(imageBuffer));

            boundingBoxs.clear();
            List<Detection> detections = objectDetector.detect(image);
            if (detections.size() > 0) {
                Size size = new Size(imageProxy.getHeight(), imageProxy.getWidth());
//                ViewGroup.LayoutParams params = binding.sequrePreviews.getLayoutParams();
//                for (Detection detection : detections) {
//                    RectF boundingBox = detection.getBoundingBox();
//                    // scale meet preview size
//                    boundingBox = new RectF(size.getWidth() - boundingBox.bottom, boundingBox.left, size.getWidth() - boundingBox.bottom + boundingBox.height(), boundingBox.right);
//                    boundingBox = new RectF(boundingBox.left / size.getWidth() * params.width, boundingBox.top / size.getHeight() * params.height, boundingBox.right / size.getWidth() * params.width, boundingBox.bottom / size.getHeight() * params.height);
//                    boundingBoxs.add(boundingBox);
//                }
                RectF boundingBox = transform(detections.get(0).getBoundingBox());

                double height = size.getHeight() * framePercentage;
                double width = height * frameRatio;

                double vertical = (size.getHeight() - height) / 2;
                double horizontal = (size.getWidth() - width) / 2;
//                log(detections.size() + " : " + size.getWidth() + "; " + size.getHeight() + ";" + horizontal + "; " + vertical + "; " + width + "; " + height + " boundingBox: " + boundingBox.left + "; " + boundingBox.top + "; " + boundingBox.right + "; " + boundingBox.bottom + "; " + boundingBox.width() + "; " + boundingBox.height());
                if (!(boundingBox.left >= horizontal && boundingBox.right <= horizontal + width &&
                        boundingBox.top >= vertical && boundingBox.bottom <= vertical + height)) {
//                    eventColor = Color.GREEN;
                    eventWidth = 10;
                    binding.sequreInfo.setVisibility(View.GONE);
                    binding.sequreInfo.setText(R.string.text_place_qr_inside_frame);
                    mask.invalidate();
                    processing = null;
                } else {
                    double percentage = boundingBox.width() / width;
                    if (percentage < moveCloser) {
//                        eventColor = Color.GREEN;
                        eventWidth = 10;
                        binding.sequreInfo.setVisibility(View.VISIBLE);
                        binding.sequreInfo.setText(R.string.text_move_closer);
                        mask.invalidate();
                        processing = null;
                    } else if (percentage > 0.8) {
//                        eventColor = Color.GREEN;
                        eventWidth = 10;
                        binding.sequreInfo.setVisibility(View.VISIBLE);
                        binding.sequreInfo.setText(R.string.text_move_further);
                        mask.invalidate();
                        processing = null;
                    } else {
                        double distance = Math.sqrt(Math.pow(boundingBox.left - left, 2) + Math.pow(boundingBox.top - top, 2));
                        left = boundingBox.left;
                        top = boundingBox.top;
                        if (distances.size() >= distancesLength) {
                            distances.remove(0);
                        }
                        distances.add(distance);
                        if (distances.size() == distancesLength) {
                            double total = 0;
                            for (double distance_ : distances) {
                                total += distance_;
                            }
                            double average = total / distancesLength;
                            eventColor = Color.GREEN;
//                            eventWidth = 20;
                            binding.sequreInfo.setVisibility(View.VISIBLE);
                            binding.sequreInfo.setText(R.string.text_hold_steady);
                            mask.invalidate();
                            timelines[1] = System.currentTimeMillis();
//                            processing = null;
                            if (average <= distancesMax) {
                                // capture
                                log("takePicture");
                                imageCapture.takePicture(ContextCompat.getMainExecutor(Sequre.this), new ImageCapture.OnImageCapturedCallback() {
                                    @Override
                                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                                        super.onCaptureSuccess(image);
                                        new Thread() {
                                            @Override
                                            public void run() {
                                                timelines[2] = System.currentTimeMillis();
                                                detect2(image);
                                                image.close();
                                            }
                                        }.start();

                                    }

                                    @Override
                                    public void onError(@NonNull ImageCaptureException exception) {
                                        super.onError(exception);
                                        processing = null;
                                    }
                                });
                            } else {
                                processing = null;
                            }
                        } else {
                            processing = null;
                        }
                    }
                }
            } else {
                eventColor = Color.GRAY;
                eventWidth = 10;
                binding.sequreInfo.setVisibility(View.GONE);
                binding.sequreInfo.setText(R.string.text_find_qr);
                mask.invalidate();
                processing = null;
            }
        } else {
            processing = null;
        }
    }

    private void detect2(ImageProxy imageProxy) {
        if (objectDetectorV2 != null) {
            log("TensorFlow: processing");
            ImageProcessor.Builder builder = new ImageProcessor.Builder();
            ImageProcessor imageProcessor = builder.build();

            ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
            int length = buffer.remaining();
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, length);
            bitmap = rotate(bitmap);
            TensorImage image = imageProcessor.process(TensorImage.fromBitmap(bitmap));
            List<Detection> detections = objectDetectorV2.detect(image);
            if (detections.size() > 0) {
                try {
                    RectF boundingBox = detections.get(0).getBoundingBox();
                    log("TensorFlow: found at " + boundingBox.left + "; " + boundingBox.top + "; " + boundingBox.width() + "; " + boundingBox.height());
                    Bitmap cropped = Bitmap.createBitmap(bitmap, (int) boundingBox.left, (int) boundingBox.top, (int) boundingBox.width(), (int) boundingBox.height());
                    Bitmap resized = Bitmap.createBitmap(cropped.getWidth(), cropped.getWidth(), Bitmap.Config.ARGB_8888);
                    resized.eraseColor(Color.WHITE);
                    Canvas canvas = new Canvas(resized);
                    canvas.drawBitmap(cropped, (float) 0, (float) ((cropped.getWidth() - cropped.getHeight()) / 2d), null);

                    image = imageProcessor.process(TensorImage.fromBitmap(cropped));
                    List<Classifications> classifications = imageClassifier.classify(image);

                    if (classifications.size() > 0 && classifications.get(0).getCategories().size() > 0) {
                        Category category = classifications.get(0).getCategories().get(0);
//                        log(category.getLabel() + "; " + category.getScore());
                        result.score = category.getScore();
                        if (category.getLabel().equals("genuine")) {
                            if (category.getScore() > 0.85f) {
                                result.status = Status.Genuine;
                            } else {
                                result.status = Status.Fake;
                                result.message = "poor_image_quality";
                            }
                        } else {
                            result.status = Status.Fake;
                        }

                        if (result.status.equals(Status.Fake)) {
                            save(bitmap);
                            save(resized);
                        }
                        finish();
                    } else {
                        save(resized);
                        log("TensorFlow: no classification found");
                        processing = null;
                    }
                } catch (Exception e) {
                    processing = null;
                    e.printStackTrace();
                    log("TensorFlow: error: " + e.toString());
                }
            } else {
                save(bitmap);
                log("TensorFlow: no object found");
                processing = null;
            }
        } else {
            processing = null;
        }
    }

    private Bitmap rotate(Bitmap bitmap) {
        if (bitmap.getWidth() > bitmap.getHeight()) {
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        }
        return bitmap;
    }

    private void log(String text) {
        try {
//            System.out.println(":: " + text);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private RectF transform(RectF box) {
        return new RectF(box.top, box.left, box.bottom, box.right);
    }

    private void save(Bitmap bitmap) {
//        try {
//            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date());
//            String name = timestamp + "_" + System.currentTimeMillis() + ".png";
//            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name);
//            FileOutputStream fos = new FileOutputStream(file);
//            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, fos);
//            fos.flush();
//            fos.close();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
    }

    private void initMask(ImageProxy image) {
        ratio = 1.0 * image.getWidth() / image.getHeight();
        ViewGroup.LayoutParams params = binding.sequrePreviews.getLayoutParams();
        params.width = binding.sequrePreviews.getWidth();
        params.height = (int) (params.width * ratio);
        binding.sequrePreviews.requestLayout();
        resized = true;

        height = params.height * framePercentage;
        width = height * frameRatio;


        vertical = (params.height - height) / 2;
        horizontal = (params.width - width) / 2;

        Paint paint = new Paint();
        int il = 80;
        mask = new View(Sequre.this) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                super.onDraw(canvas);
                // draw mask
                paint.setColor(ContextCompat.getColor(Sequre.this, R.color.transparent));

                canvas.drawRect(0, 0, params.width, (float) vertical, paint);
                canvas.drawRect(0, (float) (params.height - vertical), params.width, params.height, paint);
                canvas.drawRect(0, (float) vertical, (float) horizontal, (float) (params.height - vertical), paint);
                canvas.drawRect((float) (params.width - horizontal), (float) vertical, params.width, (float) (params.height - vertical), paint);

                // draw event indicator
                paint.setColor(eventColor);
                canvas.drawRect((float) (horizontal - eventWidth), (float) (vertical - eventWidth), (float) (horizontal + il - eventWidth), (float) vertical, paint);
                canvas.drawRect((float) (horizontal - eventWidth), (float) (vertical - eventWidth), (float) horizontal, (float) (vertical + il - eventWidth), paint);

                canvas.drawRect((float) (horizontal - eventWidth), (float) (params.height - vertical + eventWidth), (float) horizontal, (float) (params.height - vertical - il + eventWidth), paint);
                canvas.drawRect((float) (horizontal - eventWidth), (float) (params.height - vertical + eventWidth), (float) (horizontal + il - eventWidth), (float) (params.height - vertical), paint);

                canvas.drawRect((float) (params.width - horizontal + eventWidth), (float) (vertical - eventWidth), (float) (params.width - horizontal - il + eventWidth), (float) vertical, paint);
                canvas.drawRect((float) (params.width - horizontal + eventWidth), (float) (vertical - eventWidth), (float) (params.width - horizontal), (float) (vertical + il - eventWidth), paint);

                canvas.drawRect((float) (params.width - horizontal + eventWidth), (float) (params.height - vertical + eventWidth), (float) (params.width - horizontal - il + eventWidth), (float) (params.height - vertical), paint);
                canvas.drawRect((float) (params.width - horizontal + eventWidth), (float) (params.height - vertical + eventWidth), (float) (params.width - horizontal), (float) (params.height - vertical - il + eventWidth), paint);

//                // draw boundingBox
//                paint.setColor(Color.GREEN);
//                for (RectF boundingBox : boundingBoxs) {
//                    canvas.drawRect(boundingBox.left, boundingBox.top, boundingBox.left + boundingBox.width(), boundingBox.top + eventWidth / 2f, paint);
//                    canvas.drawRect(boundingBox.left, boundingBox.top, boundingBox.left + eventWidth / 2f, boundingBox.top + boundingBox.height(), paint);
//
//                    canvas.drawRect(boundingBox.right, boundingBox.bottom, boundingBox.right - boundingBox.width(), boundingBox.bottom - eventWidth / 2f, paint);
//                    canvas.drawRect(boundingBox.right, boundingBox.bottom, boundingBox.right - eventWidth / 2f, boundingBox.bottom - boundingBox.height(), paint);
//                }
            }
        };

        if (binding.sequrePreviews.getChildCount() > 1) {
            binding.sequrePreviews.removeViewAt(1);
        }
        binding.sequrePreviews.addView(mask, new RelativeLayout.LayoutParams(binding.sequrePreviews.getWidth(), binding.sequrePreviews.getHeight()));
    }


    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(Sequre.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(Sequre.this, new String[]{android.Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
        } else {
            startCamera();
        }

    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(Sequre.this);
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    Preview preview = new Preview.Builder()
//                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
//                            .setTargetRotation(Surface.ROTATION_0)
                            .build();
                    preview.setSurfaceProvider(binding.sequrePreview.getSurfaceProvider());
                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(Sequre.this, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture, imageAnalysis, preview);

                    camera.getCameraInfo().getCameraState().removeObservers(Sequre.this);
                    camera.getCameraInfo().getCameraState().observe(Sequre.this, cameraState -> {
                        if (cameraState.getType().equals(CameraState.Type.OPEN)) {
                            binding.sequrePreview.postDelayed(() -> {
                                float zoomRatio = Math.min(4f, camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio());
                                camera.getCameraControl().setZoomRatio(zoomRatio);
                            }, 100);
                        }
                    });

                    // turn torch
                    binding.sequreTorch.performClick();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, ContextCompat.getMainExecutor(Sequre.this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            camera.getCameraControl().enableTorch(torch);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                new AlertDialog.Builder(Sequre.this)
                        .setTitle(R.string.text_camera_permission)
                        .setMessage(String.format(getString(R.string.text_must_allow), CONTEXT.getApplicationInfo().loadLabel(CONTEXT.getPackageManager())))
                        .show();
            }
        }
    }

    @Override
    public void finish() {
        super.finish();
        if (!FINISHED) {
            FINISHED = true;
            timelines[3] = System.currentTimeMillis();
            if (timelines[0] != null && timelines[1] != null && timelines[2] != null && timelines[3] != null) {
                String timeline = String.format("%d - %s ms - started\n", timelines[0], (timelines[0] - timelines[0]) / 1000.0);
                timeline += String.format("%d - %s ms - hold steady\n", timelines[1], (timelines[1] - timelines[0]) / 1000.0);
                timeline += String.format("%d - %s ms - captured\n", timelines[2], (timelines[2] - timelines[0]) / 1000.0);
                timeline += String.format("%d - %s ms - finish", timelines[3], (timelines[3] - timelines[0]) / 1000.0);
                result.timeline = timeline;
            }
            CALLBACK.onResult(result);
            try {
                watcher.cancel();
                watcher = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private String getFingerprint(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SigningInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                if (info.hasMultipleSigners()) {
                    return toHex(info.getApkContentsSigners()[0].toByteArray());
                } else {
                    return toHex(info.getSigningCertificateHistory()[0].toByteArray());
                }
            } else {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                for (Signature signature : info.signatures) {
                    return toHex(signature.toByteArray());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String toHex(byte[] array) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA");
            md.update(array);
            String sha = "";
            byte[] digests = md.digest();
            for (int i = 0; i < digests.length; i++) {
                sha += String.format("%02X", digests[i]);
                if (i < digests.length - 1) {
                    sha += ":";
                }
            }
            return sha;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void init(Context context, String applicationNumber) {
        this.CONTEXT = context;
        this.applicationNumber = applicationNumber;
        validate(null);
    }

    public void setLanguage(String language) {
        SharedPreferences pref = CONTEXT.getSharedPreferences(CONTEXT.getPackageName(), MODE_PRIVATE);
        pref.edit().putString("l", language).apply();
    }

    private void validate(Callback callback) {
        if (CONTEXT == null) {
            message = CONTEXT.getString(R.string.context_can_t_be_null);
            Utils.alert(CONTEXT, CONTEXT.getString(R.string.app_name), message);
            return;
        }
        if (applicationNumber == null) {
            message = CONTEXT.getString(R.string.application_number_can_t_be_null);
            Utils.alert(CONTEXT, CONTEXT.getString(R.string.app_name), message);
            return;
        }
        if (applicationNumber.isEmpty()) {
            message = CONTEXT.getString(R.string.application_number_can_t_be_empty);
            Utils.alert(CONTEXT, CONTEXT.getString(R.string.app_name), message);
            return;
        }
        String sha = getFingerprint(CONTEXT);
        Utils.ApiRequest request = Utils.newApiRequest();
        request.put("number", applicationNumber);
        request.put("bundle", CONTEXT.getPackageName());
        request.put("sha", sha);
        Utils.api(CONTEXT, "post", Utils.ACTION_VALIDATE, request.json(), response -> {
            if (response != null) {
                message = null;
                validated = true;
                if (response.code != 0x00) {
                    if (response.code == 0x05) {
                        Utils.alert(CONTEXT, CONTEXT.getString(R.string.app_name), response.message);
                    } else if (response.code == 0x03 || response.code == 0x04) {
                        message = "Number: " + applicationNumber + "\n" +
                                "Bundle: " + CONTEXT.getPackageName() + "\n" +
                                "SHA: " + sha + "\n" +
                                "Error: " + response.message;
                        System.out.println(message);
                        message = response.message;
                        Utils.alert(CONTEXT, CONTEXT.getString(R.string.app_name), message);
                    } else {
                        message = response.message;
                        Utils.alert(CONTEXT, CONTEXT.getString(R.string.app_name), message);
                    }
                } else if (callback != null) {
                    callback.on();
                }
            } else {
                Utils.alert(CONTEXT, CONTEXT.getString(R.string.app_name), CONTEXT.getString(R.string.text_check_your_internet_connection));
            }
        });
    }

    public void scan(id.sequre.sdk.Callback callback) {
        if (!validated) {
            validate(new Callback() {
                @Override
                void on() {
                    FINISHED = false;
                    CALLBACK = callback;
                    CONTEXT.startActivity(new Intent(CONTEXT, Sequre.class));
                }
            });
        } else if (message != null) {
            Utils.alert(CONTEXT, CONTEXT.getString(R.string.app_name), message);
        } else {
            FINISHED = false;
            CALLBACK = callback;
            CONTEXT.startActivity(new Intent(CONTEXT, Sequre.class));
        }
    }

    abstract class Callback {
        abstract void on();
    }
}