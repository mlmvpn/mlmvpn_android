package org.mlmvpn.chat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * MLMCHAT: Gereh's own heart (Ehsan, 31 Oct 2026: «یک ایموجی مخصوص حمایت، ترجیحاً اختصاصی
 * متحرک»), drawn here rather than taken from a sticker set: a heart in two warm colours that beats
 * twice and rests. The large one (the support sheet) also glows with each beat and sends small
 * hearts up around it. It draws only while it is on the screen.
 */
public class GerehHeartView extends View {
    /** One beat (two pulses) and its rest; the small one rests longer, it sits on the main screen. */
    private static final long BEAT_MS = 700, REST_SMALL_MS = 3300, REST_LARGE_MS = 1100;
    private static final int TOP = 0xFFFF7AA2, BOTTOM = 0xFFFF2E55;

    private final boolean large;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heart = new Path();
    private final Path unit = new Path();
    private final RectF oval = new RectF();
    private final long born = SystemClock.uptimeMillis();
    private int shaped = -1;

    public GerehHeartView(Context context, boolean large) {
        super(context);
        this.large = large;
        shine.setColor(0x59FFFFFF);
        small.setColor(0xFFFF5C84);
        // a heart in a unit square, its point at the bottom
        unit.moveTo(0.5f, 0.92f);
        unit.cubicTo(0.12f, 0.64f, 0.0f, 0.44f, 0.06f, 0.26f);
        unit.cubicTo(0.13f, 0.05f, 0.40f, 0.03f, 0.5f, 0.23f);
        unit.cubicTo(0.60f, 0.03f, 0.87f, 0.05f, 0.94f, 0.26f);
        unit.cubicTo(1.0f, 0.44f, 0.88f, 0.64f, 0.5f, 0.92f);
        unit.close();
    }

    /** A smooth bump from 0 up to 1 and back, `len` ms long, starting at `at`. */
    private static float bump(long t, long at, long len) {
        if (t < at || t > at + len) {
            return 0;
        }
        return (float) Math.sin(Math.PI * (t - at) / (double) len);
    }

    private void shape(int side) {
        if (shaped == side) {
            return;
        }
        shaped = side;
        heart.reset();
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(side, side);
        unit.transform(m, heart);
        fill.setShader(new LinearGradient(0, 0, side, side, TOP, BOTTOM, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        long period = BEAT_MS + (large ? REST_LARGE_MS : REST_SMALL_MS);
        long t = (SystemClock.uptimeMillis() - born) % period;
        float beat = 0.16f * bump(t, 0, 220) + 0.10f * bump(t, 300, 220);
        // the heart takes most of a small view; a large one leaves room for its glow and the small hearts
        int side = (int) (Math.min(w, h) * (large ? 0.56f : 0.86f));
        shape(side);
        float cx = w / 2f, cy = h / 2f;
        if (large) {
            float r = Math.min(w, h) * 0.46f;
            int a = (int) (60 + 120 * Math.min(1f, beat * 6f));
            glow.setShader(new RadialGradient(cx, cy, r, (a << 24) | 0xFF6F92, 0x00FF6F92, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, glow);
            drawSmallHearts(canvas, w, h);
        }
        canvas.save();
        float s = 1f + beat;
        canvas.translate(cx - side / 2f, cy - side / 2f);
        canvas.scale(s, s, side / 2f, side / 2f);
        canvas.drawPath(heart, fill);
        // a soft shine on the left lobe
        oval.set(side * 0.18f, side * 0.17f, side * 0.38f, side * 0.33f);
        canvas.rotate(-28, oval.centerX(), oval.centerY());
        canvas.drawOval(oval, shine);
        canvas.restore();
        if (isShown()) {
            // every frame while it moves; the small one sleeps through its rest
            if (large || t < BEAT_MS) {
                postInvalidateOnAnimation();
            } else {
                postInvalidateDelayed(period - t);
            }
        }
    }

    /** Five small hearts that rise around the large one and fade, each in its own time. */
    private void drawSmallHearts(Canvas canvas, int w, int h) {
        long now = SystemClock.uptimeMillis() - born;
        float base = Math.min(w, h);
        for (int i = 0; i < 5; i++) {
            long cycle = 2600 + i * 170;
            float p = ((now + i * 530L) % cycle) / (float) cycle;
            float x = w / 2f + (float) Math.sin((i * 1.7) + p * Math.PI * 2) * base * 0.10f + (i - 2) * base * 0.13f;
            float y = h * (0.78f - 0.62f * p);
            float side = base * (0.07f + 0.02f * (i % 3));
            int alpha = (int) (255 * Math.sin(Math.PI * p) * 0.85f);
            small.setAlpha(Math.max(0, alpha));
            canvas.save();
            canvas.translate(x - side / 2f, y - side / 2f);
            canvas.scale(side, side);
            canvas.drawPath(unit, small);
            canvas.restore();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        invalidate();
    }
}
