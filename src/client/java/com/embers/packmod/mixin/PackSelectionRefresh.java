package com.embers.packmod.mixin;

import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PackSelectionScreen.class)
public interface PackSelectionRefresh {
    @Invoker("reload") void packmod$reloadPacks();
    @Accessor("model") PackSelectionModel packmod$model();
}
