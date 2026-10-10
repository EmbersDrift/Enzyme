package com.embers.packmod;

import com.embers.packmod.mixin.PackSelectionRefresh;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One automatic check on the first pack-screen visit; later checks are explicit batches. */
final class PackConversionManager {
    record Candidate(PackInspector.Info info, String signature) {}
    record Problem(Path path, String message) {}
    record Report(List<Candidate> incompatible, List<Problem> problems) {}
    record Outcome(Candidate candidate, Path output, Path archived, String error, PyramidConverter.Result conversion, boolean cancelled) {}
    record OutcomeCounts(int completed, int review, int failed, int cancelled, int archiveFailed) {
        static OutcomeCounts of(List<Outcome> outcomes) {
            int done=0,review=0,failed=0,cancelled=0,archiveFailed=0;
            for(var o:outcomes) {
                if(o.output()!=null) { if(o.conversion()!=null&&o.conversion().needsReview())review++;else done++;if(o.error()!=null)archiveFailed++; }
                else if(o.cancelled())cancelled++;else failed++;
            }
            return new OutcomeCounts(done,review,failed,cancelled,archiveFailed);
        }
    }
    private static ExecutorService worker;
    private static Path packDirectory, tools, stateFile;
    private static PackInspector.Version target;
    // These flags are owned by the client thread.
    private static boolean automaticCheckDone, busy;
    private static Screen queuedCheck;
    private static PackConversionScreen latestPopup;
    private static ConversionControl control;
    private static ConversionOptions options;
    private static Path optionsFile;

