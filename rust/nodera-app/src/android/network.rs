//! Which network this device is on, and whether the user is paying for it by the byte.
//!
//! # Why a peer has to ask
//!
//! Every other pause rule in this app is about *the machine* — is there a battery, is it charging,
//! is the game open. This one is about *the bill*. A node that seeds a world is deliberately moving
//! as many bytes as it can, and on a metered connection that is somebody's data allowance being
//! spent on strangers' Minecraft worlds. No amount of good behaviour elsewhere makes that
//! acceptable as a silent default, so the transport is read and the policy in
//! [`crate::settings::Network::transfer_network`] decides.
//!
//! # What "metered" means here
//!
//! Not "is it cellular". Android answers the question directly through
//! `NetworkCapabilities.NET_CAPABILITY_NOT_METERED`, and that answer is the one that respects the
//! user's own configuration: a phone tethering over Wi-Fi to a capped hotspot reports metered even
//! though the transport is Wi-Fi, and an unlimited SIM marked unmetered in system settings reports
//! unmetered even though the transport is cellular. Inferring the bill from the transport would
//! override a choice the user already made in the OS.
//!
//! Both facts are therefore reported, and [`crate::settings::NetworkPolicy`] chooses which to use:
//! `WifiOnly` keys off the transport (it is a literal request), `UnmeteredOnly` keys off the
//! capability (it is a request about cost).
//!
//! # Failure is never "go ahead"
//!
//! When the state cannot be read, [`state`] reports [`Transport::Unknown`] with the reason, and the
//! decision function treats unknown as *permitted* — see [`allows_transfers`] for why that is the
//! defensible direction rather than the convenient one.

use serde::{Deserialize, Serialize};

/// The kind of link this device is using.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Transport {
    /// Not read yet, or not readable on this platform. Distinct from [`Self::Offline`]: one is
    /// ignorance and the other is a fact, and a rule must not treat them alike.
    #[default]
    Unknown,
    /// There is no active network at all.
    Offline,
    Wifi,
    Cellular,
    Ethernet,
    /// Bluetooth, USB tethering, Wi-Fi Aware — a real link that is none of the above.
    Other,
}

impl Transport {
    /// The label the UI shows. Kept here so the phone and the desktop cannot word it differently.
    pub fn label(self) -> &'static str {
        match self {
            Transport::Unknown => "Unknown",
            Transport::Offline => "Offline",
            Transport::Wifi => "Wi-Fi",
            Transport::Cellular => "Mobile data",
            Transport::Ethernet => "Ethernet",
            Transport::Other => "Other",
        }
    }
}

/// What this device's connection is, right now.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct NetworkState {
    /// Whether this platform can answer the question at all. `false` on desktop, and the UI hides
    /// the whole subject rather than offering a control that decides nothing.
    pub supported: bool,
    pub transport: Transport,
    /// Whether the OS considers this link metered — the user's own setting, not a guess from the
    /// transport. Meaningless while `transport` is [`Transport::Unknown`].
    pub metered: bool,
    /// Whether the active network runs through a VPN. Reported because the transport underneath a
    /// VPN is frequently invisible, which is worth saying rather than silently mis-answering.
    pub vpn: bool,
    /// Why the state could not be read. Empty when it could.
    pub error: String,
}

/// The state before anything has been read.
///
/// Not `Default`, which reports `supported: false` — that is the desktop answer, and using it on a
/// phone would tell the UI the subject does not apply when in fact nobody has looked yet.
///
/// This exists because probing at construction was too early: the cache is built in `run()`, and
/// on Android `NoderaBridge.initialise` has not yet handed the process its `Context`, so
/// `ndk_context::android_context()` panics. The panic is caught, but the read is wasted and it puts
/// a `PANIC:` line in logcat on every launch — which is a bad thing to teach anyone to ignore.
pub fn pending() -> NetworkState {
    NetworkState {
        supported: cfg!(target_os = "android"),
        transport: Transport::Unknown,
        metered: false,
        vpn: false,
        error: String::new(),
    }
}

/// Read the current network state.
#[cfg(target_os = "android")]
pub fn state() -> NetworkState {
    let mut out = NetworkState {
        supported: true,
        ..NetworkState::default()
    };
    match platform::active_capabilities() {
        Ok(None) => out.transport = Transport::Offline,
        Ok(Some(caps)) => {
            out.transport = caps.transport;
            out.metered = caps.metered;
            out.vpn = caps.vpn;
        }
        // Reported, never assumed. Claiming Wi-Fi on a failed read would spend somebody's data
        // allowance on the strength of a call that did not happen.
        Err(reason) => out.error = reason,
    }
    out
}

