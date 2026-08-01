//! Reading a Minecraft version manifest, and turning it into a command line.
//!
//! # Why this is more than "read the JSON"
//!
//! A modded profile's `versions/<id>/<id>.json` is not self-contained. NeoForge 21.1 declares
//! `inheritsFrom: "1.21.1"` and carries only what it changes — its own libraries, its own main
//! class, and a `jvm` argument list full of module-path flags. Everything else (the asset index, the
//! client jar, the base libraries) is in the parent, and the parent may have a parent. Resolving
//! that chain *is* the work.
//!
//! # The substitution table is the part that breaks
//!
//! NeoForge's `mainClass` is `cpw.mods.bootstraplauncher.BootstrapLauncher`, and its JVM arguments
//! contain `${library_directory}`, `${classpath_separator}`, `${version_name}` and several
//! `--add-opens`. A launcher that substitutes most of them produces a JVM that dies inside
//! BootstrapLauncher with a message naming none of this. So the table here is complete and the
//! resolver **refuses** rather than passing an unsubstituted `${…}` through: an error naming the
//! placeholder is worth more than a stack trace that does not.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::Deserialize;

/// One `versions/<id>/<id>.json`, as much of it as launching needs.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct VersionManifest {
    #[serde(default)]
    pub id: String,
    #[serde(rename = "inheritsFrom", default)]
    pub inherits_from: Option<String>,
    #[serde(rename = "mainClass", default)]
    pub main_class: Option<String>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    /// Pre-1.13 manifests carry one flat string instead of `arguments.game`.
    #[serde(rename = "minecraftArguments", default)]
    pub legacy_arguments: Option<String>,
    #[serde(rename = "assetIndex", default)]
    pub asset_index: Option<AssetIndex>,
    #[serde(default)]
    pub assets: Option<String>,
    #[serde(rename = "javaVersion", default)]
    pub java_version: Option<JavaVersion>,
    #[serde(rename = "type", default)]
    pub release_type: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Argument>,
    #[serde(default)]
    pub jvm: Vec<Argument>,
}

/// Either a bare string or a `{rules, value}` object. Both appear in the same array.
#[derive(Clone, Debug, Deserialize)]
#[serde(untagged)]
pub enum Argument {
    Plain(String),
    Conditional {
        #[serde(default)]
        rules: Vec<Rule>,
        value: StringOrList,
    },
}

#[derive(Clone, Debug, Deserialize)]
#[serde(untagged)]
pub enum StringOrList {
    One(String),
    Many(Vec<String>),
}

