//! Turning a target and an address into a command line.
//!
//! Two shapes, because the two routes are genuinely different jobs:
//!
//! * [`delegate`] — ask a launcher that already knows how. Four arguments, no metadata read, and the
//!   launcher supplies the account, the runtime, the loader and the classpath.
//! * [`direct`] — assemble it here: resolve the version chain, build the classpath, extract natives,
//!   fill every placeholder, and choose a Java runtime.
//!
//! Building the command is separated from *running* it so the interesting half is testable. What
//! goes wrong in a launcher is almost never the spawn; it is a missing `${library_directory}`, a
//! natives jar on the classpath, or a `--quickPlayMultiplayer` that was dropped because the feature
//! flag was not set. All of that is decided here, and all of it is asserted without a JVM.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use super::auth::Account;
use super::discover::LaunchTarget;
use super::{java, version};

/// A command line, ready to spawn.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Command {
    pub program: PathBuf,
    pub arguments: Vec<String>,
    pub working_dir: PathBuf,
}

impl Command {
    /// The command as one line, for the log and for a bug report.
    ///
    /// The access token is **never** in it. A launcher that logs its own command line logs a live
    /// credential, and log files travel: into bug reports, into pastebins, into screenshots.
    pub fn redacted(&self, account: &Account) -> String {
        let mut out = self.program.display().to_string();
        for argument in &self.arguments {
            let shown = if !account.access_token.is_empty()
                && account.access_token.len() > 8
                && argument.contains(&account.access_token)
            {
                "<token>".to_owned()
            } else {
                argument.clone()
            };
            out.push(' ');
            out.push_str(&shown);
        }
        out
    }
}

/// Ask a third-party launcher to start one of its instances, joining a server.
///
/// `--server` is what Prism and MultiMC both take; on 1.20+ they forward it to the game as
/// `--quickPlayMultiplayer`, which is what makes this land in the world rather than in a menu.
pub fn delegate(target: &LaunchTarget, address: &str) -> Result<Command, String> {
    let launcher = target
        .launcher
        .as_deref()
        .ok_or("this target has no launcher to ask")?;
    let instance = target
        .instance
        .as_deref()
        .ok_or("this target has no instance name to pass")?;
    Ok(Command {
        program: PathBuf::from(launcher),
        arguments: vec![
            "--launch".to_owned(),
            instance.to_owned(),
            "--server".to_owned(),
            address.to_owned(),
        ],
        working_dir: PathBuf::from(&target.game_dir),
    })
}

/// Start the official launcher, with no instructions.
///
/// It has no command-line way to join a server, so the address goes into `servers.dat` beforehand
/// and this only opens the launcher. Stated as its own function so the caller cannot mistake it for
/// something that auto-connects.
pub fn official_launcher() -> Option<Command> {
    let candidates: &[&str] = if cfg!(target_os = "windows") {
        &["minecraft-launcher.exe", "MinecraftLauncher.exe"]
    } else if cfg!(target_os = "macos") {
        &["/Applications/Minecraft.app/Contents/MacOS/launcher"]
    } else {
        &["minecraft-launcher"]
    };
    for candidate in candidates {
        let path = PathBuf::from(candidate);
        if path.is_absolute() && path.is_file() {
            return Some(Command {
                program: path,
                arguments: Vec::new(),
                working_dir: std::env::temp_dir(),
            });
        }
        if !path.is_absolute() && on_path(candidate) {
            return Some(Command {
                program: path,
                arguments: Vec::new(),
                working_dir: std::env::temp_dir(),
            });
        }
    }
    None
}

fn on_path(binary: &str) -> bool {
    std::env::var("PATH")
        .map(|path| std::env::split_paths(&path).any(|dir| dir.join(binary).is_file()))
        .unwrap_or(false)
}

/// The features this launcher claims, which decide which conditional arguments survive.
///
/// `has_quick_plays_support` is the load-bearing one: 1.20+ guards `--quickPlayMultiplayer` behind
/// it, so a launcher that does not claim it silently drops the flag that makes Play work and lands
/// the player in the Multiplayer menu with no explanation.
fn features(quick_play: bool) -> BTreeMap<String, bool> {
    BTreeMap::from([
        ("has_quick_plays_support".to_owned(), quick_play),
        ("is_demo_user".to_owned(), false),
        ("has_custom_resolution".to_owned(), false),
    ])
}

