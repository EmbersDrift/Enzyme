package com.embers.packmod.platform;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

public final class ClientLifecycleEvents {
    public static final Channel CLIENT_STOPPING = new Channel();
    public static final class Channel {
        public void register(Consumer<Minecraft> callback) { NeoPlatform.STOPPING.add(callback); }
    }
}
