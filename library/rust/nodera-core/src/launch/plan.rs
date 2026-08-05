//! Choosing which way in, and starting it.
//!
//! # The choice is made before the tunnel is asked for
//!
//! Deciding the route reads the filesystem and nothing else: which launchers exist, which instance
//! the player last used, whether the mod is in it, whether this build has a Microsoft credential.
//! Doing it first means the common failure — no installation, or one without the mod — costs the
//! player nothing, instead of opening a tunnel to somebody's host and then discovering there is
//! nothing to start.
//!
//! # Why the plan is a value
//!
//! [`Plan`] is what the interface renders *before* the button is pressed: which instance, which
//! route, and whether it will land in the world or in a menu. A launcher that only reveals its
//! choice by doing it is one the player cannot predict, and predicting it is the entire difference
//! between "Play" and "something happened".

use std::path::PathBuf;

use super::auth::{self, Account};
use super::command::{self, Command};
use super::discover::LaunchTarget;
use super::{Remedy, Tier};

/// What Play will do, decided before it is pressed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Plan {
    pub target: LaunchTarget,
    pub tier: Tier,
    /// Why this tier and not a better one. Empty when the best one was available.
    pub note: String,
}

impl Plan {
    /// Does the player land in the world without touching a menu?
    pub fn auto_connects(&self) -> bool {
        self.tier.auto_connects()
    }
}

/// Why nothing can be launched, and what to offer instead.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NoPlan {
    pub reason: String,
    pub remedy: Remedy,
}

/// Pick a route for one target, given what this machine has.
///
/// The ladder, in order, and the reason each rung is where it is:
///
/// * a third-party launcher already owns the account, the runtime and the loader, and forwards the
///   address as quick play — so it is both the best outcome and the one needing nothing from us;
/// * the direct route matches it, and needs a credential this build does not have;
/// * `servers.dat` is one click rather than none, and needs nothing;
/// * the address always works.
///
/// The two facts about the machine are **parameters**, not reads. They are a process-wide
/// environment variable and a filesystem probe, and a test that set either would race every other
/// test in the binary — the same trap `daemon::ownership` documents. Taking them in makes the
/// decision a pure function, which is the part worth asserting.
pub fn choose_with(target: &LaunchTarget, account: Option<&Account>, official: bool) -> Plan {
    if target.tier == Tier::Prism && target.launcher.is_some() && target.instance.is_some() {
        return Plan {
            target: target.clone(),
            tier: Tier::Prism,
            note: String::new(),
        };
    }

    if !account.is_some_and(auth::is_usable) {
        // Not a failure. The player gets a worse-but-working route and a sentence saying which and
        // why, rather than a button that silently does something else.
        return Plan {
            target: target.clone(),
            tier: if official {
                Tier::ServersDat
            } else {
                Tier::Address
            },
            note: auth::DIRECT_UNAVAILABLE.to_owned(),
        };
    }

    Plan {
        target: target.clone(),
        tier: Tier::Direct,
        note: String::new(),
    }
}

/// [`choose_with`], asking this machine the two questions.
pub fn choose(target: &LaunchTarget) -> Plan {
    choose_with(
        target,
        auth::account().as_ref(),
        command::official_launcher().is_some(),
    )
}

