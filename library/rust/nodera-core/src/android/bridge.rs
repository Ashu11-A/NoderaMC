//! The door between the Android front end and the core.
//!
//! # Why the phone stopped being a webview
//!
//! It was the same React bundle as the desktop, with Material 3 *imitated* in CSS and an accent
//! colour the user had to pick from five swatches — because a WebView cannot read the system
//! wallpaper. The app said so rather than pretending, which was honest and still not Material You.
//! A Compose activity can read it, so it does.
//!
//! # Two calls, and why not more
//!
//! * [`nativeStart`](Java_dev_nodera_app_NoderaCore_nativeStart) — build the core, start the shared
//!   loops, hold it for the life of the process.
//! * [`nativeInvoke`](Java_dev_nodera_app_NoderaCore_nativeInvoke) — one verb-and-JSON entry point
//!   rather than one JNI function per command. Fifty-two `extern "C"` symbols would be fifty-two
//!   chances to get a signature wrong, and a wrong JNI signature is not a compile error — it is a
//!   crash at the call site, on a device, with `NoSuchMethodError` in logcat.
//!
//! Events go the other way, pushed: Kotlin registers nothing and Rust calls `NoderaEvents.onEvent`.
//! Polling would have re-introduced exactly the cadence this codebase retired — the dashboard is
//! emitted at the moment a snapshot is accepted, so an event means "this just changed" rather than
//! "a second has passed".
//!
//! # The rule every function here obeys
//!
//! **Clear the JNI exception before returning.** ART aborts the process outright on the next JNI
//! call made from a thread with one pending, which is how tapping a link once killed this app (see
//! `browser.rs`). Every call below checks, describes and clears.

#![cfg(target_os = "android")]

use std::sync::Arc;

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::jstring;
use jni::{JNIEnv, JavaVM};

use crate::api::events::{EventSink, WorkerEvent};
use crate::api::link::{DiscoveryEdge, Sink};
use crate::api::model::Dashboard;
use crate::core::NoderaCore;

/// The Kotlin object events are pushed into.
const EVENTS_CLASS: &str = "dev/nodera/app/NoderaEvents";

/// The core, and the runtime its loops run on.
///
/// Held for the life of the process. The runtime is owned rather than borrowed from a caller
/// because there is no Tauri here to provide one, and dropping it would stop every loop.
struct Held {
    core: Arc<NoderaCore>,
    runtime: tokio::runtime::Runtime,
}

static HELD: std::sync::OnceLock<Held> = std::sync::OnceLock::new();

fn core() -> Option<&'static Arc<NoderaCore>> {
    HELD.get().map(|held| &held.core)
}

/// A sink that calls back into Kotlin.
///
/// Holds the `JavaVM` rather than an `JNIEnv`: an env belongs to one thread, and these publish from
/// tokio worker threads that Java has never seen. Attaching per call is what makes that legal.
struct JniSink {
    vm: JavaVM,
    events: GlobalRef,
    /// The same edge detector the desktop sink uses, so both shells agree what a change in tracker
    /// reachability is.
    discovery: DiscoveryEdge,
}

impl JniSink {
    fn push(&self, topic: &str, json: String) {
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return;
        };
        let (Ok(topic), Ok(json)) = (env.new_string(topic), env.new_string(json)) else {
            return;
        };
        let _ = env.call_method(
            &self.events,
            "onEvent",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&topic), JValue::Object(&json)],
        );
        // Before anything else can touch JNI on this thread. A pending exception aborts the
        // process on the next call, which on a background thread would look like a random crash
        // with no stack pointing anywhere near here.
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
    }
}

impl Sink for JniSink {
    fn publish(&self, dashboard: Dashboard) {
        if let Some(change) = self.discovery.take(&dashboard) {
            if let Ok(json) = serde_json::to_string(&change) {
                self.push("discovery", json);
            }
        }
        if let Ok(json) = serde_json::to_string(&dashboard) {
            self.push("dashboard", json);
        }
    }
}

impl EventSink for JniSink {
    fn publish(&self, event: WorkerEvent) {
        if let Ok(json) = serde_json::to_string(&event) {
            self.push("event", json);
        }
    }
}

