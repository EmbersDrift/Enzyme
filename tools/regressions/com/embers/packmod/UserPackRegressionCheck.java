package com.embers.packmod;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Optional local real-pack verification; customer packs never enter the repository. */
public final class UserPackRegressionCheck {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if(args.length>3&&args[3].equals("all")) {
            for(String preset:new String[]{"conservative","defaults","complete"}){String[] selected=args.clone();selected[3]=preset;main(selected);}return;
        }
        Path source = Path.of(args[0]), work = Path.of(args[2]).resolve("run-" + System.nanoTime()).toAbsolutePath();
        var target = new PackInspector.Version(Integer.parseInt(args[1]), 0);
        String preset = args.length > 3 ? args[3] : "conservative";
        var options = switch (preset) {
            case "conservative" -> ConversionOptions.conservative();
            case "defaults" -> ConversionOptions.defaults();
            case "complete" -> ConversionOptions.complete();
            default -> throw new IllegalArgumentException("Unknown preset: " + preset);
        };
        String signature = PackConversionFiles.signature(source);
        Files.createDirectories(work);
        var info = PackInspector.inspect(source);
        var result = PyramidConverter.convertWithReport(info, target, work.resolve("packs"), work.resolve("tools"),
                options, new ConversionControl(), ignored -> {});
        check(signature.equals(PackConversionFiles.signature(source)), "Customer source pack unchanged");
        check(PackInspector.inspect(result.output()).supports(target), "Customer output matches target format");
        int images = 0, decodedImages=0, preservedAudio=0, migratedItems=0;
        try (var input = new ZipFile(source.toFile()); var output = new ZipFile(result.output().toFile())) {
            byte[] metadata;
            try (var stream = input.getInputStream(input.getEntry(info.metadata()))) { metadata = stream.readAllBytes(); }
            var normalized = PackMetadataStaging.normalize(metadata);
            check(normalized.escapedControls() == 0 || result.report().repairs().stream().anyMatch(r -> r.code().equals("metadata_controls_escaped")), "Actual metadata normalization is reported");
            var pack = JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("pack");
            if (pack.has("description") && pack.get("description").isJsonPrimitive() && pack.get("description").getAsJsonPrimitive().isString()) {
                String description = pack.get("description").getAsString().replace('\n', ' ').replace('\r', ' ');
                try (var stream = output.getInputStream(output.getEntry("pack.mcmeta"))) {
                    var converted = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    check(description.equals(converted.getAsJsonObject("pack").get("description").getAsString()), "Actual description preserved according to frozen core normalization");
                }
            }
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); String name = entry.getName();
                if (entry.isDirectory() || !(name.equals(info.prefix() + "pack.png")
                        || name.endsWith("/diamond_sword.png") || name.endsWith("/netherite_sword.png"))) continue;
                String mapped = name.substring(info.prefix().length()).replace("/textures/items/", "/textures/item/")
                        .replace("/mcpatcher/", "/optifine/");
                var converted = output.getEntry(mapped);
                check(converted != null, "Authored sword/icon exists: " + mapped);
                try (var first = input.getInputStream(entry); var second = output.getInputStream(converted)) {
                    var a = javax.imageio.ImageIO.read(first); var b = javax.imageio.ImageIO.read(second);
                    check(a != null && b != null && a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "Sword/icon dimensions preserved: " + mapped);
                    if (preset.equals("conservative")) check(java.util.Arrays.equals(a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth()),
                            b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth())), "Sword/icon pixels preserved: " + mapped);
                    images++;
                }
                var animation = input.getEntry(name + ".mcmeta");
                if (animation != null) {
                    var targetAnimation = output.getEntry(mapped + ".mcmeta");
                    check(targetAnimation != null, "Authored sword animation metadata exists");
                    try (var first = input.getInputStream(animation); var second = output.getInputStream(targetAnimation)) {
                        check(java.util.Arrays.equals(first.readAllBytes(), second.readAllBytes()), "Authored sword animation metadata preserved");
                    }
                }
            }
            var convertedEntries = output.entries();
            byte[] buffer = new byte[65536];
            while (convertedEntries.hasMoreElements()) {
                var entry = convertedEntries.nextElement(); if (entry.isDirectory()) continue;
                var crc = new java.util.zip.CRC32();
                try (var stream = output.getInputStream(entry)) {
                    int bytes; while ((bytes = stream.read(buffer)) != -1) crc.update(buffer, 0, bytes);
                }
                check(crc.getValue() == entry.getCrc(), "Converted archive CRC: " + entry.getName());
                if(entry.getName().endsWith(".png"))try(var stream=output.getInputStream(entry)) {
                    check(javax.imageio.ImageIO.read(stream)!=null,"Output image decodes: "+entry.getName());decodedImages++;
                }
                if(entry.getName().endsWith(".json")||entry.getName().endsWith(".mcmeta"))try(var stream=output.getInputStream(entry)) {
                    JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
                }
                if(entry.getName().matches("assets/[^/]+/items/.+\\.json"))migratedItems++;
            }
            for(var entry:java.util.Collections.list(input.entries()))if(entry.getName().endsWith(".ogg")) {
                var mapped=output.getEntry(entry.getName().substring(info.prefix().length()));check(mapped!=null,"Authored audio preserved");
                try(var a=input.getInputStream(entry);var b=output.getInputStream(mapped)){check(java.util.Arrays.equals(a.readAllBytes(),b.readAllBytes()),"Authored audio bytes unchanged");}preservedAudio++;
            }
            if(target.major()>=46) {
                int expected=0;
                for(var entry:java.util.Collections.list(input.entries()))if(entry.getName().matches(".*assets/[^/]+/models/item/.+\\.json"))try(var stream=input.getInputStream(entry)) {
                    if(JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().has("overrides"))expected++;
                }
                check(migratedItems>=expected,"All actual legacy model definitions migrated or authored: expected "+expected+", got "+migratedItems);
            }
            boolean hasCIT=java.util.Collections.list(input.entries()).stream().anyMatch(e->e.getName().contains("/optifine/")&&e.getName().endsWith(".properties"));
            check(!hasCIT||result.report().warnings().stream().anyMatch(w->w.code().equals("optifine_features_require_mod")),"External CIT requirement is reported");
            if(info.prefix().isEmpty()&&normalized.escapedControls()==0) {
                var before=new java.util.HashSet<String>();var after=new java.util.HashSet<String>();
                for(var entry:java.util.Collections.list(input.entries()))if(!entry.isDirectory())before.add(entry.getName());
                for(var entry:java.util.Collections.list(output.entries()))if(!entry.isDirectory())after.add(entry.getName());
                long added=after.stream().filter(n->!before.contains(n)).count(),removed=before.stream().filter(n->!after.contains(n)).count(),modified=0;
                for(String name:before)if(after.contains(name))try(var a=input.getInputStream(input.getEntry(name));var b=output.getInputStream(output.getEntry(name))) {
                    if(!java.util.Arrays.equals(a.readAllBytes(),b.readAllBytes()))modified++;
                }
                check(result.report().changes().equals(new ConversionReport.Changes(added,modified,removed)),"Report counts match independent final byte comparison");
            }
        }
        check(images > 0, "Real pack contains at least one sword or pack icon for pixel verification");
        var report = JsonParser.parseString(Files.readString(result.reportFile())).getAsJsonObject();
        report.addProperty("original_unchanged", true); report.addProperty("sword_and_icon_images_verified", images);
        report.addProperty("independent_crc_verified", true);
        report.addProperty("preset", preset);
        report.addProperty("decoded_images",decodedImages);report.addProperty("preserved_audio",preservedAudio);report.addProperty("modern_item_definitions",migratedItems);
        Files.writeString(work.resolve("verification.json"), report.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of(args[2]).resolve("last-user-pack-check.txt"), work.toString(), StandardCharsets.UTF_8);
        System.out.println("USER PACK PASS target=" + target + " preset=" + preset + " images=" + images + " warnings=" + result.report().warnings().size() + " repairs=" + result.report().repairs().size());
        System.out.println("OUTPUT " + result.output());
    }
}
