package com.embers.packmod;

import net.minecraft.client.gui.components.Button;
import java.util.function.Consumer;

/** Keep Minecraft's own artwork, focus, narration and input handling. */
public final class NativeButton extends Button.Plain {
    public NativeButton(int x, int y, int width, int height, String label, Consumer<NativeButton> action) {
        super(x, y, width, height, VanillaUi.ui(label), button -> action.accept((NativeButton) button), DEFAULT_NARRATION);
    }
}
