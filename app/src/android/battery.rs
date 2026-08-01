//! Battery restrictions — whether Android is allowed to stop this node, and how to say no.
//!
//! # Why a peer-to-peer app has to care
//!
//! Every Android vendor ships an aggressive power manager, and several of them kill background
//! work regardless of what the app is doing (the community catalogue of who does what is
//! <https://dontkillmyapp.com>). For an ordinary app that means a delayed notification. For a peer
//! it means the node other people are downloading a world from disappears mid-transfer, and the
//! user has no way to know why — their phone looked fine.
//!
//! So the app **detects** the state and **explains** it. It does not silently request an exemption:
//! asking for one is a decision with real battery consequences, and it belongs to the person whose
//! phone it is. What this module offers is the truth and a route to the setting.
//!
//! # How the exemption is offered
//!
//! By opening the system's battery-optimisation list
//! (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), not by firing the direct
//! `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog. The direct request needs a permission that app
//! stores restrict to apps whose core function genuinely requires it, and the outcome is the same
//! screen the user would reach anyway — with the difference that they can see what they are
//! changing.

use serde::{Deserialize, Serialize};

/// What the OS is currently allowing.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct BatteryPolicy {
    /// Whether this platform has a battery-restriction concept at all. `false` on desktop, and the
    /// UI hides the whole subject rather than showing a control that does nothing.
    pub supported: bool,
    /// Whether the OS may currently stop this app in the background.
    ///
    /// `true` means restricted — the node can be killed. Named for the condition the user cares
    /// about rather than for the API's inverted `isIgnoringBatteryOptimizations`.
    pub restricted: bool,
    /// The device vendor, used to pick the right page of advice.
    pub manufacturer: String,
    /// Vendor-specific guidance on <https://dontkillmyapp.com>.
    pub help_url: String,
    /// Why the state could not be read, when it could not. Empty otherwise.
    pub error: String,
}

/// Read the current policy.
#[cfg(target_os = "android")]
pub fn policy() -> BatteryPolicy {
    let manufacturer = property("ro.product.manufacturer");
    let mut out = BatteryPolicy {
        supported: true,
        restricted: false,
        help_url: help_url(&manufacturer),
        manufacturer,
        error: String::new(),
    };
    match is_ignoring_optimisations() {
        Ok(ignoring) => out.restricted = !ignoring,
        Err(reason) => {
            // Reported, never assumed. Claiming "unrestricted" on a failed read would tell the user
            // their node is safe when nobody checked.
            out.error = reason;
        }
    }
    out
}

/// Desktop has no such concept; say so rather than inventing an answer.
#[cfg(not(target_os = "android"))]
pub fn policy() -> BatteryPolicy {
    BatteryPolicy {
        supported: false,
        ..BatteryPolicy::default()
    }
}

/// Open the exemption prompt **for this app**.
///
/// Three targets are tried in order, because vendors differ in which ones they honour and landing
/// on a list of every installed app is a poor substitute for the one screen the user wants:
///
///  1. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with a `package:` URI — the direct yes/no dialog for
///     this app. Needs the matching permission, which the build declares.
///  2. `APPLICATION_DETAILS_SETTINGS` — this app's own settings page, from which battery is one tap.
///  3. The global battery-optimisation list, as a last resort.
#[cfg(target_os = "android")]
pub fn open_settings() -> Result<(), String> {
    let package = platform::package_name()?;
    let target = format!("package:{package}");
    open_intent_action(
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        Some(&target),
    )
    .or_else(|_| {
        open_intent_action(
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            Some(&target),
        )
    })
    .or_else(|_| {
        open_intent_action(
            "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            None,
        )
    })
}

#[cfg(not(target_os = "android"))]
pub fn open_settings() -> Result<(), String> {
    Err("battery optimisation is an Android setting".to_owned())
}

/// Open the vendor's page on dontkillmyapp.com.
///
/// Through [`crate::browser`] rather than a bare `ACTION_VIEW`, which is what this used to be: it is
/// an ordinary web link and gets the same ladder as every other one — a Custom Tab first, the
/// browser next, an in-app WebView on a device with neither. The URL is validated on the way, which
/// costs nothing here (it is composed from a whitelist-shaped slug) and means there is one door out
/// of this app instead of two with different rules.
///
/// No longer Android-only. The vendor page for an unknown manufacturer is the site's index, which is
/// a perfectly good thing to open on a desktop — refusing was an artefact of `ACTION_VIEW` being the
/// only implementation, not a decision about what the button should do.
#[cfg(target_os = "android")]
pub fn open_help(app: &tauri::AppHandle) -> Result<(), String> {
    let url = help_url(&property("ro.product.manufacturer"));
    crate::browser::open(app, &url).map(|_| ())
}