/// Build the core and start its loops. Called once, from `NoderaCore.start(context)`.
///
/// Takes the data directory rather than the `Context` it came from. The process-wide `Context` is
/// bound separately by `NoderaBridge.initialise`, and asking Java for `filesDir` here would be a
/// second way to answer a question that already has one — which is the exact shape of the
/// `filesDir` ≠ `app_data_dir()` trap that cost the storage picker a whole release.
///
/// Nothing below may touch the filesystem until this is set: on a first run the settings handle was
/// built against a path that did not exist.
#[no_mangle]
pub extern "system" fn Java_dev_nodera_app_NoderaCore_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("NoderaMC"),
    );
    // A Rust panic on Android aborts the process with nothing in the Java crash buffer, so the app
    // "just closes" and the reason dies with it.
    std::panic::set_hook(Box::new(|info| log::error!("PANIC: {info}")));

    if HELD.get().is_some() {
        // A configuration change recreates the Activity; it must not recreate the node.
        return;
    }

    if let Ok(dir) = env.get_string(&data_dir) {
        let dir: String = dir.into();
        let path = std::path::PathBuf::from(&dir);
        let _ = std::fs::create_dir_all(&path);
        log::info!("storage: {dir}");
        crate::settings::set_android_data_dir(path);
    }

    let Ok(vm) = env.get_java_vm() else {
        log::error!("no JavaVM: the interface will receive no updates");
        return;
    };
    let events = match env.find_class(EVENTS_CLASS).and_then(|c| env.new_global_ref(c)) {
        Ok(events) => events,
        Err(e) => {
            log::error!("{EVENTS_CLASS} is missing: {e}");
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
            return;
        }
    };

    let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(e) => {
            log::error!("no async runtime: {e}");
            return;
        }
    };

    let core = Arc::new(NoderaCore::new());
    // The handle was built before the data directory was known on a first run, so re-read: without
    // this, every answer the user has ever given is invisible to this process.
    core.settings.reload();

    let sink = Arc::new(JniSink {
        vm,
        events,
        discovery: DiscoveryEdge::default(),
    });
    let _guard = runtime.enter();
    core.start_shared_loops(sink.clone(), sink.clone());

    // The worker's own log file is the Activity's source. The worker runs inside this process,
    // loaded from the APK's assets — there is nothing to supervise from here.
    let logs = Arc::clone(&core.logs);
    runtime.spawn(async move { crate::logs::tail_attach_log(logs).await });

    // Materialise validated settings, then release the second half of the two-signal startup gate.
    match crate::daemon::write_worker_properties(&core.settings.snapshot()) {
        Ok(()) => super::worker::settings_ready(),
        Err(reason) => log::error!("could not prepare Android worker properties: {reason}"),
    }

    let _ = HELD.set(Held { core, runtime });
}

/// One command, by name, with JSON in and JSON out.
///
/// Errors come back as `{"error": "..."}` rather than as a thrown exception: a Kotlin caller can
/// render a sentence, and throwing across JNI is how a background thread turns a failed refresh
/// into a dead process.
#[no_mangle]
pub extern "system" fn Java_dev_nodera_app_NoderaCore_nativeInvoke(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    args: JString,
) -> jstring {
    let name: String = env.get_string(&name).map(Into::into).unwrap_or_default();
    let args: String = env.get_string(&args).map(Into::into).unwrap_or_default();
    let answer = dispatch(&name, &args);
    match env.new_string(answer) {
        Ok(out) => out.into_raw(),
        Err(_) => JObject::null().into_raw(),
    }
}

fn error(reason: impl std::fmt::Display) -> String {
    serde_json::json!({ "error": reason.to_string() }).to_string()
}

fn ok<T: serde::Serialize>(value: T) -> String {
    serde_json::to_string(&value).unwrap_or_else(|e| error(e))
}

