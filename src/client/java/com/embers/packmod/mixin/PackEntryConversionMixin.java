package com.embers.packmod.mixin;

import com.embers.packmod.PackConversionPrompt;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Scoped to the resource-pack compatibility confirmation, never other dialogs. */
@Mixin(targets = "net.minecraft.client.gui.screens.packs.TransferableSelectionList$PackEntry")
abstract class PackEntryConversionMixin {
    @Shadow @Final private PackSelectionModel.Entry pack;

    @Redirect(method = "handlePackSelection", at = @At(value = "NEW", target = "net/minecraft/client/gui/screens/ConfirmScreen"))
    private ConfirmScreen packmod$offerConversion(BooleanConsumer callback, Component title, Component message) {
        return PackConversionPrompt.create(callback, title, message, pack.getId());
    }
}
