// One world, in the panes a torrent client puts under a selected item — plus the pane this system
// needs that a torrent client does not: who administers it, and the proof of that.
//
// Everything here reads the same live dashboard payload the home screen does, so a world's numbers
// cannot disagree between the two screens, and a world that disappears from the node disappears
// from here. The only thing pulled separately is the piece grid, because it is large, it is only
// interesting for the selected world, and re-encoding every world's bitmap several times a second
// for a tab nobody has open would be the definition of waste.
import { useEffect, useRef, useState } from "react";
import {
  FiArrowLeft,
  FiActivity,
  FiCheck,
  FiGrid,
  FiInfo,
  FiUsers,
  FiCopy,
  FiSave,
  FiShare2,
  FiTrash2,
  FiX,
} from "react-icons/fi";
import {
  AVATAR,
  Card,
  Empty,
  MONO,
  KeyValue,
  Pill,
  Stat,
  STAT_GRID,
  Tabs,
  cx,
  resetScrollport,
} from "./components";
import {
  UNKNOWN,
  formatBytes,
  formatDate,
  formatPercent,
  formatSource,
  heldBytes,
  proveWorldAdmin,
  shortId,
  show,
  type AdminProof,
  type World,
} from "./api";
import { deleteWorld, fetchPieceMap, EMPTY_PIECE_MAP, type PieceMapView } from "./ipc";
import { saveShareFile, worldShareLink } from "./play";

type Tab = "info" | "content" | "sharing";

const TABS: { id: Tab; label: string; icon: JSX.Element }[] = [
  { id: "info", label: "Info", icon: <FiInfo /> },
  { id: "content", label: "Content", icon: <FiGrid /> },
  { id: "sharing", label: "Sharing", icon: <FiShare2 /> },
];

export function WorldScreen(props: { world: World; onBack: () => void }) {
  const { world } = props;
  const [tab, setTab] = useState<Tab>("info");

  return (
    <div className="flex min-h-full flex-col">
      <header className="page-canvas page-grid items-center gap-y-3 pt-7 pb-4">
        <button
          className="col-span-1 grid h-8 w-8 place-items-center rounded-sm border border-line bg-surface hover:bg-surface-hover"
          onClick={props.onBack}
          aria-label="Back to the dashboard"
        >
          <FiArrowLeft />
        </button>
        <span className={cx(AVATAR, "col-span-1 h-[38px] w-[38px] text-[16px]")} aria-hidden>
          {(world.name || "?").slice(0, 1).toUpperCase()}
        </span>
        <div className="col-span-10 min-w-0 wide:col-span-6">
          <h1 className="display-type text-title font-bold">{world.name || "Unnamed world"}</h1>
          <p className="mt-px text-[12px] text-dim">
            {describeRole(world)} · v{world.version} · {formatBytes(world.total_bytes)}
          </p>
        </div>
        <div className="col-span-12 flex flex-wrap items-center gap-2 wide:col-span-4 wide:justify-end">
          {world.connected && <Pill tone="up">You are playing here</Pill>}
          {world.administered && <Pill tone="up">You administer this</Pill>}
          {world.discoverable === false && <Pill tone="down">On no tracker — nobody can find it</Pill>}
          <Pill tone={joinable(world) ? "up" : "muted"}>
            {joinable(world) ? "Joinable now" : world.game_endpoint ? "Open, but unlisted" : "Game closed"}
          </Pill>
        </div>
      </header>

      {/* The shared strip, which was dead code while this screen and Settings each rolled their
          own — with different padding, different active colours, and in one of them no
          `role="tab"` at all. Settings keeps its own: its two chromes are a responsive pair, not a
          tab strip. */}
      <Tabs
        className="page-canvas"
        tabs={TABS}
        active={tab}
        onSelect={(id) => {
          setTab(id);
          resetScrollport();
        }}
      />

      <div
        className="page-canvas page-grid flex-1 gap-y-4 pt-6 pb-12"
        role="tabpanel"
      >
        {tab === "info" && <InfoTab world={world} />}
        {tab === "content" && (
          <div className="col-span-12"><PiecesTab world={world} /></div>
        )}
        {tab === "sharing" && (
          <div className="col-span-12"><ShareTab world={world} /></div>
        )}
      </div>
    </div>
  );
}

