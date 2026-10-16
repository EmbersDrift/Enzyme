package com.embers.packmod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Task preview comes from the same Rust version graph used by conversion. */
final class ConversionPlanScreen extends FadingScreen {
    private final Screen parent;
    private List<String> lines=List.of();
    private int offset;
    ConversionPlanScreen(Screen parent,List<PackConversionManager.Candidate> selected) {
        super(VanillaUi.ui("Preview Conversion"));this.parent=parent;
        PackConversionManager.preview(selected,result->{lines=result;clearWidgets();init();});
    }
    private int x(){return (width-Math.min(540,width-20))/2;}
    private int y(){return (height-Math.min(310,height-16))/2;}
    @Override protected void init(){
        int w=Math.min(512,width-48), third=(w-12)/3;
        addRenderableWidget(new NativeButton(x()+14,y()+260,third,20,"Previous",b->{offset=Math.max(0,offset-9);clearWidgets();init();})).active=offset>0;
        addRenderableWidget(new NativeButton(x()+20+third,y()+260,third,20,"Next",b->{offset=Math.min(Math.max(0,lines.size()-1),offset+9);clearWidgets();init();})).active=offset+9<lines.size();
        addRenderableWidget(new NativeButton(x()+26+third*2,y()+260,third,20,"Done",b->onClose()));
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float delta) {
        super.extractBackground(g, mx, my, delta);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        g.centeredText(font, getTitle(), width / 2, y()+13, VanillaUi.WHITE);
        g.text(font,Component.translatable("packmod.pack_conversion.preview_note"),x()+14,y()+34,VanillaUi.MUTED,false);
        for(int i=offset;i<Math.min(lines.size(),offset+9);i++)g.text(font,font.plainSubstrByWidth(lines.get(i),Math.min(512,width-48)),x()+14,y()+61+(i-offset)*20,VanillaUi.WHITE,false);
        super.extractRenderState(g,mx,my,delta);
    }
    @Override public void onClose(){transitionTo(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
