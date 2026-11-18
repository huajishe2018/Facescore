package com.example.facescore;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.FileNotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class LocalFaceScorer implements FaceScorer, AutoCloseable {
    private static final int SIZE = 128;
    private final AssetManager assets;
    private Interpreter runtime;
    private boolean missing;

    LocalFaceScorer(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public synchronized Score score(Bitmap face) throws Exception {
        if (runtime == null && !missing) {
            loadModel();
        }
        if (missing) {
            return new Score(3.8f, 5, true);
        }
        Bitmap resized = Bitmap.createScaledBitmap(face, SIZE, SIZE, true);
        int[] pixels = new int[SIZE * SIZE];
        resized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        ByteBuffer input = ByteBuffer.allocateDirect(pixels.length * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int pixel : pixels) {
            input.putFloat((pixel >>> 16) & 255);
            input.putFloat((pixel >>> 8) & 255);
            input.putFloat(pixel & 255);
        }
        input.rewind();
        float[][] output = new float[1][1];
        runtime.run(input, output);
        float score = output[0][0];
        if (!Float.isFinite(score)) {
            throw new IllegalStateException("Model produced an invalid score");
        }
        return new Score(Math.max(1f, Math.min(5f, score)), 5, false);
    }

    private void loadModel() throws Exception {
        byte[] model;
        try (InputStream stream = assets.open("face_score.tflite");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                bytes.write(chunk, 0, count);
            }
            model = bytes.toByteArray();
        } catch (FileNotFoundException e) {
            missing = true;
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(model.length).order(ByteOrder.nativeOrder());
        buffer.put(model).rewind();
        Interpreter interpreter = new Interpreter(buffer, new Interpreter.Options().setNumThreads(2));
        if (!Arrays.equals(interpreter.getInputTensor(0).shape(), new int[]{1, SIZE, SIZE, 3})
                || !Arrays.equals(interpreter.getOutputTensor(0).shape(), new int[]{1, 1})
                || interpreter.getInputTensor(0).dataType() != DataType.FLOAT32
                || interpreter.getOutputTensor(0).dataType() != DataType.FLOAT32) {
            interpreter.close();
            throw new IllegalArgumentException("Unexpected face model tensor format");
        }
        runtime = interpreter;
    }

    @Override
    public synchronized void close() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
