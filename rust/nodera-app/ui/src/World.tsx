// One world, in the panes a torrent client puts under a selected item — plus the pane this system
// needs that a torrent client does not: who administers it, and the proof of that.
//
// Everything here reads the same live dashboard payload the home screen does, so a world's numbers
// cannot disagree between the two screens, and a world that disappears from the node disappears
// from here. The only thing pulled separately is the piece grid, because it is large, it is only
// interesting for the selected world, and re-encoding every world's bitmap several times a second
// for a tab nobody has open would be the definition of waste.
import { useEffect, useState } from "react";
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
  cx,
} from "./components";
import {
  UNKNOWN,
  formatBytes,
  formatDate,
  formatPercent,
  formatSource,
  proveWorldAdmin,
  shortId,
  show,
  type AdminProof,
  type World,
} from "./api";
import { deleteWorld, fetchPieceMap, EMPTY_PIECE_MAP, type PieceMapView } from "./ipc";
import { saveShareFile, worldShareLink } from "./network";

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
      <header className="flex items-center gap-3 px-[26px] pt-[18px] pb-3.5">
        <button
          className="grid h-8 w-8 place-items-center rounded-sm border border-line bg-surface hover:bg-surface-hover"
          onClick={props.onBack}
          aria-label="Back to the dashboard"
        >
          <FiArrowLeft />
        </button>
        <span className={cx(AVATAR, "h-[38px] w-[38px] text-[16px]")} aria-hidden>
          {(world.name || "?").slice(0, 1).toUpperCase()}
        </span>
        <div className="min-w-0 flex-1">
          <h1 className="text-[18px]">{world.name || "Unnamed world"}</h1>
          <p className="mt-px text-[12px] text-dim">
            {describeRole(world)} · v{world.version} · {formatBytes(world.total_bytes)}
          </p>
        </div>
        {world.administered && <Pill tone="up">You administer this</Pill>}
        <Pill tone={world.game_endpoint ? "up" : "muted"}>
          {world.game_endpoint ? "Joinable now" : "Game closed"}
        </Pill>
      </header>

      <nav className="flex gap-1 border-b border-line px-[22px]" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            aria-selected={tab === t.id}
            className={cx(
              "flex items-center gap-1.5 border-b-2 px-3.5 py-[9px] text-sm",
              tab === t.id
                ? "border-b-brand-2 text-text"
                : "border-b-transparent text-dim hover:text-text",
            )}
            onClick={() => setTab(t.id)}
          >
            {t.icon}
            {t.label}
          </button>
        ))}
      </nav>

      <div
        className="flex max-w-[1100px] flex-1 flex-col gap-3.5 px-[26px] pt-[18px] pb-10"
        role="tabpanel"
      >
        {tab === "info" && <InfoTab world={world} />}
        {tab === "content" && <PiecesTab world={world} />}
        {tab === "sharing" && <ShareTab world={world} />}
      </div>
    </div>
  );
}

/** The four states a world can be in here, said in words rather than in two booleans. */
function describeRole(w: World): string {
  if (w.administered) return w.hosting ? "Yours — hosted here" : "Yours — kept available here";
  return w.hosting ? "Hosting for another peer" : "Supporting for the network";
}

/* --------------------------------------------------------------------------------------- info */

function InfoTab(props: { world: World }) {
  const w = props.world;
  return (
    <>
      <div className={STAT_GRID}>
        <Stat label="Players online" value={String(w.players)} sub="in this world" icon={<FiUsers />} />
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

    </>
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
                "break-all focus:border-brand-2 focus:outline-none",
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
          className="rounded-sm border border-line bg-surface-2 px-2.5 py-[6px] text-sm focus:border-brand-2 focus:outline-none"
          placeholder={`Type ${expected} to confirm`}
          value={confirmation}
          disabled={outcome?.done}
          onChange={(e) => setConfirmation(e.currentTarget.value)}
        />
        <input
          className="rounded-sm border border-line bg-surface-2 px-2.5 py-[6px] text-sm focus:border-brand-2 focus:outline-none"
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

  if (loaded && map.piece_count === 0) {
    return (
      <Empty icon={<FiGrid />} title="No content pieces on this node" />
    );
  }

  return (
    <Card
      title="Pieces"
      hint="One cell per piece. Filled = present here and hash-checked."
      right={
        <span className="text-xs text-faint">
          {map.held_count} / {map.piece_count} · {formatBytes(map.total_bytes)}
        </span>
      }
    >
      <div className="flex flex-wrap gap-[3px] py-2">
        {map.held.map((held, index) => (
          <span
            key={index}
            className={cx(
              "h-[9px] w-[9px] rounded-[2px]",
              held ? "bg-up" : "bg-surface-2 border border-line",
            )}
            title={`Piece ${index}: ${held ? "held" : "missing"}`}
          />
        ))}
      </div>
    </Card>
  );
}
