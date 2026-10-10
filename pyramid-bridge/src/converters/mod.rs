// Converter modules grouped by domain.
// ui/       GUI & HUD fixes
// textures/ block/item texture generation, rename, delete
// reverse/  undo counterparts of forward converters
// color/    HSV helpers
// audio/    sound conversion
// shaders/  Java shader adaptation
// bedrock/  Java <-> Bedrock pipeline

pub use ui::sign_entities::fix_sign_entities;
pub use textures::mcpatcher_to_optifine::rename_mcpatcher_to_optifine;

pub mod audio;
pub mod bedrock;
pub mod blockstate_adapter;
pub mod color;
pub mod legacy_eraser;
pub mod legacy_processor;
pub mod main_converter;
pub mod pack_analysis;
pub mod pack_diff;
pub mod reverse;
pub mod scale_factor;
pub mod shaders;
pub mod textures;
pub mod ui;
pub mod version_converter;
pub mod zip;

// Embedded runner resolves templates beside its executable.
pub fn get_uimage_path() -> Result<std::path::PathBuf, String> {
    if let Some(path) = crate::runtime::templates() {
        return if path.is_dir() {Ok(path)} else {Err("Configured template directory is missing".into())};
    }
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let path = exe.parent().ok_or("Missing runner directory")?.join("UImage");
    if path.is_dir() { Ok(path) } else { Err("Bundled UImage templates are missing".into()) }
}
