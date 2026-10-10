//! Per-process MOD conversion report. No desktop configuration or game state.
use std::collections::BTreeMap;
use std::io;
use std::path::Path;
use std::sync::Mutex;

#[derive(Clone, Debug, serde::Serialize)]
pub struct Warning { pub code: String, pub detail: String }

#[derive(Clone, Debug, serde::Serialize)]
pub struct Repair { pub code: String, pub path: String, pub detail: String }

#[derive(Clone, Debug, serde::Serialize)]
pub struct TaskResult {
    pub name: String,
    pub tier: String,
    pub success: bool,
    pub skipped: bool,
    pub worker_threads: usize,
    pub error: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, serde::Serialize)]
pub struct FileChanges { pub added: usize, pub modified: usize, pub removed: usize }

#[derive(Clone, Debug, Default, serde::Serialize)]
pub struct Report {
    pub warnings: Vec<Warning>,
    pub repairs: Vec<Repair>,
    pub tasks: Vec<TaskResult>,
    pub conversion_path: Vec<(u32, u32)>,
    pub planned_tasks: Vec<String>,
    pub changes: Option<FileChanges>,
    pub archive_validated: bool,
    pub image_budget_checks: Vec<crate::runtime::ImageBudgetCheck>,
}

// The runner handles one request per process; parallel tasks share this collector.
static REPORT: Mutex<Option<Report>> = Mutex::new(None);

pub fn begin() { *REPORT.lock().unwrap() = Some(Report::default()); }

pub fn take() -> Report { REPORT.lock().unwrap().take().unwrap_or_default() }

pub fn image_budget_check(check: crate::runtime::ImageBudgetCheck) {
    if let Some(report) = REPORT.lock().unwrap().as_mut() { report.image_budget_checks.push(check); }
}

pub fn warning(code: &str, detail: &str) {
    let mut state = REPORT.lock().unwrap();
    if let Some(report) = state.as_mut() {
        if report.warnings.len() < 512 && !report.warnings.iter().any(|w| w.code == code && w.detail == detail) {
            report.warnings.push(Warning { code: code.into(), detail: detail.chars().take(4096).collect() });
        }
    }
}

pub fn log_warning(detail: &str) {
    warning("converter_warning", detail);
}

pub fn repair(code: &str, path: &str, detail: &str) {
    if let Some(report) = REPORT.lock().unwrap().as_mut() {
        report.repairs.push(Repair { code: code.into(), path: path.into(), detail: detail.into() });
    }
}

pub fn plan(path: &[(u32, u32)], tasks: &[String]) {
    if let Some(report) = REPORT.lock().unwrap().as_mut() {
        report.conversion_path.extend_from_slice(path);
        report.planned_tasks.extend_from_slice(tasks);
    }
}

pub fn task(name: &str, tier: &str, error: Option<String>) {
    if let Some(report) = REPORT.lock().unwrap().as_mut() {
        report.tasks.push(TaskResult { name: name.into(), tier: tier.into(), success: error.is_none(), skipped: false, worker_threads: rayon::current_num_threads(), error });
    }
}

pub fn skipped(name: &str) {
    if let Some(report)=REPORT.lock().unwrap().as_mut() { report.tasks.push(TaskResult {name:name.into(),tier:"Policy".into(),success:true,skipped:true,worker_threads:0,error:None}); }
}
pub type Inventory = BTreeMap<String, (u64, u32)>;

/// Consume every entry to verify decompression/CRC and bound actual bytes read.
/// CRC is used for change statistics, never to authenticate the original pack.
pub fn inventory(path: &Path) -> Result<Inventory, String> {
    let file = std::fs::File::open(path).map_err(|e| e.to_string())?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| e.to_string())?;
    if zip.len() > 100_000 { return Err("Resource pack exceeds 100,000 entries".into()); }
    let mut result = BTreeMap::new();
    let mut bytes = 0u64;
    const LIMIT: u64 = 500 * 1024 * 1024;
    for index in 0..zip.len() {
        crate::runtime::check_cancel()?;
        let mut entry = zip.by_index(index).map_err(|e| e.to_string())?;
        if entry.is_dir() { continue; }
        let name = entry.name().to_string();
        let crc = entry.crc32();
        let size = entry.size();
        let read = io::copy(&mut std::io::Read::take(&mut entry, LIMIT - bytes + 1), &mut io::sink())
            .map_err(|e| format!("Cannot validate {}: {}", name, e))?;
        bytes += read;
        if bytes > LIMIT { return Err("Resource pack exceeds 500 MiB uncompressed".into()); }
        if read != size { return Err(format!("Incorrect uncompressed size: {}", name)); }
        if result.insert(name.clone(), (size, crc)).is_some() { return Err(format!("Duplicate ZIP entry: {}", name)); }
    }
    Ok(result)
}

pub fn changes(before: &Inventory, after: &Inventory) -> FileChanges {
    FileChanges {
        added: after.keys().filter(|p| !before.contains_key(*p)).count(),
        modified: after.iter().filter(|(p, value)| before.get(*p).is_some_and(|old| old != *value)).count(),
        removed: before.keys().filter(|p| !after.contains_key(*p)).count(),
    }
}

pub fn validated(before: &Inventory, after: &Inventory) {
    if let Some(report) = REPORT.lock().unwrap().as_mut() {
        report.changes = Some(changes(before, after));
        report.archive_validated = true;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_changes_without_counting_unchanged_files() {
        let before = BTreeMap::from([("same".into(), (1, 1)), ("edit".into(), (2, 2)), ("old".into(), (3, 3))]);
        let after = BTreeMap::from([("same".into(), (1, 1)), ("edit".into(), (2, 4)), ("new".into(), (5, 5))]);
        assert_eq!(changes(&before, &after), FileChanges { added: 1, modified: 1, removed: 1 });
    }

    #[test]
    fn corrupt_archive_is_not_validated() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("bad.zip");
        std::fs::write(&path, b"not a ZIP").unwrap();
        assert!(inventory(&path).is_err());
    }

    #[test]
    fn corrupt_entry_crc_is_not_validated() {
        use std::io::Write;
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("bad-crc.zip");
        let mut writer = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
        writer.start_file("marker.txt", zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Stored)).unwrap();
        writer.write_all(b"unique-marker-bytes").unwrap();
        writer.finish().unwrap();
        let mut bytes = std::fs::read(&path).unwrap();
        let offset = bytes.windows(19).position(|w| w == b"unique-marker-bytes").unwrap();
        bytes[offset] ^= 1;
        std::fs::write(&path, bytes).unwrap();
        assert!(inventory(&path).unwrap_err().contains("Cannot validate"));
    }
}
