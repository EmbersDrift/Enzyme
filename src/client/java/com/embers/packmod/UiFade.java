package com.embers.packmod;

/** Extraction-only opacity; never changes the world, panorama, or regular HUD. */
public final class UiFade {
    private static final ThreadLocal<Float> OPACITY = ThreadLocal.withInitial(() -> 1.0f);
    private UiFade() {}
    public static boolean opaque() { return OPACITY.get() >= 1; }

    public static float set(float opacity) {
        float previous = OPACITY.get();
        OPACITY.set(opacity);
        return previous;
    }

    public static int color(int argb) {
        float opacity = OPACITY.get();
        return opacity >= 1 ? argb : (Math.round((argb >>> 24) * opacity) << 24) | (argb & 0xFFFFFF);
    }
}
