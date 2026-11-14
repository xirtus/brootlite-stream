use std::path::Path;

fn main() {
    println!("cargo:rustc-check-cfg=cfg(has_seed_pack)");
    println!("cargo:rerun-if-changed=seed_pack.json");
    if Path::new("seed_pack.json").is_file() {
        println!("cargo:rustc-cfg=has_seed_pack");
    }
}
