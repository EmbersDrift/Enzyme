package com.embers.packmod.mixin;

import com.embers.packmod.FadingScreen;
import com.embers.packmod.UiFade;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the converter's own popup fades; native screens retain their artwork and font. */
@Mixin(Screen.class)
abstract class ScreenFadeMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
    private void packmod$begin(CallbackInfo ci) {
        UiFade.set((Object) this instanceof FadingScreen popup ? popup.fadeOpacity() : 1);
    }
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
    private void packmod$end(CallbackInfo ci) { UiFade.set(1); }
}
