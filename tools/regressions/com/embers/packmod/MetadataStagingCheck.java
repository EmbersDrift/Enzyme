package com.embers.packmod;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Customer control-character failure and existing sword appearance regressions. */
public final class MetadataStagingCheck {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void run(Path root, Path packs, Path tools, PackInspector.Version target) throws Exception {
        var defaults = ConversionOptions.defaults();
        check(defaults.generateMissing() && defaults.adaptShaders() && defaults.fixAlphaLayers() && defaults.flattenOverlays() && !defaults.allowDestructive(), "New defaults enable generation/adaptation/fixes without destructive cleanup");
        Path settings = root.resolve("config/options.json");
        check(ConversionOptions.read(settings).equals(defaults), "First installation uses new defaults");
        ConversionOptions.conservative().save(settings);
        check(ConversionOptions.read(settings).equals(ConversionOptions.conservative()), "Explicit saved OFF settings survive reload");
        defaults.save(settings);
        check(ConversionOptions.read(settings).equals(defaults), "Restore defaults persist after restart");
        String controls = "\n\r\t\0\u001f";
        String description = "First line" + controls + "Second line ";
        String metadata = "{\n \"pack\": {\n \"pack_format\": 1,\n \"description\": \"" + description + "\\\"quoted\\\"\\\\tail\"\n },\n \"custom\":{\"preserved\":true}\n}";
        // Deliberately contain literal controls, exactly reproducing serde_json's
        // line 5 column 0 error before MOD staging normalization.
        description = JsonParser.parseString(metadata).getAsJsonObject().getAsJsonObject("pack").get("description").getAsString();
        byte[] sourceMetadata = metadata.getBytes(StandardCharsets.UTF_8);
        var normalized = PackMetadataStaging.normalize(sourceMetadata);
        check(normalized.escapedControls() == 5, "All literal JSON string controls escaped");
        check(JsonParser.parseString(metadata).equals(JsonParser.parseString(new String(normalized.bytes(), StandardCharsets.UTF_8))), "Decoded metadata unchanged");
        check(Arrays.equals(normalized.bytes(), PackMetadataStaging.normalize(normalized.bytes()).bytes()), "Escaping is idempotent");
        String ordinary = "{\"pack\":{\"pack_format\":1,\"description\":\"Escaped\\nnewline \\u001f\"}}";
        byte[] valid = ordinary.getBytes(StandardCharsets.UTF_8);
        check(Arrays.equals(valid, PackMetadataStaging.normalize(valid).bytes()), "Valid metadata bytes unchanged");
        boolean invalidUtf8 = false;
        try { PackMetadataStaging.normalize(new byte[]{(byte) 0xc3, 0x28}); } catch (java.io.IOException expected) { invalidUtf8 = true; }
        check(invalidUtf8, "Malformed UTF-8 is rejected without silent replacement");
        boolean invalidEscape = false;
        try { PackMetadataStaging.normalize("{\"x\":\"\\\n\"}".getBytes(StandardCharsets.UTF_8)); } catch (java.io.IOException expected) { invalidEscape = true; }
        check(invalidEscape, "Invalid backslash/control escape is not guessed");
        StringBuilder all = new StringBuilder();
        for (char c = 0; c < 32; c++) all.append(c);
        byte[] allControls = ("{\"description\":\"" + all + "\"}").getBytes(StandardCharsets.UTF_8);
        check(PackMetadataStaging.normalize(allControls).escapedControls() == 32, "All U+0000 through U+001F controls covered");

