//! Materialize the source pack's active overlays before migrating its assets.
use std::{fs, path::{Path, PathBuf}};
use serde_json::Value;
type Version = (u32, u32);
fn version(v: &Value, upper: bool) -> Result<Version, String> {
    let number = |v: &Value| v.as_u64().filter(|n| *n <= u32::MAX as u64).map(|n| n as u32).ok_or("Invalid overlay format".to_owned());
    if let Some(a) = v.as_array() {
        if a.len() != 2 { return Err("Invalid overlay minor format".into()); }
        Ok((number(&a[0])?, number(&a[1])?))
    } else { Ok((number(v)?, if upper { u32::MAX } else { 0 })) }
}
fn range(entry: &Value) -> Result<(Version, Version), String> {
    let result = if let (Some(min),Some(max)) = (entry.get("min_format"),entry.get("max_format")) {
        (version(min,false)?,version(max,true)?)
    } else {
        let f = entry.get("formats").ok_or("Overlay formats are missing")?;
        if let Some(a) = f.as_array() {
            if a.len()!=2 { return Err("Invalid overlay format range".into()); }
            (version(&a[0],false)?,version(&a[1],true)?)
        } else if f.is_object() {
            (version(f.get("min_inclusive").ok_or("Missing overlay minimum")?,false)?,
             version(f.get("max_inclusive").ok_or("Missing overlay maximum")?,true)?)
        } else { (version(f,false)?,version(f,true)?) }
    };
    if result.0 > result.1 { return Err("Reversed overlay format range".into()); } Ok(result)
}
fn directory(value: &str) -> Result<PathBuf,String> {
    if value.is_empty() || value.contains(['\\', ':', '\0']) || value.split('/').any(|s| s.is_empty() || s=="." || s=="..") || value.split('/').next()==Some("assets") {
        return Err("Unsafe overlay directory".into());
    } Ok(PathBuf::from(value))
}
pub fn prepare(root: &Path, flatten: bool) -> Result<(), String> {
    let meta = root.join("pack.mcmeta");
    if !meta.exists() { return Ok(()); }
    let text = fs::read_to_string(&meta).map_err(|e|e.to_string())?;
    let mut data: Value = serde_json::from_str(text.trim_start_matches('\u{feff}')).map_err(|e|e.to_string())?;
    let Some(overlays) = data.get("overlays") else { return Ok(()); };
    let entries = overlays.get("entries").and_then(Value::as_array).ok_or("Invalid overlays.entries")?;
    let pack = data.get("pack").ok_or("Missing pack metadata")?;
    let source = version(pack.get("pack_format").or_else(||pack.get("min_format")).or_else(||pack.get("format")).ok_or("Missing source format")?, false)?;
    let mut layers = Vec::new();
    for entry in entries {
        let path = directory(entry.get("directory").and_then(Value::as_str).ok_or("Missing overlay directory")?)?;
        if layers.iter().any(|(old, _): &(PathBuf, bool)| path.starts_with(old) || old.starts_with(&path)) { return Err("Overlapping overlay directories".into()); }
        let (min,max) = range(entry)?;
        layers.push((path, min<=source && source<=max));
    }
    if !flatten {
        crate::reporting::warning("overlays_unconverted", "Overlay migration is disabled; only base assets are converted."); return Ok(());
    }
    for (layer,active) in &layers {
        crate::runtime::check_cancel()?;
        if *active {
            let assets = root.join(layer).join("assets");
            if assets.is_dir() {
                for entry in walkdir::WalkDir::new(&assets) {
                    crate::runtime::check_cancel()?;
                    let entry = entry.map_err(|e| e.to_string())?;
                    if entry.file_type().is_symlink() { return Err("Symlink in overlay".into()); }
                    if !entry.file_type().is_file() { continue; }
                    let target = root.join("assets").join(entry.path().strip_prefix(&assets).map_err(|e|e.to_string())?);
                    fs::create_dir_all(target.parent().unwrap()).map_err(|e|e.to_string())?;
                    fs::copy(entry.path(),target).map_err(|e|e.to_string())?;
                }
            }
        }
    }
    for (layer,_) in &layers { let path=root.join(layer); if path.exists() { fs::remove_dir_all(path).map_err(|e|e.to_string())?; } }
    data.as_object_mut().ok_or("Invalid metadata")?.remove("overlays");
    fs::write(meta,serde_json::to_vec_pretty(&data).map_err(|e|e.to_string())?).map_err(|e|e.to_string())?;
    crate::reporting::warning("overlays_flattened", &format!("Materialized {} active source overlays; output targets one version. Original retained.", layers.iter().filter(|(_,a)|*a).count()));
    Ok(())
}
#[cfg(test)] mod tests {
    use super::*;
    #[test] fn materializes_source_in_order_not_future_layer() {
        let temp=tempfile::tempdir().unwrap(); let root=temp.path();
        fs::write(root.join("pack.mcmeta"), r#"{"pack":{"pack_format":34},"overlays":{"entries":[{"directory":"one","formats":[34,34]},{"directory":"two","formats":[34,34]},{"directory":"future","formats":[88,88]}]}}"#).unwrap();
        for (layer,bytes) in [("one","first"),("two","last"),("future","wrong")] { fs::create_dir_all(root.join(layer).join("assets/test")).unwrap(); fs::write(root.join(layer).join("assets/test/file.txt"),bytes).unwrap(); }
        prepare(root,true).unwrap(); assert_eq!(fs::read_to_string(root.join("assets/test/file.txt")).unwrap(),"last");
        assert!(!root.join("future").exists()); assert!(!fs::read_to_string(root.join("pack.mcmeta")).unwrap().contains("overlays"));
    }
    #[test] fn rejects_unsafe_paths_and_ranges() {
        for path in ["../outside","/outside","assets","foo\\bar","a//b", "a:foo"] { assert!(directory(path).is_err()); }
        assert!(range(&serde_json::json!({"formats":[88,34]})).is_err());
        assert_eq!(range(&serde_json::json!({"min_format":[97,1],"max_format":[97,2]})).unwrap(),((97,1),(97,2)));
    }
}