/**
 * Whether another player could actually arrive here.
 *
 * Two conditions, not one: the host's game has to be open **and** some tracker has to list the
 * world. Reporting only the first is what made a world announce "Joinable now" on the machine
 * running it while every other peer's lookup came back with no seeder — the game was open and no
 * directory on the network had ever heard of it.
 */
function joinable(w: World): boolean {
  return Boolean(w.game_endpoint) && w.discoverable !== false;
}

/**
 * The states a world can be in here, said in words rather than in three booleans.
 *
 * "Playing" is reported alongside the other two rather than instead of them, because a player in
 * somebody else's world is doing both things at once and the screen that hides the second one is
 * the screen that makes this network look like a download client.
 */
function describeRole(w: World): string {
  const standing = w.administered
    ? w.hosting
      ? "Yours — hosted here"
      : "Yours — kept available here"
    : w.hosting
      ? "Hosting for another peer"
      : "Supporting for the network";
  return w.connected ? `You are playing here · ${standing}` : standing;
}

/* --------------------------------------------------------------------------------------- info */

function InfoTab(props: { world: World }) {
  const w = props.world;
  return (
    <>
      <div className={cx(STAT_GRID, "col-span-12 grid-cols-[repeat(auto-fit,minmax(210px,1fr))]")}>
        <Stat
          label="Players online"
          value={show(w.players, String)}
          sub={w.players === null ? "nothing here can see into it" : "in this world"}
          icon={<FiUsers />}
        />
        <Stat label="Peers holding it" value={String(w.seeders)} sub="besides this node" icon={<FiActivity />} />
        <Stat
          label="Complete"
          value={show(w.completeness_permille, formatPercent)}
          sub={
            w.completeness_permille === null
              ? "no manifest yet"
              : `${w.pieces_held} of ${w.piece_count} pieces`
          }
        />
        <Stat label="Size" value={formatBytes(w.total_bytes)} sub={`v${w.version}`} />
      </div>

      <div className="col-span-12 wide:col-span-7">
        <ContributionCard world={w} />
      </div>

      <div className="col-span-12 wide:col-span-5">
        <Card title="World">
        <dl>
          <KeyValue label="World name" value={w.name || UNKNOWN} />
          <KeyValue label="World ID" value={w.world_id || UNKNOWN} mono />
          <KeyValue
            label="Checksum"
            value={w.checksum || UNKNOWN}
            mono={Boolean(w.checksum)}
            title="Manifest root"
          />
          <KeyValue label="Date added" value={formatDate(w.added_at)} />
          <KeyValue label="Last updated" value={formatDate(w.updated_at)} />
          <KeyValue label="Role on this node" value={describeRole(w)} />
          <KeyValue
            label="Game endpoint"
            value={w.game_endpoint ? formatSource(w.game_endpoint) : "offline (the host's game is closed)"}
            mono={Boolean(w.game_endpoint)}
          />
        </dl>
        </Card>
      </div>

    </>
  );
}

/* ------------------------------------------------------------------------------- contribution */

/**
 * What THIS node is doing for THIS world.
 *
 * Separate from the "World" card immediately below it on purpose: that one describes the world, and
 * would describe it identically on every peer that has it. This one is the only part of the screen
 * whose answers change depending on whose machine you are reading it on, and it is the question the
 * feature was asked for — "what am I contributing?" — which the app previously answered nowhere.
 *
 * Nothing here is estimated except `heldBytes`, which is prorated from the piece counts and says so
 * in its own doc. In particular there is no "uploaded to this world" figure: the node meters bytes
 * per peer, not per world, and inventing a per-world number by division would be a plausible lie on
 * the one screen whose whole job is to be checkable.
 */
