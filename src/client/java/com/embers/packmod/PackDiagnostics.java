package com.embers.packmod;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Explicit local export: only allowlisted text reports, never pack contents. */
final class PackDiagnostics {
    private static final List<String> FILES = List.of("job.json", "request.json", "result.json", "staging-repairs.json", "capabilities.json", "bundle-manifest.json", "conversion.log", "progress.jsonl", "capabilities.log");
    private static final long MAX_FILE = 16L * 1024 * 1024, MAX_TOTAL = 32L * 1024 * 1024;
    private static final Pattern SECRET = Pattern.compile("(?i)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|authorization|secret|token)");
    static Path export(Path job, Path destination, JsonObject environment) throws IOException {
        Files.createDirectories(destination);
        Path archive = Files.createTempFile(destination, "packmod-diagnostics-", ".zip");
        boolean success = false;
        try {
          try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            var metadata = environment.deepCopy(); metadata.addProperty("redacted", true); metadata.addProperty("pack_contents_included", false);
            var skipped = new JsonArray(); long total = 0;
            for (String name : FILES) {
                Path file = job.resolve(name);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                long length = Files.size(file);
                if (length > MAX_FILE || total + length > MAX_TOTAL) { skipped.add(name + ": size limit"); continue; }
                byte[] bytes;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes((int) MAX_FILE + 1); }
                if (bytes.length > MAX_FILE || total + bytes.length > MAX_TOTAL) { skipped.add(name + ": size limit"); continue; }
                total += bytes.length;
                String content = new String(bytes, StandardCharsets.UTF_8);
                if (name.endsWith(".json")) {
                    try { content = new GsonBuilder().setPrettyPrinting().create().toJson(redactJson(JsonParser.parseString(content))); }
                    catch (RuntimeException malformed) { skipped.add(name + ": malformed JSON omitted"); continue; }
                } else content = redact(content);
                write(zip, name, content);
            }
            metadata.add("omitted_files", skipped);
            write(zip, "environment.json", new GsonBuilder().setPrettyPrinting().create().toJson(redactJson(metadata)));
            write(zip, "README.txt", "Pack Mod diagnostic export. Absolute paths, account names and credentials are redacted.\nResource-pack ZIPs, textures and Minecraft logs are not included.\n");
          }
          success = true;
        } finally { if (!success) Files.deleteIfExists(archive); }
        return archive;
    }
    private static void write(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    static JsonElement redactJson(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (var e : element.getAsJsonObject().entrySet()) result.add(e.getKey(), SECRET.matcher(e.getKey()).find() ? new JsonPrimitive("<redacted>") : redactJson(e.getValue()));
            return result;
        }
        if (element.isJsonArray()) { JsonArray result = new JsonArray(); for (var item : element.getAsJsonArray()) result.add(redactJson(item)); return result; }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) return new JsonPrimitive(redact(element.getAsString()));
        return element.deepCopy();
    }
    static String redact(String text) {
        for (String identity : List.of(System.getProperty("user.name", ""), System.getenv().getOrDefault("COMPUTERNAME", ""), System.getenv().getOrDefault("HOSTNAME", "")))
            if (identity.length() >= 3) text = text.replaceAll("(?i)(?<![\\w])" + Pattern.quote(identity) + "(?![\\w])", "<account>");
        text = text.replaceAll("(?i)(?<![\\w])(?:[A-Z]:[\\\\/]|\\\\\\\\)[^\\r\\n\"<>|]*", "<path>");
        text = text.replaceAll("(?<![\\w:/])/(?!/)[^\\s\"<>][^\\r\\n\"<>]*", "<path>");
        text = text.replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "<email>");
        text = text.replaceAll("(?<![\\w])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w])", "<ip>");
        text = text.replaceAll("(?i)(?<![\\w])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:]{0,39}(?![\\w])", "<ip>");
        text = text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer <redacted>");
        return text.replaceAll("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|authorization|secret|token)\\s*[=:]\\s*)([^\\s,;]+)", "$1<redacted>");
    }
}
