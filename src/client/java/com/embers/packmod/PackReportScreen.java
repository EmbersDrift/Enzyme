package com.embers.packmod;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import java.nio.file.Path;
import java.util.*;

/** Scrollable report viewer; reads reports asynchronously, including failed jobs. */
final class PackReportScreen extends FadingScreen {
    private final Screen parent;
    private final Path job;
    private List<String> paragraphs = List.of(ReportText.text("working"));
    private List<FormattedCharSequence> lines = List.of();
    private int offset;
    private boolean started, exporting;
    private String message = "";
    PackReportScreen(Screen parent, Path job) { super(Component.translatable("packmod.pack_conversion.report_details")); this.parent=parent; this.job=job; }
    private int pw(){return Math.min(620,width-20);}
    private int ph(){return Math.min(420,height-16);}
    private int x(){return (width-pw())/2;}
    private int y(){return (height-ph())/2;}
    private int visible(){return Math.max(1,(ph()-113)/12);}
    @Override protected void init(){
        if(!started){started=true;PackConversionManager.loadJob(job,document->{
            try { paragraphs=describe(document); }
            catch(RuntimeException malformed){paragraphs=List.of(ReportText.text("report_error", "Malformed report: " + malformed.getClass().getSimpleName()));}
            offset=0;rebuild();
        });}
        lines=paragraphs.stream().flatMap(p->font.split(Component.literal(p),pw()-32).stream()).toList();
        offset=Math.clamp(offset,0,Math.max(0,lines.size()-visible()));
        int third=(pw()-40)/3, bottom=y()+ph()-29;
        addRenderableWidget(new AeroButton(x()+14,bottom,third,20,"Open Conversion Reports",b->Util.getPlatform().openPath(job)));
        var export=addRenderableWidget(new AeroButton(x()+20+third,bottom,third,20,"Export Diagnostics",b->{exporting=true;message=ReportText.text("working");rebuild();PackConversionManager.exportDiagnostics(job,result->{exporting=false;message=result;rebuild();});}));
        export.active=!exporting;
        export.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("packmod.pack_conversion.diagnostic_help")));
        addRenderableWidget(new AeroButton(x()+26+third*2,bottom,third,20,"Done",b->onClose()));
    }
    private void rebuild(){clearWidgets();init();}
    private List<String> describe(JsonObject document){
        List<String> result=new ArrayList<>();
        JsonObject journal=document.has("job")?document.getAsJsonObject("job"):new JsonObject();
        JsonObject report=document.has("result")?document.getAsJsonObject("result"):new JsonObject();
        result.add(ReportText.text("report_source",value(journal,"source")));
        result.add(ReportText.text("report_output",value(journal,"installed_output")));
        result.add(ReportText.text("report_original",value(journal,"archived").isEmpty()?value(journal,"source"):value(journal,"archived")));
        result.add(ReportText.text("report_conversion",value(report,"status")));
        result.add(ReportText.text("report_archive",ReportText.text("state."+value(journal,"status"))));
        if(journal.has("options"))try{result.add(ReportText.options(ConversionOptions.fromJson(journal.getAsJsonObject("options"))));}catch(RuntimeException ignored){}
        if(report.has("elapsed_ms"))result.add(ReportText.text("elapsed",report.get("elapsed_ms").getAsLong()/1000.0));
        if(report.has("changes")&&!report.get("changes").isJsonNull()){
            var c=report.getAsJsonObject("changes");result.add(ReportText.text("changes",value(c,"added"),value(c,"modified"),value(c,"removed")));
        }
        if(document.has("capabilities"))result.add(ReportText.text("core_version",value(document.getAsJsonObject("capabilities"),"core_version")));
        if(!value(journal,"error").isEmpty())result.add(ReportText.text("report_error",value(journal,"error")));
        if(!value(report,"error").isEmpty())result.add(ReportText.text("report_error",value(report,"error")));
        if(document.has("load_error"))result.add(ReportText.text("report_error",value(document,"load_error")));
        result.add(ReportText.text("report_warnings"));
        if(report.has("warnings"))for(var item:report.getAsJsonArray("warnings")){
            var w=item.getAsJsonObject();var warning=new ConversionReport.Warning(value(w,"code"),value(w,"detail"));
            result.add(ReportText.warning(warning));result.add(ReportText.text("report_raw",warning.code()+": "+warning.detail()));
        }
        result.add(ReportText.text("report_repairs"));
        if(report.has("repairs"))for(var item:report.getAsJsonArray("repairs")){
            var r=item.getAsJsonObject();result.add(ReportText.repair(value(r,"code"),value(r,"path"),value(r,"detail")));
            result.add(ReportText.text("report_raw",value(r,"code")+": "+value(r,"detail")));
        }
        if(report.has("image_budget_checks"))for(var item:report.getAsJsonArray("image_budget_checks")){
            var c=item.getAsJsonObject();result.add(ReportText.text("report_budget",value(c,"phase"),value(c,"effective_workers"),value(c,"requested_workers"),value(c,"largest_image")));
        }
        if(report.has("tasks")){
            result.add(ReportText.text("report_tasks"));
            for(var item:report.getAsJsonArray("tasks")){var t=item.getAsJsonObject();result.add(value(t,"name")+" · "+ReportText.text(t.has("skipped")&&t.get("skipped").getAsBoolean()?"task_skipped":t.has("success")&&t.get("success").getAsBoolean()?"task_done":"task_failed"));}
        }
        return List.copyOf(result);
    }
    private static String value(JsonObject d,String key){return d.has(key)&&!d.get(key).isJsonNull()?d.get(key).getAsString():"";}
    @Override public boolean mouseScrolled(double mx,double my,double dx,double dy){if(isClosing())return true;offset=Math.clamp(offset-(int)Math.signum(dy)*3,0,Math.max(0,lines.size()-visible()));return true;}
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float delta){if(minecraft.level==null)extractPanorama(g,delta);AeroUi.modalBackdrop(g,width,height,x(),y(),pw(),ph());AeroUi.glass(g,x(),y(),pw(),ph(),13);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        g.text(font,getTitle(),x()+14,y()+13,AeroUi.WHITE,false);
        g.text(font,ReportText.text("report_scroll",Math.min(lines.size(),offset+1),lines.size()),x()+14,y()+30,AeroUi.MUTED,false);
        for(int i=offset;i<Math.min(lines.size(),offset+visible());i++)g.text(font,lines.get(i),x()+14,y()+49+(i-offset)*12,AeroUi.WHITE,false);
        g.text(font,font.plainSubstrByWidth(message,pw()-28),x()+14,y()+ph()-48,0xFFFFD080,false);super.extractRenderState(g,mx,my,delta);
    }
    @Override public void onClose(){transitionTo(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