    static void init() {
        Minecraft client = Minecraft.getInstance();
        packDirectory = client.getResourcePackDirectory().toAbsolutePath().normalize();
        tools = client.gameDirectory.toPath().resolve("packmod-tools/pyramid").toAbsolutePath().normalize();
        stateFile = FabricLoader.getInstance().getConfigDir().resolve("packmod-pack-conversions.json");
        var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        target = new PackInspector.Version(format.major(), format.minor());
        optionsFile = FabricLoader.getInstance().getConfigDir().resolve("packmod-conversion-options.json");
        options = ConversionOptions.read(optionsFile);
        try {
            if (Files.isRegularFile(stateFile)) {
                JsonObject saved = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
                automaticCheckDone = saved.has("automaticCheckDone") && saved.get("automaticCheckDone").getAsBoolean();
            }
        } catch (Exception error) { PackModClient.LOGGER.warn("Could not read pack check state", error); }
        worker = Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "PackMod-pack-conversion"); thread.setDaemon(true); return thread;
        });
        ClientTickEvents.END_CLIENT_TICK.register(clientTick -> {
            Screen screen = clientTick.gui.screen();
            if (queuedCheck != null) {
                Screen requested = queuedCheck; queuedCheck = null;
                if (screen == requested) requestCheck(requested);
                return;
            }
            if (!automaticCheckDone && !busy && packScreen(screen) != null && clientTick.gui.overlay() == null
                    && !(screen instanceof FadingScreen fading && fading.isClosing())) requestCheck(screen);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> { if(control!=null)control.cancel(); worker.shutdownNow(); });
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (screen instanceof PackSelectionScreen) {
                Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
                        AeroUi.ui("Check Pack Versions"), button -> queuedCheck = minecraft.gui.screen())
                        .bounds(width - 117, 7, 108, 18).build());
                Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
                        AeroUi.ui("Conversion Results"), button -> showResults(screen)).bounds(width - 229, 7, 108, 18).build());
                Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
                        AeroUi.ui("Conversion History"), button -> minecraft.gui.setScreen(new PackHistoryScreen(screen))).bounds(9, 7, 108, 18).build());
            }
        });
    }

    static ConversionOptions options() { return options; }
    static void options(ConversionOptions next) {
        options=next;
        worker.execute(()->{try{next.save(optionsFile);}catch(Exception e){PackModClient.LOGGER.warn("Cannot save conversion options",e);}});
    }
    static void preview(List<Candidate> selected,java.util.function.Consumer<List<String>> callback) {
        ConversionOptions chosen=options;
        String summary=ReportText.options(chosen);
        worker.execute(()->{
            List<String> lines=new ArrayList<>();lines.add(summary);
            for(Candidate candidate:selected) {
                lines.add(candidate.info().path().getFileName()+" : "+candidate.info().range()+" -> "+target);
                try {
                    var plan=PyramidConverter.preview(candidate.info(),target,tools);
                    for(var item:plan.getAsJsonArray("tasks")) {
                        String name=item.getAsString();
                        boolean skip=name.equals("adapt_java_shaders")&&!chosen.adaptShaders()
                                || name.startsWith("generate_")&&!chosen.generateMissing()
                                || !chosen.allowDestructive()&&(name.startsWith("delete_")&&!name.equals("delete_font_folder")||name.startsWith("reverse_generate_"));
                        lines.add((skip?"[skip] ":"[run] ")+name);
                    }
                    if(candidate.info().warnings().contains("overlays_unconverted"))lines.add(chosen.flattenOverlays()?"[run] materialize active source overlays":"[review] overlay migration disabled");
                }catch(Exception error){lines.add(String.valueOf(error.getMessage()));}
            }
            List<String> result=List.copyOf(lines);Minecraft.getInstance().execute(()->callback.accept(result));
        });
    }
    static void cancel() { if(control!=null)control.cancel(); }
    static void showResults(Screen parent) {
        if(latestPopup!=null) { latestPopup.reparent(parent); Minecraft.getInstance().gui.setScreen(latestPopup); }
        else Minecraft.getInstance().gui.setScreen(new PackHistoryScreen(parent));
    }
    static void loadHistory(java.util.function.Consumer<List<PackJobHistory.Entry>> callback) {
        worker.execute(()->{
            List<PackJobHistory.Entry> entries;
            try {entries=PackJobHistory.load(reportDirectory());}
            catch(Exception error){PackModClient.LOGGER.warn("Cannot load conversion history",error);entries=List.of();}
            var result=entries;Minecraft.getInstance().execute(()->callback.accept(result));
        });
    }
    static void loadJob(Path job,java.util.function.Consumer<JsonObject> callback) {
        worker.execute(()->{
            JsonObject document=new JsonObject();
            for(String name:List.of("job","result","capabilities")){
                Path file=job.resolve(name+".json");
                try{if(Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&Files.size(file)<=16*1024*1024)document.add(name,JsonParser.parseString(Files.readString(file)));}
                catch(Exception e){document.addProperty("load_error",String.valueOf(e.getMessage()));}
            }
            Minecraft.getInstance().execute(()->callback.accept(document));
        });
    }
    static void exportDiagnostics(Path job,java.util.function.Consumer<String> callback) {
        JsonObject environment=new JsonObject();
        environment.addProperty("mod_version",FabricLoader.getInstance().getModContainer(PackModClient.ID).orElseThrow().getMetadata().getVersion().getFriendlyString());
        environment.addProperty("minecraft_version",SharedConstants.getCurrentVersion().name());environment.addProperty("target_format",target.toString());
        for(String id:List.of("fabricloader","fabric-api"))FabricLoader.getInstance().getModContainer(id).ifPresent(m->environment.addProperty(id,m.getMetadata().getVersion().getFriendlyString()));
        environment.addProperty("java_version",System.getProperty("java.version"));environment.addProperty("os",System.getProperty("os.name"));environment.addProperty("arch",System.getProperty("os.arch"));
        worker.execute(()->{
            String message;
            try{Path file=PackDiagnostics.export(job,tools.resolve("diagnostics"),environment);message=file.getFileName().toString();}
            catch(Exception error){message=String.valueOf(error.getMessage());}
            String result=message;Minecraft.getInstance().execute(()->callback.accept(result));
        });
    }
    static void retry(PackJobHistory.Entry entry,Screen parent,java.util.function.Consumer<String> errorCallback) {
        if(busy||!entry.retryable())return;
        busy=true;
        worker.execute(()->{
            try {
                Path source=PackJobHistory.retryInput(entry,packDirectory);
                var info=PackInspector.inspect(source);if(info==null)throw new java.io.IOException("Original metadata is missing");
                var candidate=new Candidate(info,entry.signature());requireUnused(candidate,parent);
                Minecraft.getInstance().execute(()->{
                    busy=false;PackConversionScreen popup=new PackConversionScreen(parent);latestPopup=popup;
                    Minecraft.getInstance().gui.setScreen(popup);popup.retryStarted();convert(List.of(candidate),popup,parent,entry.options());
                });
            }catch(Exception error){Minecraft.getInstance().execute(()->{busy=false;errorCallback.accept(String.valueOf(error.getMessage()));});}
        });
    }
    static void historyAction(PackJobHistory.Entry entry, boolean restore, Screen parent, java.util.function.Consumer<String> callback) {
        if(busy)return; busy=true;
        worker.execute(()->{
            String message;
            try {
                Path path;
                if(restore) path=PackJobHistory.restore(entry,packDirectory);
                else {
                    var candidate=new Candidate(PackInspector.inspect(entry.source()),entry.signature());
                    if(candidate.info()==null)throw new java.io.IOException("Original metadata is missing");
                    requireUnused(candidate,parent);
                    path=archiveJournaled(entry.source(),entry.signature(),entry.journal().getParent());
                }
                message=path.getFileName().toString();
            } catch(Exception error) {message=String.valueOf(error.getMessage());}
            String result=message; Minecraft.getInstance().execute(()->{busy=false;Minecraft.getInstance().getResourcePackRepository().reload();callback.accept(result);});
        });
    }
    private static Path archiveJournaled(Path source,String signature,Path job) throws java.io.IOException {
        try {
            Path archived=PackConversionFiles.archive(source,signature,packDirectory,destination->{
                try {PackJobHistory.update(job,"archiving","archive_pending",destination.toString());}
                catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
            });
            PackJobHistory.update(job,"archived","archived",archived.toString()); return archived;
        }catch(java.io.UncheckedIOException error){throw error.getCause();}
    }
    static PackInspector.Version target() { return target; }
    static Path packDirectory() { return packDirectory; }
    static Path reportDirectory() { return tools.resolve("jobs"); }
    static boolean busy() { return busy; }
    static PackSelectionScreen packScreen(Screen screen) {
        return screen instanceof PackSelectionScreen packs ? packs : null;
    }

    static Set<String> protectedIds(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        Set<String> ids = new HashSet<>(client.getResourcePackRepository().getSelectedIds());
        ids.addAll(client.options.resourcePacks);
        client.getResourceManager().listPacks().map(PackResources::packId).forEach(ids::add);
        Screen context=parent;
        for(int depth=0;depth<16;depth++){
            if(context instanceof PackConversionScreen popup)context=popup.parentScreen();
            else if(context instanceof PackHistoryScreen history)context=history.parentScreen();
            else break;
        }
        PackSelectionScreen nativeScreen = packScreen(context);
        if (nativeScreen != null) ((PackSelectionRefresh) nativeScreen).packmod$model().getSelected().forEach(entry -> ids.add(entry.getId()));
        PackSelectionScreen current = packScreen(client.gui.screen());
        if (current != null && current != nativeScreen)
            ((PackSelectionRefresh) current).packmod$model().getSelected().forEach(entry -> ids.add(entry.getId()));
        return ids;
    }

    static boolean inUse(Candidate candidate, Set<String> protectedIds) {
        return protectedIds.contains("file/" + candidate.info.path().getFileName());
    }

    static void requestCheck(Screen parent) {
        if (busy || packScreen(parent) == null) return;
        busy = true;
        boolean saveFirstVisit = !automaticCheckDone;
        automaticCheckDone = true;
        PackConversionScreen popup = new PackConversionScreen(parent);
        latestPopup=popup;
        Minecraft client = Minecraft.getInstance();
        client.gui.setScreen(popup);
        worker.execute(() -> {
            if (saveFirstVisit) saveState();
            Report report = scan();
            client.execute(() -> { busy = false; popup.detected(report, protectedIds(parent)); });
        });
    }

    private static void saveState() {
        try {
            Files.createDirectories(stateFile.getParent());
            JsonObject saved = new JsonObject(); saved.addProperty("automaticCheckDone", true);
            Path staged = Files.createTempFile(stateFile.getParent(), "packmod-pack-state-", ".tmp");
            try {
                Files.writeString(staged, saved.toString());
                Files.move(staged, stateFile, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(staged); }
        } catch (Exception error) { PackModClient.LOGGER.warn("Could not save first resource pack visit", error); }
    }

    private static Report scan() {
        List<Candidate> incompatible = new ArrayList<>(); List<Problem> problems = new ArrayList<>();
        try {
            Files.createDirectories(packDirectory);
            try (var paths = Files.list(packDirectory)) {
                for (Path path : paths.filter(PackConversionFiles::candidate).sorted().toList()) {
                    try {
                        String before = PackConversionFiles.signature(path);
                        PackInspector.Info info = PackInspector.inspect(path);
                        if (!before.equals(PackConversionFiles.signature(path))) throw new java.io.IOException("File is still changing; check again after copying finishes");
                        if (info == null) throw new java.io.IOException("No resource pack metadata found");
                        if (!info.supports(target)) incompatible.add(new Candidate(info, before));
                    } catch (Exception error) { problems.add(new Problem(path, error.getMessage())); }
                }
            }
        } catch (Exception error) { problems.add(new Problem(packDirectory, error.getMessage())); }
        return new Report(List.copyOf(incompatible), List.copyOf(problems));
    }

    private static void requireUnused(Candidate candidate, Screen parent) throws Exception {
        CompletableFuture<Boolean> protectedNow = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try { protectedNow.complete(inUse(candidate, protectedIds(parent))); }
            catch (Exception error) { protectedNow.completeExceptionally(error); }
        });
        if (protectedNow.get(30, TimeUnit.SECONDS)) throw new java.io.IOException("This pack is enabled or selected; disable and apply it before converting");
    }

    static void convert(List<Candidate> selected, PackConversionScreen popup, Screen parent) {
        convert(selected,popup,parent,options);
    }
    private static void convert(List<Candidate> selected, PackConversionScreen popup, Screen parent, ConversionOptions chosenOptions) {
        if (busy || selected.isEmpty()) return;
        busy = true;
        List<Candidate> batch = List.copyOf(selected);
        control=new ConversionControl();
        ConversionControl batchControl=control; latestPopup=popup;
        worker.execute(() -> {
            Minecraft client = Minecraft.getInstance(); List<Outcome> results = new ArrayList<>();
            int index = 0;
            for (Candidate candidate : batch) {
                int ordinal = ++index; Path installed = null, archived = null; String failure = null;
                PyramidConverter.Result conversion = null;
                try {
                    batchControl.check();
                    requireUnused(candidate, parent);
                    if (!candidate.signature.equals(PackConversionFiles.signature(candidate.info.path())))
                        throw new java.io.IOException("Resource pack changed since detection. Check its version again.");
                    conversion = PyramidConverter.convertWithReport(candidate.info, target, packDirectory, tools, chosenOptions, batchControl,
                            key -> client.execute(() -> popup.progress(ordinal, batch.size(), candidate, key)));
                    installed = conversion.output();
                    if (!conversion.needsReview()) {
                        batchControl.check();
                        requireUnused(candidate, parent);
                        archived = archiveJournaled(candidate.info.path(), candidate.signature, conversion.reportFile().getParent());
                    }
                } catch (Exception error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    failure = error.getMessage();
                    if(installed!=null&&conversion!=null) {
                        try{PackJobHistory.update(conversion.reportFile().getParent(),"archive_failed","error",String.valueOf(failure));}
                        catch(java.io.IOException journalError){error.addSuppressed(journalError);}
                    }
                    if(batchControl.cancelled())PackModClient.LOGGER.info("Pack conversion cancelled for {}",candidate.info.path().getFileName());
                    else PackModClient.LOGGER.error("Pack conversion/archive failed for {}", candidate.info.path().getFileName(), error);
                }
                results.add(new Outcome(candidate, installed, archived, failure, conversion, failure!=null&&batchControl.cancelled()));
                if (batchControl.cancelled()) break;
            }
            List<Outcome> outcomes = List.copyOf(results);
            client.execute(() -> {
                busy = false; control=null;
                client.getResourcePackRepository().reload();
                popup.completed(outcomes);
            });
        });
    }
}
