package ru.noties.jlatexmath.awt;

import ru.noties.jlatexmath.awt.font.FontRenderContext;
import ru.noties.jlatexmath.awt.geom.AffineTransform;
import ru.noties.jlatexmath.awt.geom.Line2D;
import ru.noties.jlatexmath.awt.geom.Rectangle2D;
import ru.noties.jlatexmath.awt.geom.RoundRectangle2D;

public interface Graphics2D extends Graphics {

    Color getColor();

    void setColor(Color color);

    void fill(Rectangle2D.Float rectangle2D);

    void draw(Rectangle2D.Float rectangle2D);

    Stroke getStroke();

    void setStroke(Stroke st);

    AffineTransform getTransform();

    void translate(double x, double y);

    void scale(double x, double y);

    Font getFont();

    void setFont(Font font);

    void drawChars(char chars[], int offset, int length, int x, int y);

    void setTransform(AffineTransform at);

    void draw(Line2D.Float line);

    void rotate(double theta);

    void rotate(double theta, double x, double y);

    void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle);

    void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle);

    void draw(RoundRectangle2D.Float roundRectangle);

    FontRenderContext getFontRenderContext();

    void fillRect(int x, int y, int width, int height);

    RenderingHints getRenderingHints();

    void setRenderingHint(RenderingHints.Key keyAntialiasing, Object valueAntialiasOn);

    void setRenderingHints(RenderingHints oldHints);
}