impl StringOrList {
    fn into_vec(self) -> Vec<String> {
        match self {
            StringOrList::One(one) => vec![one],
            StringOrList::Many(many) => many,
        }
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct Rule {
    #[serde(default)]
    pub action: String,
    #[serde(default)]
    pub os: Option<OsRule>,
    /// `is_demo_user`, `has_custom_resolution`, `has_quick_plays_support`, …
    #[serde(default)]
    pub features: Option<BTreeMap<String, bool>>,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct OsRule {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub arch: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct Library {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub downloads: Option<LibraryDownloads>,
    #[serde(default)]
    pub rules: Vec<Rule>,
    /// Classifier map for native jars: `{"linux": "natives-linux"}`.
    #[serde(default)]
    pub natives: Option<BTreeMap<String, String>>,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct LibraryDownloads {
    #[serde(default)]
    pub artifact: Option<Artifact>,
    #[serde(default)]
    pub classifiers: Option<BTreeMap<String, Artifact>>,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct Artifact {
    #[serde(default)]
    pub path: String,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct AssetIndex {
    #[serde(default)]
    pub id: String,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct JavaVersion {
    #[serde(default)]
    pub component: String,
    #[serde(rename = "majorVersion", default)]
    pub major_version: u32,
}

/// This machine, in the words the manifest's rules use.
pub fn os_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    }
}

/// The classpath separator this platform's JVM expects.
pub fn classpath_separator() -> &'static str {
    if cfg!(target_os = "windows") {
        ";"
    } else {
        ":"
    }
}

/// Does this rule set allow the thing it guards?
///
/// The default when there are no rules is **allow** — most libraries have none. With rules, the last
/// matching one wins, which is how a `allow` followed by a `disallow: {os: osx}` reads.
///
/// Feature rules are matched against what this launcher actually enables. `has_quick_plays_support`
/// is the one that matters: it is the gate on the `--quickPlayMultiplayer` argument in 1.20+, and a
/// launcher that reports `false` for it will silently drop the very flag that makes Play work.
pub fn rules_allow(rules: &[Rule], features: &BTreeMap<String, bool>) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        let mut matches = true;
        if let Some(os) = &rule.os {
            if let Some(name) = &os.name {
                matches &= name == os_name();
            }
            if let Some(arch) = &os.arch {
                let here = if cfg!(target_arch = "x86") { "x86" } else { "x64" };
                matches &= arch == here;
            }
        }
        if let Some(wanted) = &rule.features {
            for (feature, want) in wanted {
                matches &= features.get(feature).copied().unwrap_or(false) == *want;
            }
        }
        if matches {
            allowed = rule.action == "allow";
        }
    }
    allowed
}

/// Read one manifest from `<root>/versions/<id>/<id>.json`.
pub fn read(root: &Path, id: &str) -> Result<VersionManifest, String> {
    let path = root.join("versions").join(id).join(format!("{id}.json"));
    let text = std::fs::read_to_string(&path)
        .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    serde_json::from_str(&text).map_err(|e| format!("{} is not a version manifest: {e}", path.display()))
}

/// Read a manifest and everything it inherits, merged child-first.
///
/// # Merge rules, and why each one is what it is
///
/// * **Scalars** (`mainClass`, `assetIndex`, `javaVersion`) — the child wins when it says anything.
///   NeoForge overrides the main class and inherits the asset index.
/// * **Libraries** — the child's come **first**, then the parent's. Order is the classpath, and a
///   loader that patches a base class has to be in front of the class it patches.
/// * **Arguments** — parent first, then child. The child's flags are the ones that must be able to
///   override, and `java` reads the later of two conflicting options.
///
/// A cycle, or a chain longer than eight, is an error rather than a hang: a `versions` directory
/// that points at itself is corrupt, and the useful answer is to say so.
pub fn resolve(root: &Path, id: &str) -> Result<VersionManifest, String> {
    let mut chain = Vec::new();
    let mut seen = Vec::new();
    let mut next = Some(id.to_owned());
    while let Some(current) = next {
        if seen.contains(&current) {
            return Err(format!("version {current} inherits from itself"));
        }
        if seen.len() >= 8 {
            return Err(format!("version {id} inherits through more than eight manifests"));
        }
        seen.push(current.clone());
        let manifest = read(root, &current)?;
        next = manifest.inherits_from.clone();
        chain.push(manifest);
    }

    // Fold parent-last so the child keeps winning.
    let mut merged = chain.pop().expect("the chain holds at least the manifest itself");
    while let Some(child) = chain.pop() {
        merged = merge(merged, child);
    }
    merged.id = id.to_owned();
    Ok(merged)
}

fn merge(parent: VersionManifest, child: VersionManifest) -> VersionManifest {
    let mut libraries = child.libraries;
    libraries.extend(parent.libraries);

    let arguments = match (parent.arguments, child.arguments) {
        (None, other) | (other, None) => other,
        (Some(p), Some(c)) => Some(Arguments {
            game: [p.game, c.game].concat(),
            jvm: [p.jvm, c.jvm].concat(),
        }),
    };

    VersionManifest {
        id: child.id,
        inherits_from: None,
        main_class: child.main_class.or(parent.main_class),
        libraries,
        arguments,
        legacy_arguments: child.legacy_arguments.or(parent.legacy_arguments),
        asset_index: child.asset_index.or(parent.asset_index),
        assets: child.assets.or(parent.assets),
        java_version: child.java_version.or(parent.java_version),
        release_type: child.release_type.or(parent.release_type),
    }
}

/// Every library jar this version puts on the classpath, in order.
///
/// Natives are excluded: they are extracted to a directory and named by `-Djava.library.path`, not
/// placed on the classpath. A library with no `downloads.artifact` is skipped rather than guessed
/// from its Maven name — a launcher that invents a path produces a `NoClassDefFoundError` naming a
/// class nobody has heard of.
pub fn classpath(
    manifest: &VersionManifest,
    root: &Path,
    features: &BTreeMap<String, bool>,
) -> Vec<PathBuf> {
    let libraries = root.join("libraries");
    let mut out = Vec::new();
    let mut seen = Vec::new();
    for library in &manifest.libraries {
        if !rules_allow(&library.rules, features) {
            continue;
        }
        if library.natives.is_some() {
            continue;
        }
        let Some(artifact) = library.downloads.as_ref().and_then(|d| d.artifact.as_ref()) else {
            continue;
        };
        if artifact.path.is_empty() || seen.contains(&artifact.path) {
            continue;
        }
        seen.push(artifact.path.clone());
        out.push(libraries.join(&artifact.path));
    }
    out.push(
        root.join("versions")
            .join(&manifest.id)
            .join(format!("{}.jar", manifest.id)),
    );
    out
}

/// The native jars to extract for this platform.
pub fn natives(
    manifest: &VersionManifest,
    root: &Path,
    features: &BTreeMap<String, bool>,
) -> Vec<PathBuf> {
    let libraries = root.join("libraries");
    let mut out = Vec::new();
    for library in &manifest.libraries {
        if !rules_allow(&library.rules, features) {
            continue;
        }
        let Some(map) = &library.natives else { continue };
        let Some(classifier) = map.get(os_name()) else {
            continue;
        };
        // `${arch}` appears in classifiers like `natives-windows-${arch}`.
        let classifier = classifier.replace("${arch}", if cfg!(target_pointer_width = "32") { "32" } else { "64" });
        if let Some(artifact) = library
            .downloads
            .as_ref()
            .and_then(|d| d.classifiers.as_ref())
            .and_then(|c| c.get(&classifier))
        {
            if !artifact.path.is_empty() {
                out.push(libraries.join(&artifact.path));
            }
        }
    }
    out
}

/// Flatten an argument list, dropping what this platform's rules disallow.
pub fn select(arguments: &[Argument], features: &BTreeMap<String, bool>) -> Vec<String> {
    let mut out = Vec::new();
    for argument in arguments {
        match argument {
            Argument::Plain(one) => out.push(one.clone()),
            Argument::Conditional { rules, value } => {
                if rules_allow(rules, features) {
                    out.extend(value.clone().into_vec());
                }
            }
        }
    }
    out
}

/// Replace every `${placeholder}`, and refuse if one is left.
///
/// The refusal is the point. A missing substitution reaches the JVM as a literal `${classpath}`,
/// which fails deep inside a bootstrap class loader with a message that names neither the launcher
/// nor the placeholder. Failing here costs one line and names both.
pub fn substitute(arguments: &[String], table: &BTreeMap<String, String>) -> Result<Vec<String>, String> {
    let mut out = Vec::with_capacity(arguments.len());
    for argument in arguments {
        let mut value = argument.clone();
        for (key, replacement) in table {
            value = value.replace(&format!("${{{key}}}"), replacement);
        }
        if let Some(start) = value.find("${") {
            let end = value[start..].find('}').map(|e| start + e + 1).unwrap_or(value.len());
            return Err(format!(
                "this launcher does not know what {} means, in `{argument}`",
                &value[start..end]
            ));
        }
        out.push(value);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(root: &Path, id: &str, body: &str) {
        let dir = root.join("versions").join(id);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(format!("{id}.json")), body).unwrap();
    }

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nodera-version-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    const PARENT: &str = r#"{
      "id": "1.21.1",
      "mainClass": "net.minecraft.client.main.Main",
      "assetIndex": { "id": "17" },
      "javaVersion": { "component": "java-runtime-delta", "majorVersion": 21 },
      "libraries": [
        { "name": "base:one:1", "downloads": { "artifact": { "path": "base/one/1/one-1.jar" } } }
      ],
      "arguments": {
        "game": ["--username", "${auth_player_name}"],
        "jvm": ["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"]
      }
    }"#;

    const CHILD: &str = r#"{
      "id": "neoforge-21.1.77",
      "inheritsFrom": "1.21.1",
      "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
      "libraries": [
        { "name": "loader:two:2", "downloads": { "artifact": { "path": "loader/two/2/two-2.jar" } } }
      ],
      "arguments": {
        "jvm": ["-p", "${library_directory}/a.jar${classpath_separator}${library_directory}/b.jar",
                "-DlegacyClassPath=${classpath}", "--add-modules", "ALL-MODULE-PATH"]
      }
    }"#;

    #[test]
    fn a_loader_inherits_the_base_version_and_overrides_only_what_it_says() {
        let root = temp("inherit");
        write(&root, "1.21.1", PARENT);
        write(&root, "neoforge-21.1.77", CHILD);

        let merged = resolve(&root, "neoforge-21.1.77").unwrap();

        // The loader's main class wins; the base game's asset index and Java requirement survive,
        // because the loader says nothing about either.
        assert_eq!(
            merged.main_class.as_deref(),
            Some("cpw.mods.bootstraplauncher.BootstrapLauncher")
        );
        assert_eq!(merged.asset_index.map(|a| a.id).as_deref(), Some("17"));
        assert_eq!(merged.java_version.unwrap().major_version, 21);
        assert_eq!(merged.id, "neoforge-21.1.77", "the launched id is the child's");
    }

    #[test]
    fn the_loaders_libraries_come_before_the_games() {
        let root = temp("order");
        write(&root, "1.21.1", PARENT);
        write(&root, "neoforge-21.1.77", CHILD);

        let merged = resolve(&root, "neoforge-21.1.77").unwrap();
        let paths = classpath(&merged, &root, &BTreeMap::new());
        let names: Vec<String> = paths
            .iter()
            .map(|p| p.file_name().unwrap().to_string_lossy().to_string())
            .collect();

        // Order IS the classpath. A loader that patches a base class has to be in front of the class
        // it patches, so this is behaviour rather than tidiness.
        assert_eq!(names[0], "two-2.jar");
        assert_eq!(names[1], "one-1.jar");
        // The version jar is last, and it is the child's id — the loader's jar, not the base game's.
        assert_eq!(names.last().unwrap(), "neoforge-21.1.77.jar");
    }

    #[test]
    fn a_placeholder_this_launcher_cannot_fill_is_named_rather_than_passed_through() {
        let mut table = BTreeMap::new();
        table.insert("classpath".to_owned(), "a.jar:b.jar".to_owned());

        let filled = substitute(&["-cp".to_owned(), "${classpath}".to_owned()], &table).unwrap();
        assert_eq!(filled, vec!["-cp", "a.jar:b.jar"]);

        // The failure mode this prevents: `${library_directory}` reaching the JVM literally, and
        // BootstrapLauncher dying with a message that names neither the launcher nor the flag.
        let error = substitute(&["-p".to_owned(), "${library_directory}/a.jar".to_owned()], &table)
            .unwrap_err();
        assert!(error.contains("${library_directory}"), "{error}");
    }

    #[test]
    fn quick_play_arguments_are_kept_only_when_the_launcher_claims_the_feature() {
        // 1.20+ guards `--quickPlayMultiplayer` behind `has_quick_plays_support`. A launcher that
        // does not report the feature silently drops the one flag that makes Play land in the world
        // rather than in the Multiplayer menu.
        let arguments: Vec<Argument> = serde_json::from_str(
            r#"[{"rules":[{"action":"allow","features":{"has_quick_plays_support":true}}],
                 "value":["--quickPlayMultiplayer","${quickPlayMultiplayer}"]}]"#,
        )
        .unwrap();

