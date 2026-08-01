//! Opening a link outside the app's own webview.
//!
//! ## Why this is not `<a target="_blank">`
//!
//! In a Tauri v2 webview a `target="_blank"` anchor opens **nothing**. There is no browser chrome to
//! open a tab in and no default handler wired to the navigation, so the click is swallowed. Every
//! outbound link in this interface was therefore decorative, and `About.tsx` had already worked
//! around it by turning the repository link into a copy-to-clipboard button — a workaround that
//! says, correctly, that the link did not work.
//!
//! Worse than decorative would be an anchor that *did* navigate: the app's own window would follow
//! the link, replacing the interface with somebody's web page and no way back. So links go through
//! the host, and the host decides where they open.
//!
//! ## Why the URL is validated here and not in the interface
//!
//! Most of the links this opens are **third-party data**. A tracker store's `homepage` comes out of
//! an index published by whoever the user chose to trust, and "I trust this publisher to list
//! trackers" is not "I trust this publisher to hand my operating system an arbitrary URI". A
//! `file:///` opens a local file; on Android an `intent://` URI can address another app's
//! components. Neither is a browser link, and neither should reach `startActivity` or the desktop
//! equivalent.
//!
//! So the rule is a whitelist — `http` and `https`, nothing else — and it lives on this side of the
//! IPC boundary, where the interface cannot be talked out of it.

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
    // anything that parses this line-by-line, and the tab/space that some parsers strip on the way
    // to a different scheme than the one that was checked.
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

/// Open a validated web link outside this window, and say how it was opened.
///
/// The returned tag is for the log and for tests, not for the user: `browser` on the desktop, and on
/// Android one of `custom-tab`, `browser` or `webview` depending on how far down the ladder the
/// device made it.
pub fn open(url: &str) -> Result<&'static str, String> {
    let target = check_url(url)?;
    platform::open(&target)
}

#[cfg(target_os = "android")]
mod platform {
    use jni::objects::{JString, JValue};

    /// The Kotlin helper, copied into the generated project by `scripts/android-apk.sh`.
    const HELPER: &str = "dev/nodera/app/NoderaBrowser";

    /// Try the Kotlin ladder, then fall back to a bare `ACTION_VIEW`.
    ///
    /// The fallback is not decoration. `NoderaBrowser` is staged into the APK by the build script,
    /// and a build produced some other way — or a device running an APK from before this landed —
    /// will not have the class. Answering that with a `NoSuchMethodError` instead of a working link
    /// would make the app's most basic outbound action depend on which script built it.
    pub fn open(url: &str) -> Result<&'static str, String> {
        match helper(url) {
            Ok(how) => Ok(how),
            Err(reason) => {
                log::warn!("NoderaBrowser unavailable ({reason}); falling back to ACTION_VIEW");
                crate::android::open_intent_action("android.intent.action.VIEW", Some(url))
                    .map(|()| "browser")
            }
        }
    }

    /// `NoderaBrowser.open(context, url)` — Custom Tab, then browser, then in-app WebView.
    fn helper(url: &str) -> Result<&'static str, String> {
        crate::android::with_context(|env, activity| {
            let target = env.new_string(url).map_err(|e| format!("string: {e}"))?;
            let answer = env
                .call_static_method(
                    HELPER,
                    "open",
                    "(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;",
                    &[JValue::Object(activity), JValue::Object(&target)],
                )
                .and_then(|v| v.l())
                .map_err(|e| format!("NoderaBrowser.open: {e}"))?;
            if answer.is_null() {
                return Err("no browser, no custom tab and no webview on this device".to_owned());
            }
            // Bound to a local before the conversion, matching `battery::platform::package_name`:
            // the `JavaStr` borrows the `JString`, and building it from a temporary in the same
            // expression is the shape that has bitten this file's neighbour before.
            let answer = JString::from(answer);
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

#[cfg(not(target_os = "android"))]
mod platform {
    /// Hand the link to whatever the desktop calls its browser.
    pub fn open(url: &str) -> Result<&'static str, String> {
        tauri_plugin_opener::open_url(url, None::<&str>)
            .map(|()| "browser")
            .map_err(|e| format!("this system could not open a browser: {e}"))
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
