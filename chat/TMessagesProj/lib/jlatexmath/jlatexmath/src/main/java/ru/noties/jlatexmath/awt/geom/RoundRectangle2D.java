package ru.noties.jlatexmath.awt.geom;

public class RoundRectangle2D {

    public static class Float {

        public float x;

        public float y;

        public float width;

        public float height;

        public float arcwidth;

        public float archeight;

        public Float(float x, float y, float width, float height, float arcwidth, float archeight) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.arcwidth = arcwidth;
            this.archeight = archeight;
        }

        @Override
        public String toString() {
            return "Float{" +
                    "x=" + x +
                    ", y=" + y +
                    ", width=" + width +
                    ", height=" + height +
                    ", arcwidth=" + arcwidth +
                    ", archeight=" + archeight +
                    '}';
        }
    }
}
