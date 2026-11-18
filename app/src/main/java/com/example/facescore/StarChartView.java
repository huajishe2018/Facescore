package com.example.facescore;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public final class StarChartView extends View {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ornament = new Path();
    private final float[][] stars = new float[52][3];

    public StarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Random random = new Random(42);
        for (float[] star : stars) {
            star[0] = random.nextFloat() * 264 - 132;
            star[1] = random.nextFloat() * 190 - 95;
            star[2] = 0.5f + random.nextFloat();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        float scale = Math.min(getWidth() / 270f, getHeight() / 210f);
        canvas.scale(scale, scale);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(0x80ECCC8C);
        for (float[] star : stars) {
            canvas.drawCircle(star[0], star[1], star[2], ink);
        }
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(0.8f);
        ink.setColor(0xBCECCC8C);
        for (int radius : new int[]{65, 81, 86, 94}) {
            canvas.drawCircle(0, 0, radius, ink);
        }
        canvas.save();
        for (int i = 0; i < 72; i++) {
            canvas.drawLine(0, -93, 0, i % 6 == 0 ? -82 : -89, ink);
            if (i % 6 == 0) {
                canvas.drawLine(0, -81, 0, -65, ink);
                canvas.drawCircle(0, -73, 2.2f, ink);
            }
            canvas.rotate(5);
        }
        canvas.restore();

        ink.setColor(0xFFEFD296);
        canvas.drawCircle(0, 0, 23, ink);
        canvas.drawCircle(0, 0, 17, ink);
        canvas.save();
        for (int i = 0; i < 16; i++) {
            ornament.rewind();
            ornament.moveTo(-4, -26);
            ornament.lineTo(0, i % 2 == 0 ? -54 : -37);
            ornament.lineTo(4, -26);
            canvas.drawPath(ornament, ink);
            canvas.rotate(22.5f);
        }
        canvas.restore();
        ink.setStyle(Paint.Style.FILL);
        diamond(canvas, -110, 0);
        diamond(canvas, 110, 0);
        diamond(canvas, 0, 0);
        canvas.restore();
    }

    private void diamond(Canvas canvas, float x, float y) {
        ornament.rewind();
        ornament.moveTo(x, y - 8);
        ornament.lineTo(x + 3, y);
        ornament.lineTo(x, y + 8);
        ornament.lineTo(x - 3, y);
        ornament.close();
        canvas.drawPath(ornament, ink);
    }
}
