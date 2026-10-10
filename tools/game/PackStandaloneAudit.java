package com.embers.packmod;

import com.embers.packmod.mixin.PackSelectionRefresh;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.zip.*;

/** Isolated launch only. Staged by tools/game/run_audit.py, excluded from release JARs. */
final class PackStandaloneAudit {
    private static int stage,age,ticks;
    private static Path packs;
    private static Screen home;
    private static PackSelectionScreen parent;
    private static byte[] original;
    private static byte[] overlayOriginal;
    private static final String OVERLAY = "e-overlay.zip";
    private static final String[] NAMES={"a-applied.zip","b-pending.zip","c-convert.zip","d-untouched.zip"};
    static void init(){ClientTickEvents.END_CLIENT_TICK.register(PackStandaloneAudit::tick);}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static void next(int value){stage=value;age=0;PackModClient.LOGGER.info("STANDALONE AUDIT stage {}",stage);}
    private static List<AbstractWidget> widgets(Screen s){return s.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();}
    private static AbstractWidget find(Screen s,String name){return widgets(s).stream().filter(w->w.getMessage().getString().equals(name)||AeroUi.ui(w.getMessage()).getString().equals(name)).findFirst().orElseThrow();}
    private static void click(Screen s,AbstractWidget w){
        var e=new MouseButtonEvent(w.getX()+w.getWidth()/2.0,w.getY()+w.getHeight()/2.0,new MouseButtonInfo(0,0));
        check(s.mouseClicked(e,false),"mouse input consumed: "+w.getMessage().getString());s.mouseReleased(e);
    }
    private static void click(Screen s,String label){click(s,find(s,label));}
    private static AbstractWidget convert(Screen s){return widgets(s).stream().filter(w->w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("packmod.pack_conversion.convert_selected")).findFirst().orElseThrow();}
    private static boolean phase(Screen s,String name)throws Exception{
        if(!(s instanceof PackConversionScreen))return false;
        var f=PackConversionScreen.class.getDeclaredField("phase");f.setAccessible(true);return f.get(s).toString().equals(name);
    }
    private static PackSelectionScreen packScreen(Minecraft c){return new PackSelectionScreen(c.getResourcePackRepository(),repo->c.gui.setScreen(home),packs,Component.translatable("options.resourcepack"));}
    private static void shot(Minecraft c,String name){Screenshot.grab(c.gameDirectory,"standalone-"+name+".png",c.gameRenderer.mainRenderTarget(),1,x->{});}
    private static void tick(Minecraft c){
        if(++ticks>3000){PackModClient.LOGGER.error("STANDALONE AUDIT timeout stage {}",stage);c.stop();return;}
        if(c.gui.overlay()!=null)return;age++;Screen screen=c.gui.screen();
        try{switch(stage){
            case 0->{if(!(screen instanceof TitleScreen)||age<25)return;
                c.getWindow().setWindowed(1280,800);c.options.guiScale().set(2);c.resizeGui();
                home=screen;packs=c.getResourcePackDirectory();Files.createDirectories(packs);
                try(var z=new ZipOutputStream(Files.newOutputStream(packs.resolve(NAMES[0])))){
                    z.putNextEntry(new ZipEntry("pack.mcmeta"));z.write("{\"pack\":{\"pack_format\":34,\"description\":\"Audit\"}}".getBytes());z.closeEntry();}
                original=Files.readAllBytes(packs.resolve(NAMES[0]));for(int i=1;i<NAMES.length;i++)Files.write(packs.resolve(NAMES[i]),original);
                c.options.languageCode="zh_cn";c.getLanguageManager().setSelected("zh_cn");
                c.getResourcePackRepository().reload();c.getResourcePackRepository().setSelected(List.of("vanilla","file/"+NAMES[0]));
                c.options.resourcePacks.clear();c.options.resourcePacks.add("file/"+NAMES[0]);c.reloadResourcePacks();next(1);}
            case 1->{if(age<30)return;
                check(c.getResourceManager().listPacks().anyMatch(p->p.packId().equals("file/"+NAMES[0])),"pack actually applied");
                parent=packScreen(c);c.gui.setScreen(parent);
                ((PackSelectionRefresh)parent).packmod$model().getUnselected().filter(e->e.getId().equals("file/"+NAMES[1])).findFirst().orElseThrow().select();next(2);}
            case 2->{if(!phase(screen,"SELECTING")||age<20)return;
                check(!find(screen,NAMES[0]).active&&!find(screen,NAMES[1]).active,"applied and pending packs protected");
                check(!convert(screen).active,"no implicit checkbox selection");
                click(screen,NAMES[2]);check(convert(screen).active,"eligible checkbox enables conversion");shot(c,"checklist");click(screen,AeroUi.ui("Conversion Options").getString());next(15);}
            case 3->{if(!phase(screen,"FINISHED")||age<20)return;
                check(!Files.exists(packs.resolve(NAMES[2])),"original archived out of root");
                check(Arrays.equals(original,Files.readAllBytes(packs.resolve("Old Pack").resolve(NAMES[2]))),"original bytes preserved");
                Path output=packs.resolve("[Java 26.2]"+NAMES[2]);check(PackInspector.inspect(output).supports(PackConversionManager.target()),"converted output compatible");
                check(!c.getResourcePackRepository().getSelectedIds().contains("file/"+output.getFileName()),"output not auto enabled");
                for(int i:new int[]{0,1,3})check(Files.exists(packs.resolve(NAMES[i])),"unchecked/protected source untouched");
                check(((PackSelectionRefresh)parent).packmod$model().getSelected().anyMatch(e->e.getId().equals("file/"+NAMES[1])),"pending selection survives refresh");
                shot(c,"result");click(screen,AeroUi.ui("Done").getString());next(4);}
            case 4->{if(age<35)return;check(screen==parent,"no automatic repeat");
                click(screen,AeroUi.ui("Check Pack Versions").getString());next(5);}
            case 5->{if(!phase(screen,"SELECTING")||age<20)return;
                check(find(screen,NAMES[3]).active,"manual recheck finds unchecked input");
                ((PackSelectionRefresh)parent).packmod$model().getSelected().filter(e->e.canUnselect()).toList().forEach(e->e.unselect());
                c.getResourcePackRepository().setSelected(List.of("vanilla"));c.options.resourcePacks.clear();c.reloadResourcePacks();c.gui.setScreen(home);next(6);}
            case 6->{if(age<30)return;parent=packScreen(c);c.gui.setScreen(parent);next(7);}
            case 7->{if(age<35)return;check(screen==parent,"reopening is manual only");
                click(screen,AeroUi.ui("Check Pack Versions").getString());next(8);}
            case 8->{if(!phase(screen,"SELECTING")||age<20)return;
                for(int i:new int[]{0,1,3})check(find(screen,NAMES[i]).active,"disabled applied-away pack becomes eligible");
                click(screen,AeroUi.ui("Select All").getString());click(screen,convert(screen));next(9);}
            case 9->{if(!phase(screen,"FINISHED")||age<20)return;
                for(String name:NAMES)check(Files.exists(packs.resolve("Old Pack").resolve(name)),"all original inputs archived");
                check(c.getResourcePackRepository().getSelectedIds().stream().noneMatch(id->id.startsWith("file/")),"outputs remain inactive");
                click(screen,AeroUi.ui("Done").getString());next(10);}
            case 10->{if(age<30)return;click(screen,AeroUi.ui("Check Pack Versions").getString());next(11);}
            case 11->{if(!phase(screen,"SELECTING")||age<20)return;
                check(widgets(screen).size()==1,"all-compatible popup has no pack rows");shot(c,"all-compatible");
                check(Files.readString(c.gameDirectory.toPath().resolve("config/packmod-pack-conversions.json")).contains("\"automaticCheckDone\":true"),"first visit persisted");
                click(screen,AeroUi.ui("Done").getString());next(12);}
            case 12->{if(age<30)return;
                try(var z=new ZipOutputStream(Files.newOutputStream(packs.resolve(OVERLAY)))){
                    z.putNextEntry(new ZipEntry("pack.mcmeta"));
                    z.write("{\"pack\":{\"pack_format\":34,\"description\":\"Overlay audit\"},\"overlays\":{\"entries\":[{\"formats\":[34,34],\"directory\":\"overlay\"}]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));z.closeEntry();
                    z.putNextEntry(new ZipEntry("overlay/assets/test/marker.txt"));z.write("preserved overlay".getBytes());z.closeEntry();}
                overlayOriginal=Files.readAllBytes(packs.resolve(OVERLAY));
                parent=packScreen(c);c.gui.setScreen(parent);click(parent,AeroUi.ui("Check Pack Versions").getString());next(13);}
            case 13->{if(!phase(screen,"SELECTING")||age<20)return;
                check(find(screen,OVERLAY).active,"overlay conversion can be explicitly selected");
                var row=find(screen,OVERLAY);
                var field=row.getClass().getDeclaredField("row");field.setAccessible(true);
                Object data=field.get(row);var warning=data.getClass().getDeclaredMethod("warning");warning.setAccessible(true);
                check((boolean)warning.invoke(data),"overlay warning shown before conversion");
                shot(c,"overlay-checklist");click(screen,OVERLAY);click(screen,convert(screen));next(14);}
            case 14->{if(!phase(screen,"FINISHED")||age<20)return;
                check(Arrays.equals(overlayOriginal,Files.readAllBytes(packs.resolve(OVERLAY))),"warning result leaves original in place");
                check(!Files.exists(packs.resolve("Old Pack").resolve(OVERLAY)),"warning result is not automatically archived");
                Path output=packs.resolve("[Java 26.2]"+OVERLAY);check(Files.exists(output),"warning output available for review");
                check(c.getResourcePackRepository().getAvailableIds().contains("file/"+output.getFileName()),"warning output is accepted into vanilla pack list");
                check(!c.getResourcePackRepository().getSelectedIds().contains("file/"+output.getFileName()),"warning output stays inactive");
                var row=find(screen,OVERLAY);var field=row.getClass().getDeclaredField("row");field.setAccessible(true);
                Object data=field.get(row);var warning=data.getClass().getDeclaredMethod("warning");warning.setAccessible(true);
                check((boolean)warning.invoke(data),"result distinguished as needs review");
                check(find(screen,AeroUi.ui("Open Conversion Reports").getString()).active,"reports are accessible from result screen");
                try(var zip=new ZipFile(output.toFile())){check(zip.getEntry("overlay/assets/test/marker.txt")==null&&zip.getEntry("assets/test/marker.txt")!=null,"source overlay materialized into output base");}
                shot(c,"overlay-result");click(screen,AeroUi.ui("Conversion History").getString());next(21);}
            case 15->{if(!(screen instanceof ConversionOptionsScreen)||age<15)return;
                click(screen,AeroUi.ui("Complete Preset").getString());
                var optionField=ConversionOptionsScreen.class.getDeclaredField("options");optionField.setAccessible(true);
                check(((ConversionOptions)optionField.get(screen)).allowDestructive(),"complete preset enables existing core options");
                click(screen,AeroUi.ui("Conservative Preset").getString());
                click(screen,AeroUi.ui("Generate Missing Resources [OFF]").getString());
                click(screen,AeroUi.ui("Generate Missing Resources [ON]").getString());
                shot(c,"conversion-options");click(screen,AeroUi.ui("Save").getString());next(16);}
            case 16->{if(!phase(screen,"SELECTING")||age<15)return;check(!PackConversionManager.options().generateMissing(),"options toggle saved");click(screen,AeroUi.ui("Preview Conversion").getString());next(17);}
            case 17->{if(!(screen instanceof ConversionPlanScreen)||age<20)return;
                var field=ConversionPlanScreen.class.getDeclaredField("lines");field.setAccessible(true);var lines=(List<?>)field.get(screen);
                if(lines.isEmpty())return;check(lines.stream().anyMatch(v->v.toString().contains("gui_surgeon")),"preview contains real native scheduler task");
                shot(c,"conversion-preview");click(screen,AeroUi.ui("Done").getString());next(18);}
            case 18->{if(!phase(screen,"SELECTING")||age<15)return;click(screen,convert(screen));click(screen,AeroUi.ui("Continue in Background").getString());next(27);}
            case 27->{if(screen!=parent||age<20)return;click(screen,AeroUi.ui("Conversion Results").getString());next(3);}
            case 21->{if(!(screen instanceof PackHistoryScreen)||age<20)return;
                check(find(screen,AeroUi.ui("Archive Reviewed Original").getString()).active,"reviewed original can be explicitly archived");
                var search=widgets(screen).stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow();search.setValue("e-overlay");
                check(find(screen,AeroUi.ui("Archive Reviewed Original").getString()).active,"history search keeps matching entry");
                click(screen,Component.translatable("packmod.pack_conversion.filter.all").getString());
                check(!find(screen,AeroUi.ui("Archive Reviewed Original").getString()).active,"completed filter excludes review output");
                click(screen,Component.translatable("packmod.pack_conversion.filter.completed").getString());
                check(find(screen,AeroUi.ui("Archive Reviewed Original").getString()).active,"review filter retains warnings after restart-style history loading");
                shot(c,"conversion-history");click(screen,AeroUi.ui("Report Details").getString());next(30);}
            case 30->{if(!(screen instanceof PackReportScreen)||age<20)return;
                var field=PackReportScreen.class.getDeclaredField("paragraphs");field.setAccessible(true);var lines=(List<?>)field.get(screen);
                if(lines.size()<2)return;check(lines.stream().anyMatch(v->v.toString().contains("overlays_flattened")),"detail page retains original warning code");
                shot(c,"report-details");click(screen,AeroUi.ui("Export Diagnostics").getString());next(31);}
            case 31->{if(age<20)return;Path diagnostics=PackConversionManager.reportDirectory().getParent().resolve("diagnostics");
                if(!Files.isDirectory(diagnostics))return;
                try(var paths=Files.list(diagnostics)){var archive=paths.filter(p->p.toString().endsWith(".zip")).findFirst();if(archive.isEmpty())return;try(var zip=new ZipFile(archive.get().toFile())){check(zip.getEntry("environment.json")!=null,"UI exports diagnostic package");}}
                click(screen,AeroUi.ui("Done").getString());next(32);}
            case 32->{if(!(screen instanceof PackHistoryScreen)||age<20)return;click(screen,AeroUi.ui("Archive Reviewed Original").getString());next(22);}
            case 22->{if(PackConversionManager.busy()||age<20)return;
                check(!Files.exists(packs.resolve(OVERLAY))&&Files.exists(packs.resolve("Old Pack").resolve(OVERLAY)),"explicit archive action moved reviewed original");
                check(find(screen,AeroUi.ui("Restore Original").getString()).active,"archive can be restored");click(screen,AeroUi.ui("Restore Original").getString());next(23);}
            case 23->{if(PackConversionManager.busy()||age<20)return;
                check(Arrays.equals(overlayOriginal,Files.readAllBytes(packs.resolve(OVERLAY))),"UI restore preserved original bytes");
                check(Files.exists(packs.resolve("[Java 26.2]"+OVERLAY)),"restoring original leaves converted output intact");
                shot(c,"history-restored");click(screen,AeroUi.ui("Done").getString());next(24);}
            case 24->{if(!phase(screen,"FINISHED")||age<15)return;click(screen,AeroUi.ui("Done").getString());next(25);}
            case 25->{if(screen!=parent||age<20)return;
                Files.write(packs.resolve("f-cancel.zip"),original);click(screen,AeroUi.ui("Check Pack Versions").getString());next(26);}
            case 26->{if(!phase(screen,"SELECTING")||age<20)return;
                click(screen,"f-cancel.zip");click(screen,convert(screen));check(phase(screen,"CONVERTING"),"conversion starts asynchronously");
                click(screen,AeroUi.ui("Cancel Conversion").getString());next(28);}
            case 28->{if(!phase(screen,"FINISHED")||age<20)return;
                check(Arrays.equals(original,Files.readAllBytes(packs.resolve("f-cancel.zip"))),"UI cancellation preserves original");
                check(!Files.exists(packs.resolve("[Java 26.2]f-cancel.zip")),"UI cancellation installs no output");
                shot(c,"conversion-cancelled");
                Path retryJob=Files.createTempDirectory(PackConversionManager.reportDirectory(),"retry-audit-");
                PackJobHistory.create(retryJob,packs.resolve("f-cancel.zip"),PackConversionFiles.signature(packs.resolve("f-cancel.zip")),ConversionOptions.defaults());
                PackJobHistory.update(retryJob,"failed","error","Isolated retry fixture");
                click(screen,AeroUi.ui("Conversion History").getString());next(33);}
            case 33->{if(!(screen instanceof PackHistoryScreen)||age<20)return;
                if(!find(screen,AeroUi.ui("Retry Failed Task").getString()).active)return;
                click(screen,AeroUi.ui("Retry Failed Task").getString());next(34);}
            case 34->{if(!phase(screen,"FINISHED")||age<20)return;
                check(Files.exists(packs.resolve("[Java 26.2]f-cancel.zip")),"retry publishes converted output");
                check(Arrays.equals(original,Files.readAllBytes(packs.resolve("Old Pack/f-cancel.zip"))),"retry archives original safely");
                check(!c.getResourcePackRepository().getSelectedIds().contains("file/[Java 26.2]f-cancel.zip"),"retry output stays inactive");
                shot(c,"retry-result");
                Files.writeString(c.gameDirectory.toPath().resolve("standalone-pass.txt"),"PASS: standalone launch, Chinese mouse input, first/manual checks, applied/pending protection, selective conversion, archive/refresh, overlay warnings, report details, presets, task preview, background results, history search/filter, diagnostic export, archive/restore, cancellation, failed-task retry and no auto enable.\n");
                PackModClient.LOGGER.info("STANDALONE AUDIT PASS");c.stop();next(99);}
        }}catch(Throwable error){PackModClient.LOGGER.error("STANDALONE AUDIT failed stage "+stage,error);c.stop();next(99);}
    }
}
