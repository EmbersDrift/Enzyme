use rayon::prelude::*;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::sync::Arc;
use std::thread;

use image::RgbaImage;

use crate::hurray::context::HurrayContext;
use crate::hurray::error::{EngineError, EngineResult};
use crate::{log_error, log_info, log_warn};

#[derive(Debug)]
pub enum BusEvent {
    Write { path: PathBuf, texture: RgbaImage },
    Shutdown,
}

pub struct TexturePool {
    context: Option<Arc<HurrayContext>>,
    textures: HashMap<PathBuf, RgbaImage>,
    dirty_paths: HashSet<PathBuf>,
    bus_sender: Option<mpsc::SyncSender<BusEvent>>,
    io_thread_handle: Option<thread::JoinHandle<Result<(), String>>>,
}

impl TexturePool {
    pub fn new() -> Self {
        let (sender, io_thread) = Self::spawn_io_thread();

        Self {
            context: None,
            textures: HashMap::new(),
            dirty_paths: HashSet::new(),
            bus_sender: Some(sender),
            io_thread_handle: Some(io_thread),
        }
    }

    pub fn initialize(&mut self, context: Arc<HurrayContext>) {
        self.context = Some(context);
    }

    pub fn load_texture(&mut self, path: &Path) -> EngineResult<RgbaImage> {
        if let Some(texture) = self.textures.get(path) {
            return Ok(texture.clone());
        }

        if let Some(context) = &self.context {
            if let Some(shared) = context.get_cached_texture(path) {
                // Arc 解引用克隆一次，比再从磁盘 decode 便宜；池内仍持有副本供脏写。
                let texture = (*shared).clone();
                self.textures.insert(path.to_path_buf(), texture.clone());
                return Ok(texture);
            }
        }

        let texture = image::open(path)
            .map_err(|e| EngineError::image("load_texture", path, e))?
            .to_rgba8();

        self.textures.insert(path.to_path_buf(), texture.clone());
        if let Some(context) = &self.context {
            context.cache_texture(path, texture.clone());
        }

        Ok(texture)
    }

    pub fn get_texture(&self, path: &Path) -> Option<&RgbaImage> {
        self.textures.get(path)
    }

    pub fn get_texture_mut(&mut self, path: &Path) -> Option<&mut RgbaImage> {
        self.textures.get_mut(path)
    }

    pub fn store_texture(&mut self, path: &Path, texture: RgbaImage) {
        self.textures.insert(path.to_path_buf(), texture);
        self.dirty_paths.insert(path.to_path_buf());
    }

    pub fn commit_and_release(&mut self, path: &Path) -> EngineResult<()> {
        let texture = match self.textures.remove(path) {
            Some(texture) => texture,
            None => return Ok(()),
        };

        self.dirty_paths.remove(path);

        let sender = self
            .bus_sender
            .as_ref()
            .ok_or(EngineError::ChannelClosed("texture_pool.bus_sender"))?;

        sender
            .send(BusEvent::Write {
                path: path.to_path_buf(),
                texture,
            })
            .map_err(|_| EngineError::ChannelClosed("texture_pool.bus_sender"))
    }

