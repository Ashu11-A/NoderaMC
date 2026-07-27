//! Turning a configured endpoint into something a socket can be opened to.
//!
//! Every endpoint an operator writes in this project may carry a scheme — `tcp://host:port` is what
//! the documentation, the example configs, the compose file and the Java `Endpoint.parse` all use,
//! and a bare `host:port` is equally valid. A resolver does not know that, so the scheme has to come
//! off before the connect.
//!
//! This exists because it did not, and the failure was invisible in exactly the way that matters:
//! a rendezvous service configured with `tracker_endpoints = ["tcp://1.2.3.4:6969"]` — the
//! documented form — logged one line at boot,
//!
//! ```text
//! announce to tcp://1.2.3.4:6969 failed: failed to lookup address information: Name does not resolve
//! ```
//!
//! and then ran perfectly, serving every peer that already knew its address and being undiscoverable
//! to every peer that did not. The service was healthy, the tracker was healthy, and the discovery
//! lane between them did nothing. It was caught by deploying it, not by a test.
//!
//! `udp://` is stripped too. The tracker serves the same request family on the same port over both
//! transports, so a `udp://` route names a host and port this code can also reach over TCP; refusing
//! it would mean an operator's perfectly correct route list breaking on the one entry that named the
//! cheaper transport.

/// The `host:port` a socket can be opened to, with any scheme removed.
///
/// Anything that is not a scheme this project uses is left alone, so a malformed endpoint fails at
/// the connect with the operator's own string in the error rather than being silently reinterpreted.
pub fn socket_target(endpoint: &str) -> &str {
    let trimmed = endpoint.trim();
    for scheme in ["tcp://", "udp://"] {
        if let Some(rest) = trimmed.strip_prefix(scheme) {
            return rest;
        }
    }
    trimmed
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_scheme_is_removed_because_a_resolver_cannot_read_one() {
        assert_eq!(socket_target("tcp://1.2.3.4:6969"), "1.2.3.4:6969");
        assert_eq!(
            socket_target("tcp://tracker.example.org:6969"),
            "tracker.example.org:6969"
        );
    }

    #[test]
    fn udp_names_the_same_host_and_port_this_code_reaches_over_tcp() {
        // The tracker serves the same family on the same port over both. Refusing this would break
        // a route list that is entirely correct.
        assert_eq!(socket_target("udp://1.2.3.4:6969"), "1.2.3.4:6969");
    }

    #[test]
    fn a_bare_endpoint_is_left_exactly_as_it_is() {
        assert_eq!(socket_target("1.2.3.4:6969"), "1.2.3.4:6969");
        assert_eq!(socket_target("example.org:6969"), "example.org:6969");
    }

    #[test]
    fn an_ipv6_literal_keeps_its_brackets() {
        // The brackets are what separate the address's colons from the port's.
        assert_eq!(socket_target("tcp://[::1]:6969"), "[::1]:6969");
        assert_eq!(socket_target("[2001:db8::1]:7500"), "[2001:db8::1]:7500");
    }

    #[test]
    fn surrounding_whitespace_from_a_split_list_is_dropped() {
        assert_eq!(socket_target("  tcp://1.2.3.4:6969  "), "1.2.3.4:6969");
    }

    #[test]
    fn an_unknown_scheme_is_not_reinterpreted() {
        // Left alone so the connect fails with the operator's own string, which is findable, rather
        // than with something this function invented.
        assert_eq!(socket_target("https://example.org:443"), "https://example.org:443");
    }
}
