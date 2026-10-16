#![allow(dead_code, unused_imports, unused_variables)]

// Conversion code and UImage templates are from 2-Pyramid (MIT).
// Pinned upstream: 812a7358536c28772cfda3541a83e83f9a2d8825 (2.200.0).
#[macro_export]
macro_rules! log_info { ($($arg:tt)*) => {{ eprintln!($($arg)*); }}; }
#[macro_export]
macro_rules! log_warn { ($($arg:tt)*) => {{
    let message = format!($($arg)*);
    eprintln!("{}", message);
    $crate::reporting::log_warning(&message);
}}; }
#[macro_export]
macro_rules! log_error { ($($arg:tt)*) => {{ eprintln!($($arg)*); }}; }
#[macro_export]
macro_rules! log_debug { ($($arg:tt)*) => {{ if cfg!(debug_assertions) { eprintln!($($arg)*); } }}; }

mod arom;
mod natives;
mod native_run;
mod task_registry;
mod bedrock_convert;
mod chest_region;
mod color;
mod pack;
mod scale_factor;
mod integration;
#[cfg(test)] mod invoke_conversion;
mod alpha_layers;
mod image_utils;
mod color_utils;
pub mod reporting;
mod runtime;
mod overlays;

// Embedded conversions never consult or modify the desktop application's config.
mod commands {
    pub struct Config { pub output_naming: Option<String> }
    pub fn read_config_file() -> Result<Config, String> {
        Ok(Config { output_naming: Some("[Ver][Name]".into()) })
    }
}


use std::{path::PathBuf, sync::Mutex};
pub use runtime::{ImageBudgetCheck, Options};
pub const UPSTREAM_COMMIT: &str = "812a7358536c28772cfda3541a83e83f9a2d8825";
pub const TARGETS: &[u32] = &[1,2,3,4,5,6,7,8,9,12,13,15,18,22,32,34,42,46,55,63,64,69,75,84,88,97];
static CONVERSION: Mutex<()> = Mutex::new(());
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub input: PathBuf, pub output_dir: PathBuf, pub target_major: u32, pub target_minor: u32,
    #[serde(default)] pub options: Options,
    #[serde(default)] pub cancel_file: Option<PathBuf>,
    #[serde(default)] pub templates_dir: Option<PathBuf>,
    #[serde(default)] pub events_file: Option<PathBuf>,
}
#[derive(serde::Serialize)]
pub struct JobResult {
    pub schema_version: u32, pub upstream_commit: &'static str, pub status: &'static str,
    pub success: bool, pub output: Option<PathBuf>, pub error: Option<String>,
    #[serde(flatten)] pub report: reporting::Report,
}
pub fn capabilities() -> serde_json::Value {
    serde_json::json!({"schema_version":1,"core_version":env!("CARGO_PKG_VERSION"),
        "os":std::env::consts::OS,"arch":std::env::consts::ARCH,
        "targets":TARGETS.iter().map(|m|serde_json::json!({"major":m,"max_minor":if *m==97 {1} else {0}})).collect::<Vec<_>>(),
        "engine":"A-ROM","upstream_version":"2.200.0",
        "features":["progress","cancel","source_overlays","options","image_budget","shared_core"]})
}
pub fn failure(error: String) -> JobResult {
    JobResult { schema_version:1, upstream_commit:UPSTREAM_COMMIT, status:"failed", success:false,
        output:None,error:Some(error),report:reporting::Report::default() }
}
pub fn convert(request: Request) -> JobResult {
    let _guard=CONVERSION.lock().unwrap_or_else(|e|e.into_inner());
    reporting::begin();
    let result=std::panic::catch_unwind(|| -> Result<PathBuf,String> {
        runtime::configure(request.options.clone(),request.cancel_file.clone(),request.events_file.clone(),request.templates_dir.clone())?;
        runtime::check_cancel()?;
        if !TARGETS.contains(&request.target_major) || request.target_minor > if request.target_major==97 {1} else {0} {
            return Err("Unsupported target format; query --capabilities".into());
        }
        pack::io::set_cleanup_mode(pack::io::CleanupMode::Sync);
        runtime::emit("validating_input", "");
        let before=reporting::inventory(&request.input)?;
        let pool=rayon::ThreadPoolBuilder::new().num_threads(request.options.workers).build().map_err(|e|e.to_string())?;
        let output=pool.install(|| pack::version_converter::process_zip(
            request.input.to_str().ok_or("Invalid input path")?,request.target_major,None,1.0,None,
            Some(request.output_dir.to_str().ok_or("Invalid output path")?),request.options.fix_alpha_layers,request.options.adapt_shaders))?;
        runtime::check_cancel()?;
        runtime::emit("validating_output", "");
        let after=reporting::inventory(std::path::Path::new(&output))?;
        runtime::check_cancel()?;
        reporting::validated(&before,&after);
        runtime::emit("completed", "");
        Ok(PathBuf::from(output))
    }).unwrap_or_else(|_|Err("Conversion worker panicked; original unchanged".into()));
    let cancelled=runtime::cancelled(); runtime::finish();
    let report=reporting::take();
    let status=if result.is_err() { if cancelled {"cancelled"} else {"failed"} }
        else if report.warnings.is_empty() {"completed"} else {"needs_review"};
    let (output,error)=match result {Ok(p)=>(Some(p),None),Err(e)=>(None,Some(e))};
    JobResult {schema_version:1,upstream_commit:UPSTREAM_COMMIT,status,success:output.is_some(),output,error,report}
}

/// Read-only version route preview; file-specific effects remain in the actual report.
pub fn plan(source: u32, target: u32) -> Result<serde_json::Value,String> {
    if !TARGETS.contains(&source) || !TARGETS.contains(&target) {return Err("Unknown source or target format".into());}
    let scheduler=arom::engine::scheduler::Scheduler::new();
    let path=scheduler.calculate_path(source,target).map_err(|e|e.to_string())?;
    let mut tasks=scheduler.plan(source,target).map_err(|e|e.to_string())?;
    if target>=34 {tasks.push("gui_surgeon".into());}
    Ok(serde_json::json!({"schema_version":1,"source":source,"target":target,"path":path,"tasks":tasks}))
}
