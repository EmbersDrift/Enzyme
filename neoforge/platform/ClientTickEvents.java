package com.embers.packmod.platform;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

public final class ClientTickEvents {
    public static final Channel END_CLIENT_TICK = new Channel();
    public static final class Channel {
        public void register(Consumer<Minecraft> callback) { NeoPlatform.TICKS.add(callback); }
    }
}
