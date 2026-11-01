package ru.noties.jlatexmath.awt;

public class BasicStroke implements Stroke {

    public final static int JOIN_MITER = 0;

    public final static int CAP_BUTT = 0;

    private final float width;
    private final float miterLimit;

    public BasicStroke(float width, int i, int i2) {
        this(width, i, i2, 10.F);
    }

    public BasicStroke(float width, int i, int i2, float miterLimit) {
        this.width = width;
        this.miterLimit = miterLimit;
    }

    @Override
    public float width() {
        return width;
    }

    @Override
    public float miterLimit() {
        return miterLimit;
    }

    @Override
    public String toString() {
        return "BasicStroke{" +
                "width=" + width +
                ", miterLimit=" + miterLimit +
                '}';
    }
}