/// Where natives are extracted for one launch.
pub fn natives_dir(root: &Path, version_id: &str) -> PathBuf {
    root.join("bin").join(format!("nodera-{version_id}"))
}

/// Assemble the JVM command line for one target.
///
/// `root` is the game root the *metadata* lives under (`versions/`, `libraries/`, `assets/`), which
/// is not always the target's game directory: a profile can point `gameDir` at a modpack folder
/// while its versions and libraries stay in `.minecraft`.
pub fn direct(
    root: &Path,
    target: &LaunchTarget,
    account: &Account,
    address: &str,
) -> Result<(Command, java::Runtime), String> {
    let version_id = target
        .version_id
        .as_deref()
        .ok_or("this target does not name a version to launch")?;
    let manifest = version::resolve(root, version_id)?;

    let main_class = manifest
        .main_class
        .clone()
        .ok_or_else(|| format!("{version_id} does not name a main class"))?;

    let quick_play = supports_quick_play(&manifest);
    let features = features(quick_play);

    let classpath = version::classpath(&manifest, root, &features);
    let separator = version::classpath_separator();
    let joined = classpath
        .iter()
        .map(|p| p.display().to_string())
        .collect::<Vec<_>>()
        .join(separator);

    let natives = natives_dir(root, version_id);
    let asset_index = manifest
        .asset_index
        .as_ref()
        .map(|a| a.id.clone())
        .or_else(|| manifest.assets.clone())
        .unwrap_or_else(|| "legacy".to_owned());

    let required = manifest
        .java_version
        .as_ref()
        .map(|j| j.major_version)
        .unwrap_or(8);
    let component = manifest
        .java_version
        .as_ref()
        .map(|j| j.component.clone())
        .unwrap_or_default();
    let runtime = java::select(
        root,
        target.java.as_deref().map(Path::new),
        &component,
        required,
    )?;

    let game_dir = PathBuf::from(&target.game_dir);
    let table: BTreeMap<String, String> = BTreeMap::from([
        ("classpath".to_owned(), joined),
        ("classpath_separator".to_owned(), separator.to_owned()),
        (
            "library_directory".to_owned(),
            root.join("libraries").display().to_string(),
        ),
        ("version_name".to_owned(), version_id.to_owned()),
        (
            "natives_directory".to_owned(),
            natives.display().to_string(),
        ),
        ("launcher_name".to_owned(), "nodera".to_owned()),
        (
            "launcher_version".to_owned(),
            env!("CARGO_PKG_VERSION").to_owned(),
        ),
        ("game_directory".to_owned(), game_dir.display().to_string()),
        (
            "assets_root".to_owned(),
            root.join("assets").display().to_string(),
        ),
        (
            "game_assets".to_owned(),
            root.join("assets")
                .join("virtual")
                .join("legacy")
                .display()
                .to_string(),
        ),
        ("assets_index_name".to_owned(), asset_index),
        ("auth_player_name".to_owned(), account.name.clone()),
        ("auth_uuid".to_owned(), account.uuid.clone()),
        ("auth_access_token".to_owned(), account.access_token.clone()),
        ("auth_session".to_owned(), account.access_token.clone()),
        ("clientid".to_owned(), String::new()),
        ("auth_xuid".to_owned(), String::new()),
        ("user_type".to_owned(), account.user_type.clone()),
        (
            "version_type".to_owned(),
            manifest
                .release_type
                .clone()
                .unwrap_or_else(|| "release".to_owned()),
        ),
        ("resolution_width".to_owned(), "854".to_owned()),
        ("resolution_height".to_owned(), "480".to_owned()),
        ("quickPlayPath".to_owned(), String::new()),
        ("quickPlaySingleplayer".to_owned(), String::new()),
        ("quickPlayRealms".to_owned(), String::new()),
        ("quickPlayMultiplayer".to_owned(), address.to_owned()),
    ]);

    let arguments = manifest.arguments.clone().unwrap_or_default();
    let mut jvm = version::select(&arguments.jvm, &features);
    if jvm.is_empty() {
        // Pre-1.13 manifests carry no `arguments.jvm` at all; the launcher is expected to supply
        // these two itself, and a command line without them starts a JVM with no classpath.
        jvm = vec![
            "-Djava.library.path=${natives_directory}".to_owned(),
            "-cp".to_owned(),
            "${classpath}".to_owned(),
        ];
    }
    let mut out = version::substitute(&jvm, &table)?;
    out.push(main_class);

    let game = if let Some(legacy) = &manifest.legacy_arguments {
        legacy.split_whitespace().map(str::to_owned).collect()
    } else {
        version::select(&arguments.game, &features)
    };
    out.extend(version::substitute(&game, &table)?);

    if !quick_play {
        // Pre-1.20 has no quick play, so the address goes in as the old two-flag pair. Without this
        // the game would start correctly and simply not join anything.
        let (host, port) = address.rsplit_once(':').unwrap_or((address, "25565"));
        out.push("--server".to_owned());
        out.push(host.to_owned());
        out.push("--port".to_owned());
        out.push(port.to_owned());
    }

    Ok((
        Command {
            program: runtime.path.clone(),
            arguments: out,
            working_dir: game_dir,
        },
        runtime,
    ))
}

