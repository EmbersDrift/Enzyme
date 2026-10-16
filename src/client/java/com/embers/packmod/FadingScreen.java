package com.embers.packmod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared navigation for the converter's native Minecraft screens. */
public abstract class FadingScreen extends Screen {
    protected FadingScreen(Component title) { super(title); }
    public final void onDisplayed() {}
    public final boolean isClosing() { return false; }
    public final float fadeOpacity() { return 1; }
    protected final void transitionTo(Screen next) { minecraft.gui.setScreen(next); }
}
