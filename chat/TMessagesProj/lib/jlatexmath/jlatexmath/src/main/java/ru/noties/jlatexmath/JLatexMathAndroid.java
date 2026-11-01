package ru.noties.jlatexmath;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

public abstract class JLatexMathAndroid {

    private static final String BASE = "org/scilab/forge/jlatexmath/";

    @SuppressLint("StaticFieldLeak")
    private static Context sContext = null;

    public static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    public static InputStream getResourceAsStream(String path) {
        try {
            return context().getAssets().open(BASE + path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    public static Typeface loadTypeface(@NonNull String path) {
        return Typeface.createFromAsset(context().getAssets(), BASE + path);
    }

    private JLatexMathAndroid() {
    }

    private static Context context() {
        Context context = sContext;
        if (context == null) {
            throw new NullPointerException("Please call `#init(Context)` method to initialize jLatexMath");
        }
        return context;
    }
}
