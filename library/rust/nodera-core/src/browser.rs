//! Opening a link outside the app's own interface.
//!
//! ## Why this is not `<a target="_blank">`
//!
//! In a Tauri v2 webview a `target="_blank"` anchor opens **nothing**. There is no browser chrome to
//! open a tab in and no default handler wired to the navigation, so the click is swallowed. An
//! anchor that *did* navigate would be worse: the app's own window would follow the link, replacing
//! the interface with somebody's web page and no way back. So links go through the host.
//!
//! ## The rule: a link always opens something
//!
//! The first version of this module ended its ladder with the clipboard — when nothing could open a
//! browser it copied the address and said so. That is not opening a link. Every rung now ends in
//! something the user can *read*:
//!
//! | | desktop | Android |
//! |---|---|---|
//! | 1 | the default browser | the default browser (`ACTION_VIEW`) |
//! | 2 | a window of our own, pointed at the page | a Custom Tab — the browser engine, drawn in-task |
//! | 3 | — | a WebView in a dialog |
//!
//! There is no fourth rung and no clipboard. A device that reaches the end of this list without
//! showing a page has no way to render HTML at all, which is not a state this app can be in — it is
//! itself a webview.
//!
//! ## Why the URL is validated here and not in the interface
//!
//! Most of these links are **third-party data**. A tracker store's `homepage` comes out of an index
//! published by whoever the user chose to trust, and "I trust this publisher to list trackers" is not
//! "I trust this publisher to hand my operating system an arbitrary URI". A `file:///` opens a local
//! file; on Android an `intent://` can address another app's components. So the rule is a whitelist —
//! `http` and `https` — and it lives on this side of the IPC boundary, where the interface cannot be
//! talked out of it.

/// The longest URL this will hand to the operating system.
///
/// Browsers stop being reliable long before this; the ceiling is here so a hostile index costs a
/// bounded string rather than whatever the platform does with a megabyte of URI.
const MAX_URL_LENGTH: usize = 2048;

/// Reject anything that is not a plain web link, before the platform sees it.
///
/// Returns the trimmed URL on success.
pub fn check_url(url: &str) -> Result<String, String> {
    let trimmed = url.trim();
    if trimmed.is_empty() {
        return Err("there is no address to open".to_owned());
    }
    if trimmed.len() > MAX_URL_LENGTH {
        return Err("that address is implausibly long".to_owned());
    }
    // Control characters, including the newline that would let one URL carry a second line into
    // anything that parses this line-by-line, and the tab some parsers strip on the way to a
    // different scheme than the one that was checked.
    if trimmed.chars().any(|c| c.is_control() || c == '\u{7f}') {
        return Err("that address contains characters a link cannot contain".to_owned());
    }
    // Case-insensitive, because `HTTPS://` and `Https://` are the same scheme to every URL parser
    // that will see this after us — and a check that disagrees with the parser downstream is not a
    // check.
    let scheme = trimmed
        .split_once("://")
        .map(|(scheme, _)| scheme.to_ascii_lowercase())
        .unwrap_or_default();
    if scheme != "http" && scheme != "https" {
        return Err(format!(
            "only http and https links are opened, and that one is {}",
            if scheme.is_empty() {
                "not a web address".to_owned()
            } else {
                format!("a {scheme} address")
            }
        ));
    }
    // A scheme and nothing else. `https://` alone passes every check above and means nothing.
    if trimmed[scheme.len() + 3..].trim().is_empty() {
        return Err("that address has no host".to_owned());
    }
    Ok(trimmed.to_owned())
}

/// How a shell hands an **already validated** address to the platform.
///
/// The validation is not part of this: refusing `file:///etc/passwd` is the same decision on every
/// platform, and a trait method that took the raw string would let one shell forget to check. So
/// [`open`] checks, and the implementor only ever sees an address that passed.
///
/// The tag it returns is for the log and for tests, not for the user: `browser`, `custom-tab`, or
/// `webview`.
pub trait LinkOpener: Send + Sync {
    fn open_checked(&self, url: &str) -> Result<&'static str, String>;
}

/// Open a web link, and say which rung answered.
pub fn open(opener: &dyn LinkOpener, url: &str) -> Result<&'static str, String> {
    let target = check_url(url)?;
    opener.open_checked(&target)
}

/// The platform's own opener, where the platform has one this crate can drive.
///
/// Android's whole ladder is in Kotlin (see below), so the core can own it outright. The desktop's
/// is not — its second rung is a webview window, which only the Tauri shell can build — so on
/// desktop the shell supplies the implementation instead.
#[cfg(target_os = "android")]
pub struct SystemOpener;

