package com.embers.packmod.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class ScreenEvents {
    public interface Callback { void init(Minecraft client, Screen screen, int width, int height); }
    public static final Channel AFTER_INIT = new Channel();
    public static final class Channel {
        public void register(Callback callback) { NeoPlatform.SCREENS.add(callback); }
    }
}
