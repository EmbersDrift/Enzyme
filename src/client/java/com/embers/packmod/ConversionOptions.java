package com.embers.packmod;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;

record ConversionOptions(boolean allowDestructive, boolean generateMissing, boolean adaptShaders,
                         boolean fixAlphaLayers, boolean flattenOverlays, int workers, int memoryBudgetMb) {
    static ConversionOptions defaults() { return new ConversionOptions(false, false, false, false, true, 2, 256); }
    static ConversionOptions complete() { return new ConversionOptions(true, true, true, true, true, 2, 256); }
    ConversionOptions {
        if (workers < 1 || workers > 4 || memoryBudgetMb < 128 || memoryBudgetMb > 1024)
            throw new IllegalArgumentException("Invalid conversion resource budget");
    }
    JsonObject json() {
        JsonObject data = new JsonObject();
        data.addProperty("allow_destructive", allowDestructive); data.addProperty("generate_missing", generateMissing);
        data.addProperty("adapt_shaders", adaptShaders); data.addProperty("fix_alpha_layers", fixAlphaLayers);
        data.addProperty("flatten_overlays", flattenOverlays); data.addProperty("workers", workers);
        data.addProperty("memory_budget_mb", memoryBudgetMb); return data;
    }
    static ConversionOptions read(Path path) {
        try {
            if (!Files.isRegularFile(path)) return defaults();
            JsonObject d = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            return fromJson(d);
        } catch (Exception error) { return defaults(); }
    }
    static ConversionOptions fromJson(JsonObject d) {
        return new ConversionOptions(d.get("allow_destructive").getAsBoolean(), d.get("generate_missing").getAsBoolean(),
                d.get("adapt_shaders").getAsBoolean(), d.get("fix_alpha_layers").getAsBoolean(),
                d.get("flatten_overlays").getAsBoolean(), d.get("workers").getAsInt(), d.get("memory_budget_mb").getAsInt());
    }
    void save(Path path) throws java.io.IOException {
        Files.createDirectories(path.getParent()); Path temp = Files.createTempFile(path.getParent(), "conversion-options-", ".tmp");
        try { Files.writeString(temp, json().toString()); Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }
}
