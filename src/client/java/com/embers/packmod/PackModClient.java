package com.embers.packmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Standalone entry point. No dependency on Embers Client. */
public final class PackModClient implements ClientModInitializer {
    public static final String ID = "packmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);
    @Override public void onInitializeClient() {
        // Avoid two batch converters managing the same files if accidentally co-installed.
        if (FabricLoader.getInstance().isModLoaded("embersclient")) {
            LOGGER.info("Embers Client provides the integrated converter; Pack Mod event handlers are inactive.");
            return;
        }
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof FadingScreen popup) popup.onDisplayed();
        });
        PackConversionManager.init();
    }
}