    pub fn commit_all(&mut self) -> EngineResult<()> {
        // Even with no dirty textures, queued commit_and_release writes must finish
        // and their errors must reach the caller before the pack is built.
        let io_result = self.shutdown_io_thread();

        // Collect and drain before doing parallel work
        let mut pending_items = Vec::new();
        for path in self.dirty_paths.drain() {
            if let Some(texture) = self.textures.remove(&path) {
                pending_items.push((path, texture));
            }
        }

        let total = pending_items.len();
        log_info!("Parallel committing {} modified textures...", total);

        let processed = std::sync::atomic::AtomicUsize::new(0);

        // Use Rayon for parallel PNG encoding and saving
        let save_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            pending_items.par_iter().filter_map(|(path, texture)| {
                let failure = Self::write_texture(path, texture).err();
                let current = processed.fetch_add(1, std::sync::atomic::Ordering::SeqCst) + 1;
                if current % 50 == 0 || current == total {
                    let percent = (current * 100) / total;
                    log_info!("Progress: {}/{} ({}%) - Committing textures", current, total, percent);
                }
                failure
            }).collect::<Vec<String>>()
        }));

        log_info!("Parallel texture commit complete.");

        // Always restart the IO thread, even if panic occurred above
        self.restart_io_thread();

        let mut failures = match save_result {
            Ok(failures) => failures,
            Err(_) => vec!["texture commit_all panicked during parallel write".to_string()],
        };
        if let Err(error) = io_result { failures.push(error.to_string()); }
        if failures.is_empty() { Ok(()) }
        else { Err(EngineError::Message(failures.join(" | "))) }
    }

    fn write_texture(path: &Path, texture: &RgbaImage) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("failed to create output dir for {}: {}", path.display(), e))?;
        }
        texture.save(path).map_err(|e| format!("failed to save texture {}: {}", path.display(), e))
    }

    fn spawn_io_thread() -> (mpsc::SyncSender<BusEvent>, thread::JoinHandle<Result<(), String>>) {
        let (sender, receiver) = mpsc::sync_channel::<BusEvent>(2);
        let io_thread = thread::spawn(move || {
            let mut failures = Vec::new();
            while let Ok(event) = receiver.recv() {
                match event {
                    BusEvent::Write { path, texture } => {
                        if let Err(error) = Self::write_texture(&path, &texture) { failures.push(error); }
                    }
                    BusEvent::Shutdown => break,
                }
            }
            if failures.is_empty() { Ok(()) } else { Err(failures.join(" | ")) }
        });
        (sender, io_thread)
    }

    fn restart_io_thread(&mut self) {
        let (sender, io_thread) = Self::spawn_io_thread();
        self.bus_sender = Some(sender);
        self.io_thread_handle = Some(io_thread);
    }

    pub fn clear(&mut self) -> EngineResult<()> {
        let result = self.shutdown_io_thread();
        self.textures.clear();
        self.dirty_paths.clear();
        result
    }

    pub fn clear_unused(&mut self) {
        if self.textures.is_empty() {
            return;
        }

        let before = self.textures.len();
        self.textures.retain(|path, _| self.dirty_paths.contains(path));
        let after = self.textures.len();
        log_info!(
            "texture pool cleanup: dropped {} inactive textures, kept {} dirty textures",
            before.saturating_sub(after),
            after
        );
    }

    fn shutdown_io_thread(&mut self) -> EngineResult<()> {
        if let Some(sender) = &self.bus_sender {
            let _ = sender.send(BusEvent::Shutdown);
        }

        self.bus_sender = None;
        match self.io_thread_handle.take().map(|handle| handle.join()) {
            Some(Ok(result)) => result.map_err(EngineError::Message),
            Some(Err(_)) => Err(EngineError::Message("texture io worker panicked".into())),
            None => Ok(()),
        }
    }
}

impl Drop for TexturePool {
    fn drop(&mut self) {
        if let Err(error) = self.shutdown_io_thread() { log_error!("{}", error); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parallel_write_failure_is_returned() {
        let temp = tempfile::tempdir().unwrap();
        let blocked = temp.path().join("blocked");
        std::fs::write(&blocked, b"not a directory").unwrap();
        let mut pool = TexturePool::new();
        pool.store_texture(&blocked.join("texture.png"), RgbaImage::new(2, 2));
        let error = pool.commit_all().unwrap_err().to_string();
        assert!(error.contains("failed to create output dir"));
    }

    #[test]
    fn queued_write_failure_is_returned_with_no_dirty_textures() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("texture.png");
        std::fs::create_dir(&path).unwrap();
        let mut pool = TexturePool::new();
        pool.store_texture(&path, RgbaImage::new(2, 2));
        pool.commit_and_release(&path).unwrap();
        assert!(pool.dirty_paths.is_empty());
        assert!(pool.commit_all().unwrap_err().to_string().contains("failed to save texture"));
    }

    #[test]
    fn commit_waits_for_queued_writes_and_restarted_worker_reports_errors() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("texture.png");
        let mut pool = TexturePool::new();
        pool.store_texture(&path, RgbaImage::new(2, 2));
        pool.commit_and_release(&path).unwrap();
        pool.commit_all().unwrap();
        assert_eq!(image::open(&path).unwrap().width(), 2);
        let invalid = temp.path().join("directory.png");
        std::fs::create_dir(&invalid).unwrap();
        pool.store_texture(&invalid, RgbaImage::new(2, 2));
        pool.commit_and_release(&invalid).unwrap();
        assert!(pool.commit_all().is_err());
    }

    #[test]
    fn worker_panic_is_a_commit_failure() {
        let mut pool = TexturePool::new();
        pool.shutdown_io_thread().unwrap();
        pool.io_thread_handle = Some(thread::spawn(|| panic!("simulated writer panic")));
        assert!(pool.commit_all().unwrap_err().to_string().contains("panicked"));
    }
}
