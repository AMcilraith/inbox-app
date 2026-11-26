//! Renders assets/logo.svg into assets/icon-256.png (window/in-app icon) and
//! assets/icon.ico (the .exe icon, 16–256 px).
//!
//!   cargo run --release --manifest-path tools/make-icon/Cargo.toml

use resvg::{tiny_skia, usvg};
use std::path::PathBuf;

fn main() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let assets = root.join("assets");
    let svg = std::fs::read(assets.join("logo.svg")).expect("read assets/logo.svg");
    let tree = usvg::Tree::from_data(&svg, &usvg::Options::default()).expect("parse logo.svg");

    let render = |size: u32| {
        let mut pm = tiny_skia::Pixmap::new(size, size).unwrap();
        let scale = size as f32 / tree.size().width();
        resvg::render(&tree, tiny_skia::Transform::from_scale(scale, scale), &mut pm.as_mut());
        pm
    };

    render(256).save_png(assets.join("icon-256.png")).expect("write icon-256.png");
    render(1024).save_png(assets.join("logo-1024.png")).expect("write logo-1024.png");

    let mut dir = ico::IconDir::new(ico::ResourceType::Icon);
    for size in [16, 20, 24, 32, 40, 48, 64, 128, 256] {
        let pm = render(size);
        let rgba: Vec<u8> = pm
            .pixels()
            .iter()
            .flat_map(|p| {
                let c = p.demultiply();
                [c.red(), c.green(), c.blue(), c.alpha()]
            })
            .collect();
        let img = ico::IconImage::from_rgba_data(size, size, rgba);
        dir.add_entry(ico::IconDirEntry::encode(&img).expect("encode icon"));
    }
    let f = std::fs::File::create(assets.join("icon.ico")).expect("create icon.ico");
    dir.write(f).expect("write icon.ico");
    println!("wrote assets/icon-256.png, assets/logo-1024.png, assets/icon.ico");
}