function ContributionCard(props: { world: World }) {
  const w = props.world;
  const held = heldBytes(w);
  const complete = w.completeness_permille !== null && w.completeness_permille >= 1000;
  // The claim worth making, and the only one the data supports: with this node's copy complete, the
  // world survives its owner going offline. Partial copies still help — pieces are fungible — but
  // they are not a guarantee, and the wording keeps that distinction.
  const survives = complete && w.seeders > 0;

  return (
    <Card
      title="Your part in this world"
      hint="What this machine does for it, as distinct from what the world is."
      right={
        <Pill tone={w.connected ? "up" : complete ? "up" : "muted"}>
          {w.connected ? "Playing now" : complete ? "Full copy held" : "Partial copy"}
        </Pill>
      }
    >
      <div className="flex flex-col gap-1 py-2">
        <div className="flex items-baseline justify-between gap-3 text-xs">
          <span className="text-dim">
            {w.completeness_permille === null
              ? "No manifest yet — nothing to measure against"
              : `${w.pieces_held} of ${w.piece_count} pieces verified here`}
          </span>
          <span className={cx(MONO, complete ? "text-up" : "text-warn")}>
            {show(w.completeness_permille, formatPercent)}
          </span>
        </div>
        <div className="h-2 overflow-hidden rounded-full bg-surface-2" aria-hidden>
          <div
            className={cx("h-full rounded-full", complete ? "bg-up" : "bg-brand-2")}
            style={{ width: `${(w.completeness_permille ?? 0) / 10}%` }}
          />
        </div>
      </div>

      <dl>
        <KeyValue label="What you are to it" value={describeRole(w)} />
        <KeyValue
          label="World simulation you run"
          value={
            w.regions_held > 0
              ? `${w.regions_held} region${w.regions_held === 1 ? "" : "s"} validated here`
              : "none — this node only stores the world"
          }
          title="Regions whose validated snapshots this node commits and seeds. A player validates the regions their view covers; storing a copy of the world is a separate job."
        />
        <KeyValue
          label="Data you can serve"
          value={`${formatBytes(held)} of ${formatBytes(w.total_bytes)}`}
          title="Prorated from the pieces this node has verified. Pieces are what peers request, so this is what you can actually answer with."
        />
        <KeyValue
          label="Other peers holding it"
          value={
            w.seeders === 0
              ? "none besides this node"
              : `${w.seeders} besides this node`
          }
        />
        <KeyValue
          label="If its owner goes offline"
          value={
            survives
              ? "This node alone can serve the whole world"
              : complete
                ? "This node holds a full copy; nobody else is known to"
                : "This node holds part of it — the rest has to come from other peers"
          }
        />
        <KeyValue
          label="Backup copies"
          value={<BackupCopies world={w} />}
          title="A full copy is one peer that can serve the whole world alone. Partial copies help, but are not a backup."
        />
      </dl>
    </Card>
  );
}

/**
 * How well backed up a world is, in the two numbers that decide it.
 *
 * The target is not a constant somebody picked: peers here are home machines, assumed reachable
 * about a third of the time, and a world with R independent copies is unreachable with probability
 * (1 - availability)^R. Requiring that to stay under 0.01% is what asks for ~22 copies. The target
 * is then capped by the peers that exist, so **a small network wants a full copy on every peer** —
 * which is why a two-peer swarm reads "2 of 2" and is genuinely finished, not 9% of the way to
 * something.
 *
 * The risk is shown beside the count because a bare "3 of 22" invites the reading "mostly there",
 * and three copies of a world whose holders are online a third of the time is a 27% chance that
 * nobody has it at this moment.
 */