#[cfg(not(target_os = "android"))]
pub fn open_help(app: &tauri::AppHandle) -> Result<(), String> {
    crate::browser::open(app, &help_url("")).map(|_| ())
}

/// The vendor page, or the site's index when the vendor is unknown.
// Only the `target_os = "android"` entry points call this; on desktop it is reachable from the
// tests alone, and dead code there is not a defect.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn help_url(manufacturer: &str) -> String {
    let slug: String = manufacturer
        .to_lowercase()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect();
    if slug.is_empty() {
        "https://dontkillmyapp.com".to_owned()
    } else {
        format!("https://dontkillmyapp.com/{slug}")
    }
}

#[cfg(target_os = "android")]
pub(crate) mod platform {
    use jni::objects::{JObject, JString, JValue};
    use jni::JavaVM;

    /// Run `body` with a JNI environment and this app's `Context`.
    ///
    /// Both come from `ndk_context`, which the Android activity populates before any Rust runs. The
    /// environment is attached to the calling thread and detached when the guard drops, so this is
    /// safe to call from a Tauri command thread.
    pub fn with_context<T>(
        body: impl FnOnce(&mut jni::JNIEnv, &JObject) -> Result<T, String>,
    ) -> Result<T, String> {
        // `ndk_context::android_context()` PANICS when nothing has initialised it, and this runs
        // behind an `extern "C"` boundary where a panic aborts the process rather than unwinding —
        // which is exactly how the first version of this file killed the app on launch:
        //
        //   PANIC: android context was not initialized
        //   PANIC: panic in a function that cannot unwind
        //
        // Tauri does not use `ndk-glue`, so nothing populates that global unless we do it
        // ourselves (see `Java_dev_nodera_app_NoderaBridge_initialise`). Guarding the read is not
        // belt-and-braces: it is the difference between a feature that is unavailable and an app
        // that will not open.
        let ctx = std::panic::catch_unwind(ndk_context::android_context)
            .map_err(|_| "the Android context is not available to this process".to_owned())?;
        if ctx.vm().is_null() || ctx.context().is_null() {
            return Err("the Android context is not available to this process".to_owned());
        }
        // SAFETY: the pointers were handed to us by the Android runtime through
        // `NoderaBridge.initialise`, are valid for the life of the process, and are only used to
        // attach to the already-running VM.
        let vm =
            unsafe { JavaVM::from_raw(ctx.vm().cast()) }.map_err(|e| format!("no Java VM: {e}"))?;
        let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
        let mut env = vm
            .attach_current_thread()
            .map_err(|e| format!("could not attach to the VM: {e}"))?;
        body(&mut env, &activity)
    }

    /// `PowerManager.isIgnoringBatteryOptimizations(packageName)`.
    pub fn is_ignoring_optimisations() -> Result<bool, String> {
        with_context(|env, activity| {
            let service = env
                .new_string("power")
                .map_err(|e| format!("string: {e}"))?;
            let manager = env
                .call_method(
                    activity,
                    "getSystemService",
                    "(Ljava/lang/String;)Ljava/lang/Object;",
                    &[JValue::Object(&service)],
                )
                .and_then(|v| v.l())
                .map_err(|e| format!("getSystemService(power): {e}"))?;
            if manager.is_null() {
                return Err("this device has no PowerManager".to_owned());
            }
            let package: JString = env
                .call_method(activity, "getPackageName", "()Ljava/lang/String;", &[])
                .and_then(|v| v.l())
                .map_err(|e| format!("getPackageName: {e}"))?
                .into();
            env.call_method(
                &manager,
                "isIgnoringBatteryOptimizations",
                "(Ljava/lang/String;)Z",
                &[JValue::Object(&package)],
            )
            .and_then(|v| v.z())
            .map_err(|e| format!("isIgnoringBatteryOptimizations: {e}"))
        })
    }

