package com.embers.packmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Durable local journal; no original is moved during startup recovery. */
final class PackJobHistory {
    record Entry(Path journal, Path source, String signature, Path output, Path archived, String status, String error,
                 ConversionOptions options, boolean needsReview, long updatedAt) {
        String displayStatus() { return status.equals("installed") && needsReview ? "needs_review" : status; }
        boolean retryable() { return output == null && (status.equals("failed") || status.equals("cancelled") || status.equals("interrupted")); }
    }
    static void create(Path job, Path source, String signature, ConversionOptions options) throws IOException {
        JsonObject data = new JsonObject(); data.addProperty("source", source.toAbsolutePath().normalize().toString());
        data.addProperty("signature", signature); data.addProperty("status", "running"); data.add("options", options.json());
        write(job.resolve("job.json"), data);
    }
    static void update(Path job, String status, String key, String value) throws IOException {
        Path path = job.resolve("job.json"); JsonObject data = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        data.addProperty("status", status); if (key != null && value != null) data.addProperty(key, value); write(path, data);
    }
    private static void write(Path path, JsonObject data) throws IOException {
        Path staged = Files.createTempFile(path.getParent(), "journal-", ".tmp");
        try { Files.writeString(staged, data.toString()); Files.move(staged, path, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(staged); }
    }
    static List<Entry> load(Path jobs) throws IOException {
        if (!Files.isDirectory(jobs)) return List.of();
        try (var paths = Files.list(jobs)) {
            return paths.filter(p -> Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .sorted(java.util.Comparator.comparingLong((Path p) -> modified(p.resolve("job.json"))).reversed())
                    .map(p -> p.resolve("job.json")).filter(Files::isRegularFile).map(PackJobHistory::read)
                    .filter(java.util.Objects::nonNull).limit(100).toList();
        }
    }
    private static long modified(Path path) {try{return Files.getLastModifiedTime(path).toMillis();}catch(IOException ignored){return 0;}}
    private static Entry read(Path path) {
        try {
            if (Files.size(path) > 64 * 1024) return null;
            JsonObject d = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            Path output = optionalPath(d, "installed_output"), archive = optionalPath(d, "archived");
            // A planned move may have completed immediately before the process stopped.
            Path planned = optionalPath(d, "archive_pending");
            Path source = Path.of(d.get("source").getAsString()); String signature = d.get("signature").getAsString();
            if (archive == null && planned != null && !Files.exists(source) && Files.exists(planned)
                    && signature.equals(PackConversionFiles.signature(planned))) archive = planned;
            String status = d.get("status").getAsString();
            if(output!=null&&(!Files.isRegularFile(output)||!d.has("output_signature")||!d.get("output_signature").getAsString().equals(PackConversionFiles.signature(output))))output=null;
            if(status.equals("archiving")&&archive!=null)status="archived";
            Path restored=optionalPath(d,"restore_pending");
            if(status.equals("restoring")&&archive!=null&&!Files.exists(archive)&&restored!=null&&Files.exists(restored)&&signature.equals(PackConversionFiles.signature(restored))) {status="restored";archive=null;}
            if (status.equals("running") || status.equals("installing")) status = output != null && Files.isRegularFile(output) ? "installed" : "interrupted";
            boolean review = false;
            Path result = path.getParent().resolve("result.json");
            if (Files.isRegularFile(result) && Files.size(result) <= 16 * 1024 * 1024) {
                try { review = !com.google.gson.JsonParser.parseString(Files.readString(result)).getAsJsonObject().getAsJsonArray("warnings").isEmpty(); }
                catch (RuntimeException ignored) { }
            }
            ConversionOptions options = d.has("options") ? ConversionOptions.fromJson(d.getAsJsonObject("options")) : ConversionOptions.defaults();
            return new Entry(path, source, signature, output, archive, status, d.has("error") ? d.get("error").getAsString() : "", options, review, modified(path));
        } catch (Exception error) { return null; }
    }
    private static Path optionalPath(JsonObject data, String key) { return data.has(key) ? Path.of(data.get(key).getAsString()).toAbsolutePath().normalize() : null; }
    static Path retryInput(Entry entry,Path packs) throws IOException {
        Path source=entry.source().toAbsolutePath().normalize(), root=packs.toAbsolutePath().normalize();
        if(!entry.retryable() || !root.equals(source.getParent()) || Files.isSymbolicLink(source)
                || !root.toRealPath().equals(source.toRealPath().getParent()) || !entry.signature().equals(PackConversionFiles.signature(source)))
            throw new IOException("Original changed or missing; check resource packs again");
        return source;
    }
    static Path restore(Entry entry, Path packs) throws IOException {
        Path root = packs.toRealPath(), archive = entry.archived();
        if (archive == null || Files.isSymbolicLink(archive) || Files.isSymbolicLink(packs.resolve("Old Pack"))
                || !archive.toRealPath().getParent().equals(root.resolve("Old Pack"))) throw new IOException("Invalid original archive location");
        if (!entry.signature().equals(PackConversionFiles.signature(archive))) throw new IOException("Archived original has changed");
        String name = entry.source().getFileName().toString(); Path target = root.resolve(name).normalize();
        int number = 1;
        while (true) {
            if (!target.getParent().equals(root)) throw new IOException("Invalid restore location");
            update(entry.journal().getParent(), "restoring", "restore_pending", target.toString());
            try { Files.move(archive, target); break; }
            catch (java.nio.file.FileAlreadyExistsException occupied) {
                int dot = Files.isDirectory(archive) ? -1 : name.lastIndexOf('.');
                target = root.resolve((dot > 0 ? name.substring(0, dot) : name) + " (restored " + number++ + ")" + (dot > 0 ? name.substring(dot) : ""));
            }
        }
        update(entry.journal().getParent(), "restored", "restored_output", target.toString()); return target;
    }
}
