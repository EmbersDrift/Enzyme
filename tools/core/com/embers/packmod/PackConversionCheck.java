package com.embers.packmod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Integration checks for version ranges, real native conversion and non-destructive outputs. */
public final class PackConversionCheck {
    private static final PackInspector.Version TARGET = new PackInspector.Version(88, 0);
    public static void main(String[] args) throws Exception {
        checkReportValidation();
        Path work = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(work);
        CompatibilityAdapterCheck.run(work);
        Path packs = work.resolve("packs"), tools = work.resolve("tools"); Files.createDirectories(packs);
        MetadataStagingCheck.run(work, packs, tools, TARGET);
        if (args.length > 1) {
            Path regression = Path.of(args[1]);
            if (Files.isDirectory(regression)) {
                try (var files = Files.list(regression)) {
                    for (Path input : files.filter(Files::isRegularFile).filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")).sorted().toList())
                        checkRegressionPack(input, packs, tools);
                }
            } else checkRegressionPack(regression, packs, tools);
        }
        checkMislabeledImages(work, packs, tools);
        Path old = make(work.resolve("old pack.zip"), "", "{\"pack\":{\"pack_format\":34,\"description\":\"Old\"}}");
        byte[] original = Files.readAllBytes(old);
        check(!PackInspector.inspect(old).supports(TARGET), "Old version detection");
        var first = PyramidConverter.convertWithReport(PackInspector.inspect(old), TARGET, packs, tools, System.out::println);
        Path converted = first.output();
        checkModReports(work,packs,tools,old,first);
        check(PackInspector.inspect(converted).supports(TARGET), "Real output range");
        check(first.report().changes().modified() >= 1 && first.report().completedTasks() > 0, "Native task and file change report");
        check(Files.isRegularFile(first.reportFile()) && !first.needsReview(), "Clean native report is retained without compatibility warnings");
        var installedReport = com.google.gson.JsonParser.parseString(Files.readString(first.reportFile())).getAsJsonObject();
        check(installedReport.get("installed_output").getAsString().equals(converted.toString())
                && installedReport.get("metadata_validated").getAsBoolean(), "Persistent report points to installed output after staging cleanup");
        Path legacy = work.resolve("legacy textures.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(legacy))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"pack_format\":1,\"description\":\"Legacy\"}}".getBytes()); zip.closeEntry();
            var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, 0xFF276B9C);
            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/items/diamond_sword.png"));
            javax.imageio.ImageIO.write(image, "png", zip); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/minecraft/font/default.json"));
            zip.write(("{\"providers\":[{\"type\":\"space\",\"advances\":{\"A\":19}},"
                    + "{\"type\":\"legacy_unicode\",\"sizes\":\"minecraft:font/glyph_sizes.bin\",\"template\":\"minecraft:font/unicode_page_%s.png\"}]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Path remapped = PyramidConverter.convert(PackInspector.inspect(legacy), TARGET, packs, tools, ignored -> {});
        try (var zip = new java.util.zip.ZipFile(remapped.toFile())) {
            var texture = zip.getEntry("assets/minecraft/textures/item/diamond_sword.png");
            check(texture != null, "Legacy item texture path remapped by real pipeline");
            try (var image = zip.getInputStream(texture)) {
                check(javax.imageio.ImageIO.read(image).getRGB(3, 3) == 0xFF276B9C, "Texture pixels preserved");
            }
            var font = zip.getEntry("assets/minecraft/font/default.json");
            check(font != null, "Legacy Minecraft font is preserved instead of deleted");
            try (var stream = zip.getInputStream(font)) {
                var json = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                var providers = json.getAsJsonObject().getAsJsonArray("providers");
                check(providers.get(0).getAsJsonObject().getAsJsonObject("advances").get("A").getAsInt() == 19,
                        "Custom font settings unchanged");
                check(providers.get(1).getAsJsonObject().get("id").getAsString().equals("minecraft:include/unifont"),
                        "Removed legacy Unicode provider upgraded");
            }
        }
        check(Arrays.equals(original, Files.readAllBytes(old)), "Source bytes preserved");
        Path again = PyramidConverter.convert(PackInspector.inspect(old), TARGET, packs, tools, ignored -> {});
        check(!again.equals(converted) && Files.exists(converted), "Output name collision does not overwrite");
        Path modern = make(work.resolve("newer.zip"), "", "{\"pack\":{\"min_format\":[97,0],\"max_format\":[97,1],\"description\":\"New\"}}");
        Path downgraded = PyramidConverter.convert(PackInspector.inspect(modern), TARGET, packs, tools, ignored -> {});
        check(PackInspector.inspect(downgraded).supports(TARGET), "Modern source downgrade");
        Path nested = make(work.resolve("nested.zip"), "wrapper/", "{\"pack\":{\"pack_format\":18,\"description\":\"Nested\"}}");
        Path normalized = PyramidConverter.convert(PackInspector.inspect(nested), TARGET, packs, tools, ignored -> {});
        check(PackInspector.inspect(normalized).metadata().equals("pack.mcmeta"), "Nested input root normalized");
        try (var zip = new java.util.zip.ZipFile(normalized.toFile())) {
            check(zip.getEntry("assets/test/font/custom.json") != null, "Resource-pack font retained");
            check(zip.getEntry("assets/test/marker.txt") != null, "Custom namespace retained");
        }
        Path directory = work.resolve("folder"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"Folder\"}}");
        check(PackInspector.inspect(PyramidConverter.convert(PackInspector.inspect(directory), TARGET, packs, tools, ignored -> {})).supports(TARGET), "Directory input");
        Path supported = make(work.resolve("supported.zip"), "", "{\"pack\":{\"pack_format\":34,\"supported_formats\":[34,97],\"description\":\"Range\"}}");
        check(PackInspector.inspect(supported).supports(TARGET), "Supported range accepted without conversion");
        Path minor = make(work.resolve("minor.zip"), "", "{\"pack\":{\"min_format\":[88,1],\"max_format\":[88,3],\"description\":\"Minor\"}}");
        check(!PackInspector.inspect(minor).supports(TARGET), "Minor format mismatch");
        Path unsafe = make(work.resolve("unsafe.zip"), "", "{\"pack\":{\"pack_format\":34,\"description\":\"Unsafe\"}}");
        try (var zip = new ZipOutputStream(Files.newOutputStream(unsafe))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"pack_format\":34,\"description\":\"Unsafe\"}}".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../outside.txt")); zip.write(1); zip.closeEntry();
        }
        byte[] unsafeOriginal = Files.readAllBytes(unsafe);
        boolean rejected = false;
        try { PyramidConverter.convert(PackInspector.inspect(unsafe), TARGET, packs, tools, ignored -> {}); }
        catch (java.io.IOException expected) { rejected = expected.getMessage().startsWith("Unsafe ZIP entry"); }
        check(rejected && !Files.exists(work.resolve("outside.txt")), "Invalid ZIP path rejected before conversion");
        check(Arrays.equals(unsafeOriginal, Files.readAllBytes(unsafe)), "Failure preserves source");
        Path batchSource = packs.resolve("batch source.zip");
        Files.copy(old, batchSource);
        String batchSignature = PackConversionFiles.signature(batchSource);
        Path batchOutput = PyramidConverter.convert(PackInspector.inspect(batchSource), TARGET, packs, tools, ignored -> {});
        Path archived = PackConversionFiles.archive(batchSource, batchSignature, packs);
        check(!Files.exists(batchSource) && Files.exists(batchOutput), "Successful batch archives source but leaves output available");
        check(archived.getParent().equals(packs.resolve("Old Pack")) && Arrays.equals(original, Files.readAllBytes(archived)), "Archived original bytes preserved");
        check(!PackConversionFiles.candidate(archived.getParent()), "Old Pack excluded from conversion candidates");
        Files.copy(old, batchSource);
        Path collision = PackConversionFiles.archive(batchSource, PackConversionFiles.signature(batchSource), packs);
        check(!collision.equals(archived) && Arrays.equals(original, Files.readAllBytes(archived)), "Archive collision does not overwrite original backups");
        Path batchFolder = packs.resolve("folder archive"); Files.createDirectories(batchFolder.resolve("assets/test"));
        Files.copy(directory.resolve("pack.mcmeta"), batchFolder.resolve("pack.mcmeta"));
        Path nestedAsset = batchFolder.resolve("assets/test/marker.txt"); Files.writeString(nestedAsset, "old");
        String folderSignature = PackConversionFiles.signature(batchFolder);
        Files.writeString(nestedAsset, "changed contents");
        boolean changedRejected = false;
        try { PackConversionFiles.archive(batchFolder, folderSignature, packs); }
        catch (java.io.IOException expected) { changedRejected = true; }
        check(changedRejected && Files.exists(nestedAsset), "Changed folder contents prevent archiving stale conversion");
        Path folderArchive = PackConversionFiles.archive(batchFolder, PackConversionFiles.signature(batchFolder), packs);
        check(!Files.exists(batchFolder) && Files.readString(folderArchive.resolve("assets/test/marker.txt")).equals("changed contents"), "Directory-form original moved intact");

        Path overlay = make(work.resolve("overlay.zip"), "", "{\"pack\":{\"pack_format\":34,\"description\":\"Layers\"},\"overlays\":{\"entries\":[{\"formats\":[34,34],\"directory\":\"overlay\"}]}}",
                new String[]{"overlay/assets/test/marker.txt", "overlay retained"});
        byte[] overlayOriginal = Files.readAllBytes(overlay);
        check(PackInspector.inspect(overlay).warnings().contains("overlays_unconverted"), "Overlay warning available before conversion");
        var overlayResult = PyramidConverter.convertWithReport(PackInspector.inspect(overlay), TARGET, packs, tools, ignored -> {});
        check(overlayResult.needsReview() && overlayResult.report().warnings().stream().anyMatch(w -> w.code().equals("overlays_flattened")), "Flattened overlay result requires review");
        var persisted = com.google.gson.JsonParser.parseString(Files.readString(overlayResult.reportFile())).getAsJsonObject();
        check(persisted.get("status").getAsString().equals("needs_review") && persisted.get("archive_validated").getAsBoolean(), "Persistent report separates readability from compatibility warnings");
        try (var zip = new java.util.zip.ZipFile(overlayResult.output().toFile())) {
            check(zip.getEntry("overlay/assets/test/marker.txt") == null, "Flattened overlay directory removed");
            check(new String(zip.getInputStream(zip.getEntry("assets/test/marker.txt")).readAllBytes()).equals("overlay retained"), "Active overlay overrides base content");
            check(!new String(zip.getInputStream(zip.getEntry("pack.mcmeta")).readAllBytes()).contains("overlays"), "Flattened metadata no longer declares removed overlay directories");
        }
        check(Arrays.equals(overlayOriginal, Files.readAllBytes(overlay)), "Warning result preserves original bytes");

        Path modelPack = make(work.resolve("legacy models.zip"), "", "{\"pack\":{\"pack_format\":1,\"description\":\"Models\"}}",
                new String[]{"assets/minecraft/models/item/demo.json", "{\"parent\":\"minecraft:item/generated\"}"});
        var modelResult = PyramidConverter.convertWithReport(PackInspector.inspect(modelPack), TARGET, packs, tools, ignored -> {});
        check(modelResult.needsReview() && modelResult.report().warnings().stream().anyMatch(w -> w.code().equals("models_preserved")), "Default conversion preserves models with review warning");
        try(var zip=new java.util.zip.ZipFile(modelResult.output().toFile())) {check(zip.getEntry("assets/minecraft/models/item/demo.json")!=null,"Legacy model retained by conservative default");}
        var destructive=new ConversionOptions(true,true,true,true,true,2,256);
        var removed=PyramidConverter.convertWithReport(PackInspector.inspect(modelPack),TARGET,packs,tools,destructive,new ConversionControl(),ignored->{});
        check(removed.report().warnings().stream().anyMatch(w->w.code().equals("removed_models")),"Explicit destructive cleanup records removed models");
        try(var zip=new java.util.zip.ZipFile(removed.output().toFile())){check(zip.getEntry("assets/minecraft/models/item/demo.json")==null,"Explicit destructive option takes effect");}
        var keepOptions=new ConversionOptions(false,false,false,false,false,1,128);
        var kept=PyramidConverter.convertWithReport(PackInspector.inspect(overlay),TARGET,packs,tools,keepOptions,new ConversionControl(),ignored->{});
        try(var zip=new java.util.zip.ZipFile(kept.output().toFile())){check(zip.getEntry("overlay/assets/test/marker.txt")!=null,"Overlay migration can be disabled explicitly");}
        check(kept.report().warnings().stream().anyMatch(w->w.code().equals("overlays_unconverted")),"Disabled overlay migration remains a review result");
        checkFullFeatures(work,packs,tools,old,first);

        checkContentSignatures(work);
        checkNativeFailure(work, tools, old, original);
        System.out.println("PASS: native conversion, overlays, conservative/full options, preview/progress, cancellation, image budget, durable history, archive/restore recovery, signatures and conflicts");
    }

    private static void checkFullFeatures(Path work,Path packs,Path tools,Path old,PyramidConverter.Result first) throws Exception {
        check(NativePlatform.resolve("Linux","aarch64").id().equals("linux-aarch64"),"Linux ARM platform mapping");
        check(NativePlatform.resolve("Mac OS X","arm64").id().equals("macos-aarch64"),"macOS ARM platform mapping");
        Path optionFile=work.resolve("config/options.json");var options=new ConversionOptions(true,true,true,true,false,4,512);options.save(optionFile);
        check(options.equals(ConversionOptions.read(optionFile)),"Conversion options persist without loss");
        var plan=PyramidConverter.preview(PackInspector.inspect(old),TARGET,tools);
        check(plan.getAsJsonArray("tasks").asList().stream().anyMatch(v->v.getAsString().equals("gui_surgeon")),"Preview comes from native scheduler including final GUI task");
        var events=Files.readAllLines(first.reportFile().getParent().resolve("progress.jsonl"));
        check(events.stream().anyMatch(line->line.contains("gui_surgeon"))&&events.stream().anyMatch(line->line.contains("completed")),"Real native task events retained");
        try(var listing=Files.list(packs)) {
            long before=listing.count();var control=new ConversionControl();boolean cancelled=false;
            try {PyramidConverter.convertWithReport(PackInspector.inspect(old),TARGET,packs,tools,ConversionOptions.defaults(),control,key->{if(key.equals("pack_conversion.converting"))control.cancel();});}
            catch(java.io.IOException error){cancelled=error.getMessage().contains("cancelled");}
            try(var after=Files.list(packs)){check(cancelled&&after.count()==before,"Cancel before publication installs no output");}
        }
        Path restoreSource=packs.resolve("recover original.zip");Files.copy(old,restoreSource);
        String signature=PackConversionFiles.signature(restoreSource);Path job=Files.createTempDirectory(tools.resolve("jobs"),"recovery-");
        PackJobHistory.create(job,restoreSource,signature,ConversionOptions.defaults());
        Path archived=PackConversionFiles.archive(restoreSource,signature,packs,destination->{try{PackJobHistory.update(job,"archiving","archive_pending",destination.toString());}catch(Exception e){throw new RuntimeException(e);}});
        // Simulate interruption after the move but before the final journal update.
        var recovered=PackJobHistory.load(tools.resolve("jobs")).stream().filter(e->e.journal().getParent().equals(job)).findFirst().orElseThrow();
        check(recovered.archived().equals(archived),"Journal recovers an archive move interrupted before completion record");
        Files.writeString(restoreSource,"occupied name");Path restored=PackJobHistory.restore(recovered,packs);
        check(!restored.equals(restoreSource)&&Files.readString(restoreSource).equals("occupied name")&&Files.isRegularFile(restored),"Restore avoids overwriting name collision");
        check(Files.readString(job.resolve("job.json")).contains("restored"),"Restore recorded for restart history");
        Path budget=work.resolve("image-budget.zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(budget))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));zip.write("{\"pack\":{\"pack_format\":34,\"description\":\"Budget\"}}".getBytes());zip.closeEntry();
            var image=new java.awt.image.BufferedImage(1024,1024,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for(int i=0;i<9;i++){zip.putNextEntry(new ZipEntry("assets/test/textures/large"+i+".png"));javax.imageio.ImageIO.write(image,"png",zip);zip.closeEntry();}
        }
        var streamed=PyramidConverter.convertWithReport(PackInspector.inspect(budget),TARGET,packs,tools,new ConversionOptions(false,false,false,false,true,2,128),new ConversionControl(),ignored->{});
        check(Files.isRegularFile(streamed.output())&&Files.isRegularFile(budget),"Multiple images stream within budget instead of counting whole-pack decoded bytes");
        var streamingReport=com.google.gson.JsonParser.parseString(Files.readString(streamed.reportFile())).getAsJsonObject();
        var budgetCheck=streamingReport.getAsJsonArray("image_budget_checks").get(0).getAsJsonObject();
        check(budgetCheck.get("image_count").getAsInt()==9&&budgetCheck.get("effective_workers").getAsInt()==2
                &&budgetCheck.get("estimated_peak_bytes").getAsLong()==64L*1024*1024,"Report records actual concurrency model and peak estimate");
        Path large=work.resolve("single-image-budget.zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(large))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));zip.write("{\"pack\":{\"pack_format\":34,\"description\":\"Budget\"}}".getBytes());zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/test/textures/large.png"));
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4096,4096,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",zip);zip.closeEntry();
        }
        boolean rejected=false;
        try {PyramidConverter.convertWithReport(PackInspector.inspect(large),TARGET,packs,tools,new ConversionOptions(false,false,false,false,true,2,128),new ConversionControl(),ignored->{});}
        catch(java.io.IOException error){rejected=error.getMessage().contains("budget");}
        check(rejected&&Files.isRegularFile(large),"Single oversized working set still rejects before conversion without removing input");
        Path adaptive=work.resolve("adaptive-image-workers.zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(adaptive))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));zip.write("{\"pack\":{\"pack_format\":34,\"description\":\"Adaptive\"}}".getBytes());zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/test/textures/adaptive.png"));
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2048,2048,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",zip);zip.closeEntry();
        }
        var adapted=PyramidConverter.convertWithReport(PackInspector.inspect(adaptive),TARGET,packs,tools,new ConversionOptions(false,false,false,false,true,4,128),new ConversionControl(),ignored->{});
        var adaptiveReport=com.google.gson.JsonParser.parseString(Files.readString(adapted.reportFile())).getAsJsonObject();
        check(adaptiveReport.getAsJsonArray("image_budget_checks").get(0).getAsJsonObject().get("effective_workers").getAsInt()==1,"Budget reduces concurrency before rejecting a streamable pack");
        for(var task:adaptiveReport.getAsJsonArray("tasks")) {
            var t=task.getAsJsonObject();if(!t.get("skipped").getAsBoolean())check(t.get("worker_threads").getAsInt()==1,"Migration tasks really execute with the reduced worker count");
        }
        Path runner;
        try(var paths=Files.walk(tools)){runner=paths.filter(v->v.getFileName().toString().equals("embers-pyramid.exe")).findFirst().orElseThrow();}
        Path flag=work.resolve("cancel.flag"),req=work.resolve("cancel-request.json"),result=work.resolve("cancel-result.json");Files.writeString(flag,"cancel");
        var request=new com.google.gson.JsonObject();request.addProperty("input",old.toString());request.addProperty("output_dir",work.resolve("cancel-out").toString());
        request.addProperty("target_major",88);request.addProperty("target_minor",0);request.addProperty("cancel_file",flag.toString());Files.writeString(req,request.toString());
        Process nativeCancel=new ProcessBuilder(runner.toString(),req.toString(),result.toString()).redirectErrorStream(true).redirectOutput(work.resolve("cancel.log").toFile()).start();
        try{check(nativeCancel.waitFor(10,java.util.concurrent.TimeUnit.SECONDS),"Native cooperative cancellation returns promptly");}
        finally{if(nativeCancel.isAlive())nativeCancel.destroyForcibly();}
        var cancelled=com.google.gson.JsonParser.parseString(Files.readString(result)).getAsJsonObject();
        check(nativeCancel.exitValue()==3&&cancelled.get("status").getAsString().equals("cancelled")&&!cancelled.get("success").getAsBoolean(),"Native cancellation has a distinct structured status");
        var history=PackJobHistory.load(tools.resolve("jobs"));check(history.stream().anyMatch(e->e.status().equals("cancelled")),"Cancelled task remains in durable history");
        check(history.stream().anyMatch(e->first.output().equals(e.output())),"History recognizes the published output by signature");
        Path occupied=packs.resolve("unrelated occupied.zip");Files.writeString(occupied,"not this conversion");
        Path intent=Files.createTempDirectory(tools.resolve("jobs"),"interrupted-install-");PackJobHistory.create(intent,old,PackConversionFiles.signature(old),ConversionOptions.defaults());
        PackJobHistory.update(intent,"installing","output_signature",PackConversionFiles.signature(first.output()));
        PackJobHistory.update(intent,"installing","installed_output",occupied.toString());
        var interrupted=PackJobHistory.load(tools.resolve("jobs")).stream().filter(e->e.journal().getParent().equals(intent)).findFirst().orElseThrow();
        check(interrupted.output()==null&&interrupted.status().equals("interrupted"),"Interrupted install never mistakes an unrelated occupied file for its output");
    }
    private static void checkRegressionPack(Path source, Path packs, Path tools) throws Exception {
        String before = PackConversionFiles.signature(source);
        var converted = PyramidConverter.convertWithReport(PackInspector.inspect(source), TARGET, packs, tools,
                ConversionOptions.conservative(), new ConversionControl(), ignored -> {});
        check(PackInspector.inspect(converted.output()).supports(TARGET), "User regression pack reaches the target version through the Java adapter");
        int preserved = 0;
        try (var src = new java.util.zip.ZipFile(source.toFile()); var dst = new java.util.zip.ZipFile(converted.output().toFile())) {
            if (src.getEntry("pack.png") != null) {
                checkSameImagePixels(src, dst, "pack.png");
                try (var image = dst.getInputStream(dst.getEntry("pack.png"))) {
                    check(Arrays.equals(image.readNBytes(8), new byte[]{(byte)137,80,78,71,13,10,26,10}), "Output pack icon has standard PNG encoding");
                }
            }
            var entries = src.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (!name.startsWith("assets/minecraft/textures/gui/sprites/")
                        && !name.startsWith("assets/minecraft/optifine/sky/")
                        && !name.equals("assets/minecraft/textures/gui/title/minecraft.png")) continue;
                var target = dst.getEntry(name);
                check(target != null, "Authored asset remains present: " + name);
                try (var input = src.getInputStream(entry); var output = dst.getInputStream(target)) {
                    check(Arrays.equals(input.readAllBytes(), output.readAllBytes()), "Authored asset bytes preserved: " + name);
                }
                preserved++;
            }
        }
        check(before.equals(PackConversionFiles.signature(source)), "User regression pack source is unchanged");
        System.out.println("USER_PACK_REGRESSION_PASSED preserved_assets=" + preserved + " report=" + converted.reportFile());
    }
    private static void checkModReports(Path work,Path packs,Path tools,Path old,PyramidConverter.Result converted) throws Exception {
        var candidate=new PackConversionManager.Candidate(PackInspector.inspect(old),PackConversionFiles.signature(old));
        var counts=PackConversionManager.OutcomeCounts.of(java.util.List.of(
                new PackConversionManager.Outcome(candidate,converted.output(),null,"archive denied",converted,false),
                new PackConversionManager.Outcome(candidate,null,null,"conversion failed",null,false),
                new PackConversionManager.Outcome(candidate,null,null,"cancelled",null,true)));
        check(counts.completed()==1&&counts.failed()==1&&counts.cancelled()==1&&counts.archiveFailed()==1,"Published output with archive failure remains a conversion success");
        String unix=PackDiagnostics.redact("path /data/private-folder/pack.zip\nasset assets/test/textures/a.png\nurl https://example.org/report");
        check(!unix.contains("private-folder")&&unix.contains("assets/test/textures/a.png")&&unix.contains("https://example.org/report"),"Unix paths are redacted while relative assets and public URLs remain useful");
        Path source=packs.resolve("retry-source.zip");Files.copy(old,source);
        Path job=Files.createTempDirectory(tools.resolve("jobs"),"retry-check-");
        PackJobHistory.create(job,source,PackConversionFiles.signature(source),ConversionOptions.complete());PackJobHistory.update(job,"failed","error","fixture");
        var entry=PackJobHistory.load(tools.resolve("jobs")).stream().filter(e->e.journal().getParent().equals(job)).findFirst().orElseThrow();
        check(entry.options().equals(ConversionOptions.complete())&&entry.retryable(),"Failed history stores original options and enables retry");
        check(PackJobHistory.retryInput(entry,packs).equals(source.toAbsolutePath()),"Unchanged local original can be retried");
        Files.writeString(source,"changed");boolean denied=false;try{PackJobHistory.retryInput(entry,packs);}catch(java.io.IOException expected){denied=true;}
        check(denied,"Retry rejects changed original");
        Files.writeString(job.resolve("result.json"),"{\"warnings\":[{\"code\":\"future_warning\",\"detail\":\"kept\"}]}");
        Files.writeString(job.resolve("conversion.log"),"path D:\\Private Name\\resourcepacks\\pack.zip\nemail test@example.org ip 192.168.1.24 ipv6 2001:db8::1\napi_key=secret-example\nAuthorization: Bearer private-token\n");
        Files.writeString(job.resolve("private-resource.zip"),"private texture bytes");
        var environment=new com.google.gson.JsonObject();environment.addProperty("mod_version","0.3.0");environment.addProperty("upstream_commit","503a0859e3ab6bf09ed03f361b9335e5931a6d6d");
        Path diagnostic=PackDiagnostics.export(job,work.resolve("diagnostics"),environment);
        try(var zip=new java.util.zip.ZipFile(diagnostic.toFile())){
            check(zip.getEntry("private-resource.zip")==null,"Diagnostic allowlist excludes pack contents");
            try(var input=zip.getInputStream(zip.getEntry("conversion.log"))){String text=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                for(String secret:java.util.List.of("Private Name","test@example.org","192.168.1.24","2001:db8::1","secret-example","private-token"))check(!text.contains(secret),"Diagnostic redacts "+secret);}
            try(var input=zip.getInputStream(zip.getEntry("job.json"))){var json=com.google.gson.JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();check(json.get("source").getAsString().equals("<path>"),"Structured JSON paths are redacted and JSON remains readable");}
        }
        check(!PackJobHistory.load(tools.resolve("jobs")).stream().filter(e->e.output()!=null).anyMatch(PackJobHistory.Entry::retryable),"Published outputs cannot be accidentally reconverted through retry");
    }

    private static void checkSameImagePixels(java.util.zip.ZipFile source, java.util.zip.ZipFile target, String name) throws Exception {
        try (var input = source.getInputStream(source.getEntry(name)); var output = target.getInputStream(target.getEntry(name))) {
            var before = javax.imageio.ImageIO.read(input); var after = javax.imageio.ImageIO.read(output);
            check(before != null && after != null, "Images decode: " + name);
            check(before.getWidth() == after.getWidth() && before.getHeight() == after.getHeight(), "Image dimensions preserved: " + name);
            int width = before.getWidth(), height = before.getHeight();
            check(Arrays.equals(before.getRGB(0,0,width,height,null,0,width), after.getRGB(0,0,width,height,null,0,width)), "Image pixels preserved: " + name);
        }
    }

    private static void checkMislabeledImages(Path work, Path packs, Path tools) throws Exception {
        Path source = work.resolve("bmp-disguised-as-png.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"pack_format\":34,\"supported_formats\":[34,75],\"description\":\"Repair\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            var image = new java.awt.image.BufferedImage(32,24,java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y=0;y<24;y++) for(int x=0;x<32;x++) image.setRGB(x,y,0xFF0000AD | (x<<16) | (y<<8));
            for (String name : new String[]{"pack.png", "assets/test/textures/custom.png"}) {
                zip.putNextEntry(new ZipEntry(name));check(javax.imageio.ImageIO.write(image,"bmp",zip),"BMP fixture encoded");zip.closeEntry();
            }
        }
        String before = PackConversionFiles.signature(source);
        var result = PyramidConverter.convertWithReport(PackInspector.inspect(source),TARGET,packs,tools,ignored->{});
        check(!result.needsReview(),"Successfully resolved version range and image encoding do not retain stale warnings");
        var report = com.google.gson.JsonParser.parseString(Files.readString(result.reportFile())).getAsJsonObject();
        check(report.getAsJsonArray("repairs").size()==3,"Icon, texture and version range repairs remain in the durable report");
        try(var input=new java.util.zip.ZipFile(source.toFile());var output=new java.util.zip.ZipFile(result.output().toFile())) {
            for(String name:new String[]{"pack.png","assets/test/textures/custom.png"})checkSameImagePixels(input,output,name);
        }
        check(before.equals(PackConversionFiles.signature(source)),"Image repair leaves the original archive unchanged");
    }

    private static void checkContentSignatures(Path work) throws Exception {
        Path folder = work.resolve("hash-folder"); Files.createDirectories(folder);
        Path marker = folder.resolve("marker.txt"); Files.writeString(marker, "old");
        var stamp = Files.getLastModifiedTime(marker);
        String before = PackConversionFiles.signature(folder);
        Files.writeString(marker, "new"); Files.setLastModifiedTime(marker, stamp);
        check(!before.equals(PackConversionFiles.signature(folder)), "Same-size same-timestamp folder edits are detected");
        Path file = work.resolve("hash-file.zip"); Files.writeString(file, "old");
        stamp = Files.getLastModifiedTime(file); before = PackConversionFiles.signature(file);
        Files.writeString(file, "new"); Files.setLastModifiedTime(file, stamp);
        check(!before.equals(PackConversionFiles.signature(file)), "Same-size same-timestamp ZIP edits are detected");
    }

    private static void checkNativeFailure(Path work, Path tools, Path input, byte[] original) throws Exception {
        Path runner;
        try (var paths = Files.walk(tools)) { runner = paths.filter(p -> p.getFileName().toString().equals("embers-pyramid.exe")).findFirst().orElseThrow(); }
        Path blocked = work.resolve("blocked-output"); Files.writeString(blocked, "not a directory");
        Path request = work.resolve("failure-request.json"), result = work.resolve("failure-result.json");
        var json = new com.google.gson.JsonObject();
        json.addProperty("input", input.toString()); json.addProperty("output_dir", blocked.toString());
        json.addProperty("target_major", TARGET.major()); json.addProperty("target_minor", TARGET.minor());
        Files.writeString(request, json.toString());
        Process process = new ProcessBuilder(runner.toString(), request.toString(), result.toString())
                .directory(runner.getParent().toFile()).redirectErrorStream(true).redirectOutput(work.resolve("native-failure.log").toFile()).start();
        try { check(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Native failure returns promptly"); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        var report = com.google.gson.JsonParser.parseString(Files.readString(result)).getAsJsonObject();
        check(process.exitValue() != 0 && !report.get("success").getAsBoolean() && report.get("status").getAsString().equals("failed"), "Native output I/O failure cannot report success");
        check(Arrays.equals(original, Files.readAllBytes(input)), "Native failure preserves input");
    }

    private static void checkReportValidation() throws Exception {
        var clean = com.google.gson.JsonParser.parseString("{\"schema_version\":1,\"success\":true,\"status\":\"completed\",\"archive_validated\":true,\"warnings\":[],\"tasks\":[{\"name\":\"test\",\"success\":true}],\"changes\":{\"added\":1,\"modified\":2,\"removed\":3}}").getAsJsonObject();
        check(ConversionReport.parse(clean).changes().modified() == 2, "Clean structured report accepted");
        var invalid = clean.deepCopy(); invalid.addProperty("archive_validated", false); rejectReport(invalid);
        invalid = clean.deepCopy(); invalid.addProperty("schema_version", 99); rejectReport(invalid);
        invalid = clean.deepCopy(); invalid.getAsJsonArray("tasks").get(0).getAsJsonObject().addProperty("success", false); rejectReport(invalid);
        invalid = clean.deepCopy(); invalid.addProperty("status", "needs_review"); rejectReport(invalid);
        invalid = clean.deepCopy(); invalid.getAsJsonObject("changes").addProperty("added", -1); rejectReport(invalid);
        invalid = clean.deepCopy(); invalid.remove("tasks"); rejectReport(invalid);
        invalid = clean.deepCopy(); invalid.addProperty("success", false); invalid.addProperty("error", "write failed"); rejectReport(invalid);
    }

    private static void rejectReport(com.google.gson.JsonObject report) throws Exception {
        boolean rejected = false;
        try { ConversionReport.parse(report); } catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "Invalid or failed native report rejected before installation");
    }

    private static Path make(Path path, String prefix, String metadata, String[]... extras) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            var files = new java.util.ArrayList<>(java.util.List.of(new String[][] {{"pack.mcmeta", metadata}, {"assets/test/marker.txt", "unchanged"},
                    {"assets/test/font/custom.json", "{\"providers\":[{\"type\":\"space\",\"advances\":{\"A\":19}}]}"}}));
            files.addAll(java.util.Arrays.asList(extras));
            for (String[] file : files) {
                zip.putNextEntry(new ZipEntry(prefix + file[0])); zip.write(file[1].getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return path;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