function BackupCopies(props: { world: World }) {
  const w = props.world;
  const safe = w.backup_copies_wanted > 0 && w.backup_copies >= w.backup_copies_wanted;
  return (
    <span className="inline-flex flex-wrap items-baseline gap-x-2 gap-y-1">
      <span className={cx("tabular-nums", safe ? "text-up" : "text-warn")}>
        {w.backup_copies} of {w.backup_copies_wanted} full {w.backup_copies_wanted === 1 ? "copy" : "copies"}
      </span>
      <span className="text-xs text-faint">
        {safe
          ? "as many as this network can hold"
          : w.loss_risk_permille === null
            ? "more copies wanted"
            : `${formatPercent(w.loss_risk_permille)} chance nobody has it right now`}
      </span>
    </span>
  );
}

/* -------------------------------------------------------------------------------------- share */

/**
 * The invitation for this world.
 *
 * Modelled on a magnet link on purpose: people already know that a link is the whole invitation,
 * that it contains no content, and that receiving one does not oblige the sender to stay online
 * while you think about it. All three are true here, and the copy says so — because a link that
 * looked like a download would get treated like one.
 */
function ShareTab(props: { world: World }) {
  const w = props.world;
  const [uri, setUri] = useState("");
  const [saved, setSaved] = useState("");
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  // One world, one link. Carrying a previous world's invitation across a selection change would
  // hand somebody the wrong world with the right name on it.
  useEffect(() => {
    setUri("");
    setSaved("");
    setCopied(false);
    setError("");
  }, [w.world_id]);

  const mint = () => {
    setBusy(true);
    setError("");
    worldShareLink(w.world_id)
      .then((outcome) => (outcome.ok ? setUri(outcome.value) : setError(outcome.error)))
      .catch((e: unknown) => setError(String(e)))
      .finally(() => setBusy(false));
  };

  return (
    <>
      <OwnershipTab world={w} />
      <DeleteCard world={w} />

      <Card
        title="Invite someone"
        hint="Carries the world id, this node's trackers and rendezvous services, and the world's public key. No content, no password."
        right={
          <button className={BUTTON} onClick={mint} disabled={busy}>
            {busy ? "Minting…" : uri ? "Mint again" : "Create link"}
          </button>
        }
      >
        {error && <p className="py-2 text-sm text-danger">{error}</p>}
        {!uri && !error && (
          <p className="py-2 text-sm text-faint">Not created yet.</p>
        )}
        {uri && (
          <>
            <textarea
              className={cx(
                MONO,
                "mt-1 h-[76px] w-full resize-none rounded-sm border border-line bg-surface-2 p-2.5",
                "break-all focus:border-brand-1 focus:outline-none",
              )}
              readOnly
              value={uri}
              onFocus={(e) => e.currentTarget.select()}
            />
            <div className="flex flex-wrap items-center gap-2 pt-2">
              <button
                className={BUTTON}
                onClick={() => {
                  void navigator.clipboard?.writeText(uri);
                  setCopied(true);
                }}
              >
                <FiCopy aria-hidden className="inline" /> {copied ? "Copied" : "Copy link"}
              </button>
              <button
                className={BUTTON}
                onClick={() => {
                  saveShareFile(w.name || w.world_id.slice(0, 12), uri)
                    .then(setSaved)
                    .catch((e: unknown) => setError(String(e)));
                }}
              >
                <FiSave aria-hidden className="inline" /> Save as a file
              </button>
              {saved && <span className={cx(MONO, "text-xs text-up")}>{saved}</span>}
            </div>
          </>
        )}
      </Card>

    </>
  );
}

const BUTTON =
  "rounded-sm border border-line bg-surface-2 px-2.5 py-1 text-xs hover:bg-surface-hover disabled:opacity-50";

/* ---------------------------------------------------------------------------------- ownership */

/**
 * Who runs this world, and the button that demonstrates it.
 *
 * The wording here is load-bearing. Pressing the button asks *this* peer to sign a challenge the app
 * just generated; a success shows this machine holds the world's private key. It is not a
 * cryptographic verification — the app has no Ed25519 implementation and would be checking a claim
 * about the process it is already talking to. Verification is what other peers do, against the world
 * public key in the ownership record, and the copy says so rather than implying more.
 */
