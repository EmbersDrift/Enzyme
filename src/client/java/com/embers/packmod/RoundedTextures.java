package com.embers.packmod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Nine patches share one texture, with a one-quad fast path for repeated inventory slots. */
final class RoundedTextures {
    private static final Identifier ATLAS=Identifier.fromNamespaceAndPath(PackModClient.ID,"textures/gui/rounded_masks.png");
    private RoundedTextures() {}
    static boolean draw(GuiGraphicsExtractor g,int x,int y,int w,int h,int radius,int color,boolean outline){
        int r=Math.clamp(radius,0,Math.min(w,h)/2);
        if(r==0){
            if(outline)g.outline(x,y,w,h,color);else g.fill(x,y,x+w,y+h,color);
            return true;
        }
        if(r>RoundedMaskLayout.MAX_RADIUS)return false;
        if(w==18 && h==18 && r==3){
            var slot=RoundedMaskLayout.SLOTS[outline?1:0];
            blit(g,x,y,w,h,slot.x(),slot.y(),slot.size(),slot.size(),color);return true;
        }
        var mask=RoundedMaskLayout.PATCHES[r][outline?1:0];
        int middleW=w-r*2,middleH=h-r*2,cut=r*2,tail=mask.size()-cut;
        // Corners retain the original 4x4 coverage at half a GUI pixel; only flat edges stretch.
        blit(g,x,y,r,r,mask.x(),mask.y(),cut,cut,color);
        blit(g,x+w-r,y,r,r,mask.x()+tail,mask.y(),cut,cut,color);
        blit(g,x,y+h-r,r,r,mask.x(),mask.y()+tail,cut,cut,color);
        blit(g,x+w-r,y+h-r,r,r,mask.x()+tail,mask.y()+tail,cut,cut,color);
        if(middleW>0){
            blit(g,x+r,y,middleW,r,mask.x()+cut,mask.y(),4,cut,color);
            blit(g,x+r,y+h-r,middleW,r,mask.x()+cut,mask.y()+tail,4,cut,color);
        }
        if(middleH>0){
            blit(g,x,y+r,r,middleH,mask.x(),mask.y()+cut,cut,4,color);
            blit(g,x+w-r,y+r,r,middleH,mask.x()+tail,mask.y()+cut,cut,4,color);
        }
        if(!outline && middleW>0 && middleH>0)
            blit(g,x+r,y+r,middleW,middleH,mask.x()+cut,mask.y()+cut,4,4,color);
        return true;
    }
    private static void blit(GuiGraphicsExtractor g,int x,int y,int w,int h,int u,int v,int sw,int sh,int tint){
        g.blit(RenderPipelines.GUI_TEXTURED,ATLAS,x,y,u,v,w,h,sw,sh,RoundedMaskLayout.WIDTH,RoundedMaskLayout.HEIGHT,tint);
    }
}