        assert!(select(&arguments, &BTreeMap::new()).is_empty());

        let mut features = BTreeMap::new();
        features.insert("has_quick_plays_support".to_owned(), true);
        assert_eq!(
            select(&arguments, &features),
            vec!["--quickPlayMultiplayer", "${quickPlayMultiplayer}"]
        );
    }

    #[test]
    fn a_demo_argument_is_dropped_because_this_launcher_never_asks_for_demo() {
        let arguments: Vec<Argument> = serde_json::from_str(
            r#"["--gameDir",{"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}]"#,
        )
        .unwrap();
        assert_eq!(select(&arguments, &BTreeMap::new()), vec!["--gameDir"]);
    }

    #[test]
    fn a_manifest_that_inherits_from_itself_is_an_error_rather_than_a_hang() {
        let root = temp("cycle");
        write(&root, "loop", r#"{"id":"loop","inheritsFrom":"loop"}"#);
        let error = resolve(&root, "loop").unwrap_err();
        assert!(error.contains("inherits from itself"), "{error}");
    }

    #[test]
    fn natives_are_never_on_the_classpath() {
        let root = temp("natives");
        write(
            &root,
            "n",
            r#"{"id":"n","libraries":[{"name":"org.lwjgl:lwjgl:3",
              "natives":{"linux":"natives-linux","windows":"natives-windows","osx":"natives-macos"},
              "downloads":{"classifiers":{"natives-linux":{"path":"lwjgl-natives-linux.jar"},
                                          "natives-windows":{"path":"lwjgl-natives-windows.jar"},
                                          "natives-macos":{"path":"lwjgl-natives-macos.jar"}}}}]}"#,
        );
        let manifest = resolve(&root, "n").unwrap();
        let features = BTreeMap::new();

        let cp = classpath(&manifest, &root, &features);
        // File names, not whole paths: the temp directory this runs in is itself named for the test.
        assert!(
            !cp.iter()
                .any(|p| p.file_name().unwrap().to_string_lossy().contains("natives")),
            "a natives jar on the classpath shadows the real library: {cp:?}"
        );
        // It is still extracted — it is just named by `-Djava.library.path` instead.
        assert_eq!(natives(&manifest, &root, &features).len(), 1);
    }

    #[test]
    fn a_library_this_platform_disallows_is_left_out() {
        let root = temp("rules");
        write(
            &root,
            "r",
            &format!(
                r#"{{"id":"r","libraries":[
                  {{"name":"a","downloads":{{"artifact":{{"path":"a.jar"}}}},
                   "rules":[{{"action":"allow","os":{{"name":"{}"}}}}]}},
                  {{"name":"b","downloads":{{"artifact":{{"path":"b.jar"}}}},
                   "rules":[{{"action":"allow","os":{{"name":"plan9"}}}}]}}]}}"#,
                os_name()
            ),
        );
        let manifest = resolve(&root, "r").unwrap();
        let names: Vec<String> = classpath(&manifest, &root, &BTreeMap::new())
            .iter()
            .map(|p| p.file_name().unwrap().to_string_lossy().to_string())
            .collect();
        assert!(names.contains(&"a.jar".to_owned()));
        assert!(!names.contains(&"b.jar".to_owned()), "{names:?}");
    }
}
