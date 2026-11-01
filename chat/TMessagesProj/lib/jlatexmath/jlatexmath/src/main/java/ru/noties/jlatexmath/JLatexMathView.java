package ru.noties.jlatexmath;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import ru.noties.jlatexmath.android.R;
import ru.noties.jlatexmath.awt.Color;

public class JLatexMathView extends View {

    public static final int ALIGN_START = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_END = 2;

    @IntDef({ALIGN_START, ALIGN_CENTER, ALIGN_END})
    @Retention(RetentionPolicy.CLASS)
    public @interface Align {
    }

    private int textSize;
    private int textColor;
    private Drawable background;

    private int alignVertical;
    private int alignHorizontal;

    private JLatexMathDrawable drawable;

    private float scale;
    private float left;
    private float top;

    public JLatexMathView(Context context) {
        super(context);
        init(context, null);
    }

    public JLatexMathView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    private void init(Context context, @Nullable AttributeSet attrs) {

        final TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.JLatexMathView);
        try {

            final Drawable background;
            {
                final int res = array.getResourceId(R.styleable.JLatexMathView_jlmv_background, 0);
                if (res != 0) {
                    final String type = context.getResources().getResourceTypeName(res);
                    if ("drawable".equals(type)) {
                        background = array.getDrawable(R.styleable.JLatexMathView_jlmv_background);
                    } else if ("color".equals(type)) {
                        background = new ColorDrawable(array.getColor(R.styleable.JLatexMathView_jlmv_background, 0));
                    } else {
                        throw new IllegalStateException(String.format(
                                "Unexpected background reference: %s is of type: %s. Supported: drawable, color",
                                context.getResources().getResourceName(res), type
                        ));
                    }
                } else {
                    background = null;
                }
            }

            this
                    .textSize(array.getDimensionPixelSize(R.styleable.JLatexMathView_jlmv_textSize, 0))
                    .textColor(array.getColor(R.styleable.JLatexMathView_jlmv_textColor, Color.black.getColorInt()))
                    .background(background)
                    .align(
                            array.getInteger(R.styleable.JLatexMathView_jlmv_alignVertical, ALIGN_START),
                            array.getInteger(R.styleable.JLatexMathView_jlmv_alignHorizontal, ALIGN_START)
                    );
        } finally {
            array.recycle();
        }

