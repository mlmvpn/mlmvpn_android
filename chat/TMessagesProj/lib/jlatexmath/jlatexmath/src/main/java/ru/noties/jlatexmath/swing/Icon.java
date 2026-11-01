package ru.noties.jlatexmath.swing;

import ru.noties.jlatexmath.awt.Graphics;
import ru.noties.jlatexmath.awt.Component;


public interface Icon {
    void paintIcon(Component c, Graphics g, int x, int y);

    int getIconWidth();

    int getIconHeight();
}

