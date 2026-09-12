# probes

Generated. Do not edit, do not branch from this.

One document: a dated reading of whether each service on the published list accepted a TCP
connection, and how long that took. Written by `.github/workflows/service-probe.yml` from
`web/scripts/probe-services.mjs`, read at site build time by `web/scripts/build-services.mjs`,
and rendered on https://noderamc.org/services/ together with its own age.

A connect is not a protocol handshake. `reachable` means the port accepted; it does not
mean the service answered, which is why `peerClosed` is recorded separately and the page
says so in those words. This branch is an orphan and is force-pushed on every run; it
carries no source.
