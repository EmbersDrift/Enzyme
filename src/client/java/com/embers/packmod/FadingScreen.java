package com.embers.packmod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Client windows share a short fade and defer navigation until fade-out completes. */
public abstract class FadingScreen extends Screen {
    private static final long OPEN_DURATION = 180_000_000L;
    private static final long CLOSE_DURATION = 130_000_000L;
    private long openedAt;
    private long closedAt;
    private float closeOpacity;
    private boolean needsOpening = true;
    private boolean closing;
    private Screen destination;

    protected FadingScreen(Component title) { super(title); }

    public final void onDisplayed() {
        if (!needsOpening) return;
        needsOpening = false;
        closing = false;
        openedAt = System.nanoTime();
    }

    public final boolean isClosing() { return closing; }

    public final float fadeOpacity() {
        if (openedAt == 0) return 0;
        long now = System.nanoTime();
        return closing ? closeOpacity * (1 - ease((now - closedAt) / (float) CLOSE_DURATION))
                : ease((now - openedAt) / (float) OPEN_DURATION);
    }

    private static float ease(float value) {
        float t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    protected final void transitionTo(Screen next) {
        if (closing) return;
        closeOpacity = fadeOpacity();
        closedAt = System.nanoTime();
        destination = next;
        closing = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (closing && System.nanoTime() - closedAt >= CLOSE_DURATION) {
            Screen next = destination;
            minecraft.gui.setScreen(next);
        }
    }

    @Override
    public void removed() {
        super.removed();
        needsOpening = true;
        closing = false;
        destination = null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return closing || super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return closing || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) { return closing || super.keyPressed(event); }
}
