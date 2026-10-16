package com.embers.packmod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ConversionOptionsScreen extends FadingScreen {
    private final Screen parent;
    private ConversionOptions options;
    ConversionOptionsScreen(Screen parent) {
        super(Component.translatable("packmod.pack_conversion.options")); this.parent = parent; options = PackConversionManager.options();
    }
    private int x() { return (width - Math.min(440, width - 20)) / 2; }
    private int y() { return (height - Math.min(302, height - 16)) / 2; }
    private void changed(ConversionOptions next) { options = next; clearWidgets(); init(); }
    private void toggle(int row, String label, boolean value, Runnable action, String help) {
        var button = addRenderableWidget(new NativeButton(x()+14, y()+36+row*25, Math.min(412,width-48), 21,
                label + (value ? " [ON]" : " [OFF]"), b -> action.run()));
        button.setTooltip(Tooltip.create(Component.translatable("packmod.pack_conversion.option_help." + help)));
    }
    @Override protected void init() {
        var o=options;
        toggle(0,"Allow Destructive Cleanup",o.allowDestructive(),()->changed(new ConversionOptions(!o.allowDestructive(),o.generateMissing(),o.adaptShaders(),o.fixAlphaLayers(),o.flattenOverlays(),o.workers(),o.memoryBudgetMb())),"cleanup");
        toggle(1,"Generate Missing Resources",o.generateMissing(),()->changed(new ConversionOptions(o.allowDestructive(),!o.generateMissing(),o.adaptShaders(),o.fixAlphaLayers(),o.flattenOverlays(),o.workers(),o.memoryBudgetMb())),"generate");
        toggle(2,"Adapt Shaders",o.adaptShaders(),()->changed(new ConversionOptions(o.allowDestructive(),o.generateMissing(),!o.adaptShaders(),o.fixAlphaLayers(),o.flattenOverlays(),o.workers(),o.memoryBudgetMb())),"shaders");
        toggle(3,"Fix Alpha Layers",o.fixAlphaLayers(),()->changed(new ConversionOptions(o.allowDestructive(),o.generateMissing(),o.adaptShaders(),!o.fixAlphaLayers(),o.flattenOverlays(),o.workers(),o.memoryBudgetMb())),"alpha");
        toggle(4,"Flatten Source Overlays",o.flattenOverlays(),()->changed(new ConversionOptions(o.allowDestructive(),o.generateMissing(),o.adaptShaders(),o.fixAlphaLayers(),!o.flattenOverlays(),o.workers(),o.memoryBudgetMb())),"overlays");
        int w=Math.min(412,width-48), half=(w-6)/2;
        var workers=addRenderableWidget(new NativeButton(x()+14,y()+164,half,21,"",b->changed(new ConversionOptions(o.allowDestructive(),o.generateMissing(),o.adaptShaders(),o.fixAlphaLayers(),o.flattenOverlays(),o.workers()%4+1,o.memoryBudgetMb()))));
        workers.setMessage(Component.translatable("packmod.pack_conversion.workers",o.workers()));
        var memory=addRenderableWidget(new NativeButton(x()+20+half,y()+164,half,21,"",b->changed(new ConversionOptions(o.allowDestructive(),o.generateMissing(),o.adaptShaders(),o.fixAlphaLayers(),o.flattenOverlays(),o.workers(),o.memoryBudgetMb()>=1024?128:o.memoryBudgetMb()*2))));
        memory.setMessage(Component.translatable("packmod.pack_conversion.image_budget",o.memoryBudgetMb()));
        memory.setTooltip(Tooltip.create(Component.translatable("packmod.pack_conversion.option_help.budget")));
        addRenderableWidget(new NativeButton(x()+14,y()+187,half,21,"Conservative Preset",b->changed(ConversionOptions.conservative())));
        addRenderableWidget(new NativeButton(x()+20+half,y()+187,half,21,"Complete Preset",b->changed(ConversionOptions.complete())));
        addRenderableWidget(new NativeButton(x()+14,y()+242,half,21,"Restore Defaults",b->changed(ConversionOptions.defaults())));
        addRenderableWidget(new NativeButton(x()+20+half,y()+242,half,21,"Save",b->{ PackConversionManager.options(options); onClose(); }));
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float delta) {
        super.extractBackground(g, mx, my, delta);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        g.centeredText(font, getTitle(), width / 2, y()+13, VanillaUi.WHITE);
        g.text(font,Component.translatable("packmod.pack_conversion.options_note"),x()+14,y()+212,VanillaUi.MUTED,false);
        g.text(font,Component.translatable("packmod.pack_conversion.options_preview"),x()+14,y()+225,VanillaUi.MUTED,false);
        super.extractRenderState(g,mx,my,delta);
    }
    @Override public void onClose() { transitionTo(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
