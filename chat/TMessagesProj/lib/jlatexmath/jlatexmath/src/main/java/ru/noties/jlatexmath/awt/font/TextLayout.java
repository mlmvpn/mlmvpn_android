package ru.noties.jlatexmath.awt.font;

import android.graphics.Paint;
import android.graphics.Rect;

import ru.noties.jlatexmath.awt.Font;
import ru.noties.jlatexmath.awt.Graphics2D;
import ru.noties.jlatexmath.awt.geom.Rectangle2D;

public class TextLayout {

    private final char[] chars;
    private final Font font;
    private final Rectangle2D bounds;

    public TextLayout(String str, Font font, FontRenderContext frc) {
        this.chars = str.toCharArray();
        this.font = font;

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(font.typeface());
        paint.setTextSize(font.size());

        final Rect rect = new Rect();
        paint.getTextBounds(str, 0, str.length(), rect);
        this.bounds = new Rectangle2D.Float(rect.left, rect.top, rect.width(), rect.height());
    }

    public Rectangle2D getBounds() {
        return bounds;
    }

    public void draw(Graphics2D g2, int i, int i1) {
        final Font current = g2.getFont();
        final boolean differentFonts = font != current;
        if (differentFonts) {
            g2.setFont(font);
        }

        g2.drawChars(chars, 0, chars.length, i, i1);

        if (differentFonts) {
            g2.setFont(current);
        }
    }
}
