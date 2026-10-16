package com.embers.packmod;

import net.minecraft.network.chat.Component;

/** Label translation and Minecraft's standard text colors; rendering stays native. */
public final class VanillaUi {
    public static final int WHITE = 0xFFFFFFFF;
    public static final int MUTED = 0xFFA0A0A0;
    private VanillaUi() {}

    public static Component ui(String text) {
        return Component.literal(ClientText.get(text));
    }

    public static Component ui(Component text) {
        String value = text.getString();
        return value.equals(ClientText.get(value)) ? text : ui(value);
    }
}
