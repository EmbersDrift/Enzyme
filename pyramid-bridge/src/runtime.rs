//! Request-scoped controls shared with the migration scheduler.
use std::{fs::File, io::Write, path::{Path, PathBuf}, sync::Mutex};
#[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Options {
    pub allow_destructive: bool,
    pub generate_missing: bool,
    pub adapt_shaders: bool,
    pub fix_alpha_layers: bool,
    pub flatten_overlays: bool,
    pub workers: usize,
    pub memory_budget_mb: u64,
}
impl Default for Options {
    fn default() -> Self { Self { allow_destructive: false, generate_missing: false,
        adapt_shaders: false, fix_alpha_layers: false, flatten_overlays: true,
        workers: 2, memory_budget_mb: 256 } }
}
struct Runtime { options: Options, worker_limit: usize, templates: Option<PathBuf>, cancel: Option<PathBuf>, events: Option<File>, done: usize, total: usize }
static STATE: Mutex<Option<Runtime>> = Mutex::new(None);
pub fn configure(options: Options, cancel: Option<PathBuf>, events: Option<PathBuf>, templates: Option<PathBuf>) -> Result<(), String> {
    if !(1..=4).contains(&options.workers) || !(128..=1024).contains(&options.memory_budget_mb) {
        return Err("Workers must be 1–4 and image budget 128–1024 MiB".into());
    }
    let events = events.map(File::create).transpose().map_err(|e| e.to_string())?;
    let worker_limit = options.workers;
    *STATE.lock().unwrap() = Some(Runtime { options, worker_limit, templates, cancel, events, done: 0, total: 0 }); Ok(())
}
pub fn finish() { *STATE.lock().unwrap() = None; }
pub fn options() -> Option<Options> { STATE.lock().unwrap().as_ref().map(|s| s.options.clone()) }
pub fn conversion_workers() -> Option<usize> { STATE.lock().unwrap().as_ref().map(|s| s.worker_limit) }
pub fn templates() -> Option<PathBuf> { STATE.lock().unwrap().as_ref().and_then(|s|s.templates.clone()) }
pub fn cancelled() -> bool { STATE.lock().unwrap().as_ref().and_then(|s| s.cancel.as_ref()).is_some_and(|p| p.exists()) }
pub fn check_cancel() -> Result<(), String> { if cancelled() { Err("Conversion cancelled".into()) } else { Ok(()) } }
pub fn emit(stage: &str, task: &str) {
    if let Some(state) = STATE.lock().unwrap().as_mut() {
        let value = serde_json::json!({"stage":stage,"task":task,"completed":state.done,"total":state.total});
        if let Some(file) = &mut state.events { let _ = writeln!(file, "{value}"); let _ = file.flush(); }
    }
}
pub fn plan(total: usize) { if let Some(s) = STATE.lock().unwrap().as_mut() { s.total += total; } }
pub fn task_start(name: &str) -> Result<(), String> { check_cancel()?; emit("tasks", name); Ok(()) }
pub fn task_done(name: &str) { if let Some(s) = STATE.lock().unwrap().as_mut() { s.done += 1; } emit("tasks", name); }
pub fn skip_task(name: &str, root: &Path) -> bool {
    let Some(o) = options() else { return false; };
    if name == "adapt_java_shaders" && !o.adapt_shaders {
        if root.join("assets/minecraft/shaders").exists() { crate::reporting::warning("shaders_preserved", "Shader migration is disabled; shader compatibility needs review."); }
        return true;
    }
    if name.starts_with("generate_") && !o.generate_missing { return true; }
    if !o.allow_destructive && ((name.starts_with("delete_") && name != "delete_font_folder") || name.starts_with("reverse_generate_")) {
        if root.join("assets/minecraft/models").exists() || root.join("assets/minecraft/blockstates").exists() {
            crate::reporting::warning("models_preserved", "Destructive legacy cleanup was skipped; preserved models need review.");
        }
        return true;
    }
    false
}
#[derive(Clone, Debug, serde::Serialize)]
pub struct ImageBudgetCheck {
    pub phase: String,
    pub image_count: usize,
    pub largest_image: String,
    pub largest_width: u32,
    pub largest_height: u32,
    pub largest_rgba_bytes: u64,
    pub budget_bytes: u64,
    pub cache_reserve_bytes: u64,
    pub estimated_peak_bytes: u64,
    pub requested_workers: usize,
    pub effective_workers: usize,
    pub accepted: bool,
}

