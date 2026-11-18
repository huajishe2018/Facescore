"""Train and export a local MobileNetV2 score regressor for FaceScorer."""

import argparse
import json
from pathlib import Path
import random

import cv2
import numpy as np
import tensorflow as tf


SIZE = 128


def load_faces(root, cache):
    if cache.exists():
        with np.load(cache) as saved:
            return saved["images"], saved["scores"], saved["groups"]

    images, scores, groups = [], [], []
    paths = {p.name: p for p in root.rglob("*.jpg")}
    labels = next(root.rglob("All_labels.txt"), None)
    if not paths or labels is None:
        raise SystemExit("Expected SCUT-FBP5500 JPG images and All_labels.txt.")
    detector = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    for row in labels.read_text(encoding="utf-8-sig").splitlines():
        if not row.strip():
            continue
        name, score = row.split()
        image = cv2.imdecode(np.fromfile(paths[name], dtype=np.uint8), cv2.IMREAD_COLOR)
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        boxes = detector.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(50, 50))
        if len(boxes) == 0:
            continue
        x, y, width, height = max(boxes, key=lambda box: box[2] * box[3])
        face = image[y:y + height, x:x + width]
        face = cv2.resize(face, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
        images.append(cv2.cvtColor(face, cv2.COLOR_BGR2RGB))
        scores.append(float(score))
        groups.append(name[:2])
    print(f"Usable face crops: {len(images)} / {len(paths)}", flush=True)
    result = np.stack(images), np.asarray(scores, dtype=np.float32), np.asarray(groups)
    np.savez(cache, images=result[0], scores=result[1], groups=result[2])
    return result


def split(groups):
    rng = random.Random(42)
    train, validation, test = [], [], []
    for group in sorted(set(groups)):
        indices = np.flatnonzero(groups == group).tolist()
        rng.shuffle(indices)
        first, second = int(len(indices) * .7), int(len(indices) * .85)
        train.extend(indices[:first])
        validation.extend(indices[first:second])
        test.extend(indices[second:])
    return train, validation, test


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", type=Path, default=Path("training/output"))
    parser.add_argument("--epochs", type=int, default=12)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    tf.keras.utils.set_random_seed(42)
    images, scores, groups = load_faces(args.dataset, args.output / "crops.npz")
    train, validation, test = split(groups)

    backbone = tf.keras.applications.MobileNetV2(
        input_shape=(SIZE, SIZE, 3), alpha=0.5, include_top=False, pooling="avg")
    backbone.trainable = False
    inputs = tf.keras.Input((SIZE, SIZE, 3), dtype=tf.float32)
    x = tf.keras.layers.Rescaling(1 / 127.5, offset=-1)(inputs)
    features = backbone(x, training=False)
    encoder = tf.keras.Model(inputs, features)
    print("Extracting MobileNetV2 features", flush=True)
    vectors = encoder.predict(images, batch_size=32, verbose=0)
    head = tf.keras.Sequential([
        tf.keras.Input((vectors.shape[-1],)),
        tf.keras.layers.Dense(64, activation="relu"),
        tf.keras.layers.Dense(1),
    ])
    head.compile(optimizer=tf.keras.optimizers.Adam(0.001), loss="mse")
    head.fit(
        vectors[train], scores[train], validation_data=(vectors[validation], scores[validation]),
        batch_size=32, epochs=args.epochs, verbose=2,
        callbacks=[tf.keras.callbacks.EarlyStopping(patience=3, restore_best_weights=True)],
    )
    model = tf.keras.Model(inputs, head(features))
    predictions = head.predict(vectors[test], batch_size=32, verbose=0).ravel().clip(1, 5)
    actual = scores[test]
    metrics = {
        "train": len(train), "validation": len(validation), "test": len(test),
        "mae": float(np.mean(np.abs(predictions - actual))),
        "rmse": float(np.sqrt(np.mean(np.square(predictions - actual)))),
        "pearson": float(np.corrcoef(predictions, actual)[0, 1]),
        "mean_score_baseline_mae": float(np.mean(np.abs(scores[train].mean() - actual))),
    }
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    flatbuffer = converter.convert()
    interpreter = tf.lite.Interpreter(model_content=flatbuffer)
    interpreter.allocate_tensors()
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], images[test[:1]].astype(np.float32))
    interpreter.invoke()
    output = float(interpreter.get_tensor(interpreter.get_output_details()[0]["index"])[0, 0])
    np.testing.assert_allclose(np.clip(output, 1, 5), predictions[0], atol=1e-4)
    (args.output / "face_score.tflite").write_bytes(flatbuffer)
    (args.output / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n")
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