/// The verb table.
///
/// Deliberately explicit rather than generated: this is the list of things the phone can do, and it
/// should be possible to read it. Anything absent is absent on purpose — the launch lane, for one,
/// which is compiled out entirely because there is no Java Minecraft client on Android.
fn dispatch(name: &str, args: &str) -> String {
    let Some(core) = core() else {
        return error("the node has not started yet");
    };
    let held = HELD.get().expect("core implies held");
    let value: serde_json::Value = serde_json::from_str(args).unwrap_or(serde_json::Value::Null);
    let string = |key: &str| {
        value
            .get(key)
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_owned()
    };
    let flag = |key: &str| value.get(key).and_then(|v| v.as_bool()).unwrap_or(false);

    match name {
        "dashboard" => ok(core.snapshot()),
        "settings" => ok(core.settings()),
        "save_settings" => match serde_json::from_value(value.clone()) {
            Ok(next) => match core.save_settings(next) {
                Ok(()) => ok(serde_json::json!({})),
                Err(reason) => error(reason),
            },
            Err(e) => error(e),
        },
        "settings_fault" => ok(core.settings_fault()),
        "setting_status" => ok(core.setting_status()),
        "config_status" => ok(core.config_status()),
        "setup_state" => ok(core.setup_state()),
        "complete_setup" => match core.complete_setup(string("worlds_dir")) {
            Ok(()) => ok(serde_json::json!({})),
            Err(reason) => error(reason),
        },
        "worker_logs" => ok(core.worker_logs()),
        "pause_reason" => ok(core.pause_reason()),
        "toggle_pause" => ok(core.toggle_pause()),
        "network_state" => ok(core.network_state()),
        "battery_policy" => ok(super::battery::policy()),
        "open_battery_settings" => match super::battery::open_settings() {
            Ok(()) => ok(serde_json::json!({})),
            Err(reason) => error(reason),
        },
        "open_battery_help" => match super::battery::open_help(&crate::browser::SystemOpener) {
            Ok(()) => ok(serde_json::json!({})),
            Err(reason) => error(reason),
        },
        "open_external" => match crate::browser::open(&crate::browser::SystemOpener, &string("url")) {
            Ok(how) => ok(how),
            Err(reason) => error(reason),
        },
        "pick_storage_folder" => match super::battery::pick_folder() {
            Ok(()) => ok(serde_json::json!({})),
            Err(reason) => error(reason),
        },
        "tracker_stores" => ok(core.tracker_stores()),
        "resolved_services" => ok(core.resolved_services()),
        "official_store_url" => ok(crate::stores::OFFICIAL_STORE_URL),
        "take_pending_tracker_store" => ok(core.take_pending_tracker_store()),
        "offer_tracker_store" => {
            // The deep link records, it never acts. A page on any website can send the user here,
            // so the app holds the URL, shows it, and waits for a person to say yes.
            core.pending_store.offer(string("url"));
            ok(serde_json::json!({}))
        }
        "about" => ok(crate::api::about::about()),
        "is_mobile_build" => ok(crate::peer::is_mobile()),

        // The blocking ones. Run on the runtime rather than on the calling thread, which is the
        // Android main thread — a network round trip there is a frozen interface and, past five
        // seconds, an ANR dialog.
        "preview_tracker_store" => held
            .runtime
            .block_on(core.preview_tracker_store(string("url")))
            .map(ok)
            .unwrap_or_else(error),
        "add_tracker_store" => held
            .runtime
            .block_on(core.add_tracker_store(string("url")))
            .map(ok)
            .unwrap_or_else(error),
        "remove_tracker_store" => core
            .remove_tracker_store(string("url"))
            .map(|()| ok(serde_json::json!({})))
            .unwrap_or_else(error),
        "refresh_tracker_stores" => held
            .runtime
            .block_on(core.refresh_tracker_stores())
            .map(ok)
            .unwrap_or_else(error),
        "refresh_tracker_store" => held
            .runtime
            .block_on(core.refresh_tracker_store(string("url")))
            .map(ok)
            .unwrap_or_else(error),
        "telemetry_status" => ok(held.runtime.block_on(core.telemetry_status())),
        "set_telemetry_consent" => ok(held.runtime.block_on(core.set_telemetry_consent(flag("granted")))),
        "collected_schema" => held
            .runtime
            .block_on(core.collected_schema(string("endpoint")))
            .map(ok)
            .unwrap_or_else(error),
        "peer_status" => held
            .runtime
            .block_on(core.peer_status())
            .map(ok)
            .unwrap_or_else(error),
        "peer_self_test" => held
            .runtime
            .block_on(core.peer_self_test())
            .map(ok)
            .unwrap_or_else(error),
        "world_share_link" => ok(held.runtime.block_on(core.world_share_link(string("world_id")))),
        "browse_network" => ok(held.runtime.block_on(core.browse_network(None))),

        other => error(format!("{other} is not a verb this build answers")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_unknown_verb_answers_rather_than_panicking() {
        // The bridge is the only thing between a Compose screen and a crash. A verb the build does
        // not carry — an older APK against a newer Kotlin layer, say — has to come back as a
        // sentence, because there is nothing above this that could catch a panic.
        let answer = dispatch("no_such_verb", "{}");
        assert!(answer.contains("is not a verb"), "{answer}");
    }
}