function OwnershipTab(props: { world: World }) {
  const w = props.world;
  const [proof, setProof] = useState<AdminProof | null>(null);
  const [busy, setBusy] = useState(false);

  // A proof is about one world; carrying one across a selection change would show the previous
  // world's answer under this world's name.
  useEffect(() => {
    setProof(null);
  }, [w.world_id]);

  return (
    <>
      <Card
        title="Administration"
        hint="Every world has its own key pair; the peer that created it holds the private half."
      >
        <dl>
          <KeyValue
            label="Administrator"
            value={w.administered ? "You" : "Another peer"}
            title={w.administered ? "This peer holds the world's private key." : undefined}
          />
          <KeyValue
            label="World public key"
            value={w.world_public_key || UNKNOWN}
            mono={Boolean(w.world_public_key)}
            title="The world's own public key, not this node's identity key."
          />
          <KeyValue label="World ID" value={w.world_id || UNKNOWN} mono />
        </dl>
      </Card>

      {w.administered ? (
        <Card
          title="Prove it"
          hint="Signs a fresh challenge with this world's private key. Shows the key is on this machine; verification is what other peers do."
          right={
            <button
              className="rounded-sm border border-line bg-surface-2 px-3 py-1.5 text-sm hover:bg-surface-hover disabled:opacity-50"
              disabled={busy}
              onClick={() => {
                setBusy(true);
                proveWorldAdmin(w.world_id)
                  .then(setProof)
                  .catch((e: unknown) =>
                    setProof({
                      signed: false,
                      challenge: "",
                      proof: "",
                      error: String(e),
                    }),
                  )
                  .finally(() => setBusy(false));
              }}
            >
              {busy ? "Signing…" : "Sign a challenge"}
            </button>
          }
        >
          {proof === null ? (
            <p className="py-2 text-sm text-faint">Not asked yet.</p>
          ) : proof.signed ? (
            <dl>
              <KeyValue
                label="Result"
                value={
                  <span className="inline-flex items-center gap-1.5 text-up">
                    <FiCheck aria-hidden /> Your peer signed this challenge
                  </span>
                }
              />
              <KeyValue label="Challenge sent" value={proof.challenge} mono />
              <KeyValue label="Signed proof" value={shortId(proof.proof, 24, 12)} mono title={proof.proof} />
            </dl>
          ) : (
            <dl>
              <KeyValue
                label="Result"
                value={
                  <span className="inline-flex items-center gap-1.5 text-danger">
                    <FiX aria-hidden /> No proof
                  </span>
                }
              />
              <KeyValue label="Reason" value={proof.error || "the worker declined"} />
            </dl>
          )}
        </Card>
      ) : (
        <Card title="Prove it">
          <p className="py-2 text-sm text-faint">
            This peer holds no key for this world, so it cannot sign for it.
          </p>
        </Card>
      )}
    </>
  );
}

/* ------------------------------------------------------------------------------------ deletion */

/**
 * Delete this world from the whole network.
 *
 * The one control in this app that destroys something. It is shown only for a world this peer
 * administers, because the worker refuses without the world's private key — offering it otherwise
 * would be offering an action that cannot work, dressed up as one that might.
 *
 * The confirmation is the world's name typed out, not a second button. A second button is a
 * double-click; typing the name is a decision. Every peer that receives the request verifies the
 * owner's signature before destroying its copy, so nothing here depends on this app being trusted —
 * and equally, nothing here can be undone by it.
 */
