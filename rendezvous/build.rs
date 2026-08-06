//! Stamp the product version into this binary. See `nodera-build`, which all three services share.

fn main() {
    nodera_build::stamp_version();
}