    /// `startActivity(new Intent(action[, Uri.parse(data)]).addFlags(NEW_TASK))`.
    pub fn open_intent_action(action: &str, data: Option<&str>) -> Result<(), String> {
        with_context(|env, activity| {
            let action_string = env.new_string(action).map_err(|e| format!("string: {e}"))?;
            let intent = match data {
                Some(uri) => {
                    let uri_string = env.new_string(uri).map_err(|e| format!("string: {e}"))?;
                    let parsed = env
                        .call_static_method(
                            "android/net/Uri",
                            "parse",
                            "(Ljava/lang/String;)Landroid/net/Uri;",
                            &[JValue::Object(&uri_string)],
                        )
                        .and_then(|v| v.l())
                        .map_err(|e| format!("Uri.parse: {e}"))?;
                    env.new_object(
                        "android/content/Intent",
                        "(Ljava/lang/String;Landroid/net/Uri;)V",
                        &[JValue::Object(&action_string), JValue::Object(&parsed)],
                    )
                }
                None => env.new_object(
                    "android/content/Intent",
                    "(Ljava/lang/String;)V",
                    &[JValue::Object(&action_string)],
                ),
            }
            .map_err(|e| format!("new Intent: {e}"))?;

            // FLAG_ACTIVITY_NEW_TASK. Required because the call originates from a non-UI thread.
            env.call_method(
                &intent,
                "addFlags",
                "(I)Landroid/content/Intent;",
                &[JValue::Int(0x1000_0000)],
            )
            .map_err(|e| format!("addFlags: {e}"))?;

            env.call_method(
                activity,
                "startActivity",
                "(Landroid/content/Intent;)V",
                &[JValue::Object(&intent)],
            )
            .map_err(|e| format!("startActivity: {e}"))?;
            Ok(())
        })
    }

    /// This app's package name — the `package:` target every per-app settings screen needs.
    pub fn package_name() -> Result<String, String> {
        with_context(|env, activity| {
            let name: jni::objects::JString = env
                .call_method(activity, "getPackageName", "()Ljava/lang/String;", &[])
                .and_then(|v| v.l())
                .map_err(|e| format!("getPackageName: {e}"))?
                .into();
            // Bound to a local before the block ends: the `JavaStr` borrows `name`, and returning
            // the conversion inline keeps that temporary alive past `name`'s scope.
            let package: String = env
                .get_string(&name)
                .map_err(|e| format!("package name: {e}"))?
                .into();
            Ok(package)
        })
    }

    /// Every app-specific directory on external volumes (`Context.getExternalFilesDirs`).
    ///
    /// This is the only way to find an SD card that an app may write to without the Storage Access
    /// Framework — and SAF hands back `content://` URIs, which the Java worker cannot open as
    /// files. Enumerating the real ones is therefore not a shortcut; it is the whole set of places
    /// that actually work.
    pub fn external_files_dirs() -> Vec<String> {
        with_context(|env, activity| {
            let null_type = jni::objects::JObject::null();
            let array = env
                .call_method(
                    activity,
                    "getExternalFilesDirs",
                    "(Ljava/lang/String;)[Ljava/io/File;",
                    &[jni::objects::JValue::Object(&null_type)],
                )
                .and_then(|v| v.l())
                .map_err(|e| format!("getExternalFilesDirs: {e}"))?;
            let array: jni::objects::JObjectArray = array.into();
            let count = env.get_array_length(&array).unwrap_or(0);
            let mut out = Vec::new();
            for index in 0..count {
                let Ok(file) = env.get_object_array_element(&array, index) else {
                    continue;
                };
                if file.is_null() {
                    continue; // an unmounted card is a null slot
                }
                let Ok(path) = env
                    .call_method(&file, "getAbsolutePath", "()Ljava/lang/String;", &[])
                    .and_then(|v| v.l())
                else {
                    continue;
                };
                if let Ok(text) = env.get_string(&path.into()) {
                    out.push(text.into());
                }
            }
            Ok(out)
        })
        .unwrap_or_default()
    }

    /// One `android.os.SystemProperties`-style value, read through `Build`.
    pub fn build_field(field: &str) -> String {
        with_context(|env, _| {
            let value: jni::objects::JString = env
                .get_static_field("android/os/Build", field, "Ljava/lang/String;")
                .and_then(|v| v.l())
                .map_err(|e| format!("Build.{field}: {e}"))?
                .into();
            let text: String = env
                .get_string(&value)
                .map_err(|e| format!("Build.{field} string: {e}"))?
                .into();
            Ok(text)
        })
        .unwrap_or_default()
    }
}

