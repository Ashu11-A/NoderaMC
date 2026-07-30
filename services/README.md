# `services/`

<!-- AI-AGENT-INSTRUCTION: `official.json` is data maintained by pull request from outside the
     project. Validate it, never rewrite it to suit a code change: if a code change would invalidate
     an entry somebody contributed, the code change is wrong or the schema version must go up.
     `index.schema.json` is a PUBLISHED contract — third-party stores are written against it, so a
     backwards-incompatible edit means `schema_version: 2` and a reader that handles both. -->

The list of trackers and rendezvous services the project knows about, and the schema that describes
it.

| File | What it is |
|---|---|
| `official.json` | The project's list. Add yours by pull request. |
| `index.schema.json` | The format — for this file **and** for any third-party store. |

Read it with [`scripts/services.py`](../scripts/services.py):

```bash
scripts/services.py --validate
scripts/services.py --endpoints tracker      # tcp://…:6969,tcp://…:6969
```

---

## What this list is, and what it is not

It is **a set of hints about where to start looking.** That is all, and the design depends on it
being all.

A peer that reads this list still verifies every service the same way it verifies one it discovered
by itself: the service signs its own address record with its own Ed25519 key, and a record whose
signature does not check out is dropped before anything is dialled. Being listed here grants nothing.
A service in this file cannot forge a world, a region, an identity, or a vote, because
**no service holds authority over world state** — the whole trust model of the project is that
infrastructure is replaceable and unprivileged.

So the worst thing a hostile entry in this file could do is waste a peer's time: answer slowly, hide
peers it knows about, or fail to relay. All three are things peers measure for themselves and route
around, which is what the scoring lane exists for.

It is also **not a ranking.** The order is the order entries were added. A peer probes what it finds,
scores it on its own measurements, and picks — code that treats a position in this file as a quality
signal is wrong.

## Adding your service

Open a pull request adding an entry. Two entries if you run both a tracker and a relay: they are
separate services, on separate ports, with separate identities.

```json
{
  "kind": "rendezvous",
  "name": "example.org",
  "endpoints": ["tcp://relay.example.org:7500"],
  "operator": "your-handle",
  "region": "us-east"
}
```

Before you open it:

- **Run it for a while first.** A service that appears and disappears is worse for peers than one
  that was never listed, because they will have measured it and preferred it.
- **`endpoints` must be reachable from the public internet**, and must be the address *others* use —
  not a bind address, not a LAN address. `docker/compose.yml` refuses to start without this for the
  same reason.
- **List more than one route if you have one.** A hostname plus a literal address survives a DNS
  outage; an A and an AAAA route survive a client with only one stack.
- **`node_id` is optional and worth including.** Take it from the service's own startup line
  (`service identity <32 hex>`). It is what turns a hijacked DNS name from something a peer accepts
  into something a peer notices.
- CI validates the file against the schema on every pull request. Run `scripts/services.py
  --validate` first and save yourself a round trip.

Setting up the service itself: [`docs/tracker/SELF-HOSTING.md`](../docs/tracker/SELF-HOSTING.md) and
[`docs/rendezvous/SELF-HOSTING.md`](../docs/rendezvous/SELF-HOSTING.md).

## Third-party stores

`index.schema.json` is not private to this file. Anyone can publish a list in the same format and
users can add it in the app under **Settings → Tracker stores**, the same way Mihon users add an
extension repository — paste the URL, or follow a `nodera://tracker-store?url=…` link from a website.

### Linking to your own list

A `nodera://` href does not survive most websites' link sanitisers (GitHub strips every scheme but
http/https/mailto), so the one-click path goes through an ordinary https page that invokes the scheme
on a *click*:

```
https://noderamc.org/add-store?url=<your index URL, percent-encoded>
```

That page shows the address, hands it to the app when the visitor presses the button, and — for the
majority of visitors, who do not have the app — offers the address to copy and a link to the
releases. It runs no tracking and makes no requests of its own. The page is
[`site/add-store.html`](../site/add-store.html) in this repository, deployed by
[`scripts/deploy-site.sh`](../scripts/deploy-site.sh); nothing about it is privileged, so a store
that would rather host its own copy can serve the same file from its own domain.

Neither the page nor the link can *add* anything: both ends of this only record an address, and the
app asks before it fetches.

This file is simply the store that ships with the app. There is no privileged format and no
privileged host: the built-in list is one store among however many a user chooses to trust, and can
be removed like any other.

An index is **not signed**, and that is deliberate rather than an omission. A signature is only worth
what the key distribution behind it is worth, and there is no trust root here that would make one
meaningful — the app would end up pinning a key that says "this file came from whoever published this
file". What carries the weight instead is that adding a store is an explicit decision the user takes,
with the URL shown to them first, and that every service inside any store still has to prove its own
identity before a peer will use it.
