# NoderaMC — the `services` branch

<!-- AI-AGENT-INSTRUCTION: this branch carries DATA, not code. `index.json` is maintained by pull
     request from outside the project — validate it, never rewrite it to suit a code change on
     `main`: if a change on `main` would invalidate an entry somebody contributed, the change is
     wrong or `schema_version` must go up. `index.schema.json` is a PUBLISHED contract that
     third-party stores are written against, so a backwards-incompatible edit means
     `schema_version: 2` and a reader on `main` that handles both. Never merge this branch into
     `main` or `main` into this one; they share no history by design. -->

This branch is the project's published list of trackers and rendezvous services, and the schema that
describes the format. It shares no history with `main`: it is an orphan branch holding four files,
so that a list which changes when an operator joins is not a change to the source tree, and so that
a client fetching it downloads a few kilobytes rather than resolving a path inside a code
repository. The layout mirrors [keiyoushi/extensions](https://github.com/keiyoushi/extensions),
whose `repo` branch does the same job for Mihon extension repositories.

| File | What it is |
|---|---|
| `index.json` | **The list.** Pretty-printed, one service per entry. This is the file you edit. |
| `index.min.json` | The same list, minified. **This is what clients fetch.** Regenerated, never hand-written. |
| `index.schema.json` | The format — for this file **and** for any third-party store. |
| `.github/workflows/index.yml` | The gate. Validates both files on every pull request to this branch. |
| `README.md` | This file. |

The URL an app or a peer actually reads:

```
https://raw.githubusercontent.com/Ashu11-A/NoderaMC/services/index.min.json
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

Open a pull request **against this branch**, adding an entry to `index.json`. Two entries if you run
both a tracker and a relay: they are separate services, on separate ports, with separate identities.

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
  not a bind address, not a LAN address. `docker/compose.yml` on `main` refuses to start without
  this, for the same reason.
- **List more than one route if you have one.** A hostname plus a literal address survives a DNS
  outage; an A and an AAAA route survive a client with only one stack.
- **`node_id` is optional and worth including.** Take it from the service's own startup line
  (`service identity <32 hex>`). It is what turns a hijacked DNS name from something a peer accepts
  into something a peer notices.
- **Never hand-write `index.min.json`.** Regenerate it (below). CI compares it against `index.json`
  and fails if they disagree — two files a human may edit are two files that will disagree, and the
  disagreement ships: the app reads one list while the pull request showed another.

Both steps use the checker on `main`, which is the same code the app and the mod resolve this list
with. There is one implementation of "is this a valid index", not one per consumer.

```bash
# with a checkout of `main` beside your checkout of this branch
../NoderaMC/scripts/services.py --validate     --index index.json
../NoderaMC/scripts/services.py --endpoints tracker --index index.json
../NoderaMC/scripts/services.py --minify       --index index.json > index.min.json
```

From a checkout of `main` alone, the same script resolves this branch by itself — no `--index`
needed:

```bash
scripts/services.py --validate
scripts/services.py --endpoints tracker   # tcp://…:6969,…
scripts/services.py --resolve             # where it put the copy it read
```

Setting up the service itself: [`docs/tracker/SELF-HOSTING.md`](https://github.com/Ashu11-A/NoderaMC/blob/main/docs/tracker/SELF-HOSTING.md)
and [`docs/rendezvous/SELF-HOSTING.md`](https://github.com/Ashu11-A/NoderaMC/blob/main/docs/rendezvous/SELF-HOSTING.md).

## Third-party stores

`index.schema.json` is not private to this branch. Anyone can publish a list in the same format and
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
releases. It runs no tracking and makes no requests of its own. The page is `web/add-store.html` on
`main`, deployed by `scripts/deploy-site.sh`; nothing about it is privileged, so a store that would
rather host its own copy can serve the same file from its own domain.

Neither the page nor the link can *add* anything: both ends of this only record an address, and the
app asks before it fetches.

This branch is simply the store that ships with the app. There is no privileged format and no
privileged host: the built-in list is one store among however many a user chooses to trust, and can
be removed like any other.

An index is **not signed**, and that is deliberate rather than an omission. A signature is only worth
what the key distribution behind it is worth, and there is no trust root here that would make one
meaningful — the app would end up pinning a key that says "this file came from whoever published this
file". What carries the weight instead is that adding a store is an explicit decision the user takes,
with the URL shown to them first, and that every service inside any store still has to prove its own
identity before a peer will use it.

## How `main` consumes this branch

`main` holds no copy of the list. Three builds compile it in — the companion app's built-in store,
the Java `DefaultServices` defaults, and the CI validator — and all three resolve it the same way,
in this order:

1. `git show services:index.json` — the local ref.
2. `git show origin/services:index.json` — the fetched remote ref.
3. the raw URL above, if the build host has a network.
4. otherwise the build fails, telling you to run `git fetch origin services:services`.

Whatever is resolved is cached in `build/services/` (git-ignored), so one fetch is enough and every
later build is offline. The branch name and the URL are written down once, in `/layout.properties` on
`main`, and nothing may guess them.
