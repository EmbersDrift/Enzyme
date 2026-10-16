package com.embers.packmod.platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

public final class NeoPlatform {
    static final List<Consumer<Minecraft>> TICKS = new CopyOnWriteArrayList<>();
    static final List<Consumer<Minecraft>> STOPPING = new CopyOnWriteArrayList<>();
    static final List<ScreenEvents.Callback> SCREENS = new CopyOnWriteArrayList<>();
    private static ScreenEvent.Init.Post currentInit;
    private static boolean initialized;

    public static void install(Runnable init) {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (!initialized) { initialized = true;init.run(); }
            for (var callback : TICKS) callback.accept(Minecraft.getInstance());
        });
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> {
            for (var callback : STOPPING) callback.accept(Minecraft.getInstance());
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
            currentInit = event;
            try {
                Screen screen = event.getScreen();
                for (var callback : SCREENS) callback.init(Minecraft.getInstance(),screen,screen.width,screen.height);
            } finally { currentInit = null; }
        });
    }

    static void addWidget(Screen screen, AbstractWidget widget) {
        if (currentInit == null || currentInit.getScreen() != screen) throw new IllegalStateException("Screen widget added outside its init event");
        currentInit.addListener(widget);
    }
}