/// Images are streamed through tasks, not decoded all at once. Reserve a
/// quarter for the bounded context cache and 16 bytes per pixel per active
/// worker for decode, source copies and transformed images. Reduce concurrency
/// before rejecting a pack that can fit at one worker. This remains an estimate,
/// not an operating-system memory limit.
fn image_peak(largest_pixels: u64, workers: usize, budget: u64) -> (usize, u64, bool) {
    let reserve = budget / 4;
    let per_worker = largest_pixels.saturating_mul(16);
    let fitting = if per_worker == 0 { workers } else { ((budget - reserve) / per_worker) as usize };
    let effective = workers.min(fitting.max(1));
    let peak = per_worker.saturating_mul(effective as u64).saturating_add(reserve);
    (effective, peak, peak <= budget)
}

pub fn preflight_images(root: &Path, phase: &str) -> Result<(), String> {
    let Some(o) = options() else { return Ok(()); };
    let mut count = 0;
    let mut largest = (0u64, 0u32, 0u32, String::new());
    let mut mislabeled_pngs = Vec::new();
    for entry in walkdir::WalkDir::new(root) {
        check_cancel()?;
        let entry = entry.map_err(|e| e.to_string())?;
        if !entry.file_type().is_file() { continue; }
        let ext = entry.path().extension().and_then(|s| s.to_str()).unwrap_or("").to_ascii_lowercase();
        if !["png", "jpg", "jpeg", "webp", "bmp", "tga", "gif"].contains(&ext.as_str()) { continue; }
        // Packs sometimes name BMP/JPEG files .png. Sniff the bytes before
        // measuring them; all admission checks still precede full decoding.
        let (format, w, h) = image_header(entry.path())?;
        let pixels = u64::from(w) * u64::from(h);
        let name = entry.path().strip_prefix(root).unwrap_or(entry.path()).to_string_lossy().replace('\\', "/");
        count += 1;
        if ext == "png" && format != image::ImageFormat::Png {
            // Only these encodings are unambiguously a single still image.
            // Do not flatten GIF/WebP animations or multipage containers.
            if !matches!(format, image::ImageFormat::Bmp | image::ImageFormat::Jpeg) {
                return Err(format!("Cannot normalize {name}: {format:?} content named .png may contain animation or multiple images; export an explicit PNG first"));
            }
            mislabeled_pngs.push((entry.path().to_path_buf(), name.clone(), format));
        }
        if pixels > largest.0 { largest = (pixels, w, h, name); }
    }
    let budget = o.memory_budget_mb * 1024 * 1024;
    let (workers, peak, fits) = image_peak(largest.0, o.workers, budget);
    let accepted = fits && largest.0 <= 16_777_216;
    crate::reporting::image_budget_check(ImageBudgetCheck {
        phase: phase.into(), image_count: count, largest_image: largest.3.clone(),
        largest_width: largest.1, largest_height: largest.2, largest_rgba_bytes: largest.0.saturating_mul(4),
        budget_bytes: budget, cache_reserve_bytes: budget / 4, estimated_peak_bytes: peak,
        requested_workers: o.workers, effective_workers: workers, accepted,
    });
    if largest.0 > 16_777_216 {
        return Err(format!("Image exceeds 16,777,216 pixels: {} ({}x{})", largest.3, largest.1, largest.2));
    }
    if !fits {
        return Err(format!("Image budget exceeded by {} ({}x{}): estimated single-worker peak {:.1} MiB including cache, selected budget {} MiB",
            largest.3, largest.1, largest.2, peak as f64 / 1048576.0, o.memory_budget_mb));
    }
    if let Some(state) = STATE.lock().unwrap().as_mut() { state.worker_limit = state.worker_limit.min(workers); }
    for (path, name, format) in mislabeled_pngs {
        check_cancel()?;
        normalize_png(&path, budget)?;
        let detail = format!("Normalized {format:?} content named .png to PNG without resizing.");
        crate::reporting::repair("image_encoding_normalized", &name, &detail);
        crate::log_info!("Image encoding repaired: {name}: {detail}");
    }
    crate::log_info!("Image budget [{phase}]: {count} images, largest {} ({}x{}), peak estimate {:.1}/{} MiB, workers {}/{}",
        largest.3, largest.1, largest.2, peak as f64 / 1048576.0, o.memory_budget_mb, workers, o.workers);
    Ok(())
}

