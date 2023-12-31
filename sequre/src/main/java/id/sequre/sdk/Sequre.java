package id.sequre.sdk;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Environment;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.SeekBar;

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

import com.google.android.gms.tflite.client.TfLiteInitializationOptions;
import com.google.common.util.concurrent.ListenableFuture;

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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

import id.sequre.sdk.databinding.SequreBinding;


public class Sequre extends AppCompatActivity {

    private Context context;
    private String applicationNumber;
    private static Callback callback;

    private Random random = new Random();

    private static int PERMISSION_REQUEST = 0x01;
    private SequreBinding binding;
    private ImageAnalysis imageAnalysis;
    private ImageCapture imageCapture;
    private Camera camera;
    private boolean resized, torch, processing;
    private double moveCloser = 0.6, moveFurther = 0.8, distancesLength = 3, distancesMax = 40, percentage = 0.9, ratio, left, top, width, height, vertical, horizontal;
    private int eventColor = Color.RED;

    private ObjectDetector objectDetector, objectDetectorV2;
    private ImageClassifier imageClassifier;
    private List<Double> distances = new ArrayList<>();

    private View mask;
    private Result result = new Result();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = SequreBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
            detect(imageProxy);
            imageProxy.close();
        });

        imageCapture = new ImageCapture.Builder()
