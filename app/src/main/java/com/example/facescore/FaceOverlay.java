package com.example.facescore;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;

public final class FaceOverlay extends View {
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint details = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Rect> boxes = Collections.emptyList();
    private List<PointF> marks = Collections.emptyList();
    private int photoWidth;
    private int photoHeight;

    public FaceOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        outline.setColor(context.getColor(R.color.face_outline));
        outline.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        outline.setStyle(Paint.Style.STROKE);
    }

    void setPreviewBoxes(List<Rect> boxes) {
        this.boxes = boxes;
        invalidate();
    }

    void setPhotoBox(Rect box, int width, int height, List<PointF> marks) {
        boxes = Collections.singletonList(box);
        this.marks = marks;
        photoWidth = width;
        photoHeight = height;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = 1f;
        float x = 0f;
        float y = 0f;
        if (photoWidth > 0 && photoHeight > 0) {
            scale = Math.min((float) getWidth() / photoWidth, (float) getHeight() / photoHeight);
            x = (getWidth() - photoWidth * scale) / 2f;
            y = (getHeight() - photoHeight * scale) / 2f;
        }
        for (Rect box : boxes) {
            drawCorners(canvas, x + box.left * scale, y + box.top * scale,
                    x + box.right * scale, y + box.bottom * scale);
        }
        details.setColor(0xFFFFE09A);
        for (PointF mark : marks) {
            canvas.drawCircle(x + mark.x * scale, y + mark.y * scale,
                    1.8f * outline.getStrokeWidth(), details);
        }
    }

    private void drawCorners(Canvas canvas, float left, float top, float right, float bottom) {
        float arm = Math.min(right - left, bottom - top) / 5;
        for (int directionX : new int[]{1, -1}) {
            float x = directionX > 0 ? left : right;
            for (int directionY : new int[]{1, -1}) {
                float y = directionY > 0 ? top : bottom;
                canvas.drawLine(x, y, x + directionX * arm, y, outline);
                canvas.drawLine(x, y, x, y + directionY * arm, outline);
            }
        }
    }
}