        if (isInEditMode()) {
            JLatexMathAndroid.init(context);
            String latex = "\\begin{array}{l}";
            latex += "\\forall\\varepsilon\\in\\mathbb{R}_+^*\\ \\exists\\eta>0\\ |x-x_0|\\leq\\eta\\Longrightarrow|f(x)-f(x_0)|\\leq\\varepsilon\\\\";
            latex += "\\det\\begin{bmatrix}a_{11}&a_{12}&\\cdots&a_{1n}\\\\a_{21}&\\ddots&&\\vdots\\\\\\vdots&&\\ddots&\\vdots\\\\a_{n1}&\\cdots&\\cdots&a_{nn}\\end{bmatrix}\\overset{\\mathrm{def}}{=}\\sum_{\\sigma\\in\\mathfrak{S}_n}\\varepsilon(\\sigma)\\prod_{k=1}^n a_{k\\sigma(k)}\\\\";
            latex += "\\sideset{_\\alpha^\\beta}{_\\gamma^\\delta}{\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}}\\\\";
            latex += "\\int_0^\\infty{x^{2n} e^{-a x^2}\\,dx} = \\frac{2n-1}{2a} \\int_0^\\infty{x^{2(n-1)} e^{-a x^2}\\,dx} = \\frac{(2n-1)!!}{2^{n+1}} \\sqrt{\\frac{\\pi}{a^{2n+1}}}\\\\";
            latex += "\\int_a^b{f(x)\\,dx} = (b - a) \\sum\\limits_{n = 1}^\\infty  {\\sum\\limits_{m = 1}^{2^n  - 1} {\\left( { - 1} \\right)^{m + 1} } } 2^{ - n} f(a + m\\left( {b - a} \\right)2^{-n} )\\\\";
            latex += "\\int_{-\\pi}^{\\pi} \\sin(\\alpha x) \\sin^n(\\beta x) dx = \\textstyle{\\left \\{ \\begin{array}{cc} (-1)^{(n+1)/2} (-1)^m \\frac{2 \\pi}{2^n} \\binom{n}{m} & n \\mbox{ odd},\\ \\alpha = \\beta (2m-n) \\\\ 0 & \\mbox{otherwise} \\\\ \\end{array} \\right .}\\\\";
            latex += "L = \\int_a^b \\sqrt{ \\left|\\sum_{i,j=1}^ng_{ij}(\\gamma(t))\\left(\\frac{d}{dt}x^i\\circ\\gamma(t)\\right)\\left(\\frac{d}{dt}x^j\\circ\\gamma(t)\\right)\\right|}\\,dt\\\\";
            latex += "\\begin{array}{rl} s &= \\int_a^b\\left\\|\\frac{d}{dt}\\vec{r}\\,(u(t),v(t))\\right\\|\\,dt \\\\ &= \\int_a^b \\sqrt{u'(t)^2\\,\\vec{r}_u\\cdot\\vec{r}_u + 2u'(t)v'(t)\\, \\vec{r}_u\\cdot\\vec{r}_v+ v'(t)^2\\,\\vec{r}_v\\cdot\\vec{r}_v}\\,\\,\\, dt. \\end{array}\\\\";
            latex += "\\end{array}";
            setLatex(latex);
        }
    }

    @NonNull
    public JLatexMathView textSize(@Px int textSize) {
        this.textSize = textSize;
        return this;
    }

    @NonNull
    public JLatexMathView textColor(@Px int textColor) {
        this.textColor = textColor;
        return this;
    }

    @NonNull
    public JLatexMathView background(@Nullable Drawable background) {
        this.background = background;
        return this;
    }

    @NonNull
    public JLatexMathView align(@Align int alignVertical, @Align int alignHorizontal) {
        this.alignVertical = alignVertical;
        this.alignHorizontal = alignHorizontal;
        return this;
    }

    public void setLatex(@NonNull String latex) {
        final JLatexMathDrawable drawable = JLatexMathDrawable.builder(latex)
                .textSize(textSize)
                .color(textColor)
                .background(background)
                .fitCanvas(false)
                .build();
        setLatexDrawable(drawable);
    }

    public void setLatexDrawable(@NonNull JLatexMathDrawable drawable) {
        this.drawable = drawable;
        requestLayout();
    }

    public void clear() {
        this.drawable = null;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        if (drawable == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);

        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        final int drawableWidth = drawable.getIntrinsicWidth();
        final int drawableHeight = drawable.getIntrinsicHeight();

        final int paddingLeft = getPaddingLeft();
        final int paddingTop = getPaddingTop();

        // okay, a dimension:
        //  if it's exact -> use it
        //  if it's not -> use smallest value of AT_MOST, (drawable.intrinsic + padding)

        int width;
        int height;

        if (MeasureSpec.EXACTLY == widthMode) {
            width = widthSize;
        } else {
            final int wrap = drawableWidth + paddingLeft + getPaddingRight();
            width = widthSize > 0
                    ? Math.min(widthSize, wrap)
                    : wrap;
        }

        if (MeasureSpec.EXACTLY == heightMode) {
            height = heightSize;
        } else {
            final int wrap = drawableHeight + paddingTop + getPaddingBottom();
            height = heightSize > 0
                    ? Math.min(heightSize, wrap)
                    : wrap;
        }

        final int canvasWidth = width - paddingLeft - getPaddingRight();
        final int canvasHeight = height - paddingTop - getPaddingBottom();

        final float scale;

        // now, let's see if these dimensions change our drawable scale (we need modify it to fit)
        if (drawableWidth < canvasWidth && drawableHeight < canvasHeight) {
            // we do not need to modify drawable
            scale = 1.F;
        } else {
            scale = Math.min(
                    (float) canvasWidth / drawableWidth,
                    (float) canvasHeight / drawableHeight
            );
        }

        final int displayWidth = (int) (drawableWidth * scale + .5F);
        final int displayHeight = (int) (drawableHeight * scale + .5F);

        if (MeasureSpec.EXACTLY != widthMode) {
            width = displayWidth + paddingLeft + getPaddingRight();
        }

        if (MeasureSpec.EXACTLY != heightMode) {
            height = displayHeight + paddingTop + getPaddingBottom();
        }

        // let's see if we should align our formula
        final float left = alignment(alignHorizontal, (width - paddingLeft - getPaddingRight()) - displayWidth);
        final float top = alignment(alignVertical, (height - paddingTop - getPaddingBottom()) - displayHeight);

        this.scale = scale;
        this.left = paddingLeft + left;
        this.top = paddingTop + top;

        setMeasuredDimension(width, height);
    }

    private static float alignment(int align, float difference) {
        final float out;
        if (ALIGN_START == align) {
            out = .0F;
        } else if (ALIGN_CENTER == align) {
            out = difference / 2;
        } else {
            out = difference;
        }
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (drawable == null) {
            return;
        }

        final int save = canvas.save();
        try {

            if (left > .0F) {
                canvas.translate(left, 0);
            }
            if (top > .0F) {
                canvas.translate(0, top);
            }

            if (scale > .0F && Float.compare(scale, 1.F) != 0) {
                canvas.scale(scale, scale);
            }

            drawable.draw(canvas);

        } finally {
            canvas.restoreToCount(save);
        }
    }
}
