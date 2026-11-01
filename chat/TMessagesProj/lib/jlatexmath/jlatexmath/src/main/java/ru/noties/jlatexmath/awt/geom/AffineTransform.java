package ru.noties.jlatexmath.awt.geom;

import android.graphics.Canvas;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class AffineTransform implements Cloneable {

    public static AffineTransform create(Canvas canvas) {
        return new AffineTransform(null, canvas);
    }

    private final AffineTransform parent;
    private final Canvas canvas;
    private int save = -1;

    private double scaleX = 1.0;
    private double scaleY = 1.0;

    private float translateX;
    private float translateY;

    private AffineTransform(@Nullable AffineTransform parent, @NonNull Canvas canvas) {
        this.parent = parent;
        this.canvas = canvas;
    }

    public AffineTransform save() {
        final AffineTransform transform = new AffineTransform(this, canvas);
        transform.setScale(scaleX, scaleY);
        transform.setTranslate(translateX, translateY);
        transform.save = canvas.save();
        return transform;
    }

    public AffineTransform restore() {
        if (save != -1) {
            canvas.restoreToCount(save);
            save = -1;
        }
        if (parent == null) {
            throw new IllegalStateException("Cannot restore root transform instance");
        }
        return parent;
    }

    public double getScaleX() {
        return scaleX;
    }

    public double getScaleY() {
        return scaleY;
    }

    public void scale(double v, double v1) {
        setScale(v, v1);
        canvas.scale((float) v, (float) v1);
    }

    public void translate(float x, float y) {
//        Debug.i("x: %s, y: %s, by:{%s, %s}", x, y, x - translateX, y - translateY);
        canvas.translate(x, y);
        setTranslate(x, y);
    }

    public void setScale(double x, double y) {
        this.scaleX = x;
        this.scaleY = y;
    }

    public void setTranslate(float x, float y) {
        this.translateX = x;
        this.translateY = y;
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public float translateX() {
        return translateX;
    }

    public float translateY() {
        return translateY;
    }

    public AffineTransform clone() {
        final AffineTransform transform = new AffineTransform(this, canvas);
        transform.setScale(scaleX, scaleY);
        transform.setTranslate(translateX, translateY);
        transform.save = canvas.save();
        return transform;
    }
}