/// Pick a target and a route out of everything this machine has.
///
/// `preferred` is the player's choice when they have made one. Without it the first target wins,
/// which [`super::discover::targets`] has already ordered by "auto-connects, then most recently
/// played" — the best available guess at the thing they meant.
pub fn plan_with(
    targets: &[LaunchTarget],
    preferred: Option<&str>,
    account: Option<&Account>,
    official: bool,
) -> Result<Plan, NoPlan> {
    if targets.is_empty() {
        return Err(NoPlan {
            reason: "no Minecraft installation was found on this machine".to_owned(),
            remedy: Remedy::PickInstall,
        });
    }

    let chosen = match preferred {
        Some(selector) => {
            if let Some(target) = targets.iter().find(|target| target.id == selector) {
                target
            } else {
                // Compatibility for callers that sent display names before target ids existed.
                let mut named = targets.iter().filter(|target| target.name == selector);
                let target = named.next().ok_or(NoPlan {
                    reason: format!("there is no installation here called {selector}"),
                    remedy: Remedy::PickInstall,
                })?;
                if named.next().is_some() {
                    return Err(NoPlan {
                        reason: format!(
                            "more than one installation is called {selector}; select its target id"
                        ),
                        remedy: Remedy::PickInstall,
                    });
                }
                target
            }
        }
        None => &targets[0],
    };

    if !chosen.has_mod {
        // Refused rather than launched: a game that starts without the mod connects to nothing, and
        // the player is left looking at a working Minecraft wondering why Nodera did not do
        // anything. Naming the install is what makes the remedy actionable.
        return Err(NoPlan {
            reason: format!(
                "the Nodera mod is not installed in {} — the game would start without it",
                chosen.name
            ),
            remedy: Remedy::InstallMod,
        });
    }

    Ok(choose_with(chosen, account, official))
}

/// [`plan_with`], asking this machine the two questions.
pub fn plan(targets: &[LaunchTarget], preferred: Option<&str>) -> Result<Plan, NoPlan> {
    plan_with(
        targets,
        preferred,
        auth::account().as_ref(),
        command::official_launcher().is_some(),
    )
}

/// Everything needed to actually start the chosen route.
#[derive(Clone, Debug)]
pub struct Prepared {
    pub command: Command,
    /// The Java runtime, when this route chose one.
    pub java: Option<PathBuf>,
    /// Written before the launcher opens, for the `servers.dat` route.
    pub saved_server: Option<String>,
}

/// Turn a plan and an address into something spawnable.
///
/// `world_name` names the `servers.dat` entry.
pub fn prepare(
    plan: &Plan,
    address: &str,
    world_name: &str,
    account: Option<&Account>,
) -> Result<Prepared, NoPlan> {
    let game_dir = PathBuf::from(&plan.target.game_dir);
    match plan.tier {
        Tier::Prism => command::delegate(&plan.target, address)
            .map(|command| Prepared {
                command,
                java: None,
                saved_server: None,
            })
            .map_err(|reason| NoPlan {
                reason,
                remedy: Remedy::PickInstall,
            }),

        Tier::Direct => {
            let account = match account.filter(|account| auth::is_usable(account)) {
                Some(account) => account.clone(),
                None => {
                    return Err(NoPlan {
                        reason: auth::DIRECT_UNAVAILABLE.to_owned(),
                        remedy: Remedy::SignIn,
                    })
                }
            };
            // Metadata lives under the game root, which is not always the profile's game directory:
            // a profile can point `gameDir` at a modpack folder while `versions/` and `libraries/`
            // stay in `.minecraft`.
            let root = metadata_root(&game_dir);
            command::direct(&root, &plan.target, &account, address)
                .map(|(command, runtime)| Prepared {
                    command,
                    java: Some(runtime.path),
                    saved_server: None,
                })
                .map_err(|reason| NoPlan {
                    remedy: if reason.contains("needs Java") {
                        Remedy::InstallJava
                    } else {
                        Remedy::PickInstall
                    },
                    reason,
                })
        }

        Tier::ServersDat => {
            let entry = super::servers_dat::entry_name(world_name);
            super::servers_dat::upsert(
                &game_dir.join("servers.dat"),
                super::servers_dat::Server::new(entry.clone(), address),
            )
            .map_err(|reason| NoPlan {
                reason,
                remedy: Remedy::CopyAddress,
            })?;
            let command = command::official_launcher().ok_or(NoPlan {
                reason: "the Minecraft launcher is not on this machine".to_owned(),
                remedy: Remedy::CopyAddress,
            })?;
            Ok(Prepared {
                command,
                java: None,
                saved_server: Some(entry),
            })
        }

        Tier::Address => Err(NoPlan {
            reason: format!("open Minecraft and connect to {address}"),
            remedy: Remedy::CopyAddress,
        }),
    }
}