/// Desktop links are not metered in any sense this app can read, and a laptop on a tethered phone
/// is a case the OS does not expose. Say the subject does not apply rather than inventing an answer.
#[cfg(not(target_os = "android"))]
pub fn state() -> NetworkState {
    NetworkState {
        supported: false,
        ..NetworkState::default()
    }
}

/// Whether the user's policy permits moving bytes over the link they are on.
///
/// Reading order, and the decisions it encodes:
///
/// 1. **Unsupported platform ⇒ permitted.** A desktop cannot answer the question, and a rule about
///    an unanswerable question must be inert rather than maximally restrictive — the alternative is
///    a laptop that silently stops seeding because a phone-shaped setting was saved once.
/// 2. **Unknown transport ⇒ permitted.** This is the one place the safe-looking answer is the wrong
///    one. A failed `getSystemService` call is an app defect, and pausing the node for it would
///    present as "Nodera stopped working" with no visible cause. The state is surfaced in the UI
///    with its error instead, so the failure is *seen* rather than *acted on*.
/// 3. **Offline ⇒ permitted.** There is nothing to spend and nothing to transfer; pausing here would
///    only mean the node stays paused for one sample interval after the network returns.
/// 4. `WifiOnly` is read literally — Ethernet counts, because a device on a cable is not on a
///    mobile plan and refusing there would be pedantry rather than policy.
/// 5. `UnmeteredOnly` defers entirely to the OS's own metered flag, which is the user's setting.
pub fn allows_transfers(policy: crate::settings::NetworkPolicy, state: &NetworkState) -> bool {
    use crate::settings::NetworkPolicy;
    if !state.supported || policy == NetworkPolicy::Any {
        return true;
    }
    match state.transport {
        Transport::Unknown | Transport::Offline => true,
        Transport::Wifi | Transport::Ethernet => match policy {
            NetworkPolicy::Any | NetworkPolicy::WifiOnly => true,
            NetworkPolicy::UnmeteredOnly => !state.metered,
        },
        Transport::Cellular | Transport::Other => match policy {
            NetworkPolicy::Any => true,
            NetworkPolicy::WifiOnly => false,
            NetworkPolicy::UnmeteredOnly => !state.metered,
        },
    }
}

#[cfg(target_os = "android")]
mod platform {
    use super::Transport;
    use jni::objects::{JObject, JValue};

    /// `NetworkCapabilities` reduced to the three things any rule here needs.
    pub struct Capabilities {
        pub transport: Transport,
        pub metered: bool,
        pub vpn: bool,
    }

    // android.net.NetworkCapabilities constants. Inlined rather than read through JNI because they
    // are frozen platform API values, and one static field read per sample to learn a number that
    // cannot change is a round trip for nothing.
    const TRANSPORT_CELLULAR: i32 = 0;
    const TRANSPORT_WIFI: i32 = 1;
    const TRANSPORT_ETHERNET: i32 = 3;
    const TRANSPORT_VPN: i32 = 4;
    const NET_CAPABILITY_NOT_METERED: i32 = 11;

