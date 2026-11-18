package com.example.facescore;

import android.graphics.Bitmap;

@FunctionalInterface
public interface FaceScorer {
    // The model implementation owns the resize and normalization used in training.
    Score score(Bitmap uprightFaceCrop) throws Exception;

    record Score(float value, int maximum, boolean example) {}
}