#[cfg(target_os = "android")]
impl LinkOpener for SystemOpener {
    fn open_checked(&self, url: &str) -> Result<&'static str, String> {
        platform::open(url)
    }
}

#[cfg(target_os = "android")]
mod platform {
    /// The Kotlin helper, copied into the generated project by `scripts/android-apk.sh`.
    const HELPER: &str = "dev/nodera/app/NoderaBrowser";

    /// The whole ladder, in one JNI call.
    ///
    /// **One call is the point.** The previous version called Kotlin, and on failure made a second
    /// JNI call to fall back to `ACTION_VIEW` — without clearing the Java exception the first one
    /// left pending. ART does not tolerate that: the next JNI call on a thread with a pending
    /// exception aborts the process outright, which is why tapping a link killed the app rather than
    /// failing over. `battery.rs` and `worker.rs` both clear before returning and say why in a
    /// comment; this module did not.
    ///
    /// So the fallback ladder lives entirely in Kotlin now, where a failed rung is an ordinary
    /// caught exception and never becomes a pending JNI one. Rust makes exactly one call, and clears
    /// anything it left behind before returning.
    pub fn open(url: &str) -> Result<&'static str, String> {
        crate::android::with_context(|env, context| {
            let target = env.new_string(url).map_err(|e| format!("string: {e}"))?;
            let call = env.call_static_method(
                HELPER,
                "open",
                "(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;",
                &[
                    jni::objects::JValue::Object(context),
                    jni::objects::JValue::Object(&target),
                ],
            );
            // Before anything else can touch JNI on this thread. See the note above: this is the
            // difference between a link that fails and an app that dies.
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
                return Err("the browser helper failed on this device".to_owned());
            }

            let answer = call
                .and_then(|v| v.l())
                .map_err(|e| format!("NoderaBrowser.open: {e}"))?;
            if answer.is_null() {
                return Err("nothing on this device could open a web page".to_owned());
            }
            // Bound to a local before the conversion, matching `battery::platform::package_name`:
            // the `JavaStr` borrows the `JString`, and building it from a temporary in the same
            // expression is the shape that has bitten this file's neighbour before.
            let answer = jni::objects::JString::from(answer);
            let how: String = env
                .get_string(&answer)
                .map_err(|e| format!("reading the result: {e}"))?
                .into();
            // Mapped onto known values rather than returned verbatim, so an unexpected string from
            // the other side of the bridge cannot become an unexpected value on this side.
            Ok(match how.as_str() {
                "custom-tab" => "custom-tab",
                "webview" => "webview",
                _ => "browser",
            })
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_plain_web_link_is_accepted() {
        assert_eq!(
            check_url("  https://noderamc.org/  ").unwrap(),
            "https://noderamc.org/"
        );
        assert!(check_url("http://example.org").is_ok());
        // The scheme is matched case-insensitively because every parser downstream does.
        assert!(check_url("HTTPS://example.org").is_ok());
    }

    #[test]
    fn a_local_file_is_refused() {
        // The most important single case. A store's `homepage` is written by whoever published the
        // index; `file:///etc/passwd` handed to the platform opener is that publisher reading the
        // user's disk through a link the user thought went to a web page.
        let refused = check_url("file:///etc/passwd").unwrap_err();
        assert!(refused.contains("http"), "{refused}");
    }

    #[test]
    fn a_scheme_that_addresses_another_app_is_refused() {
        // On Android an `intent://` URI names components in other applications, and `startActivity`
        // will honour it. A tracker store must not be able to reach one.
        for hostile in [
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "content://media/external/images",
            "nodera://tracker-store?url=https://example.org/i.json",
        ] {
            assert!(check_url(hostile).is_err(), "{hostile} must be refused");
        }
    }

    #[test]
    fn a_url_carrying_a_second_line_is_refused() {
        // Anything downstream that reads a line at a time — a log, a shell, an intent extra — would
        // see two. Cheaper to refuse the character than to audit every consumer.
        assert!(check_url("https://example.org\nhttps://evil.example").is_err());
        assert!(check_url("https://example.org\r\nX-Header: y").is_err());
        assert!(check_url("https:\t//example.org").is_err());
    }

    #[test]
    fn a_scheme_with_no_host_is_refused() {
        assert!(check_url("https://").is_err());
        assert!(check_url("https://   ").is_err());
    }

    #[test]
    fn nothing_at_all_is_refused() {
        assert!(check_url("").is_err());
        assert!(check_url("   ").is_err());
        assert!(
            check_url("example.org").is_err(),
            "a bare host has no scheme"
        );
        assert!(check_url(&format!("https://e.org/{}", "a".repeat(4096))).is_err());
    }
}