    /// The active network's capabilities, or `None` when there is no active network.
    ///
    /// Both `getActiveNetwork` and `getNetworkCapabilities` legitimately return null — the first
    /// with no connection, the second when the network went away between the two calls — and each
    /// null is "offline", not an error. Treating them as errors would put a scary red string on the
    /// screen every time somebody walked into a lift.
    pub fn active_capabilities() -> Result<Option<Capabilities>, String> {
        crate::android::battery::with_android_context(|env, activity| {
            let service = env
                .new_string("connectivity")
                .map_err(|e| format!("string: {e}"))?;
            let manager = env
                .call_method(
                    activity,
                    "getSystemService",
                    "(Ljava/lang/String;)Ljava/lang/Object;",
                    &[JValue::Object(&service)],
                )
                .and_then(|v| v.l())
                .map_err(|e| format!("getSystemService(connectivity): {e}"))?;
            if manager.is_null() {
                return Err("this device has no ConnectivityManager".to_owned());
            }

            let network = env
                .call_method(&manager, "getActiveNetwork", "()Landroid/net/Network;", &[])
                .and_then(|v| v.l())
                .map_err(|e| format!("getActiveNetwork: {e}"))?;
            if network.is_null() {
                return Ok(None);
            }

            let caps = env
                .call_method(
                    &manager,
                    "getNetworkCapabilities",
                    "(Landroid/net/Network;)Landroid/net/NetworkCapabilities;",
                    &[JValue::Object(&network)],
                )
                .and_then(|v| v.l())
                .map_err(|e| format!("getNetworkCapabilities: {e}"))?;
            if caps.is_null() {
                return Ok(None);
            }

            let has_transport = |env: &mut jni::JNIEnv, caps: &JObject, id: i32| -> bool {
                env.call_method(caps, "hasTransport", "(I)Z", &[JValue::Int(id)])
                    .and_then(|v| v.z())
                    .unwrap_or(false)
            };

            // VPN is checked but never reported as *the* transport: a VPN rides on Wi-Fi or on
            // cellular, and answering "VPN" would lose the fact the policy is actually about.
            let vpn = has_transport(env, &caps, TRANSPORT_VPN);
            let transport = if has_transport(env, &caps, TRANSPORT_WIFI) {
                Transport::Wifi
            } else if has_transport(env, &caps, TRANSPORT_CELLULAR) {
                Transport::Cellular
            } else if has_transport(env, &caps, TRANSPORT_ETHERNET) {
                Transport::Ethernet
            } else {
                Transport::Other
            };

            let metered = !env
                .call_method(
                    &caps,
                    "hasCapability",
                    "(I)Z",
                    &[JValue::Int(NET_CAPABILITY_NOT_METERED)],
                )
                .and_then(|v| v.z())
                .map_err(|e| format!("hasCapability(NOT_METERED): {e}"))?;

            Ok(Some(Capabilities {
                transport,
                metered,
                vpn,
            }))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings::NetworkPolicy;

    fn on(transport: Transport, metered: bool) -> NetworkState {
        NetworkState {
            supported: true,
            transport,
            metered,
            vpn: false,
            error: String::new(),
        }
    }

    #[test]
    fn a_platform_that_cannot_answer_never_pauses() {
        let desktop = NetworkState::default(); // supported: false
        for policy in [
            NetworkPolicy::Any,
            NetworkPolicy::UnmeteredOnly,
            NetworkPolicy::WifiOnly,
        ] {
            assert!(allows_transfers(policy, &desktop), "{policy:?}");
        }
    }

    #[test]
    fn wifi_only_refuses_mobile_data_and_permits_a_cable() {
        assert!(!allows_transfers(
            NetworkPolicy::WifiOnly,
            &on(Transport::Cellular, true)
        ));
        assert!(allows_transfers(
            NetworkPolicy::WifiOnly,
            &on(Transport::Wifi, false)
        ));
        // A device on a cable is not on a mobile plan; refusing there would be pedantry.
        assert!(allows_transfers(
            NetworkPolicy::WifiOnly,
            &on(Transport::Ethernet, false)
        ));
    }

    /// The case that separates the two policies: a capped hotspot is Wi-Fi *and* metered.
    #[test]
    fn a_metered_hotspot_passes_wifi_only_and_fails_unmetered_only() {
        let hotspot = on(Transport::Wifi, true);
        assert!(allows_transfers(NetworkPolicy::WifiOnly, &hotspot));
        assert!(!allows_transfers(NetworkPolicy::UnmeteredOnly, &hotspot));
    }

    /// ...and its mirror: an unlimited SIM the user marked unmetered in system settings.
    #[test]
    fn unmetered_cellular_passes_unmetered_only_because_the_user_said_so() {
        let unlimited = on(Transport::Cellular, false);
        assert!(allows_transfers(NetworkPolicy::UnmeteredOnly, &unlimited));
        assert!(!allows_transfers(NetworkPolicy::WifiOnly, &unlimited));
    }

    /// A failed read must be visible, not enforced. Pausing on ignorance presents to the user as
    /// "Nodera stopped working" with nothing on screen to explain it.
    #[test]
    fn an_unreadable_state_permits_transfers_rather_than_pausing_on_ignorance() {
        let broken = NetworkState {
            supported: true,
            transport: Transport::Unknown,
            error: "getSystemService(connectivity): boom".to_owned(),
            ..NetworkState::default()
        };
        assert!(allows_transfers(NetworkPolicy::WifiOnly, &broken));
        assert!(allows_transfers(NetworkPolicy::UnmeteredOnly, &broken));
    }

    #[test]
    fn being_offline_is_not_a_reason_to_pause() {
        assert!(allows_transfers(
            NetworkPolicy::WifiOnly,
            &on(Transport::Offline, false)
        ));
    }

    #[test]
    fn the_any_policy_is_inert_on_every_transport() {
        for transport in [
            Transport::Wifi,
            Transport::Cellular,
            Transport::Ethernet,
            Transport::Other,
            Transport::Offline,
            Transport::Unknown,
        ] {
            assert!(allows_transfers(NetworkPolicy::Any, &on(transport, true)));
        }
    }
}
