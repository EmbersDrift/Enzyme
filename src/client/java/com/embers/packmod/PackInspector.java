package com.embers.packmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only metadata inspection; Minecraft version strings are not pack format numbers. */
final class PackInspector {
    static final int MAX_METADATA = 1_048_576;
    record Version(int major, int minor) implements Comparable<Version> {
        Version { if (major < 0 || minor < 0) throw new IllegalArgumentException("Negative pack format"); }
        @Override public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            return result == 0 ? Integer.compare(minor, other.minor) : result;
        }
        @Override public String toString() { return minor == Integer.MAX_VALUE ? major + ".*" : major + "." + minor; }
    }
    record Info(Path path, String metadata, Version min, Version max, boolean bedrock, List<String> warnings) {
        Info { warnings = List.copyOf(warnings); }
        boolean supports(Version target) { return !bedrock && min.compareTo(target) <= 0 && max.compareTo(target) >= 0; }
        String range() { return bedrock ? "Bedrock" : min.equals(max) ? min.toString() : min + " - " + max; }
        String prefix() {
            int slash = metadata.lastIndexOf('/');
            return slash < 0 ? "" : metadata.substring(0, slash + 1);
        }
    }

    static boolean candidate(Path path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return true;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && (name.endsWith(".zip") || name.endsWith(".mcpack"));
    }

    static Info inspect(Path input) throws IOException {
        if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
            Path meta = directoryMetadata(input);
            if (meta == null) return null;
            try (InputStream stream = Files.newInputStream(meta)) {
                return parse(input, input.relativize(meta).toString().replace('\\', '/'), stream);
            }
        }
        try (ZipFile zip = new ZipFile(input.toFile())) {
            ZipEntry selected = zip.stream().filter(e -> !e.isDirectory() && metadataName(e.getName()))
                    .min(Comparator.comparingInt((ZipEntry e) -> e.getName().split("/").length)
                            .thenComparing(e -> !e.getName().endsWith("pack.mcmeta"))).orElse(null);
            if (selected != null) {
                try (InputStream stream = zip.getInputStream(selected)) { return parse(input, selected.getName(), stream); }
            }
            ZipEntry manifest = zip.stream().filter(e -> e.getName().endsWith("manifest.json") && !e.isDirectory())
                    .min(Comparator.comparingInt(e -> e.getName().length())).orElse(null);
            return manifest == null ? null : new Info(input, manifest.getName(), null, null, true, List.of("bedrock_experimental"));
        }
    }

    static int sourceMajor(Info info) throws IOException {
        if(info.bedrock())return 97;
        String text;
        if(Files.isDirectory(info.path())) {Path meta=info.path().resolve(info.metadata());
            if(Files.size(meta)>MAX_METADATA)throw new IOException("Metadata exceeds 1 MiB");text=Files.readString(meta);
        }else try(ZipFile zip=new ZipFile(info.path().toFile());InputStream in=zip.getInputStream(zip.getEntry(info.metadata()))) {
            byte[] bytes=in.readNBytes(MAX_METADATA+1);if(bytes.length>MAX_METADATA)throw new IOException("Metadata exceeds 1 MiB");text=new String(bytes,StandardCharsets.UTF_8);
        }
        JsonObject pack=JsonParser.parseString(text.replace("\uFEFF","")).getAsJsonObject().getAsJsonObject("pack");
        JsonElement format=pack.has("pack_format")?pack.get("pack_format"):pack.has("min_format")?pack.get("min_format"):pack.get("format");
        return format.isJsonArray()?format.getAsJsonArray().get(0).getAsInt():format.getAsInt();
    }
    static Path directoryMetadata(Path folder) throws IOException {
        Path direct = folder.resolve("pack.mcmeta");
        if (Files.isRegularFile(direct, LinkOption.NOFOLLOW_LINKS)) return direct;
        try (var files = Files.walk(folder, 4)) {
            return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && metadataName(p.getFileName().toString()))
                    .min(Comparator.comparingInt(Path::getNameCount)).orElse(null);
        }
    }

    private static boolean metadataName(String name) {
        String last = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return last.equals("pack.mcmeta") || last.startsWith("pack.mcmeta.");
    }

    private static Info parse(Path input, String name, InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_METADATA + 1);
        if (bytes.length > MAX_METADATA) throw new IOException("pack.mcmeta exceeds 1 MiB");
        try {
            String text = new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject pack = root.getAsJsonObject("pack");
            if (pack == null) throw new IllegalArgumentException("Missing pack object");
            Version min, max;
            if (pack.has("min_format") && pack.has("max_format")) {
                min = version(pack.get("min_format"), false); max = version(pack.get("max_format"), true);
            } else if (pack.has("supported_formats")) {
                JsonElement supported = pack.get("supported_formats");
                if (supported.isJsonObject()) {
                    min = version(supported.getAsJsonObject().get("min_inclusive"), false);
                    max = version(supported.getAsJsonObject().get("max_inclusive"), true);
                } else if (supported.isJsonArray()) {
                    if (supported.getAsJsonArray().size() != 2) throw new IllegalArgumentException("Invalid supported_formats range");
                    min = version(supported.getAsJsonArray().get(0), false);
                    max = version(supported.getAsJsonArray().get(1), true);
                } else { min = version(supported, false); max = version(supported, true); }
            } else {
                JsonElement format = pack.has("pack_format") ? pack.get("pack_format") : pack.get("format");
                min = version(format, false); max = version(format, true);
            }
            if (min.compareTo(max) > 0) throw new IllegalArgumentException("Reversed pack format range");
            // Presence alone warrants a warning: malformed or future overlay layouts
            // must not be presented as an ordinary single-layer conversion.
            List<String> warnings = root.has("overlays") ? List.of("overlays_unconverted") : List.of();
            return new Info(input, name, min, max, false, warnings);
        } catch (RuntimeException error) { throw new IOException("Invalid resource pack metadata: " + error.getMessage(), error); }
    }

    private static Version version(JsonElement element, boolean upper) {
        if (element == null || element.isJsonNull()) throw new IllegalArgumentException("Missing pack format");
        if (element.isJsonArray()) {
            if (element.getAsJsonArray().size() != 2) throw new IllegalArgumentException("Invalid major/minor format");
            return new Version(element.getAsJsonArray().get(0).getAsInt(), element.getAsJsonArray().get(1).getAsInt());
        }
        return new Version(element.getAsInt(), upper ? Integer.MAX_VALUE : 0);
    }
}