/// Receive the Android context from Kotlin, once, at startup.
///
/// Called by `NoderaBridge.initialise(applicationContext)`. It exists because Tauri does not use
/// `ndk-glue`, so the process-wide handle every `jni` call needs is simply absent until somebody
/// provides it — and only Java can.
///
/// # Safety
/// Invoked by the JVM through JNI with a valid environment and a live `Context`. The context is
/// promoted to a global reference so it outlives this call, which is what makes the cached pointer
/// safe to use later from other threads.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_dev_nodera_app_NoderaBridge_initialise(
    env: jni::JNIEnv,
    class: jni::objects::JClass,
    context: jni::objects::JObject,
) {
    // Nothing here may panic across the JNI boundary, so every step is checked and the failure path
    // is a log line.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let vm = env.get_java_vm().map_err(|e| e.to_string())?;
        // Preserve the class supplied by Java. Resolving app classes by name from a Rust-created
        // thread uses Android's bootstrap class loader and can fail even though the class exists.
        let bridge = env.new_global_ref(&class).map_err(|e| e.to_string())?;
        let global = env.new_global_ref(&context).map_err(|e| e.to_string())?;
        // Deliberately leaked: the context must live as long as the process, and dropping the
        // global ref would invalidate the pointer handed to `ndk_context`.
        let global = Box::leak(Box::new(global));
        unsafe {
            ndk_context::initialize_android_context(
                vm.get_java_vm_pointer().cast(),
                global.as_obj().as_raw().cast(),
            );
        }
        Ok::<jni::objects::GlobalRef, String>(bridge)
    }));
    match result {
        Ok(Ok(bridge)) => {
            log::info!("android: context bound for battery checks");
            crate::android::worker::context_ready(bridge);
        }
        Ok(Err(reason)) => log::error!("android: could not bind the context: {reason}"),
        Err(_) => log::error!("android: binding the context panicked"),
    }
}

/// Open the system folder picker (`ACTION_OPEN_DOCUMENT_TREE`), via Kotlin.
///
/// The result is an activity result, which only an activity can receive, so `NoderaStorage.kt`
/// handles it and writes the outcome where [`crate::api::storage::picked_folder`] reads it.
#[cfg(target_os = "android")]
pub fn pick_folder() -> Result<(), String> {
    platform::with_context(|env, _context| {
        // No argument: the Kotlin side holds the activity, because the context this process caches
        // is the *application* context and only an activity can start something for a result.
        let call = env.call_static_method("dev/nodera/app/NoderaStorage", "pick", "()V", &[]);
        // A pending Java exception must be cleared, or every later JNI call in this thread fails
        // with the same stale error.
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
            return Err("the folder picker could not be opened".to_owned());
        }
        call.map(|_| ())
            .map_err(|e| format!("could not open the folder picker: {e}"))
    })
}

#[cfg(not(target_os = "android"))]
pub fn pick_folder() -> Result<(), String> {
    Err("the system folder picker is an Android feature".to_owned())
}

/// Every app-writable directory on external volumes. Empty on desktop.
#[cfg(target_os = "android")]
pub fn external_storage_dirs() -> Vec<String> {
    platform::external_files_dirs()
}

/// Desktop has no such concept.
#[cfg(not(target_os = "android"))]
pub fn external_storage_dirs() -> Vec<String> {
    Vec::new()
}

#[cfg(target_os = "android")]
use platform::{is_ignoring_optimisations, open_intent_action};

/// Run a body with a JNI environment and this app's `Context`.
///
/// Re-exported so [`crate::android::network`] can reach the framework through the *same* guarded
/// entry point — including the `catch_unwind` around `ndk_context`, which is the difference between
/// a feature being unavailable and the app aborting on launch.
#[cfg(target_os = "android")]
pub use platform::with_context as with_android_context;

/// The device vendor. Reads `Build.MANUFACTURER`; the argument names the intent, not the mechanism.
#[cfg(target_os = "android")]
fn property(_name: &str) -> String {
    platform::build_field("MANUFACTURER")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_help_url_is_vendor_specific_and_always_valid() {
        assert_eq!(help_url("Xiaomi"), "https://dontkillmyapp.com/xiaomi");
        assert_eq!(help_url("OnePlus"), "https://dontkillmyapp.com/oneplus");
        // Punctuation and spacing vary by vendor string; the slug must stay a usable path segment.
        assert_eq!(
            help_url("Sony Ericsson"),
            "https://dontkillmyapp.com/sonyericsson"
        );
        // No vendor is not a broken link.
        assert_eq!(help_url(""), "https://dontkillmyapp.com");
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn desktop_reports_the_subject_as_not_applicable() {
        let policy = policy();

        // Not "unrestricted" — a desktop has no such setting, and the UI keys off `supported` to
        // hide the whole section rather than showing a green tick for something that does not exist.
        assert!(!policy.supported);
        assert!(!policy.restricted);
    }
}
