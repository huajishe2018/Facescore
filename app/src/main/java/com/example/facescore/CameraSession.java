package com.example.facescore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.mlkit.vision.MlKitAnalyzer;
import androidx.camera.view.CameraController;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class CameraSession implements AutoCloseable {
    private final LifecycleCameraController controller;
    private final FaceDetector detector;
    private final Executor main;
    private PreviewView preview;
    private volatile boolean closed;
    private final Context context;
    private ExecutorService liveWorker;
    private long nextSample;

    CameraSession(Context context) {
        this.context = context.getApplicationContext();
        main = ContextCompat.getMainExecutor(context);
        controller = new LifecycleCameraController(context);
        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build());
    }

    void start(LifecycleOwner owner, PreviewView preview, Consumer<List<Rect>> onFaces,
               Runnable onReady, IntConsumer onFailure) {
        this.preview = preview;
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        try {
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE | CameraController.IMAGE_ANALYSIS);
            controller.setImageAnalysisAnalyzer(main, new MlKitAnalyzer(
                    Collections.singletonList(detector), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                    main, result -> {
                        if (closed) return;
                        List<Rect> boxes = new ArrayList<>();
                        List<Face> faces = result.getValue(detector);
                        if (faces != null) {
                            for (Face face : faces) {
                                boxes.add(face.getBoundingBox());
                            }
                        }
                        onFaces.accept(boxes);
                    }));
            preview.setController(controller);
        } catch (RuntimeException error) {
            onFailure.accept(R.string.camera_unavailable);
            return;
        }
        bind(owner, onReady, onFailure);
    }

    void startLive(LifecycleOwner owner, PreviewView preview, Consumer<FaceScorer.Score> onScore,
                   IntConsumer onStatus, IntConsumer onFailure) {
        this.preview = preview;
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        LocalFaceScorer scorer = new LocalFaceScorer(context.getAssets());
        FaceAnalyzer analyzer = new FaceAnalyzer(scorer);
        liveWorker = Executors.newSingleThreadExecutor();
        closeLive = () -> {
            analyzer.close();
            scorer.close();
        };
        try {
            controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS);
            controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
            controller.setImageAnalysisAnalyzer(liveWorker, image -> {
                Bitmap photo;
                try {
                    long now = SystemClock.elapsedRealtime();
                    if (closed || now < nextSample) return;
                    nextSample = now + 500L;
                    photo = uprightBitmap(image);
                } catch (RuntimeException error) {
                    reportStatus(onStatus, R.string.analysis_failed);
                    return;
                } finally {
                    image.close();
                }
                try {
                    FaceScorer.Score score = analyzer.analyze(photo).score();
                    main.execute(() -> {
                        if (!closed) onScore.accept(score);
                    });
                } catch (FaceAnalyzer.AnalysisException error) {
                    reportStatus(onStatus, error.messageRes);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (Exception error) {
                    reportStatus(onStatus, R.string.analysis_failed);
                }
            });
            preview.setController(controller);
            bind(owner, () -> onStatus.accept(R.string.camera_waiting_for_face), onFailure);
        } catch (RuntimeException error) {
            onFailure.accept(R.string.camera_unavailable);
        }
    }

    private Runnable closeLive;

    private void reportStatus(IntConsumer onStatus, int message) {
        main.execute(() -> {
            if (!closed) onStatus.accept(message);
        });
    }

    private void bind(LifecycleOwner owner, Runnable onReady, IntConsumer onFailure) {
        ListenableFuture<Void> initialized = controller.getInitializationFuture();
        initialized.addListener(() -> {
            if (closed) return;
            try {
                initialized.get();
                controller.setCameraSelector(controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA);
                controller.bindToLifecycle(owner);
                onReady.run();
            } catch (Exception error) {
                onFailure.accept(R.string.camera_unavailable);
            }
        }, main);
    }

    void takePicture(Consumer<Bitmap> onCapture, IntConsumer onFailure) {
        try {
            controller.takePicture(ForkJoinPool.commonPool(), new ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onCaptureSuccess(@NonNull ImageProxy image) {
                    Bitmap photo = null;
                    try {
                        photo = uprightBitmap(image);
                    } catch (RuntimeException ignored) {
                        // Report the decode failure on the UI thread after releasing the frame.
                    } finally {
                        image.close();
                    }
                    Bitmap captured = photo;
                    main.execute(() -> {
                        if (closed) return;
                        if (captured == null) {
                            onFailure.accept(R.string.capture_failed);
                        } else {
                            onCapture.accept(captured);
                        }
                    });
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    main.execute(() -> {
                        if (!closed) onFailure.accept(R.string.capture_failed);
                    });
                }
            });
        } catch (IllegalStateException | SecurityException error) {
            onFailure.accept(R.string.capture_failed);
        }
    }

    private static Bitmap uprightBitmap(ImageProxy image) {
        Bitmap bitmap = image.toBitmap();
        Rect crop = new Rect(image.getCropRect());
        if (!crop.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) {
            throw new IllegalArgumentException("Invalid capture crop");
        }
        float scale = FaceScoreViewModel.imageScale(crop.width(), crop.height());
        Matrix transform = new Matrix();
        transform.setRotate(image.getImageInfo().getRotationDegrees());
        transform.postScale(scale, scale);
        return Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height(), transform, true);
    }

    @Override
    public void close() {
        closed = true;
        controller.clearImageAnalysisAnalyzer();
        if (preview != null) preview.setController(null);
        controller.unbind();
        detector.close();
        if (liveWorker != null) {
            liveWorker.execute(closeLive);
            liveWorker.shutdown();
        }
    }
}