//                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
//                .setTargetRotation(Surface.ROTATION_0)
                .build();

        binding.sequreBack.setOnClickListener(view -> finish());

        binding.sequreTorch.setOnClickListener(view -> {
            if (camera.getCameraInfo().hasFlashUnit()) {
                camera.getCameraControl().enableTorch((torch = !torch));
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

        requestPermissions();
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
        if (!processing && objectDetector != null) {
            processing = true;
            ImageProcessor.Builder builder = new ImageProcessor.Builder();
            ImageProcessor imageProcessor = builder.build();

            Bitmap imageBuffer = Bitmap.createBitmap(imageProxy.getWidth(), imageProxy.getHeight(), Bitmap.Config.ARGB_8888);
            imageBuffer.copyPixelsFromBuffer(imageProxy.getPlanes()[0].getBuffer());

            TensorImage image = imageProcessor.process(TensorImage.fromBitmap(imageBuffer));

            List<Detection> detections = objectDetector.detect(image);
            if (detections.size() > 0) {

                Size size = new Size(imageProxy.getHeight(), imageProxy.getWidth());
                RectF boundingBox = transform(detections.get(0).getBoundingBox());

                double height = size.getHeight() * percentage;
                double width = height * ratio;

                double vertical = (size.getHeight() - height) / 2;
                double horizontal = (size.getWidth() - width) / 2;
//                System.out.println(":: " + detections.size() + " : " + size.getWidth() + "; " + size.getHeight() + ";" + horizontal + "; " + vertical + "; " + width + "; " + height + " boundingBox: " + boundingBox.left + "; " + boundingBox.top + "; " + boundingBox.right + "; " + boundingBox.bottom + "; " + boundingBox.width() + "; " + boundingBox.height());
                if (!(boundingBox.left >= horizontal && boundingBox.right <= horizontal + width &&
                        boundingBox.top >= vertical && boundingBox.bottom <= vertical + height)) {
                    eventColor = Color.WHITE;
                    binding.sequreInfo.setText(R.string.text_place_qr_inside_frame);
                    mask.invalidate();
                    processing = false;
                } else {
                    double percentage = boundingBox.width() / width;
                    if (percentage < moveCloser) {
                        eventColor = Color.WHITE;
                        binding.sequreInfo.setText(R.string.text_move_closer);
                        mask.invalidate();
                        processing = false;
                    } else if (percentage > 0.8) {
                        eventColor = Color.WHITE;
                        binding.sequreInfo.setText(R.string.text_move_further);
                        mask.invalidate();
                        processing = false;
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
                            binding.sequreInfo.setText(R.string.text_hold_steady);
                            mask.invalidate();
//                            processing = false;
                            if (average <= distancesMax) {
                                // capture
                                imageCapture.takePicture(ContextCompat.getMainExecutor(Sequre.this), new ImageCapture.OnImageCapturedCallback() {
                                    @Override
                                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                                        super.onCaptureSuccess(image);
                                        detect2(image);
                                    }

                                    @Override
                                    public void onError(@NonNull ImageCaptureException exception) {
                                        super.onError(exception);
                                        processing = false;
                                    }
                                });
                            } else {
                                processing = false;
                            }
                        } else {
                            processing = false;
                        }
                    }
                }
            } else {
                eventColor = Color.WHITE;
                binding.sequreInfo.setText(R.string.text_find_qr);
                mask.invalidate();
                processing = false;
            }
        }
    }

    private void detect2(ImageProxy imageProxy) {
        if (objectDetectorV2 != null) {
            ImageProcessor.Builder builder = new ImageProcessor.Builder();
            ImageProcessor imageProcessor = builder.build();

            ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
            int length = buffer.remaining();
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, length);
            TensorImage image = imageProcessor.process(TensorImage.fromBitmap(bitmap));
            List<Detection> detections = objectDetectorV2.detect(image);
            if (detections.size() > 0) {

                RectF boundingBox = detections.get(0).getBoundingBox();
//                System.out.println(":: boundingBox: " + boundingBox.left + "; " + boundingBox.top + "; " + boundingBox.width() + "; " + boundingBox.height());

                Bitmap cropped = Bitmap.createBitmap(bitmap, (int) boundingBox.left, (int) boundingBox.top, (int) boundingBox.width(), (int) boundingBox.height());


                Bitmap resized = Bitmap.createBitmap(cropped.getWidth(), cropped.getWidth(), Bitmap.Config.ARGB_8888);
                resized.eraseColor(Color.WHITE);
                Canvas canvas = new Canvas(resized);
                canvas.drawBitmap(cropped, (float) 0, (float) ((cropped.getWidth() - cropped.getHeight()) / 2d), null);

                image = imageProcessor.process(TensorImage.fromBitmap(cropped));
                List<Classifications> classifications = imageClassifier.classify(image);

                if (classifications.size() > 0 && classifications.get(0).getCategories().size() > 0) {
                    Category category = classifications.get(0).getCategories().get(0);
                    System.out.println(":: " + category.getLabel() + "; " + category.getScore());
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

//                    save(bitmap);
//                    save(resized);

                    finish();
                } else {
                    processing = false;
                }
            } else {
                processing = false;
            }
        } else {
            processing = false;
        }
    }

    private RectF transform(RectF box) {
        return new RectF(box.top, box.left, box.bottom, box.right);
    }

    private void save(Bitmap bitmap) {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date());
            String name = timestamp + "_" + random.nextInt() + ".png";
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initMask(ImageProxy image) {
        ratio = 1.0 * image.getWidth() / image.getHeight();
        ViewGroup.LayoutParams params = binding.sequrePreviews.getLayoutParams();
        params.width = binding.sequrePreviews.getWidth();
        params.height = (int) (params.width * ratio);
        binding.sequrePreviews.requestLayout();
        resized = true;

        ratio = 2.0 / 4.0;
        height = params.height * percentage;
        width = height * ratio;


        vertical = (params.height - height) / 2;
        horizontal = (params.width - width) / 2;

//        System.out.println(":: params: " + params.width + "; " + params.height + "; " + width + "; " + height + "; " + vertical + "; " + horizontal);

        Paint paint = new Paint();
        int iw = 10;
        int il = 80;
        mask = new View(Sequre.this) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                super.onDraw(canvas);
                // draw mask
                paint.setColor(getResources().getColor(R.color.transparent));

                canvas.drawRect(0, 0, params.width, (float) vertical, paint);
                canvas.drawRect(0, (float) (params.height - vertical), params.width, params.height, paint);
                canvas.drawRect(0, (float) vertical, (float) horizontal, (float) (params.height - vertical), paint);
                canvas.drawRect((float) (params.width - horizontal), (float) vertical, params.width, (float) (params.height - vertical), paint);

                // draw event indicator
                paint.setColor(eventColor);
                canvas.drawRect((float) (horizontal - iw), (float) (vertical - iw), (float) (horizontal + il - iw), (float) vertical, paint);
                canvas.drawRect((float) (horizontal - iw), (float) (vertical - iw), (float) horizontal, (float) (vertical + il - iw), paint);

                canvas.drawRect((float) (horizontal - iw), (float) (params.height - vertical + iw), (float) horizontal, (float) (params.height - vertical - il + iw), paint);
                canvas.drawRect((float) (horizontal - iw), (float) (params.height - vertical + iw), (float) (horizontal + il - iw), (float) (params.height - vertical), paint);

                canvas.drawRect((float) (params.width - horizontal + iw), (float) (vertical - iw), (float) (params.width - horizontal - il + iw), (float) vertical, paint);
                canvas.drawRect((float) (params.width - horizontal + iw), (float) (vertical - iw), (float) (params.width - horizontal), (float) (vertical + il - iw), paint);

                canvas.drawRect((float) (params.width - horizontal + iw), (float) (params.height - vertical + iw), (float) (params.width - horizontal - il + iw), (float) (params.height - vertical), paint);
                canvas.drawRect((float) (params.width - horizontal + iw), (float) (params.height - vertical + iw), (float) (params.width - horizontal), (float) (params.height - vertical - il + iw), paint);
            }
        };

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
                                System.out.println(":: setZoomRatio: " + zoomRatio);
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                new AlertDialog.Builder(Sequre.this)
                        .setTitle(R.string.text_camera_permission)
                        .setMessage(R.string.text_must_allow)
                        .show();
            }
        }
    }

    @Override
    public void finish() {
        super.finish();
        callback.onResult(result);
    }

    public void init(Context context, String applicationNumber) {
        this.context = context;
        this.applicationNumber = applicationNumber;
    }

    public void scan(Callback callback) {
        this.callback = callback;
        context.startActivity(new Intent(context, Sequre.class));
    }
}