//! Approximate geolocation, derived at ingest and **coarse by construction**.
//!
//! The product question is "which regions is the network reaching, and is reachability worse in
//! some of them?" — a question about countries and networks, not about people. So the source
//! address is used for exactly two things, both inside this process, and then it is gone:
//!
//! * the per-source quota ([`crate::limits`]), which needs the full address to be effective;
//! * this lookup, which turns it into a **country code and an autonomous-system number**.
//!
//! Neither the address nor any truncation of it is written to the sink. There is no column for it,
//! which is a stronger guarantee than a policy about not filling one in.
//!
//! The table is operator-supplied (`geo_table_path`) in the format
//! `<cidr>,<ISO-3166-1 alpha-2>,<asn>` — one line each, `#` comments allowed. Nodera ships no
//! bundled database: a geolocation table is a licensing question, and a service that silently
//! degrades to `ZZ`/`0` when the operator has not provided one is the honest default.

use std::net::IpAddr;
use std::path::Path;

/// The only geographic fact that is ever stored.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Geo {
    /// ISO-3166-1 alpha-2, or `ZZ` when unknown. Never inferred from anything but the table.
    pub country: [u8; 2],
    /// Autonomous-system number, or `0` when unknown.
    pub asn: u32,
}

impl Geo {
    pub const UNKNOWN: Geo = Geo {
        country: *b"ZZ",
        asn: 0,
    };

    pub fn country_str(&self) -> &str {
        std::str::from_utf8(&self.country).unwrap_or("ZZ")
    }
}

/// One CIDR row of the table.
#[derive(Debug, Clone, Copy)]
struct Entry {
    network: u128,
    prefix_len: u8,
    v6: bool,
    geo: Geo,
}

/// A longest-prefix-match table.
///
/// A linear scan rather than a trie: the table is loaded once, holds thousands of rows rather than
/// millions, and is consulted once per *batch* — not once per event. A trie would be faster and
/// would be the wrong thing to maintain for the load this service actually sees.
#[derive(Debug, Default)]
pub struct GeoTable {
    entries: Vec<Entry>,
}

impl GeoTable {
    /// An empty table. Every lookup answers [`Geo::UNKNOWN`] — the state a service runs in until
    /// its operator supplies a database.
    pub fn empty() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Parse a table. Unreadable lines are skipped and counted, never fatal: an operator's table is
    /// a third-party artefact, and one malformed row must not stop a service from starting.
    pub fn parse(text: &str) -> (Self, usize) {
        let mut entries = Vec::new();
        let mut skipped = 0usize;
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            match parse_row(line) {
                Some(entry) => entries.push(entry),
                None => skipped += 1,
            }
        }
        // Longest prefix first, so the first match in a scan is the most specific one.
        entries.sort_by(|a, b| b.prefix_len.cmp(&a.prefix_len));
        (Self { entries }, skipped)
    }

    pub fn load(path: &Path) -> std::io::Result<(Self, usize)> {
        let text = std::fs::read_to_string(path)?;
        Ok(Self::parse(&text))
    }

    /// Resolve one address. Unknown addresses answer [`Geo::UNKNOWN`] rather than a guess.
    pub fn lookup(&self, ip: IpAddr) -> Geo {
        let (value, v6) = normalise(ip);
        for entry in &self.entries {
            if entry.v6 != v6 {
                continue;
            }
            let bits = if v6 { 128 } else { 32 };
            let shift = bits - entry.prefix_len as u32;
            // A /0 shifts by the full width, which is UB for `>>`; treat it as "matches anything".
            let matches = if shift >= bits {
                true
            } else {
                (value >> shift) == (entry.network >> shift)
            };
            if matches {
                return entry.geo;
            }
        }
        Geo::UNKNOWN
    }
}

/// IPv4-mapped IPv6 addresses (`::ffff:a.b.c.d`) are folded back to IPv4 so a dual-stack listener
/// does not split one network across two address families in the aggregates.
fn normalise(ip: IpAddr) -> (u128, bool) {
    match ip {
        IpAddr::V4(v4) => (u32::from(v4) as u128, false),
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => (u32::from(v4) as u128, false),
            None => (u128::from(v6), true),
        },
    }
}

fn parse_row(line: &str) -> Option<Entry> {
    let mut fields = line.split(',');
    let cidr = fields.next()?.trim();
    let country = fields.next()?.trim();
    let asn = fields.next().unwrap_or("0").trim();

    let (addr, prefix) = cidr.split_once('/')?;
    let ip: IpAddr = addr.trim().parse().ok()?;
    let prefix_len: u8 = prefix.trim().parse().ok()?;
    let (network, v6) = normalise(ip);
    if prefix_len as u32 > if v6 { 128 } else { 32 } {
        return None;
    }

    let country = country.as_bytes();
    if country.len() != 2 || !country.iter().all(|b| b.is_ascii_alphabetic()) {
        return None;
    }
    Some(Entry {
        network,
        prefix_len,
        v6,
        geo: Geo {
            country: [
                country[0].to_ascii_uppercase(),
                country[1].to_ascii_uppercase(),
            ],
            asn: asn.parse().unwrap_or(0),
        },
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const TABLE: &str = "\
# comment
198.51.100.0/24,br,264644
198.51.100.128/25,br,28573
203.0.113.0/24,DE,3320
2001:db8::/32,jp,2497
not-a-cidr
10.0.0.0/8,zz
";

    fn table() -> GeoTable {
        GeoTable::parse(TABLE).0
    }

    #[test]
    fn a_malformed_row_is_skipped_and_counted_without_failing_the_load() {
        let (table, skipped) = GeoTable::parse(TABLE);
        assert_eq!(skipped, 1);
        assert_eq!(table.len(), 5);
    }

    #[test]
    fn the_longest_matching_prefix_wins() {
        let table = table();
        assert_eq!(table.lookup("198.51.100.1".parse().unwrap()).asn, 264644);
        assert_eq!(table.lookup("198.51.100.200".parse().unwrap()).asn, 28573);
    }

    #[test]
    fn country_codes_are_normalised_to_upper_case() {
        assert_eq!(
            table()
                .lookup("198.51.100.1".parse().unwrap())
                .country_str(),
            "BR"
        );
        assert_eq!(
            table().lookup("203.0.113.9".parse().unwrap()).country_str(),
            "DE"
        );
    }

    #[test]
    fn ipv6_is_matched_on_its_own_family() {
        let table = table();
        assert_eq!(
            table.lookup("2001:db8::1".parse().unwrap()).country_str(),
            "JP"
        );
        assert_eq!(
            table.lookup("2001:beef::1".parse().unwrap()),
            Geo::UNKNOWN,
            "an address outside every prefix must not borrow a neighbour's country"
        );
    }

    #[test]
    fn an_ipv4_mapped_ipv6_address_resolves_as_ipv4() {
        assert_eq!(
            table()
                .lookup("::ffff:203.0.113.9".parse().unwrap())
                .country_str(),
            "DE"
        );
    }

    #[test]
    fn an_empty_table_answers_unknown_rather_than_guessing() {
        assert_eq!(
            GeoTable::empty().lookup("203.0.113.9".parse().unwrap()),
            Geo::UNKNOWN
        );
    }

    #[test]
    fn a_default_route_matches_everything_without_overflowing() {
        let (table, _) = GeoTable::parse("0.0.0.0/0,xx,0\n");
        assert_eq!(table.lookup("8.8.8.8".parse().unwrap()).country_str(), "XX");
    }
}
