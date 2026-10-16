//! MOD-only policy and observability around upstream A-ROM tasks.
use std::path::Path;
use crate::arom::{AromError, Pack, Tx};

pub fn select_plan(plan: &mut Vec<String>, source: u32, target: u32, gui: bool) -> Result<(), AromError> {
    if crate::runtime::options().is_none() { return Ok(()); }
    crate::runtime::check_cancel().map_err(AromError::internal)?;
    if crate::runtime::options().is_some_and(|o| o.fix_alpha_layers) {
        plan.push("fix_alpha_layers_in_textures".into());
    }
    crate::runtime::set_target(target);
    let path = crate::arom::engine::scheduler::Scheduler::new().calculate_path(source,target)
        .map_err(|e| AromError::internal(e.to_string()))?;
    let mut all = plan.clone();
    if gui && target >= 34 { all.push("gui_surgeon".into()); }
    crate::reporting::plan(&path,&all);
    crate::runtime::plan(all.len());
    plan.retain(|name| {
        if crate::runtime::skip_task(name,Path::new("")) {
            crate::reporting::skipped(name); crate::runtime::task_done(name); false
        } else { true }
    });
    Ok(())
}

pub fn run_task<T>(name: &str, tier: &str, run: impl FnOnce() -> Result<T,AromError>) -> Result<T,AromError> {
    crate::runtime::task_start(name).map_err(AromError::internal)?;
    if name=="delete_blockstates_models" && crate::runtime::source_root().is_some_and(|r| r.join("assets/minecraft/models").exists() || r.join("assets/minecraft/blockstates").exists()) {
        crate::reporting::warning("removed_models","Explicit destructive cleanup removes source models or blockstates.");
    }
    let result = run().and_then(|v| { crate::runtime::check_cancel().map_err(AromError::internal)?; Ok(v) });
    crate::reporting::task(name,tier,result.as_ref().err().map(ToString::to_string));
    crate::runtime::task_done(name);
    result
}

pub fn put_sprite(tx: &mut Tx<'_>, path: &str, image: &image::RgbaImage) -> Result<(),AromError> {
    if path.starts_with("assets/minecraft/textures/gui/sprites/")
        && crate::runtime::source_root().is_some_and(|root| root.join(path).is_file()) {
        return Ok(());
    }
    tx.put_image(path,image)
}

pub fn preserve_fonts(tx: &mut Tx<'_>) -> Result<crate::natives::Outcome,AromError> {
    let mut result = crate::natives::Outcome::default();
    for entry in tx.list("assets")? {
        if entry.is_dir || !entry.path.ends_with(".json") { continue; }
        let parts:Vec<_> = entry.path.split('/').collect();
        if parts.len()<4 || parts[2]!="font" { continue; }
        let raw=tx.text(&entry.path)?;
        let Ok(mut value)=serde_json::from_str::<serde_json::Value>(raw.trim_start_matches('\u{feff}')) else { continue; };
        let mut changed=false;
        if let Some(providers)=value.get_mut("providers").and_then(|v|v.as_array_mut()) {
            for provider in providers {
                if matches!(provider.get("type").and_then(|v|v.as_str()),Some("legacy_unicode"|"minecraft:legacy_unicode")) {
                    *provider=serde_json::json!({"type":"reference","id":"minecraft:include/unifont"});changed=true;
                }
            }
        }
        if changed {
            tx.put(&entry.path,serde_json::to_vec_pretty(&value).map_err(|e|AromError::internal(e.to_string()))?)?;
            result.changed+=1;
        }
    }
    Ok(result)
}

/// Keep the input/output image admission checks even though output stays in memory.
pub fn preflight_pack(pack: &Pack) -> Result<(),AromError> {
    if crate::runtime::options().is_none() { return Ok(()); }
    let mut count=0;
    let mut largest=(0,0,0,String::new());
    crate::runtime::emit("validating_images","");
    let view=pack.view();
    for entry in view.entries()? {
        crate::runtime::check_cancel().map_err(AromError::internal)?;
        if entry.is_dir { continue; }
        let ext=entry.path.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
        if !["png","jpg","jpeg","webp","bmp","tga","gif"].contains(&ext.as_str()) { continue; }
        let data=pack.read_body(&entry.body)?;
        let reader=image::io::Reader::new(std::io::Cursor::new(data)).with_guessed_format().map_err(|e|AromError::internal(e.to_string()))?;
        if ext=="png" && reader.format()!=Some(image::ImageFormat::Png) { return Err(AromError::internal(format!("Invalid output PNG encoding: {}",entry.path))); }
        let (w,h)=reader.into_dimensions().map_err(|e|AromError::internal(format!("Invalid output image {}: {e}",entry.path)))?;
        let pixels=u64::from(w)*u64::from(h);count+=1;
        if pixels>largest.0 { largest=(pixels,w,h,entry.path); }
    }
    crate::runtime::admit_images(count,&largest,"output").map(|_|()).map_err(AromError::internal)
}
