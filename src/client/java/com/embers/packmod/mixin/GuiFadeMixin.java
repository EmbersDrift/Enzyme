package com.embers.packmod.mixin;

import com.embers.packmod.UiFade;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GuiRenderState.class)
abstract class GuiFadeMixin {
    @ModifyVariable(method = "addGuiElement", at = @At("HEAD"), argsOnly = true)
    private GuiElementRenderState packmod$fadeElement(GuiElementRenderState state) {
        if(UiFade.opaque())return state;
        if (state instanceof ColoredRectangleRenderState rect) {
            int color1 = UiFade.color(rect.col1()), color2 = UiFade.color(rect.col2());
            if (color1 != rect.col1() || color2 != rect.col2()) return new ColoredRectangleRenderState(
                    rect.pipeline(), rect.textureSetup(), rect.pose(), rect.x0(), rect.y0(), rect.x1(), rect.y1(),
                    color1, color2, rect.scissorArea(), rect.bounds());
        } else if (state instanceof BlitRenderState blit) return packmod$fadeBlit(blit);
        return state;
    }

    @ModifyVariable(method = "addBlitToCurrentLayer", at = @At("HEAD"), argsOnly = true)
    private BlitRenderState packmod$fadeBlit(BlitRenderState blit) {
        if(UiFade.opaque())return blit;
        int color = UiFade.color(blit.color());
        return color == blit.color() ? blit : new BlitRenderState(blit.pipeline(), blit.textureSetup(), blit.pose(),
                blit.x0(), blit.y0(), blit.x1(), blit.y1(), blit.u0(), blit.u1(), blit.v0(), blit.v1(),
                color, blit.scissorArea(), blit.bounds());
    }
}