function DeleteCard(props: { world: World }) {
  const w = props.world;
  const [confirmation, setConfirmation] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<{ done: boolean; message: string } | null>(null);

  // A half-typed confirmation must not survive a change of world; it would be pointing at a
  // different world than the one the user was looking at when they typed it.
  useEffect(() => {
    setConfirmation("");
    setReason("");
    setOutcome(null);
  }, [w.world_id]);

  if (!w.administered) return null;

  // Falls back to the world id, and the id is never blank — a world whose name is empty *or only
  // whitespace* used to produce `expected === ""`, and `"" === ""` armed an irreversible Delete
  // with nothing typed. The guard below makes that impossible rather than unlikely.
  const named = w.name.trim();
  const expected = named.length > 0 ? named : w.world_id.trim();
  const armed =
    expected.length > 0 &&
    confirmation.trim().toLowerCase() === expected.toLowerCase() &&
    !busy;

  return (
    <Card
      title="Delete this world"
      hint="Asks every peer to destroy its copy. Signed with this world's private key, and verified by each peer before anything is deleted."
    >
      <p className="py-1 text-sm text-danger">
        This cannot be undone. The world's data is removed from this peer and from every peer that
        receives the request; the trackers remember the deletion for 120 days so peers that are
        offline today still hear about it.
      </p>
      <div className="flex flex-col gap-2 py-1">
        <input
          className="rounded-sm border border-line bg-surface-2 px-2.5 py-[6px] text-sm focus:border-brand-1 focus:outline-none"
          placeholder={`Type ${expected} to confirm`}
          value={confirmation}
          disabled={outcome?.done}
          onChange={(e) => setConfirmation(e.currentTarget.value)}
        />
        <input
          className="rounded-sm border border-line bg-surface-2 px-2.5 py-[6px] text-sm focus:border-brand-1 focus:outline-none"
          placeholder="Reason (optional, shown to other players)"
          maxLength={200}
          value={reason}
          disabled={outcome?.done}
          onChange={(e) => setReason(e.currentTarget.value)}
        />
        <button
          className="self-start rounded-sm border border-danger px-3 py-1.5 text-sm text-danger hover:bg-surface-hover disabled:opacity-40"
          disabled={!armed || outcome?.done}
          onClick={() => {
            setBusy(true);
            setOutcome(null);
            deleteWorld(w.world_id, reason)
              .then((result) =>
                setOutcome(
                  result.deleted
                    ? {
                        done: true,
                        // Said exactly: peers reached NOW. Zero is still a deletion — it means
                        // nobody else was connected at this moment, not that it failed.
                        message: `Deleted. ${result.peers_notified} peer(s) told directly; the trackers tell the rest.`,
                      }
                    : { done: false, message: result.error || "the worker declined" },
                ),
              )
              .catch((e: unknown) => setOutcome({ done: false, message: String(e) }))
              .finally(() => setBusy(false));
          }}
        >
          <FiTrash2 aria-hidden className="inline" /> {busy ? "Deleting…" : "Delete everywhere"}
        </button>
      </div>
      {outcome && (
        <p className={cx("py-1 text-sm", outcome.done ? "text-up" : "text-danger")}>
          {outcome.message}
        </p>
      )}
    </Card>
  );
}

/* -------------------------------------------------------------------------------------- pieces */

function PiecesTab(props: { world: World }) {
  const [map, setMap] = useState<PieceMapView>(EMPTY_PIECE_MAP);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoaded(false);
    const pull = () =>
      fetchPieceMap(props.world.world_id)
        .then((m) => {
          if (alive) {
            setMap(m);
            setLoaded(true);
          }
        })
        .catch(() => alive && setLoaded(true));
    pull();
    // Pulled on a slow cadence rather than pushed: a piece map is big, and it only matters while
    // this tab is open.
    const timer = window.setInterval(pull, 3000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [props.world.world_id]);

  // Before the first `get_piece_map` returns there is nothing to draw, and drawing the empty
  // manifest anyway would render a full grid of unheld cells — a picture that says "you hold none
  // of this world" when the truth is that nobody has been asked yet. Same defect as a stat tile
  // showing `0`, in the one place on this screen where it would be a whole screenful of it.
  if (!loaded) {
    return (
      <Card title="Pieces" hint="Asking your peer which pieces this node holds.">
        <div className="my-2 h-[120px] animate-pulse rounded-sm border border-line-soft bg-surface-2" />
        <p className="pb-1 text-xs text-faint">Loading piece map…</p>
      </Card>
    );
  }

  if (map.piece_count === 0) {
    return (
      <Empty icon={<FiGrid />} title="No content pieces on this node" />
    );
  }

  return (
    <Card
      title="Pieces"
      hint={
        map.held.length <= EXACT_PIECE_LIMIT
          ? "Exact map: one cell per piece. Filled means present here and hash-checked."
          : "Bounded density map: each cell summarizes a contiguous numbered piece range."
      }
      right={
        <span className="text-xs text-faint">
          {map.held_count} / {map.piece_count} · {formatBytes(map.total_bytes)}
        </span>
      }
    >
      <PieceCanvas held={map.held} />
    </Card>
  );
}