fn image_header(path: &Path) -> Result<(image::ImageFormat, u32, u32), String> {
    let reader = image::io::Reader::open(path).and_then(|r| r.with_guessed_format())
        .map_err(|e| format!("Cannot read image {}: {e}", path.display()))?;
    let format = reader.format().ok_or_else(|| format!("Unknown image encoding: {}", path.display()))?;
    let (width, height) = reader.into_dimensions()
        .map_err(|e| format!("Invalid image {}: {e}", path.display()))?;
    Ok((format, width, height))
}

/// Only called on the extracted staging copy, after every image passed the
/// pixel and working-set limits. Never rewrite the user's source archive.
fn normalize_png(path: &Path, budget: u64) -> Result<(), String> {
    let mut reader = image::io::Reader::open(path).and_then(|r| r.with_guessed_format())
        .map_err(|e| format!("Cannot read image {}: {e}", path.display()))?;
    let mut limits = image::io::Limits::default();
    limits.max_alloc = Some(budget / 4);
    reader.limits(limits);
    let image = reader.decode().map_err(|e| format!("Cannot repair image {}: {e}", path.display()))?;
    image.save_with_format(path, image::ImageFormat::Png)
        .map_err(|e| format!("Cannot write repaired image {}: {e}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::*;
    const MIB: u64 = 1024 * 1024;
    #[test]
    fn estimates_concurrent_images_not_entire_pack() {
        // Even thousands of 1024x1024 images can stream at two workers.
        assert_eq!(image_peak(1024 * 1024, 2, 128 * MIB), (2, 64 * MIB, true));
    }
    #[test]
    fn real_pack_sky_texture_fits_default_budget() {
        assert_eq!(image_peak(3072 * 2048, 2, 256 * MIB), (2, 256 * MIB, true));
    }
    #[test]
    fn reduces_workers_before_rejecting() {
        assert_eq!(image_peak(3072 * 2048, 4, 128 * MIB), (1, 128 * MIB, true));
    }
    #[test]
    fn rejects_single_image_that_cannot_fit_with_cache() {
        assert_eq!(image_peak(4096 * 4096, 2, 128 * MIB), (1, 288 * MIB, false));
    }
    #[test]
    fn bmp_disguised_as_png_is_recognized_and_repaired_without_pixel_changes() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("pack.png");
        let image = image::RgbImage::from_fn(32, 24, |x, y| image::Rgb([x as u8, y as u8, 173]));
        image.save_with_format(&path, image::ImageFormat::Bmp).unwrap();
        assert_eq!(image_header(&path).unwrap(), (image::ImageFormat::Bmp, 32, 24));
        normalize_png(&path, 128 * MIB).unwrap();
        assert_eq!(image_header(&path).unwrap(), (image::ImageFormat::Png, 32, 24));
        assert_eq!(image::open(&path).unwrap().to_rgb8(), image);
    }
    #[test]
    fn corrupt_png_is_not_silently_accepted_or_replaced() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("bad.png");
        std::fs::write(&path, b"not an image").unwrap();
        assert!(image_header(&path).is_err());
        assert!(normalize_png(&path, 128 * MIB).is_err());
        assert_eq!(std::fs::read(&path).unwrap(), b"not an image");
    }
    #[test]
    fn jpeg_disguised_as_png_keeps_decoded_pixels() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("pack.png");
        image::RgbImage::from_fn(24, 16, |x, y| image::Rgb([x as u8 * 5, y as u8 * 7, 113]))
            .save_with_format(&path, image::ImageFormat::Jpeg).unwrap();
        let original = image::io::Reader::open(&path).unwrap().with_guessed_format().unwrap().decode().unwrap().to_rgb8();
        normalize_png(&path, 128 * MIB).unwrap();
        assert_eq!(image_header(&path).unwrap(), (image::ImageFormat::Png, 24, 16));
        assert_eq!(image::open(&path).unwrap().to_rgb8(), original);
    }
}
