use crate::hurray::context::HurrayContext;

// Embers adaptation: keep resource-pack fonts when crossing the legacy 5 -> 6
// edge. Only the removed legacy_unicode provider needs a vanilla fallback;
// bitmap, space and TTF providers and their textures are left intact.
pub fn delete_font_folder(ctx: &HurrayContext) -> Result<(), String> {
    let assets = ctx.temp_dir().join("assets");
    if !assets.is_dir() { return Ok(()); }
    for namespace in std::fs::read_dir(&assets).map_err(|e| e.to_string())? {
        let font_path = namespace.map_err(|e| e.to_string())?.path().join("font");
        if !font_path.is_dir() { continue; }
        for entry in walkdir::WalkDir::new(font_path).follow_links(false) {
            let entry = entry.map_err(|e| e.to_string())?;
            if !entry.file_type().is_file()
                || entry.path().extension().and_then(|e| e.to_str()) != Some("json") { continue; }
            let text = std::fs::read_to_string(entry.path()).map_err(|e| e.to_string())?;
            // Preserve malformed/foreign font files so the ordinary game's error
            // reporting can explain them, rather than silently deleting assets.
            let Ok(mut font) = serde_json::from_str::<serde_json::Value>(text.trim_start_matches('\u{feff}')) else { continue; };
            let mut changed = false;
            if let Some(providers) = font.get_mut("providers").and_then(|p| p.as_array_mut()) {
                for provider in providers {
                    let kind = provider.get("type").and_then(|t| t.as_str());
                    if kind == Some("legacy_unicode") || kind == Some("minecraft:legacy_unicode") {
                        *provider = serde_json::json!({"type":"reference", "id":"minecraft:include/unifont"});
                        changed = true;
                    }
                }
            }
            if changed {
                std::fs::write(entry.path(), serde_json::to_vec_pretty(&font).map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
                crate::log_info!("Adapted legacy_unicode font provider: {}", entry.path().display());
            }
        }
    }
    Ok(())
}

pub fn register_task(engine: &mut crate::hurray::engine::HurrayEngine) {
    engine.register_task(
        "delete_font_folder",
        crate::hurray::scheduler::TaskType::Exclusive,
        crate::hurray::scheduler::TaskTier::Eraser,
        |context| delete_font_folder(context),
    );
}
