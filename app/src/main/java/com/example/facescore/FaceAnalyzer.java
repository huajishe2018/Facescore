package com.example.facescore;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.List;

final class FaceAnalyzer implements AutoCloseable {
    private final FaceScorer scorer;
    private final FaceDetector detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build());

    FaceAnalyzer(FaceScorer scorer) {
        this.scorer = scorer;
    }

    Result analyze(Bitmap photo) throws Exception {
        List<Face> faces = Tasks.await(detector.process(InputImage.fromBitmap(photo, 0)));
        if (faces.isEmpty()) {
            throw new AnalysisException(R.string.no_face);
        }
        if (faces.size() > 1) {
            throw new AnalysisException(R.string.multiple_faces);
        }
        Face face = faces.get(0);
        Rect bounds = new Rect(face.getBoundingBox());
        if (!bounds.intersect(0, 0, photo.getWidth(), photo.getHeight())) {
            throw new AnalysisException(R.string.face_outside_photo);
        }
        PointF leftEye = point(face, FaceLandmark.LEFT_EYE);
        PointF rightEye = point(face, FaceLandmark.RIGHT_EYE);
        PointF nose = point(face, FaceLandmark.NOSE_BASE);
        PointF mouth = point(face, FaceLandmark.MOUTH_BOTTOM);
        PointF leftMouth = point(face, FaceLandmark.MOUTH_LEFT);
        PointF rightMouth = point(face, FaceLandmark.MOUTH_RIGHT);
        Measurements measures = new Measurements(
                ratio(leftEye, rightEye, bounds.width()),
                ratio(leftMouth, rightMouth, bounds.width()),
                ratio(nose, mouth, bounds.height()),
                face.getHeadEulerAngleY(), face.getHeadEulerAngleZ());
        List<PointF> marks = new java.util.ArrayList<>();
        for (PointF mark : new PointF[]{leftEye, rightEye, nose, mouth, leftMouth, rightMouth}) {
            if (mark != null) marks.add(mark);
        }
        Bitmap crop = Bitmap.createBitmap(photo, bounds.left, bounds.top, bounds.width(), bounds.height());
        return new Result(photo, bounds, scorer.score(crop), measures, marks);
    }

    private static @Nullable PointF point(Face face, int type) {
        FaceLandmark landmark = face.getLandmark(type);
        return landmark == null ? null : landmark.getPosition();
    }

    private static @Nullable Float ratio(@Nullable PointF a, @Nullable PointF b, int reference) {
        if (a == null || b == null) return null;
        return (float) Math.hypot(a.x - b.x, a.y - b.y) / reference;
    }

    @Override
    public void close() {
        detector.close();
    }

    record Measurements(@Nullable Float eyeRatio, @Nullable Float mouthRatio,
                        @Nullable Float noseToLipRatio, float yaw, float roll) {}

    record Result(Bitmap photo, Rect bounds, FaceScorer.Score score,
                  Measurements measures, List<PointF> marks) {}

    static final class AnalysisException extends Exception {
        final int messageRes;

        AnalysisException(int messageRes) {
            this.messageRes = messageRes;
        }
    }
}