/// The directory holding `versions/` and `libraries/` for a profile's game directory.
///
/// A profile's `gameDir` is where saves and mods live and is frequently a modpack folder with no
/// metadata in it. The launcher keeps metadata in the root, so walk up to the first directory that
/// actually has a `versions/`, and fall back to the game directory itself.
fn metadata_root(game_dir: &std::path::Path) -> PathBuf {
    let mut candidate = Some(game_dir);
    while let Some(directory) = candidate {
        if directory.join("versions").is_dir() {
            return directory.to_path_buf();
        }
        candidate = directory.parent();
    }
    game_dir.to_path_buf()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn instance(name: &str, has_mod: bool) -> LaunchTarget {
        LaunchTarget {
            id: format!("instance:{name}"),
            name: name.to_owned(),
            tier: Tier::Prism,
            game_dir: "/tmp/instance/.minecraft".to_owned(),
            version_id: None,
            launcher: Some("prismlauncher".to_owned()),
            instance: Some(name.to_owned()),
            java: None,
            has_mod,
            last_used: 10,
        }
    }

    fn profile(name: &str, has_mod: bool) -> LaunchTarget {
        LaunchTarget {
            id: format!("profile:{name}"),
            name: name.to_owned(),
            tier: Tier::Direct,
            game_dir: "/tmp/.minecraft".to_owned(),
            version_id: Some("1.21.1".to_owned()),
            launcher: None,
            instance: None,
            java: None,
            has_mod,
            last_used: 5,
        }
    }

    fn account() -> Account {
        Account {
            name: "Ashu".to_owned(),
            uuid: "00000000-0000-0000-0000-000000000001".to_owned(),
            access_token: "token".to_owned(),
            user_type: "msa".to_owned(),
            online: true,
        }
    }

    #[test]
    fn nothing_installed_asks_the_player_to_pick_an_installation() {
        let refused = plan_with(&[], None, None, false).unwrap_err();
        assert_eq!(refused.remedy, Remedy::PickInstall);
    }

    #[test]
    fn an_installation_without_the_mod_is_refused_by_name() {
        let refused = plan_with(&[instance("Modded", false)], None, None, false).unwrap_err();
        // Launching anyway would start a working Minecraft that connects to nothing, and leave the
        // player wondering why Nodera did nothing.
        assert_eq!(refused.remedy, Remedy::InstallMod);
        assert!(refused.reason.contains("Modded"), "{}", refused.reason);
    }

    #[test]
    fn a_third_party_launcher_is_preferred_and_needs_no_credential() {
        let chosen = plan_with(&[instance("Modded", true)], None, None, false).unwrap();

        assert_eq!(chosen.tier, Tier::Prism);
        assert!(chosen.auto_connects());
        // No note: this is the best route, so there is nothing to explain.
        assert!(chosen.note.is_empty(), "{}", chosen.note);
    }

    #[test]
    fn without_a_credential_a_profile_falls_back_and_says_why() {
        let with_launcher = plan_with(&[profile("Vanilla", true)], None, None, true).unwrap();
        assert_eq!(with_launcher.tier, Tier::ServersDat);
        assert!(!with_launcher.auto_connects());
        // A worse-but-working route with a sentence, rather than a button that silently does
        // something else.
        assert!(
            with_launcher.note.contains("signed-in Microsoft account"),
            "{}",
            with_launcher.note
        );

        // With no launcher either, the floor is the address — still a plan, never a dead end.
        let bare = plan_with(&[profile("Vanilla", true)], None, None, false).unwrap();
        assert_eq!(bare.tier, Tier::Address);
    }

    #[test]
    fn a_credential_opens_the_direct_route() {
        let account = account();
        let chosen = plan_with(&[profile("Vanilla", true)], None, Some(&account), true).unwrap();
        assert_eq!(chosen.tier, Tier::Direct);
        assert!(chosen.auto_connects());
    }

    #[test]
    fn the_players_choice_wins_over_the_ordering() {
        let targets = [instance("Modded", true), profile("Vanilla", true)];
        assert_eq!(
            plan_with(&targets, None, None, false).unwrap().target.name,
            "Modded"
        );
        assert_eq!(
            plan_with(&targets, Some("Vanilla"), None, false)
                .unwrap()
                .target
                .name,
            "Vanilla"
        );

        let missing = plan_with(&targets, Some("Nope"), None, false).unwrap_err();
        assert_eq!(missing.remedy, Remedy::PickInstall);
    }

    #[test]
    fn preferred_profile_uses_stable_id_and_keeps_unique_legacy_name() {
        let mut renamed = instance("Display Name", true);
        renamed.id = "instance:stable-folder".to_owned();
        renamed.instance = Some("stable-folder".to_owned());
        let targets = [profile("Vanilla", true), renamed];

        let selected = plan_with(&targets, Some("instance:stable-folder"), None, false).unwrap();
        assert_eq!(selected.target.name, "Display Name");
        assert_eq!(selected.target.instance.as_deref(), Some("stable-folder"));
        assert_eq!(
            plan_with(&targets, Some("Display Name"), None, false)
                .unwrap()
                .target
                .id,
            "instance:stable-folder"
        );
    }

    #[test]
    fn ambiguous_legacy_profile_name_is_refused() {
        let mut first = instance("Same", true);
        first.id = "one".to_owned();
        let mut second = instance("Same", true);
        second.id = "two".to_owned();

        let refused = plan_with(&[first, second], Some("Same"), None, false).unwrap_err();
        assert_eq!(refused.remedy, Remedy::PickInstall);
        assert!(
            refused.reason.contains("more than one"),
            "{}",
            refused.reason
        );
    }

    #[test]
    fn the_address_route_is_a_refusal_that_still_tells_the_player_what_to_type() {
        let chosen = Plan {
            target: profile("Vanilla", true),
            tier: Tier::Address,
            note: String::new(),
        };
        let outcome = prepare(&chosen, "127.0.0.1:25601", "SkyRealm", None).unwrap_err();
        assert_eq!(outcome.remedy, Remedy::CopyAddress);
        // The floor still has to be usable: an error with no address in it is worse than no error.
        assert!(
            outcome.reason.contains("127.0.0.1:25601"),
            "{}",
            outcome.reason
        );
    }

    #[test]
    fn the_direct_route_without_a_credential_asks_for_a_sign_in_rather_than_starting() {
        let chosen = Plan {
            target: profile("Vanilla", true),
            tier: Tier::Direct,
            note: String::new(),
        };
        let outcome = prepare(&chosen, "127.0.0.1:25601", "SkyRealm", None).unwrap_err();
        // Before anything is started. A flow that begins and then discovers it has no credential has
        // already cost the player a device-code screen they cannot complete.
        assert_eq!(outcome.remedy, Remedy::SignIn);
    }

    #[test]
    fn metadata_is_looked_for_above_a_modpack_game_directory() {
        let root = std::env::temp_dir().join(format!("nodera-meta-{}", std::process::id()));
        let pack = root.join("packs").join("mypack");
        std::fs::create_dir_all(root.join("versions")).unwrap();
        std::fs::create_dir_all(&pack).unwrap();

        // A profile whose `gameDir` is a modpack folder still resolves its versions from the root,
        // which is where the launcher put them.
        assert_eq!(metadata_root(&pack), root);
        // And a plain install answers with itself.
        assert_eq!(metadata_root(&root), root);

        let _ = std::fs::remove_dir_all(&root);
    }
}
