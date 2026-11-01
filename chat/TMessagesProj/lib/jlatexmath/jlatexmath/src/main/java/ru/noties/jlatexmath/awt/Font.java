package ru.noties.jlatexmath.awt;

import android.annotation.SuppressLint;
import android.graphics.Typeface;

import androidx.annotation.NonNull;

import java.io.InputStream;
import java.util.Locale;

public class Font {

    public static final int PLAIN = 0;

    public static final int BOLD = 1;

    public static final int ITALIC = 2;

    @Deprecated
    public static Font createFont(int truetypeFont, InputStream fontIn) {
        return null;
    }

    @NonNull
    public static Font createFont(@NonNull Typeface typeface, float size) {
        return new Font(typeface, 0, size);
    }

    private final Typeface typeface;
    private int style;
    private float size;

    public Font(String name, int style, int size) {
        this(createTypeface(name, style), style, size);
    }

    private Font(@NonNull Typeface typeface, int style, float size) {
        this.typeface = applyStyle(typeface, style);
        this.style = style;
        this.size = size;
    }

    @NonNull
    private static Typeface applyStyle(@NonNull Typeface typeface, int style) {
        final int current = (typeface.isBold() ? BOLD : 0) | (typeface.isItalic() ? ITALIC : 0);
        if (current != style) {
            // both will be 3 (BOLD_ITALIC)
            @SuppressLint("WrongConstant") final int out = ((style & BOLD) != 0 ? BOLD : 0) | ((style & ITALIC) != 0 ? ITALIC : 0);
            typeface = Typeface.create(typeface, out);
        }

        return typeface;
    }

    public Font deriveFont(int type) {
        return new Font(typeface, type, size);
    }

    public Typeface typeface() {
        return typeface;
    }

    public int style() {
        return style;
    }

    public float size() {
        return size;
    }

    public boolean isBold() {
        return (style & BOLD) != 0;
    }

    public boolean isItalic() {
        return (style & ITALIC) != 0;
    }

    @NonNull
    private static Typeface createTypeface(@NonNull String name, int style) {
        Typeface typeface = Typeface.create(name.toLowerCase(Locale.US), toAndroidStyle(style));
        if (typeface == null) {
            typeface = Typeface.DEFAULT;
        }
        return typeface;
    }

    // @since 0.1.2
    private static int toAndroidStyle(int style) {
        if (style == PLAIN) {
            return Typeface.NORMAL;
        }
        return ((style & BOLD) != 0 ? Typeface.BOLD : 0)
                | ((style & ITALIC) != 0 ? Typeface.ITALIC : 0);
    }
}
