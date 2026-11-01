package ru.noties.jlatexmath.awt.geom;

public abstract class Rectangle2D {

    public abstract float getY();

    public abstract float getHeight();

    public abstract float getWidth();

    public abstract float getX();

    public static class Float extends Rectangle2D {

        public float x;
        public float y;
        public float w;
        public float h;

        public Float(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override
        public float getY() {
            return y;
        }

        @Override
        public float getHeight() {
            return h;
        }

        @Override
        public float getWidth() {
            return w;
        }

        @Override
        public float getX() {
            return x;
        }

        @Override
        public String toString() {
            return "Float{" +
                    "x=" + x +
                    ", y=" + y +
                    ", w=" + w +
                    ", h=" + h +
                    '}';
        }
    }
}
