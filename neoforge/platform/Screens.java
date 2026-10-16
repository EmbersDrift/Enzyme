package com.embers.packmod.platform;

import java.util.AbstractList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

public final class Screens {
    public static List<AbstractWidget> getWidgets(Screen screen) {
        return new AbstractList<>() {
            public AbstractWidget get(int index) { throw new UnsupportedOperationException(); }
            public int size() { return 0; }
            @Override public boolean add(AbstractWidget widget) {
                NeoPlatform.addWidget(screen, widget);return true;
            }
        };
    }
}