/// Does this version understand `--quickPlayMultiplayer`?
///
/// Read from the manifest rather than compared against a version number: the flag arrived in 23w14a
/// and the argument list is the only place that says whether *this* build has it. A version-string
/// comparison would have to understand snapshots, which are not ordered by their names.
fn supports_quick_play(manifest: &version::VersionManifest) -> bool {
    let Some(arguments) = &manifest.arguments else {
        return false;
    };
    arguments.game.iter().any(|argument| match argument {
        version::Argument::Plain(one) => one.contains("quickPlay"),
        version::Argument::Conditional { rules, value } => {
            let mentions = match value {
                version::StringOrList::One(one) => one.contains("quickPlay"),
                version::StringOrList::Many(many) => many.iter().any(|v| v.contains("quickPlay")),
            };
            mentions
                || rules.iter().any(|rule| {
                    rule.features
                        .as_ref()
                        .map(|f| f.contains_key("has_quick_plays_support"))
                        .unwrap_or(false)
                })
        }
    })
}

#[cfg(test)]
mod tests {
    use super::super::Tier;
    use super::*;

    fn write(root: &Path, id: &str, body: &str) {
        let dir = root.join("versions").join(id);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(format!("{id}.json")), body).unwrap();
    }

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nodera-cmd-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn target(root: &Path, version: &str) -> LaunchTarget {
        LaunchTarget {
            name: "Modded".to_owned(),
            tier: Tier::Direct,
            game_dir: root.display().to_string(),
            version_id: Some(version.to_owned()),
            launcher: None,
            instance: None,
            java: None,
            has_mod: true,
            last_used: 0,
        }
    }

    const MODERN: &str = r#"{
      "id": "1.21.1",
      "type": "release",
      "mainClass": "net.minecraft.client.main.Main",
      "assetIndex": { "id": "17" },
      "javaVersion": { "component": "java-runtime-delta", "majorVersion": 8 },
      "libraries": [{ "name": "a", "downloads": { "artifact": { "path": "a/a.jar" } } }],
      "arguments": {
        "game": ["--username", "${auth_player_name}", "--uuid", "${auth_uuid}",
                 "--accessToken", "${auth_access_token}", "--userType", "${user_type}",
                 {"rules":[{"action":"allow","features":{"has_quick_plays_support":true}}],
                  "value":["--quickPlayMultiplayer","${quickPlayMultiplayer}"]}],
        "jvm": ["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"]
      }
    }"#;

    #[test]
    fn a_delegated_launch_asks_for_the_instance_and_the_server() {
        let mut t = target(Path::new("/tmp"), "x");
        t.tier = Tier::Prism;
        t.launcher = Some("prismlauncher".to_owned());
        t.instance = Some("modded-1211".to_owned());

        let command = delegate(&t, "127.0.0.1:25601").unwrap();
        assert_eq!(
            command.arguments,
            vec!["--launch", "modded-1211", "--server", "127.0.0.1:25601"]
        );
    }

    #[test]
    fn a_modern_version_joins_through_quick_play() {
        let root = temp("quickplay");
        write(&root, "1.21.1", MODERN);
        let account = Account::offline("Ashu");

        let (command, _) = direct(&root, &target(&root, "1.21.1"), &account, "127.0.0.1:25601")
            .expect("a command line");

        let line = command.arguments.join(" ");
        assert!(
            line.contains("--quickPlayMultiplayer 127.0.0.1:25601"),
            "{line}"
        );
        // The old pair must NOT also be present: passing both makes the game join twice, and the
        // second attempt lands on a port the first one is already using.
        assert!(!line.contains("--server "), "{line}");
        assert!(line.contains("--username Ashu"));
        assert!(line.contains("net.minecraft.client.main.Main"));
    }

    #[test]
    fn a_version_without_quick_play_falls_back_to_server_and_port() {
        let root = temp("legacy");
        write(
            &root,
            "1.12.2",
            r#"{"id":"1.12.2","mainClass":"net.minecraft.client.main.Main",
                "javaVersion":{"component":"jre-legacy","majorVersion":8},
                "minecraftArguments":"--username ${auth_player_name} --accessToken ${auth_access_token}"}"#,
        );
        let account = Account::offline("Ashu");

        let (command, _) = direct(&root, &target(&root, "1.12.2"), &account, "127.0.0.1:25601")
            .expect("a command");
        let line = command.arguments.join(" ");

        // Quick play arrived in 23w14a. Without this branch the game would start correctly and
        // simply not join anything, which reads as "Play did nothing".
        assert!(line.contains("--server 127.0.0.1 --port 25601"), "{line}");
        // A pre-1.13 manifest has no `arguments.jvm`, so the launcher must supply the classpath
        // itself — otherwise the JVM starts with none.
        assert!(line.contains("-cp "), "{line}");
    }

    #[test]
    fn a_neoforge_module_path_is_filled_or_the_launch_is_refused() {
        let root = temp("neoforge");
        write(&root, "1.21.1", MODERN);
        write(
            &root,
            "neoforge-21.1.77",
            r#"{"id":"neoforge-21.1.77","inheritsFrom":"1.21.1",
                "mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
                "arguments":{"jvm":["-p","${library_directory}/x.jar${classpath_separator}${library_directory}/y.jar",
                                    "-DlegacyClassPath=${classpath}","--add-modules","ALL-MODULE-PATH"]}}"#,
        );
        let account = Account::offline("Ashu");

        let (command, _) = direct(
            &root,
            &target(&root, "neoforge-21.1.77"),
            &account,
            "127.0.0.1:25601",
        )
        .expect("a command line");
        let line = command.arguments.join(" ");

        // The failure this prevents: a literal `${library_directory}` reaching the JVM, and
        // BootstrapLauncher dying with a message that names neither the launcher nor the flag.
        assert!(!line.contains("${"), "{line}");
        assert!(line.contains("--add-modules ALL-MODULE-PATH"), "{line}");
        assert!(
            line.contains("cpw.mods.bootstraplauncher.BootstrapLauncher"),
            "{line}"
        );
        assert!(
            line.contains(&root.join("libraries").join("x.jar").display().to_string()),
            "{line}"
        );
    }

    #[test]
    fn the_access_token_is_never_in_a_line_that_might_be_logged() {
        let account = Account {
            name: "Ashu".to_owned(),
            uuid: "u".to_owned(),
            access_token: "a-very-real-looking-token".to_owned(),
            user_type: "msa".to_owned(),
            online: true,
        };
        let command = Command {
            program: PathBuf::from("java"),
            arguments: vec![
                "--accessToken".to_owned(),
                "a-very-real-looking-token".to_owned(),
            ],
            working_dir: PathBuf::from("/tmp"),
        };

        // Log files travel: into bug reports, into pastebins, into screenshots.
        let line = command.redacted(&account);
        assert!(!line.contains("a-very-real-looking-token"), "{line}");
        assert!(line.contains("<token>"), "{line}");
    }

    #[test]
    fn a_target_with_no_version_is_refused_by_name() {
        let root = temp("noversion");
        let mut t = target(&root, "x");
        t.version_id = None;
        let error = direct(&root, &t, &Account::offline("Ashu"), "127.0.0.1:1").unwrap_err();
        assert!(error.contains("does not name a version"), "{error}");
    }
}
