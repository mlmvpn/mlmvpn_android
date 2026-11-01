package ru.noties.jlatexmath.awt;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import ru.noties.jlatexmath.awt.font.FontRenderContext;
import ru.noties.jlatexmath.awt.geom.AffineTransform;
import ru.noties.jlatexmath.awt.geom.Line2D;
import ru.noties.jlatexmath.awt.geom.Rectangle2D;
import ru.noties.jlatexmath.awt.geom.RoundRectangle2D;

public class AndroidGraphics2D implements Graphics2D {

    private final RectF rectF = new RectF();

    private final Paint paint;

    {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
    }

    private Canvas canvas;
    private Color color;
    private Stroke stroke;
    private Font font;
    private AffineTransform transform;

    public void setCanvas(Canvas canvas) {
        this.canvas = canvas;
        this.transform = AffineTransform.create(canvas);
    }

    @Override
    public Color getColor() {
        if (color == null) {
            color = new Color(paint.getColor());
        }
        return color;
    }

    @Override
    public void setColor(Color color) {
        this.color = color;
        paint.setColor(color.getColorInt());
    }

    @Override
    public void fill(Rectangle2D.Float rectangle2D) {
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(
                rectangle2D.x, rectangle2D.y,
                rectangle2D.x + rectangle2D.w,
                rectangle2D.y + rectangle2D.h,
                paint
        );
    }

    @Override
    public Stroke getStroke() {
        if (stroke == null) {
            stroke = new BasicStroke(
                    paint.getStrokeWidth(),
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER,
                    paint.getStrokeMiter()
            );
        }
        return stroke;
    }

    @Override
    public void setStroke(Stroke stroke) {
        this.stroke = stroke;
        this.paint.setStrokeWidth(stroke.width());

        // todo: find out about this, if added, bad draws..
//        this.paint.setStrokeMiter(stroke.miterLimit());
    }

    @Override
    public AffineTransform getTransform() {
        transform = transform.save();
        return transform;
    }

    @Override
    public void setTransform(AffineTransform at) {
        if (canvas != at.getCanvas()) {
            throw new IllegalStateException("Supplied transform has different Canvas attached");
        }
        this.transform = at.restore();
    }

    @Override
    public void draw(Rectangle2D.Float rectangle2D) {
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(
                rectangle2D.x,
                rectangle2D.y,
                rectangle2D.x + rectangle2D.w,
                rectangle2D.y + rectangle2D.h,
                paint
        );
    }

    @Override
    public void translate(double x, double y) {
        transform.translate((float) x, (float) y);
    }

    @Override
    public void scale(double x, double y) {
        transform.scale(x, y);
    }

    @Override
    public Font getFont() {
        return font;
    }

    @Override
    public void setFont(Font font) {
        this.font = font;
    }

    @Override
    public void drawChars(char chars[], int offset, int length, int x, int y) {
        if (font != null) {
            paint.setTypeface(font.typeface());
            paint.setTextSize(font.size());
        }
        canvas.drawText(chars, offset, length, x, y, paint);
    }

    @Override
    public void draw(Line2D.Float line) {
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(
                (float) line.x1,
                (float) line.y1,
                (float) line.x2,
                (float) line.y2,
                paint
        );
    }

    @Override
    public void rotate(double theta) {
        canvas.rotate((float) Math.toDegrees(theta));
    }

    @Override
    public void rotate(double theta, double x, double y) {
        canvas.rotate((float) Math.toDegrees(theta), (float) x, (float) y);
    }

    @Override
    public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        paint.setStyle(Paint.Style.STROKE);
        rectF.set(x, y, x + width, y + height);
        canvas.drawArc(rectF, startAngle, arcAngle, false, paint);
    }

    @Override
    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        paint.setStyle(Paint.Style.FILL);
        rectF.set(x, y, x + width, y + height);
        canvas.drawArc(rectF, startAngle, arcAngle, false, paint);
    }

    @Override
    public void draw(RoundRectangle2D.Float aFloat) {
        paint.setStyle(Paint.Style.STROKE);
        rectF.set(
                aFloat.x,
                aFloat.y,
                aFloat.x + aFloat.width,
                aFloat.y + aFloat.height
        );
        canvas.drawRoundRect(rectF, aFloat.arcwidth, aFloat.archeight, paint);
    }

    @Override
    public FontRenderContext getFontRenderContext() {
        return null;
    }

    @Override
    public void fillRect(int x, int y, int w, int h) {
//        Debug.i("fillRect, i: %s, i1: %s, w: %s, h: %s", i, i1, w, h);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(
                x, y,
                x + w, y + h,
                paint
        );
    }

    @Override
    public RenderingHints getRenderingHints() {
        return null;
    }

    @Override
    public void setRenderingHint(RenderingHints.Key keyAntialiasing, Object valueAntialiasOn) {

    }

    @Override
    public void setRenderingHints(RenderingHints oldHints) {

    }
}
