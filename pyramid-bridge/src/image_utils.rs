use image::{imageops, GenericImage, RgbaImage};

// ── UImage 路径解析（§9.125：从 `converters/mod.rs` 迁来这里）─────────────
//
// 它**不是**旧转换器的一部分：旧转换器与原生实现的测试夹具都要用它，
// 而 `converters/mod.rs` 现在要保留成「生产模块的容器」，因此这个通用工具跟随
// 同样被两边共用的 `scale_factor` 一起移到 crate 根（§9.124 的同类处置）。

/// 通过 Tauri 资源 API 解析 UImage 路径并缓存（委托 resource_resolver）
pub fn get_uimage_path() -> Result<std::path::PathBuf, String> {
    if let Some(path) = crate::runtime::templates() {
        if path.is_dir() { return Ok(path); }
        return Err("Configured UImage directory is missing".into());
    }
    #[cfg(debug_assertions)] {
        let path=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("UImage");
        if path.is_dir() { return Ok(path); }
    }
    let parent=std::env::current_exe().ok().and_then(|p|p.parent().map(|v|v.join("UImage")));
    if let Some(path)=parent.filter(|p|p.is_dir()) { return Ok(path); }
    let path=std::path::PathBuf::from("UImage");
    if path.is_dir() { Ok(path) } else { Err("Provide templates_dir with the packaged UImage directory".into()) }
}

/// Copy `src` into `dst` at the given top-left coordinate using **raw RGBA
/// overwrites** (no alpha blending). This matches Pillow's
/// `Image.paste(src, box)` without a mask — every channel of every pixel
/// of `src` replaces the destination byte-for-byte, including the source's
/// alpha values.
///
/// Contrast this with `image::imageops::overlay`, which blends by source
/// alpha. Use this helper when the converter is *moving* a region from one
/// spot to another on the same atlas (or onto a freshly cleared canvas);
/// keep `imageops::overlay` when the intent is to layer a semi-transparent
/// decal on top of an existing pixel (matches `Image.alpha_composite`).
pub fn paste_region(dst: &mut RgbaImage, src: &RgbaImage, dx: u32, dy: u32) -> Result<(), String> {
    dst.copy_from(src, dx, dy).map_err(|e| e.to_string())
}

/// 对应 Python 的 swap_and_mirror
/// 交换两个区域，并各自进行 180 度旋转（镜像翻转）
pub fn swap_and_mirror(
    img: &mut RgbaImage,
    x1: u32, y1: u32, x2: u32, y2: u32, // 区域1和2的起点
    w: u32, h: u32,                   // 区域的宽高
) -> image::ImageResult<()> {
    // 裁剪区域 1 和 2
    let region1 = imageops::crop_imm(img, x1, y1, w, h).to_image();
    let region2 = imageops::crop_imm(img, x2, y2, w, h).to_image();

    // 进行 180 度翻转(水平+垂直)
    let flipped1 = imageops::flip_vertical(&imageops::flip_horizontal(&region1));
    let flipped2 = imageops::flip_vertical(&imageops::flip_horizontal(&region2));

    // 交换粘贴
    img.copy_from(&flipped2, x1, y1)?;
    img.copy_from(&flipped1, x2, y2)?;

    Ok(())
}
