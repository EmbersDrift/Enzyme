package com.embers.packmod;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.ZipFile;

/** Report changes to existing resources made by upstream's generation tasks. */
final class GeneratedResourceReview {
    record Existing(String path, String task, byte[] hash) {}

    static List<Existing> snapshot(Path staged) throws IOException {
        Map<String, String> generated = new HashMap<>();
        generated.put("assets/minecraft/textures/block/netherite_block.png", "generate_netherite_block");
        generated.put("assets/minecraft/textures/item/netherite_ingot.png", "generate_netherite_ingot");
        for (String tool : List.of("sword", "helmet", "chestplate", "leggings", "boots", "axe", "pickaxe", "shovel", "hoe"))
            generated.put("assets/minecraft/textures/item/netherite_" + tool + ".png", "generate_netherite_tools");
        generated.put("assets/minecraft/textures/item/spectral_arrow.png", "generate_netherite_tools");
        for (int layer = 1; layer <= 2; layer++)
            generated.put("assets/minecraft/textures/models/armor/netherite_layer_" + layer + ".png", "generate_netherite_armor_models");
        var existing = new ArrayList<Existing>();
        try (var zip = new ZipFile(staged.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String path = entry.getName().replace("/textures/items/", "/textures/item/").replace("/textures/blocks/", "/textures/block/");
                String texture = path.endsWith(".mcmeta") ? path.substring(0, path.length() - 7) : path;
                String task = generated.get(texture);
                if (!entry.isDirectory() && task != null)
                    try (var stream = zip.getInputStream(entry)) { existing.add(new Existing(path, task, hash(stream))); }
            }
        }
        return List.copyOf(existing);
    }

    static List<ConversionReport.Warning> changed(List<Existing> existing, JsonObject report, Path output) throws IOException {
        if (existing.isEmpty()) return List.of();
        var ran = new java.util.HashSet<String>();
        for (var item : report.getAsJsonArray("tasks")) {
            var task = item.getAsJsonObject();
            if (task.get("success").getAsBoolean() && (!task.has("skipped") || !task.get("skipped").getAsBoolean()))
                ran.add(task.get("name").getAsString());
        }
        var warnings = new ArrayList<ConversionReport.Warning>();
        try (var zip = new ZipFile(output.toFile())) {
            for (var source : existing) {
                if (!ran.contains(source.task())) continue;
                var entry = zip.getEntry(source.path());
                if (entry == null) continue; // Other migrations may move or remove the resource.
                try (var stream = zip.getInputStream(entry)) {
                    if (!Arrays.equals(source.hash(), hash(stream))) warnings.add(new ConversionReport.Warning(
                            "generated_resource_changed", "Existing resource changed while " + source.task() + " ran: " + source.path()
                            + ". Review the generated appearance; the original pack is retained."));
                }
            }
        }
        return List.copyOf(warnings);
    }

    private static byte[] hash(InputStream stream) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int bytes;
            while ((bytes = stream.read(buffer)) != -1) digest.update(buffer, 0, bytes);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