const EXACT_PIECE_LIMIT = 2048;

/** Exact for small manifests; bounded contiguous-range density buckets for large manifests. */
function PieceCanvas(props: { held: boolean[] }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [bucketCount, setBucketCount] = useState(0);
  const aggregated = props.held.length > EXACT_PIECE_LIMIT;
  useEffect(() => {
    const element = canvas.current;
    if (!element) return;
    const width = Math.max(240, element.clientWidth);
    const cell = 5;
    const columns = Math.max(1, Math.floor(width / cell));
    const rows = aggregated
      ? Math.min(48, Math.max(1, Math.ceil(props.held.length / columns)))
      : Math.max(1, Math.ceil(props.held.length / columns));
    const buckets = aggregated ? Math.min(props.held.length, columns * rows) : props.held.length;
    setBucketCount(buckets);
    const height = Math.max(cell, rows * cell);
    const ratio = window.devicePixelRatio || 1;
    element.width = width * ratio;
    element.height = height * ratio;
    element.style.height = `${height}px`;
    const context = element.getContext("2d");
    if (!context) return;
    context.scale(ratio, ratio);
    const styles = getComputedStyle(document.documentElement);
    context.fillStyle = styles.getPropertyValue("--surface-2");
    context.fillRect(0, 0, width, height);
    const color = styles.getPropertyValue("--brand-1").trim();
    if (aggregated) {
      for (let bucket = 0; bucket < buckets; bucket += 1) {
        const start = Math.floor((bucket * props.held.length) / buckets);
        const end = Math.max(start + 1, Math.floor(((bucket + 1) * props.held.length) / buckets));
        let count = 0;
        for (let index = start; index < end; index += 1) if (props.held[index]) count += 1;
        if (count === 0) continue;
        context.globalAlpha = Math.max(0.2, count / (end - start));
        context.fillStyle = color;
        context.fillRect((bucket % columns) * cell, Math.floor(bucket / columns) * cell, cell - 1, cell - 1);
      }
    } else {
      // The exact branch never set a fill, so every held cell was painted in the background colour
      // this function had just filled the canvas with — an "exact map" that was uniformly blank for
      // every manifest of 2048 pieces or fewer, which is most of them. The aggregated branch sets
      // it inside its own loop, which is why the bug only ever showed on the common path.
      context.fillStyle = color;
      props.held.forEach((held, index) => {
        if (!held) return;
        context.fillRect((index % columns) * cell, Math.floor(index / columns) * cell, cell - 1, cell - 1);
      });
    }
    context.globalAlpha = 1;
  }, [aggregated, props.held]);
  return (
    <figure className="my-2">
      <canvas
        ref={canvas}
        className="w-full rounded-sm border border-line-soft bg-surface-2"
        role="img"
        aria-label={`${props.held.filter(Boolean).length} of ${props.held.length} pieces held`}
      />
      <figcaption className="mt-2 text-xs text-faint">
        {aggregated
          ? `${bucketCount.toLocaleString()} cells cover contiguous ranges of up to ${Math.ceil(props.held.length / Math.max(1, bucketCount)).toLocaleString()} pieces; brighter cells have a larger held share.`
          : `${props.held.length.toLocaleString()} exact cells in piece-number order, left to right.`}
      </figcaption>
    </figure>
  );
}
