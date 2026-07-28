//! Deterministic Android worker startup.
//!
//! Activity creation and Tauri's Rust setup hook run on independent lifecycle paths. Neither may
//! infer that the other has completed from `super.onCreate()` returning. This gate starts the Java
//! worker exactly once, and only after both the Android context and the persisted property handoff
//! are ready.

#[cfg(any(target_os = "android", test))]
#[derive(Default)]
struct StartupGate {
    context_ready: bool,
    settings_ready: bool,
    start_claimed: bool,
}

#[cfg(any(target_os = "android", test))]
#[derive(Clone, Copy)]
enum Signal {
    ContextReady,
    SettingsReady,
}

#[cfg(any(target_os = "android", test))]
impl StartupGate {
    fn signal(&mut self, signal: Signal) -> bool {
        match signal {
            Signal::ContextReady => self.context_ready = true,
            Signal::SettingsReady => self.settings_ready = true,
        }
        if self.context_ready && self.settings_ready && !self.start_claimed {
            self.start_claimed = true;
            true
        } else {
            false
        }
    }
}

#[cfg(target_os = "android")]
static STARTUP: std::sync::Mutex<StartupGate> = std::sync::Mutex::new(StartupGate {
    context_ready: false,
    settings_ready: false,
    start_claimed: false,
});

#[cfg(target_os = "android")]
static BRIDGE_CLASS: std::sync::OnceLock<jni::objects::GlobalRef> = std::sync::OnceLock::new();

/// Signal that `NoderaBridge.initialise` has installed a process-lifetime Android context.
#[cfg(target_os = "android")]
pub fn context_ready(bridge_class: jni::objects::GlobalRef) {
    let _ = BRIDGE_CLASS.set(bridge_class);
    signal(Signal::ContextReady);
}

/// Signal that validated settings have been written for Kotlin to apply as Java properties.
#[cfg(target_os = "android")]
pub fn settings_ready() {
    signal(Signal::SettingsReady);
}

#[cfg(target_os = "android")]
fn signal(signal: Signal) {
    let should_start = STARTUP
        .lock()
        .map(|mut gate| gate.signal(signal))
        .unwrap_or(false);
    if should_start {
        start_worker();
    }
}

#[cfg(target_os = "android")]
fn start_worker() {
    let Some(bridge_class) = BRIDGE_CLASS.get() else {
        log::error!("worker: Android bridge class is unavailable");
        return;
    };
    let result = crate::android::battery::with_android_context(|env, context| {
        let call = env.call_static_method(
            bridge_class,
            "startWorker",
            "(Landroid/content/Context;)V",
            &[jni::objects::JValue::Object(context)],
        );
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
            return Err("NoderaBridge.startWorker raised a Java exception".to_owned());
        }
        call.map(|_| ())
            .map_err(|e| format!("could not call NoderaBridge.startWorker: {e}"))
    });
    match result {
        Ok(()) => log::info!("worker: Android context and settings ready; starting in-process"),
        Err(reason) => log::error!("worker: setup completed but startup failed: {reason}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_launch_waits_for_settings_even_when_the_activity_is_ready_first() {
        let mut gate = StartupGate::default();

        assert!(!gate.signal(Signal::ContextReady));
        assert!(gate.signal(Signal::SettingsReady));
        assert!(!gate.signal(Signal::SettingsReady));
        assert!(!gate.signal(Signal::ContextReady));
    }

    #[test]
    fn first_launch_waits_for_the_activity_when_rust_setup_finishes_first() {
        let mut gate = StartupGate::default();

        assert!(!gate.signal(Signal::SettingsReady));
        assert!(gate.signal(Signal::ContextReady));
        assert!(!gate.signal(Signal::ContextReady));
        assert!(!gate.signal(Signal::SettingsReady));
    }
}
