//! Library lifecycle and cooperative cancellation, isolated from unit-test globals.
use std::{fs, io::Write, path::Path};
use pyramid_core::{Options, Request};
fn request(root: &Path, input: &Path, name: &str) -> Request {
    Request { input:input.into(),output_dir:root.join(name),target_major:88,target_minor:0,
        options:Options::default(),cancel_file:Some(root.join("cancel.flag")),
        events_file:Some(root.join("events.jsonl")),templates_dir:Some(Path::new(env!("CARGO_MANIFEST_DIR")).join("UImage")) }
}
#[test]
fn shared_core_reinitializes_and_cancels_during_extraction() {
    let temp=tempfile::tempdir().unwrap(); let root=temp.path(); let input=root.join("source.zip");
    let mut zip=zip::ZipWriter::new(fs::File::create(&input).unwrap());
    zip.start_file("pack.mcmeta",zip::write::FileOptions::default()).unwrap();
    zip.write_all(br#"{"pack":{"pack_format":34,"description":"API"}}"#).unwrap();
    for i in 0..5000 {zip.start_file(format!("assets/test/files/{i}.txt"),zip::write::FileOptions::default()).unwrap();zip.write_all(b"original").unwrap();}
    zip.finish().unwrap(); let original=fs::read(&input).unwrap();
    let events=root.join("events.jsonl");let cancel=root.join("cancel.flag");
    let watcher=std::thread::spawn(move|| {
        let deadline=std::time::Instant::now()+std::time::Duration::from_secs(10);
        loop {
            if fs::read_to_string(&events).unwrap_or_default().contains("extracting") {fs::write(cancel,"cancel").unwrap();return;}
            assert!(std::time::Instant::now()<deadline,"No extraction event");
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
    });
    let cancelled=pyramid_core::convert(request(root,&input,"cancel-output"));watcher.join().unwrap();
    assert_eq!(cancelled.status,"cancelled");assert!(!cancelled.success);assert!(cancelled.output.is_none());
    fs::remove_file(root.join("cancel.flag")).unwrap();
    let completed=pyramid_core::convert(request(root,&input,"output"));
    assert!(completed.success,"{:?}",completed.error);assert!(completed.report.archive_validated);
    assert!(completed.report.tasks.iter().any(|t|t.name=="gui_surgeon" && t.success));
    assert!(completed.report.tasks.iter().any(|t|t.skipped));
    assert_eq!(fs::read(&input).unwrap(),original);
    let mut invalid=request(root,&input,"invalid");invalid.target_minor=99;
    let failed=pyramid_core::convert(invalid);assert_eq!(failed.status,"failed");assert!(!failed.success);
    assert!(pyramid_core::plan(34,88).unwrap()["tasks"].as_array().unwrap().iter().any(|t|t=="gui_surgeon"));
}

#[test]
fn mislabeled_images_are_repaired_but_budget_and_corruption_still_reject() {
    use std::io::{Cursor, Read};
    let temp = tempfile::tempdir().unwrap(); let root = temp.path();
    let pixels = image::RgbImage::from_fn(16, 16, |x, y| image::Rgb([x as u8, y as u8, 201]));
    let mut bytes = Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(pixels.clone()).write_to(&mut bytes, image::ImageOutputFormat::Bmp).unwrap();
    for (name, content, expected_success) in [
        ("bmp-icon", bytes.get_ref().clone(), true),
        ("broken-icon", b"not an image".to_vec(), false),
        ("disguised-gif", {
            let mut encoded = Cursor::new(Vec::new());
            image::DynamicImage::ImageRgb8(pixels.clone()).write_to(&mut encoded, image::ImageOutputFormat::Gif).unwrap();
            encoded.into_inner()
        }, false),
        ("huge-bmp", {
            let mut huge = bytes.get_ref().clone();
            huge[18..22].copy_from_slice(&8192i32.to_le_bytes());
            huge[22..26].copy_from_slice(&8192i32.to_le_bytes());
            huge
        }, false),
    ] {
        let input = root.join(format!("{name}.zip"));
        let mut zip = zip::ZipWriter::new(fs::File::create(&input).unwrap());
        zip.start_file("pack.mcmeta", zip::write::FileOptions::default()).unwrap();
        zip.write_all(br#"{"pack":{"pack_format":34,"supported_formats":[34,75],"description":"Repair"}}"#).unwrap();
        for path in ["pack.png", "assets/test/textures/custom.png"] {
            zip.start_file(path, zip::write::FileOptions::default()).unwrap(); zip.write_all(&content).unwrap();
        }
        zip.finish().unwrap(); let original = fs::read(&input).unwrap();
        let result = pyramid_core::convert(request(root, &input, name));
        assert_eq!(result.success, expected_success, "{name}: {:?}", result.error);
        assert_eq!(fs::read(&input).unwrap(), original);
        if expected_success {
            assert_eq!(result.status, "completed");
            assert!(result.report.warnings.is_empty());
            assert_eq!(result.report.repairs.iter().filter(|r| r.code == "image_encoding_normalized").count(), 2);
            assert!(result.report.repairs.iter().any(|r| r.code == "format_range_rewritten"));
            assert_eq!(result.report.changes.as_ref().unwrap().modified, 3);
            let mut output = zip::ZipArchive::new(fs::File::open(result.output.unwrap()).unwrap()).unwrap();
            for path in ["pack.png", "assets/test/textures/custom.png"] {
                let mut image_bytes = Vec::new(); output.by_name(path).unwrap().read_to_end(&mut image_bytes).unwrap();
                assert_eq!(image::guess_format(&image_bytes).unwrap(), image::ImageFormat::Png);
                assert_eq!(image::load_from_memory(&image_bytes).unwrap().to_rgb8(), pixels);
            }
        } else {
            assert_eq!(result.status, "failed");
            assert!(result.report.repairs.is_empty());
            assert!(result.report.tasks.is_empty());
            assert!(result.output.is_none());
            if name == "huge-bmp" { assert!(result.error.unwrap().contains("16,777,216 pixels")); }
        }
    }
}
