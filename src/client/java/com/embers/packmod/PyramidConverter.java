package com.embers.packmod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
/** Local 2-Pyramid process, operating solely on a staged pack and a private temporary tree. */
final class PyramidConverter {
    record Result(Path output, Path reportFile, ConversionReport report) {
        boolean needsReview() { return !report.warnings().isEmpty(); }
    }

    private static final long MAX_BYTES = 500L * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    static Path convert(PackInspector.Info info, PackInspector.Version target, Path packDirectory,
                        Path toolDirectory, Consumer<String> progress) throws Exception {
        return convertWithReport(info, target, packDirectory, toolDirectory, progress).output();
    }

    static Result convertWithReport(PackInspector.Info info, PackInspector.Version target, Path packDirectory,
                                   Path toolDirectory, Consumer<String> progress) throws Exception {
        return convertWithReport(info, target, packDirectory, toolDirectory, ConversionOptions.defaults(), new ConversionControl(), progress);
    }
    static Result convertWithReport(PackInspector.Info info, PackInspector.Version target, Path packDirectory,
                                   Path toolDirectory, ConversionOptions options, ConversionControl control, Consumer<String> progress) throws Exception {
        control.check();
        if (!NativePlatform.current().bundled()) throw new IOException("Runner not bundled for " + NativePlatform.current().id());
        progress.accept("pack_conversion.preparing");
        Path runner = installRunner(toolDirectory);
        Files.createDirectories(toolDirectory.resolve("jobs"));
        Path job = Files.createTempDirectory(toolDirectory.resolve("jobs"), "convert-");
        PackJobHistory.create(job, info.path(), PackConversionFiles.signature(info.path()), options);
        try {
            Path input = job.resolve("input"); Files.createDirectories(input);
            String name = info.path().getFileName().toString();
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip") || name.toLowerCase(Locale.ROOT).endsWith(".mcpack"))
                name = name.substring(0, name.lastIndexOf('.'));
            Path staged = input.resolve(name + ".zip");
            var stagingRepairs = stage(info, staged, control);
            if (!stagingRepairs.isEmpty()) Files.writeString(job.resolve("staging-repairs.json"),
                    new com.google.gson.Gson().toJson(stagingRepairs), StandardCharsets.UTF_8);
            var existingGenerated = options.generateMissing() ? GeneratedResourceReview.snapshot(staged) : java.util.List.<GeneratedResourceReview.Existing>of();
            control.check();
            Path output = job.resolve("output"), temp = job.resolve("temp");
            Files.createDirectories(output); Files.createDirectories(temp);
            JsonObject request = new JsonObject();
            request.addProperty("input", staged.toString()); request.addProperty("output_dir", output.toString());
            request.addProperty("target_major", target.major()); request.addProperty("target_minor", target.minor());
            request.add("options", options.json());
            Path cancelFile = job.resolve("cancel.flag"), eventsFile = job.resolve("progress.jsonl");
            request.addProperty("cancel_file", cancelFile.toString()); request.addProperty("events_file", eventsFile.toString());
            requireCapability(runner, target, job);
            Path requestFile = job.resolve("request.json"), resultFile = job.resolve("result.json");
            Files.writeString(requestFile, request.toString(), StandardCharsets.UTF_8);
            long started = System.nanoTime();
            progress.accept("pack_conversion.converting");
            ProcessBuilder builder = new ProcessBuilder(runner.toString(), requestFile.toString(), resultFile.toString());
            builder.directory(runner.getParent().toFile()).redirectErrorStream(true).redirectOutput(job.resolve("conversion.log").toFile());
            builder.environment().put("TEMP", temp.toString()); builder.environment().put("TMP", temp.toString());
            builder.environment().put("2PYR_SYNC_CLEANUP", "1");
            Process process = builder.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10), cancellationDeadline = Long.MAX_VALUE;
                long eventOffset = 0;
                String pending = "";
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (control.cancelled()) {
                        if (cancellationDeadline == Long.MAX_VALUE) { Files.writeString(cancelFile, "cancel"); cancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); }
                        if (System.nanoTime() > cancellationDeadline) throw new IOException("Conversion cancelled; original retained");
                    }
                    if (System.nanoTime() > deadline) throw new IOException("Conversion exceeded ten minutes; see " + job.resolve("conversion.log"));
                    if (Files.isRegularFile(eventsFile)) {
                        try (var events = new java.io.RandomAccessFile(eventsFile.toFile(), "r")) {
                            events.seek(eventOffset); long remaining = events.length() - eventOffset;
                            if (remaining > 1024 * 1024) throw new IOException("Invalid progress stream");
                            byte[] bytes = new byte[(int) remaining]; events.readFully(bytes); eventOffset = events.getFilePointer();
                            pending += new String(bytes, StandardCharsets.UTF_8);
                            int newline;
                            while ((newline = pending.indexOf('\n')) >= 0) {
                                String line = pending.substring(0, newline); pending = pending.substring(newline + 1);
                                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                                progress.accept("@" + event.get("stage").getAsString() + "|" + event.get("completed").getAsInt() + "|" + event.get("total").getAsInt() + "|" + event.get("task").getAsString());
                            }
                            if (pending.length() > 16384) throw new IOException("Invalid progress line");
                        }
                    }
                }
                control.check();
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
            if (!Files.isRegularFile(resultFile)) throw new IOException("2-Pyramid did not return a result; see " + job.resolve("conversion.log"));
            if (Files.size(resultFile) > 16 * 1024 * 1024) throw new IOException("Conversion report exceeds 16 MiB");
            JsonObject result = JsonParser.parseString(Files.readString(resultFile)).getAsJsonObject();
            if (!stagingRepairs.isEmpty()) {
                if (!result.has("repairs")) result.add("repairs", new com.google.gson.JsonArray());
                for (var repair : stagingRepairs) result.getAsJsonArray("repairs").add(new com.google.gson.Gson().toJsonTree(repair));
                Files.writeString(resultFile, result.toString(), StandardCharsets.UTF_8);
            }
            ConversionReport report;
            try {
                report = ConversionReport.parse(result).withWarnings(info.warnings().stream()
                        .filter(code -> !options.flattenOverlays() || !code.equals("overlays_unconverted")).toList());
                if (process.exitValue() != 0) throw new IOException("2-Pyramid exited with code " + process.exitValue());
            } catch (IOException error) { throw new IOException(error.getMessage() + "; report: " + resultFile, error); }
            Path converted = Path.of(result.get("output").getAsString()).toAbsolutePath().normalize();
            if (!converted.startsWith(output.toAbsolutePath().normalize()) || !Files.isRegularFile(converted))
                throw new IOException("Invalid conversion output path");
            var compatibility=PackCompatibility.apply(converted,target,options,control);
            if(!compatibility.repairs().isEmpty()||!compatibility.warnings().isEmpty()) {
                var warnings=new java.util.ArrayList<>(report.warnings());warnings.addAll(compatibility.warnings());
                var repairs=new java.util.ArrayList<>(report.repairs());repairs.addAll(compatibility.repairs());
                var changes=compatibility.added()+compatibility.modified()>0?PackCompatibility.changes(staged,converted,control):report.changes();
                report=new ConversionReport(warnings,changes,report.completedTasks(),repairs);
                result.add("repairs",new com.google.gson.Gson().toJsonTree(repairs));
                result.add("changes",new com.google.gson.Gson().toJsonTree(changes));
            }
            PackInspector.Info verified = PackInspector.inspect(converted);
            if (verified == null || !verified.supports(target)) throw new IOException("Converted pack does not support the current client's resource format");
            var generationWarnings = GeneratedResourceReview.changed(existingGenerated, result, converted);
            if (!generationWarnings.isEmpty()) {
                var warnings = new java.util.ArrayList<>(report.warnings()); warnings.addAll(generationWarnings);
                report = new ConversionReport(warnings, report.changes(), report.completedTasks(), report.repairs());
            }
            control.check();
            progress.accept("pack_conversion.installing");
            control.check();
            Files.createDirectories(packDirectory);
            // Move without replacement prevents overwriting originals or previous outputs.
            String fileName = converted.getFileName().toString();
            Path installed = packDirectory.resolve(fileName);
            Path installation = Files.createTempFile(packDirectory, ".embers-conversion-", ".tmp");
            try {
                Files.copy(converted, installation, StandardCopyOption.REPLACE_EXISTING);
                PackJobHistory.update(job, "installing", "output_signature", PackConversionFiles.signature(installation));
                int suffix = 1;
                while (true) {
                    control.check();
                    PackJobHistory.update(job, "installing", "installed_output", installed.toString());
                    try { Files.move(installation, installed); break; }
                    catch (java.nio.file.FileAlreadyExistsException occupied) {
                        installed = packDirectory.resolve(fileName.substring(0, fileName.length() - 4) + " (" + suffix++ + ").zip");
                    }
                }
            } finally { Files.deleteIfExists(installation); }
            // Native paths point into staging, which is cleaned below. Persist the
            // actual installed path and Java validation so the report remains useful.
            result.addProperty("source", info.path().toString());
            result.addProperty("installed_output", installed.toString());
            result.addProperty("metadata_validated", true);
            result.addProperty("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            result.add("warnings", new com.google.gson.Gson().toJsonTree(report.warnings()));
            result.addProperty("status", report.warnings().isEmpty() ? "completed" : "needs_review");
            Path reportStaged = job.resolve("result.tmp");
            try {
                Files.writeString(reportStaged, result.toString(), StandardCharsets.UTF_8);
                Files.move(reportStaged, resultFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) {
                var warnings = new java.util.ArrayList<>(report.warnings());
                warnings.add(new ConversionReport.Warning("report_update_failed", "Could not record installed output: " + error.getMessage()));
                report = new ConversionReport(warnings, report.changes(), report.completedTasks(), report.repairs());
            } finally {
                try { Files.deleteIfExists(reportStaged); }
                catch (IOException cleanup) {
                    System.getLogger(PyramidConverter.class.getName()).log(System.Logger.Level.WARNING, "Could not clean staged report", cleanup);
                }
            }
            PackJobHistory.update(job, "installed", null, null);
            return new Result(installed, resultFile, report);
        } catch (Exception error) {
            try { PackJobHistory.update(job, control.cancelled() ? "cancelled" : "failed", "error", String.valueOf(error.getMessage())); }
            catch (IOException journalError) { error.addSuppressed(journalError); }
            throw error;
        } finally {
            // Every recursive cleanup target is an owned, fixed child of this conversion job.
            // Files.walk does not follow symlinks; originals and installed packs are outside it.
            for (String child : new String[] {"input", "output", "temp"}) {
                Path owned = job.resolve(child).toAbsolutePath().normalize();
                if (!owned.getParent().equals(job.toAbsolutePath().normalize())) throw new IOException("Invalid job cleanup target");
                if (Files.exists(owned, LinkOption.NOFOLLOW_LINKS)) {
                    try (var paths = Files.walk(owned)) {
                        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                            Files.deleteIfExists(path);
                    } catch (IOException cleanup) {
                        System.getLogger(PyramidConverter.class.getName()).log(System.Logger.Level.WARNING,
                                "Could not clean conversion staging directory: " + owned, cleanup);
                    }
                }
            }
        }
    }
    private static Path installRunner(Path tools) throws Exception {
        byte[] executable;
        NativePlatform platform = NativePlatform.current();
        try (InputStream stream = resource(platform.resource())) { executable = stream.readAllBytes(); }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(executable));
        byte[] templates;
        try (InputStream stream = resource("/tools/pyramid/uimage.zip")) { templates = stream.readAllBytes(); }
        JsonObject manifest = manifest(platform);
        if (!hash.equals(manifest.get("binary_sha256").getAsString())
                || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(templates)).equals(manifest.get("templates_sha256").getAsString()))
            throw new IOException("Bundled runner or templates do not match the release manifest");
        MessageDigest bundleDigest = MessageDigest.getInstance("SHA-256");
        bundleDigest.update(executable);
        String bundleHash = HexFormat.of().formatHex(bundleDigest.digest(templates));
        Path bundle = tools.resolve(bundleHash.substring(0, 16));
        Files.createDirectories(bundle);
        Path runner = bundle.resolve(platform.executable());
        if (!Files.exists(runner) || !hash.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runner))))) {
            Path staged = bundle.resolve("runner.tmp"); Files.write(staged, executable);
            Files.move(staged, runner, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!platform.id().startsWith("windows") && !runner.toFile().setExecutable(true, true)) throw new IOException("Cannot make runner executable");
        Path ready = bundle.resolve("templates.ready");
        if (!Files.exists(ready)) {
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(templates))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path path = bundle.resolve("UImage").resolve(safeName(entry.getName())).normalize();
                    if (!path.startsWith(bundle.resolve("UImage"))) throw new IOException("Template outside bundle");
                    if (entry.isDirectory()) Files.createDirectories(path);
                    else { Files.createDirectories(path.getParent()); Files.copy(zip, path, StandardCopyOption.REPLACE_EXISTING); }
                }
            }
            Files.writeString(ready, bundleHash);
        }
        return runner;
    }
    static JsonObject preview(PackInspector.Info info,PackInspector.Version target,Path tools) throws Exception {
        Path runner=installRunner(tools), temp=Files.createTempFile(tools,"preview-",".json");
        Process process=new ProcessBuilder(runner.toString(),"--plan",Integer.toString(PackInspector.sourceMajor(info)),Integer.toString(target.major()))
                .redirectOutput(temp.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if(!process.waitFor(5,TimeUnit.SECONDS))throw new IOException("Preview timed out");
            if(process.exitValue()!=0||Files.size(temp)>65536)throw new IOException("No supported conversion route for this source");
            return JsonParser.parseString(Files.readString(temp)).getAsJsonObject();
        }finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}Files.deleteIfExists(temp);}
    }
    private static void requireCapability(Path runner, PackInspector.Version target, Path job) throws Exception {
        Path file = job.resolve("capabilities.json");
        Process query = new ProcessBuilder(runner.toString(), "--capabilities").redirectOutput(file.toFile())
                .redirectError(job.resolve("capabilities.log").toFile()).start();
        try {
            if (!query.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Runner capability query timed out");
            if (query.exitValue() != 0 || Files.size(file) > 65536) throw new IOException("Invalid runner capabilities");
            JsonObject data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (data.get("schema_version").getAsInt() != 1) throw new IOException("Unsupported runner protocol");
            JsonObject manifest = manifest(NativePlatform.current());
            if (!data.get("core_version").equals(manifest.get("core_version")) || !data.getAsJsonArray("targets").equals(manifest.getAsJsonObject("capabilities").getAsJsonArray("targets")))
                throw new IOException("Runner capabilities do not match the bundled converter version");
            Files.writeString(job.resolve("bundle-manifest.json"),manifest.toString(),StandardCharsets.UTF_8);
            boolean supported = false;
            for (var item : data.getAsJsonArray("targets")) { var format = item.getAsJsonObject();
                supported |= format.get("major").getAsInt() == target.major() && target.minor() <= format.get("max_minor").getAsInt(); }
            if (!supported) throw new IOException("Runner cannot convert to resource format " + target);
        } finally { if (query.isAlive()) { query.destroyForcibly(); query.waitFor(5, TimeUnit.SECONDS); } }
    }
    private static InputStream resource(String name) throws IOException {
        InputStream stream = PyramidConverter.class.getResourceAsStream(name);
        if (stream == null) throw new IOException("Bundled 2-Pyramid resource is missing: " + name);
        return stream;
    }
    private static JsonObject manifest(NativePlatform platform) throws IOException {
        try(InputStream stream=resource("/tools/pyramid/"+platform.id()+"/runner.json")){
            byte[] bytes=stream.readNBytes(65537);if(bytes.length>65536)throw new IOException("Invalid runner manifest");
            return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(RuntimeException malformed){throw new IOException("Invalid runner manifest",malformed);}
    }
    private static String safeName(String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf(':') >= 0 || normalized.indexOf('\0') >= 0)
            throw new IOException("Unsafe ZIP entry: " + name);
        for (String part : normalized.split("/")) if (part.equals("..")) throw new IOException("Unsafe ZIP entry: " + name);
        return normalized;
    }
    private static java.util.List<ConversionReport.Repair> stage(PackInspector.Info info, Path destination, ConversionControl control) throws IOException {
        long[] size = {0}; int[] entries = {0};
        var repairs = new java.util.ArrayList<ConversionReport.Repair>();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(destination))) {
            // Staging is short-lived: avoid recompressing thousands of textures before native extraction.
            out.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            if (Files.isDirectory(info.path(), LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(info.path())) {
                    var files = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).iterator();
                    while (files.hasNext()) {
                        Path file = files.next();
                        String relative = info.path().relativize(file).toString().replace('\\', '/');
                        try (InputStream input = Files.newInputStream(file)) { add(out, relative, input, info, size, entries, control, repairs); }
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(info.path().toFile())) {
                    var files = zip.entries();
                    while (files.hasMoreElements()) {
                        ZipEntry entry = files.nextElement();
                        if (entry.isDirectory()) continue;
                        try (InputStream input = zip.getInputStream(entry)) { add(out, entry.getName(), input, info, size, entries, control, repairs); }
                    }
                }
            }
        }
        return java.util.List.copyOf(repairs);
    }
    private static void add(ZipOutputStream out, String name, InputStream input, PackInspector.Info info,
                            long[] size, int[] count, ConversionControl control, java.util.List<ConversionReport.Repair> repairs) throws IOException {
        control.check();
        name = safeName(name);
        if (!info.prefix().isEmpty() && !name.startsWith(info.prefix())) throw new IOException("Multiple pack roots or files outside selected root; unpack this pack first");
        if (++count[0] > MAX_ENTRIES) throw new IOException("Resource pack exceeds 100,000 files");
        String prefix = info.prefix();
        if (!prefix.isEmpty() && name.startsWith(prefix)) name = name.substring(prefix.length());
        String metadataName = info.metadata().substring(info.prefix().length());
        boolean metadata = !info.bedrock() && name.equals(metadataName);
        if (metadata) name = "pack.mcmeta";
        out.putNextEntry(new ZipEntry(name));
        if (metadata) {
            byte[] original = input.readNBytes(PackInspector.MAX_METADATA + 1);
            if (original.length > PackInspector.MAX_METADATA) throw new IOException("pack.mcmeta exceeds 1 MiB");
            var normalized = PackMetadataStaging.normalize(original);
            size[0] += normalized.bytes().length;
            if (size[0] > MAX_BYTES) throw new IOException("Resource pack exceeds 500 MiB uncompressed");
            control.check();
            out.write(normalized.bytes());
            if (normalized.escapedControls() > 0) repairs.add(new ConversionReport.Repair("metadata_controls_escaped", name,
                    "Escaped " + normalized.escapedControls() + " literal JSON string control characters in the staged copy; decoded values preserved."));
            out.closeEntry();
            return;
        }
        byte[] buffer = new byte[65536]; int bytes;
        while ((bytes = input.read(buffer)) != -1) {
            control.check();
            size[0] += bytes;
            if (size[0] > MAX_BYTES) throw new IOException("Resource pack exceeds 500 MiB uncompressed");
            out.write(buffer, 0, bytes);
        }
        out.closeEntry();
    }
}
