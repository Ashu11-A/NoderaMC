//! The command line every Nodera service has, and the dispatch behind it.
//!
//! The three services take the same five flags — `--config`, `--bind`, `--healthcheck`,
//! `--print-env`, `--version`/`-h` — and each had written the parser, the `Command` enum, the usage
//! string, the dispatch `match` and the four tests for it out for itself, at a measured similarity
//! of 0.96–1.00. Nothing about a tracker makes `--config` mean something different from what it
//! means to a relay, so there is one of each here and a service supplies only what is actually its
//! own: its name, its serve function, and its healthcheck.
//!
//! A service that needs a flag of its own declares it in [`Service::extra`] — telemetry's
//! `--print-schema` is the only one — and gets [`Command::Extra`] back. The mechanism exists so the
//! shared parser never has to know about one service's feature.

use std::path::PathBuf;
use std::process::ExitCode;

/// What the command line asked for.
#[derive(Debug, PartialEq, Eq)]
pub enum Command {
    /// Run the service.
    Serve {
        /// `--config <file>`, or `None` for built-in defaults.
        config_path: Option<PathBuf>,
        /// `--bind <addr>`, which overrides both the file and the environment.
        bind_override: Option<String>,
    },
    /// `--healthcheck <addr>`: probe a running instance and exit.
    Healthcheck(String),
    /// `--version` / `-V`.
    Version,
    /// `--print-env`: list every environment variable this service reads. Names only — no values
    /// are read, so it is safe to run anywhere.
    PrintEnv,
    /// One of the service's own flags, by name. See [`Service::extra`].
    Extra(&'static str),
    /// Anything unrecognised, and `--help`. Prints usage and fails.
    Usage,
}

/// A flag one service has and the others do not, and what printing it produces.
pub type ExtraFlag = (&'static str, fn() -> String);

/// Everything the shared dispatch needs that is genuinely this service's own.
pub struct Service<'a> {
    /// The binary name, used as the prefix of every line it prints.
    pub name: &'a str,
    /// The one-line usage text.
    pub usage: &'a str,
    /// The product version, from the `NODERA_VERSION` the build script stamps.
    pub version: &'a str,
    /// The service's environment reference, for `--print-env`.
    pub env_reference: fn() -> Vec<String>,
    /// Flags only this service has. Empty for all but telemetry.
    pub extra: &'a [ExtraFlag],
}

/// Parse an argument list. Never panics and never partially applies: an unusable line is
/// [`Command::Usage`].
pub fn parse_args(args: &[String], extra: &[ExtraFlag]) -> Command {
    let mut config_path = None;
    let mut bind_override = None;
    let mut index = 0;
    while index < args.len() {
        let arg = args[index].as_str();
        if let Some((flag, _)) = extra.iter().find(|(flag, _)| *flag == arg) {
            return Command::Extra(flag);
        }
        match arg {
            "--version" | "-V" => return Command::Version,
            "--print-env" => return Command::PrintEnv,
            "--help" | "-h" => return Command::Usage,
            "--healthcheck" => {
                return args
                    .get(index + 1)
                    .map(|addr| Command::Healthcheck(addr.clone()))
                    .unwrap_or(Command::Usage);
            }
            "--config" => {
                let Some(value) = args.get(index + 1) else {
                    return Command::Usage;
                };
                config_path = Some(PathBuf::from(value));
                index += 1;
            }
            "--bind" => {
                let Some(value) = args.get(index + 1) else {
                    return Command::Usage;
                };
                bind_override = Some(value.clone());
                index += 1;
            }
            _ => return Command::Usage,
        }
        index += 1;
    }
    Command::Serve {
        config_path,
        bind_override,
    }
}

/// Parse this process' arguments and run what they asked for.
///
/// `serve` and `healthcheck` are the two halves no service can share. Everything else — printing
/// the version, the environment reference and the usage, and turning an error into an exit code
/// with the service's name in front of it — happens here.
pub async fn run<S, ServeFuture, H, HealthFuture>(
    service: Service<'_>,
    serve: S,
    healthcheck: H,
) -> ExitCode
where
    S: FnOnce(Option<PathBuf>, Option<String>) -> ServeFuture,
    ServeFuture: std::future::Future<Output = Result<(), Box<dyn std::error::Error>>>,
    H: FnOnce(String) -> HealthFuture,
    HealthFuture: std::future::Future<Output = Result<(), Box<dyn std::error::Error>>>,
{
    let args: Vec<String> = std::env::args().skip(1).collect();
    let name = service.name;
    match parse_args(&args, service.extra) {
        Command::Version => {
            println!("{name} {}", service.version);
            ExitCode::SUCCESS
        }
        Command::PrintEnv => {
            for key in (service.env_reference)() {
                println!("{key}");
            }
            ExitCode::SUCCESS
        }
        Command::Extra(flag) => {
            match service.extra.iter().find(|(name, _)| *name == flag) {
                Some((_, render)) => println!("{}", render()),
                // Unreachable by construction: `parse_args` only names flags from this same slice.
                None => return ExitCode::FAILURE,
            }
            ExitCode::SUCCESS
        }
        Command::Usage => {
            eprintln!("{}", service.usage);
            ExitCode::FAILURE
        }
        Command::Healthcheck(addr) => match healthcheck(addr.clone()).await {
            Ok(()) => {
                println!("{name}: {addr} healthy");
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("{name}: {addr} unhealthy: {e}");
                ExitCode::FAILURE
            }
        },
        Command::Serve {
            config_path,
            bind_override,
        } => match serve(config_path, bind_override).await {
            Ok(()) => ExitCode::SUCCESS,
            Err(e) => {
                eprintln!("{name}: {e}");
                ExitCode::FAILURE
            }
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(list: &[&str]) -> Vec<String> {
        list.iter().map(|a| (*a).to_owned()).collect()
    }

    #[test]
    fn version_and_help_are_recognised() {
        assert_eq!(parse_args(&args(&["--version"]), &[]), Command::Version);
        assert_eq!(parse_args(&args(&["-V"]), &[]), Command::Version);
        assert_eq!(parse_args(&args(&["-h"]), &[]), Command::Usage);
        assert_eq!(parse_args(&args(&["--print-env"]), &[]), Command::PrintEnv);
    }

    #[test]
    fn an_unknown_flag_prints_usage_rather_than_starting_a_service() {
        assert_eq!(
            parse_args(&args(&["--serve-everything"]), &[]),
            Command::Usage
        );
    }

    /// A flag missing its value must not start a service configured by whatever came next.
    #[test]
    fn a_flag_missing_its_value_is_usage_not_a_panic() {
        for flag in ["--config", "--bind", "--healthcheck"] {
            assert_eq!(parse_args(&args(&[flag]), &[]), Command::Usage, "{flag}");
        }
    }

    #[test]
    fn config_and_bind_are_parsed_together() {
        assert_eq!(
            parse_args(&args(&["--config", "t.toml", "--bind", "127.0.0.1:1"]), &[]),
            Command::Serve {
                config_path: Some(PathBuf::from("t.toml")),
                bind_override: Some("127.0.0.1:1".to_owned()),
            }
        );
    }

    #[test]
    fn an_empty_line_serves_with_defaults() {
        assert_eq!(
            parse_args(&args(&[]), &[]),
            Command::Serve {
                config_path: None,
                bind_override: None,
            }
        );
    }

    #[test]
    fn a_services_own_flag_is_recognised_and_belongs_only_to_that_service() {
        let extra: &[ExtraFlag] = &[("--print-schema", || "{}".to_owned())];
        assert_eq!(
            parse_args(&args(&["--print-schema"]), extra),
            Command::Extra("--print-schema")
        );
        // The same flag on a service that does not declare it is not silently tolerated.
        assert_eq!(parse_args(&args(&["--print-schema"]), &[]), Command::Usage);
    }
}
