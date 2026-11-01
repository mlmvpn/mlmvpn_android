package ru.noties.jlatexmath.awt;

public class Color {

    public static final Color black = new Color(0xFF000000);
    public static final Color white = new Color(0xFFffffff);
    public static final Color red = new Color(0xFFff0000);
    public static final Color green = new Color(0xFF00ff00);
    public static final Color blue = new Color(0xFF0000ff);
    public static final Color cyan = new Color(android.graphics.Color.parseColor("cyan"));
    public static final Color magenta = new Color(android.graphics.Color.parseColor("magenta"));
    public static final Color yellow = new Color(android.graphics.Color.parseColor("yellow"));

    public static final Color BLACK = black;
    public static final Color RED = red;

    public static Color decode(String s) {
        return new Color(android.graphics.Color.parseColor(s));
    }

    private final int color;

    public Color(int color) {
        this.color = color;
    }

    public Color(int r, int g, int b) {
        this(android.graphics.Color.rgb(r, g, b));
    }

    public Color(float r, float g, float b) {
        this(
                (int) (r * 255 + .5F),
                (int) (g * 255 + .5F),
                (int) (b * 255 + .5F)
        );
    }

    public int getRed() {
        return android.graphics.Color.red(color);
    }

    public int getBlue() {
        return android.graphics.Color.blue(color);
    }

    public int getGreen() {
        return android.graphics.Color.green(color);
    }

    public int getAlpha() {
        return 255;
    }

    public int getColorInt() {
        return color;
    }
}
