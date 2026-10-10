package com.embers.packmod.mixin;

import com.embers.packmod.UiFade;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiTextRenderState.class)
abstract class TextFadeMixin {
    @Shadow @Final @Mutable private int color;
    @Shadow @Final @Mutable private int backgroundColor;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void packmod$fadeText(CallbackInfo ci) {
        color = UiFade.color(color);
        backgroundColor = UiFade.color(backgroundColor);
    }
}