        byte[] diamond = texture(0xff278ac1), netherite = texture(0xffbd258d);
        Path source = root.resolve("customer-description-swords.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            for (var file : Map.of("pack.mcmeta", sourceMetadata,
                    "assets/minecraft/textures/items/diamond_sword.png", diamond,
                    "assets/minecraft/textures/items/netherite_sword.png", netherite,
                    "assets/test/models/item/custom_sword.json", "{\"parent\":\"minecraft:item/handheld\"}".getBytes(StandardCharsets.UTF_8)).entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry();
            }
        }
        byte[] original = Files.readAllBytes(source);
        var result = PyramidConverter.convertWithReport(PackInspector.inspect(source), target, packs, tools, ConversionOptions.conservative(), new ConversionControl(), ignored -> {});
        check(PackInspector.inspect(result.output()).supports(target), "Customer metadata converts to " + target);
        check(Arrays.equals(original, Files.readAllBytes(source)), "Original customer ZIP unchanged");
        check(result.report().repairs().stream().anyMatch(r -> r.code().equals("metadata_controls_escaped")), "Metadata repair returned in report");
        var persisted = JsonParser.parseString(Files.readString(result.reportFile())).getAsJsonObject();
        boolean repairPersisted = false;
        for (var repair : persisted.getAsJsonArray("repairs")) repairPersisted |= repair.getAsJsonObject().get("code").getAsString().equals("metadata_controls_escaped");
        check(repairPersisted, "Metadata repair durable after staging cleanup");
        try (var zip = new ZipFile(result.output().toFile())) {
            check(pixel(zip, "diamond_sword") == 0xff278ac1, "Diamond sword appearance preserved with generation disabled");
            check(pixel(zip, "netherite_sword") == 0xffbd258d, "Existing netherite sword appearance preserved with generation disabled");
            try (var in = zip.getInputStream(zip.getEntry("pack.mcmeta"))) {
                var data = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                check(description.replace('\n', ' ').replace('\r', ' ').equals(data.getAsJsonObject("pack").get("description").getAsString()), "Final description follows frozen core newline normalization");
                check(data.getAsJsonObject("custom").get("preserved").getAsBoolean(), "Custom metadata field preserved");
            }
            check(zip.getEntry("assets/test/models/item/custom_sword.json") != null, "Custom namespace sword model preserved");
        }
        var generating = new ConversionOptions(false, true, false, false, true, 2, 256);
        var generated = PyramidConverter.convertWithReport(PackInspector.inspect(source), target, packs, tools, generating, new ConversionControl(), ignored -> {});
        check(generated.needsReview() && generated.report().warnings().stream().anyMatch(w -> w.code().equals("generated_resource_changed") && w.detail().contains("netherite_sword")), "Upstream overwrites existing netherite sword only with generation enabled; MOD requires review");
        try (var zip = new ZipFile(generated.output().toFile())) { check(pixel(zip, "netherite_sword") != 0xffbd258d, "Existing generation overwrite reproduced, core unchanged"); }
        check(Arrays.equals(original, Files.readAllBytes(source)), "Original preserved when generation changes appearance");
        var generatedReport = JsonParser.parseString(Files.readString(generated.reportFile())).getAsJsonObject();
        check(generatedReport.get("status").getAsString().equals("needs_review"), "Generation review status persisted");

        Path folder = root.resolve("customer-description-folder/wrapper"); Files.createDirectories(folder);
        Files.write(folder.resolve("pack.mcmeta.txt"), sourceMetadata);
        var directory = PyramidConverter.convertWithReport(PackInspector.inspect(folder.getParent()), target, packs, tools, ignored -> {});
        check(PackInspector.inspect(directory.output()).supports(target), "Nested folder metadata suffix and raw description supported");
        check(Arrays.equals(sourceMetadata, Files.readAllBytes(folder.resolve("pack.mcmeta.txt"))), "Directory metadata unchanged");
        checkModernSwords(root, packs, tools, target, diamond, netherite);
        Path missing = root.resolve("missing-netherite.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(missing))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta")); zip.write(valid); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/items/diamond_sword.png")); zip.write(diamond); zip.closeEntry();
        }
        var supplied = PyramidConverter.convertWithReport(PackInspector.inspect(missing), target, packs, tools, ConversionOptions.defaults(), new ConversionControl(), ignored -> {});
        check(supplied.report().warnings().stream().noneMatch(w -> w.code().equals("generated_resource_changed")), "Generating a missing sword does not warn about an existing resource");
        check(supplied.report().repairs().stream().noneMatch(r -> r.code().equals("metadata_controls_escaped")), "Already escaped metadata is not reported as repaired");
        try (var zip = new ZipFile(supplied.output().toFile())) { check(pixel(zip, "netherite_sword") != 0xff278ac1, "Missing netherite sword generated from diamond texture"); }
        System.out.println("CUSTOMER REGRESSION PASS format " + target + ": raw controls, description, source ZIP/folder, diamond/netherite pixels, custom model, generation review");
    }

    private static void checkModernSwords(Path root, Path packs, Path tools, PackInspector.Version target, byte[] diamond, byte[] netherite) throws Exception {
        byte[] animation = "{\"animation\":{\"frametime\":7}}".getBytes(StandardCharsets.UTF_8);
        for (int format : new int[]{15, 75}) {
            Path source = root.resolve("existing-modern-swords-" + format + ".zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
                String pack = format < 69 ? "\"pack_format\":" + format : "\"min_format\":[75,0],\"max_format\":[75,0]";
                for (var file : Map.of("pack.mcmeta", ("{\"pack\":{" + pack + ",\"description\":\"Modern swords\"}}").getBytes(StandardCharsets.UTF_8),
                        "assets/minecraft/textures/item/diamond_sword.png", diamond,
                        "assets/minecraft/textures/item/netherite_sword.png", netherite,
                        "assets/minecraft/textures/item/netherite_sword.png.mcmeta", animation,
                        "assets/minecraft/models/item/netherite_sword.json", "{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\"minecraft:item/netherite_sword\"}}".getBytes(StandardCharsets.UTF_8)).entrySet()) {
                    zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry();
                }
            }
            byte[] original = Files.readAllBytes(source);
            var result = PyramidConverter.convertWithReport(PackInspector.inspect(source), target, packs, tools,
                    new ConversionOptions(false, true, false, false, true, 2, 256), new ConversionControl(), ignored -> {});
            check(Arrays.equals(original, Files.readAllBytes(source)), "Modern sword pack unchanged: " + format);
            check(PackInspector.inspect(result.output()).supports(target), "Modern sword target format verified: " + format);
            check(result.report().warnings().stream().noneMatch(w -> w.code().equals("generated_resource_changed")), "No generation warning when netherite generation task did not run");
            try (var zip = new ZipFile(result.output().toFile())) {
                check(pixel(zip, "diamond_sword") == 0xff278ac1 && pixel(zip, "netherite_sword") == 0xffbd258d, "Modern sword pixels preserved: " + format + " -> " + target);
                try (var in = zip.getInputStream(zip.getEntry("assets/minecraft/textures/item/netherite_sword.png.mcmeta"))) {
                    check(Arrays.equals(animation, in.readAllBytes()), "Sword animation metadata unchanged");
                }
                check(zip.getEntry("assets/minecraft/models/item/netherite_sword.json") != null, "Existing modern sword model preserved");
            }
        }
    }

    private static byte[] texture(int pixel) throws Exception {
        var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, pixel);
        var output = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", output); return output.toByteArray();
    }
    private static int pixel(ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry("assets/minecraft/textures/item/" + name + ".png");
        if (entry == null) entry = zip.getEntry("assets/minecraft/textures/items/" + name + ".png");
        check(entry != null, "Sword texture exists: " + name);
        try (var input = zip.getInputStream(entry)) { return javax.imageio.ImageIO.read(input).getRGB(3, 3); }
    }
}
