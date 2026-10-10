package com.embers.packmod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import java.util.List;
import java.util.Locale;

final class PackHistoryScreen extends FadingScreen {
    private final Screen parent;
    private List<PackJobHistory.Entry> entries=List.of(), filtered=List.of();
    private int index, filter;
    private String message="", search="";
    private boolean loading, loaded;
    private static final String[] FILTERS={"all","completed","needs_review","failed","cancelled"};
    PackHistoryScreen(Screen parent){super(AeroUi.ui("Conversion History"));this.parent=parent;}
    Screen parentScreen(){return parent;}
    private int pw(){return Math.min(560,width-20);}
    private int ph(){return Math.min(350,height-16);}
    private int x(){return (width-pw())/2;}
    private int y(){return (height-ph())/2;}
    private void applyFilter(){
        filtered=entries.stream().filter(e->e.source().getFileName().toString().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))
                .filter(e->switch(FILTERS[filter]){
                    case "completed"->e.output()!=null&&!e.needsReview();
                    case "needs_review"->e.needsReview()&&e.output()!=null;
                    case "failed"->e.status().equals("failed")||e.status().equals("interrupted")||e.status().equals("archive_failed");
                    case "cancelled"->e.status().equals("cancelled");default->true;
                }).toList();
        index=Math.clamp(index,0,Math.max(0,filtered.size()-1));
    }
    @Override protected void init(){
        if(!loaded&&!loading){loading=true;PackConversionManager.loadHistory(result->{entries=result;loaded=true;loading=false;rebuild();});}
        applyFilter(); int w=pw()-28,half=(w-6)/2,third=(w-12)/3,bottom=y()+ph()-29;
        EditBox input=addRenderableWidget(new EditBox(font,x()+14,y()+35,w-third-6,20,Component.translatable("packmod.pack_conversion.search_history")));
        input.setMaxLength(128);input.setValue(search);input.setHint(Component.translatable("packmod.pack_conversion.search_history"));
        input.setResponder(value->{search=value;index=0;rebuild();setFocused(children().get(0));});
        var filterButton=addRenderableWidget(new AeroButton(x()+14+w-third,y()+35,third,20,"",b->{filter=(filter+1)%FILTERS.length;index=0;rebuild();}));
        filterButton.setMessage(Component.translatable("packmod.pack_conversion.filter."+FILTERS[filter]));
        addRenderableWidget(new AeroButton(x()+14,y()+62,half,20,"Previous",b->{index=Math.max(0,index-1);rebuild();})).active=index>0;
        addRenderableWidget(new AeroButton(x()+20+half,y()+62,half,20,"Next",b->{index=Math.min(filtered.size()-1,index+1);rebuild();})).active=index+1<filtered.size();
        PackJobHistory.Entry entry=filtered.isEmpty()?null:filtered.get(index);
        var details=addRenderableWidget(new AeroButton(x()+14,bottom-83,half,20,"Report Details",b->minecraft.gui.setScreen(new PackReportScreen(this,entry.journal().getParent()))));details.active=entry!=null;
        var retry=addRenderableWidget(new AeroButton(x()+20+half,bottom-83,half,20,"Retry Failed Task",b->{message=ReportText.text("working");PackConversionManager.retry(entry,parent,result->{message=result;rebuild();});rebuild();}));
        retry.active=entry!=null&&entry.retryable()&&!PackConversionManager.busy();retry.setTooltip(Tooltip.create(Component.translatable("packmod.pack_conversion.retry_help")));
        var restore=addRenderableWidget(new AeroButton(x()+14,bottom-56,half,21,"Restore Original",b->action(entry,true)));
        restore.active=entry!=null&&entry.archived()!=null&&java.nio.file.Files.exists(entry.archived())&&!PackConversionManager.busy();
        restore.setTooltip(Tooltip.create(Component.translatable("packmod.pack_conversion.restore_help")));
        var archive=addRenderableWidget(new AeroButton(x()+20+half,bottom-56,half,21,"Archive Reviewed Original",b->action(entry,false)));
        archive.active=entry!=null&&entry.output()!=null&&java.nio.file.Files.exists(entry.source())&&!PackConversionManager.busy()&&!entry.status().equals("restored");
        archive.setTooltip(Tooltip.create(Component.translatable("packmod.pack_conversion.archive_review_help")));
        addRenderableWidget(new AeroButton(x()+14,bottom,half,21,"Open Conversion Reports",b->Util.getPlatform().openPath(entry==null?PackConversionManager.reportDirectory():entry.journal().getParent())));
        addRenderableWidget(new AeroButton(x()+20+half,bottom,half,21,"Done",b->onClose()));
    }
    private void rebuild(){clearWidgets();init();}
    private void action(PackJobHistory.Entry entry,boolean restore){if(entry==null)return;message=ReportText.text("working");PackConversionManager.historyAction(entry,restore,parent,result->{message=result;loaded=false;rebuild();});rebuild();}
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float delta){if(minecraft.level==null)extractPanorama(g,delta);AeroUi.modalBackdrop(g,width,height,x(),y(),pw(),ph());AeroUi.glass(g,x(),y(),pw(),ph(),13);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        int w=pw()-28;g.text(font,getTitle(),x()+14,y()+13,AeroUi.WHITE,false);
        if(!filtered.isEmpty()){
            var e=filtered.get(index);g.text(font,font.plainSubstrByWidth((index+1)+"/"+filtered.size()+"  "+e.source().getFileName(),w),x()+14,y()+95,AeroUi.WHITE,false);
            g.text(font,Component.translatable("packmod.pack_conversion.history_state",Component.translatable("packmod.pack_conversion.state."+e.displayStatus())),x()+14,y()+111,AeroUi.MUTED,false);
            String output=e.output()==null?e.error():e.output().getFileName().toString();g.text(font,font.plainSubstrByWidth(output,w),x()+14,y()+127,AeroUi.MUTED,false);
            g.text(font,font.plainSubstrByWidth(ReportText.options(e.options()),w),x()+14,y()+143,AeroUi.MUTED,false);
            String date=java.time.Instant.ofEpochMilli(e.updatedAt()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            g.text(font,date,x()+14,y()+159,AeroUi.MUTED,false);
        }else g.text(font,Component.translatable(loading?"packmod.pack_conversion.working":"packmod.pack_conversion.no_history"),x()+14,y()+95,AeroUi.MUTED,false);
        g.text(font,font.plainSubstrByWidth(message,w),x()+14,y()+ph()-70,0xFFFFD080,false);super.extractRenderState(g,mx,my,delta);
    }
    @Override public void onClose(){transitionTo(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
