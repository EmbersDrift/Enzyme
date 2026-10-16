package com.embers.packmod;

import net.minecraft.network.chat.Component;
import java.util.*;

/** Presentation only: native warning codes/details remain untouched. */
final class ReportText {
    static String text(String key, Object... args) { return Component.translatable("packmod.pack_conversion." + key, args).getString(); }
    static String options(ConversionOptions o) {
        List<String> enabled = new ArrayList<>();
        if(o.allowDestructive()) enabled.add(text("option.cleanup"));
        if(o.generateMissing()) enabled.add(text("option.generate"));
        if(o.adaptShaders()) enabled.add(text("option.shaders"));
        if(o.fixAlphaLayers()) enabled.add(text("option.alpha"));
        if(o.flattenOverlays()) enabled.add(text("option.overlays"));
        return text("options_summary", String.join(" / ", enabled), o.workers(), o.memoryBudgetMb());
    }
    static String warning(ConversionReport.Warning warning) {
        return switch(warning.code()) {
            case "overlays_unconverted", "bedrock_experimental", "multiple_roots", "version_folders", "removed_models", "overlays_flattened", "models_preserved", "shaders_preserved" -> text("warning."+warning.code());
            case "generated_resource_changed", "optifine_features_require_mod", "legacy_item_model_review", "shader_adapter_review" -> text("warning."+warning.code());
            default -> warning.detail().startsWith("mixed resource-pack resolutions") ? text("warning.mixed_resolution") : text("warning.converter_warning", warning.detail());
        };
    }
    static String repair(String code, String path, String detail) {
        return switch(code) {
            case "image_encoding_normalized" -> text("repair.image_encoding",path);
            case "format_range_rewritten" -> text("repair.format_range");
            case "metadata_controls_escaped" -> text("repair.metadata_controls", path);
            case "legacy_item_model_migrated" -> text("repair.legacy_item_model",path);
            case "custom_sprites_registered" -> text("repair.custom_sprites",path);
            case "shader_interface_migrated" -> text("repair.shader_interface",path);
            case "special_model_parent_removed" -> text("repair.special_model_parent",path);
            default -> code + ": " + path + " " + detail;
        };
    }
}
