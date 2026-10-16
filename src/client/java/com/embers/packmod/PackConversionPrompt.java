package com.embers.packmod;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import com.embers.packmod.mixin.PackSelectionRefresh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;

/** The native warning and callbacks stay intact; conversion is a third choice. */
public final class PackConversionPrompt extends ConfirmScreen {
    private final PackSelectionScreen parent;
    private final Path source;
    private final String packId;

    private PackConversionPrompt(BooleanConsumer callback, Component title, Component message,
                                 PackSelectionScreen parent, Path source, String packId) {
        super(callback, title, message.copy().append("\n\n")
                .append(Component.translatable("packmod.pack_conversion.prompt_note")));
        this.parent = parent;
        this.source = source;
        this.packId = packId;
    }

    public static ConfirmScreen create(BooleanConsumer callback, Component title, Component message, String packId) {
        var parent = PackConversionManager.packScreen(Minecraft.getInstance().gui.screen());
        Path source = PackConversionManager.promptSource(packId);
        boolean resourceDirectory = parent != null && source != null && ((PackSelectionRefresh)parent)
                .packmod$packDirectory().toAbsolutePath().normalize().equals(PackConversionManager.packDirectory());
        return !resourceDirectory ? new ConfirmScreen(callback, title, message)
                : new PackConversionPrompt(callback, title, message, parent, source, packId);
    }

    private NativeButton conversionButton(int x, int y) {
        var button = new NativeButton(x, y, Math.min(200, width-40), 20, "Convert Resource Pack", b -> {
            if (PackConversionManager.busy() || PackConversionManager.protectedIds(parent).contains(packId)) return;
            // Use vanilla's No callback: restore the actual parent without selecting the pack.
            callback.accept(false);
            if (minecraft.gui.screen() == parent) PackConversionManager.requestCheck(parent, source);
        });
        boolean runner;
        try { runner = NativePlatform.current().bundled(); } catch (Exception error) { runner = false; }
        button.active = runner && !PackConversionManager.busy() && !PackConversionManager.protectedIds(parent).contains(packId);
        button.setTooltip(Tooltip.create(Component.translatable(!runner ? "packmod.pack_conversion.runner_missing"
                : button.active ? "packmod.pack_conversion.prompt_help" : "packmod.pack_conversion.prompt_blocked")));
        return button;
    }

    @Override protected void addButtons(LinearLayout buttons) {
        super.addButtons(buttons);
        layout.addChild(conversionButton(0, 0));
    }
}
