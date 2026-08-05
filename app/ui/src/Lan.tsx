// "You opened a world to LAN. Shall I put it on the Nodera network?"
//
// Two surfaces for one decision, and the second is the important one. The **modal** appears when a
// world is first detected, because that is the moment the player is thinking about it. The **card**
// is always there, listing every LAN world this machine can see with its current state — so a player
// who dismissed the modal, or who opened the world before the app was running, can still say yes
// later. A prompt you can only answer once is a prompt that has to be answered immediately, which is
// exactly the design that makes people click the wrong button.
import { useEffect, useState } from "react";
import { FiGlobe, FiRadio, FiShare2, FiX } from "react-icons/fi";
import { Button, Card, Empty, Modal, Pill, cx } from "./components";
import {
  lanAction,
  onWorkerEvent,
  type Lan,
  type LanAction,
  type LanSession,
} from "./play";

/** Worlds waiting on an answer — what the modal is raised for. */
function unanswered(lan: Lan): LanSession[] {
  return lan.sessions.filter((s) => s.state === "offered");
}

/**
 * The modal.
 *
 * # Two triggers, deliberately
 *
 * The worker **announces** `lan.opened` the instant it hears the beacon, and this listens for it.
 * That is the trigger that matters: it fires within a couple of seconds of the player pressing the
 * button, whether or not the app was already looking, because the backend replays the event stream
 * from a sequence number.
 *
 * The dashboard's LAN list is the second trigger, and it is the backstop rather than the mechanism —
 * it covers a world that was open before this app ever ran and whose event has since aged out of the
 * worker's buffer. Building the modal on the list *alone* is what did not work: a list is a
 * description of the situation, and something being in it is not the same as it having just
 * happened.
 *
 * # It never covers the consent question
 *
 * The first-run privacy question is a gate, not a notification, and two stacked full-screen dialogs
 * is the design where somebody answers the wrong one. This yields until that is answered.
 */
export function LanOfferModal(props: {
  lan: Lan;
  /** Hold the prompt back while a more important dialog is up (the first-run consent question). */
  blocked?: boolean;
  onAnswered: () => void;
}) {
  const waiting = unanswered(props.lan);
  const [dismissed, setDismissed] = useState<Set<number>>(new Set());
  const [announced, setAnnounced] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // Subscribed once. The worker's announcement is the trigger; the payload carries the port, which
  // is all this needs to match it against the list.
  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | undefined;
    onWorkerEvent((event) => {
      if (!alive || event.event !== "lan.opened") return;
      const port = Number(event.attributes.port);
      if (!Number.isFinite(port)) return;
      // A freshly-announced world clears any earlier dismissal of that port: the player closed a
      // world and opened a new one, which is a new decision.
      setDismissed((previous) => {
        const next = new Set(previous);
        next.delete(port);
        return next;
      });
      setAnnounced((previous) => new Set(previous).add(port));
    })
      .then((un) => {
        if (alive) unlisten = un;
        else un();
      })
      .catch(() => {});
    return () => {
      alive = false;
      unlisten?.();
    };
  }, []);

  // Forget ports that are no longer open, so a world closing takes its prompt with it and a
  // reopened one is asked about again. Compared by value, not by array identity: the dashboard
  // payload is a fresh object on every push, and reacting to that would rewrite this state several
  // times a second for nothing.
  const openPorts = props.lan.sessions.map((s) => s.port).sort().join(",");
  useEffect(() => {
    const open = new Set(openPorts ? openPorts.split(",").map(Number) : []);
    const prune = (previous: Set<number>) => {
      const next = new Set([...previous].filter((port) => open.has(port)));
      return next.size === previous.size ? previous : next;
    };
    setDismissed(prune);
    setAnnounced(prune);
  }, [openPorts]);

  // The one the worker just told us about wins over one that was merely already in the list: with
  // two worlds open, the prompt should be about the thing that just happened.
  const pending = waiting.filter((s) => !dismissed.has(s.port));
  const session = pending.find((s) => announced.has(s.port)) ?? pending[0];
  if (!session || props.blocked) return null;

  const answer = (action: LanAction) => {
    setBusy(true);
    setError("");
    lanAction(action, session.port)
      .then((outcome) => {
        if (outcome.ok) props.onAnswered();
        else setError(outcome.error);
      })
      .catch((e: unknown) => setError(String(e)))
      .finally(() => setBusy(false));
  };

  return (
    <Modal
      title={`Share “${session.name}” with the Nodera network?`}
      width="md"
      busy={busy}
      onClose={() => setDismissed((current) => new Set(current).add(session.port))}
      footer={
        <>
          <Button
            variant="ghost"
            size="md"
            onClick={() => setDismissed((current) => new Set(current).add(session.port))}
            disabled={busy}
          >
            Not now
          </Button>
          <Button variant="secondary" size="md" onClick={() => answer("DECLINE")} disabled={busy}>
            Don’t share this world
          </Button>
          <Button variant="primary" size="md" onClick={() => answer("SHARE")} disabled={busy}>
            {busy ? "Sharing…" : "Share it"}
          </Button>
        </>
      }
    >
        <div className="flex items-start gap-3">
          <span className="grid h-10 w-10 flex-none place-items-center rounded-md border border-brand-edge bg-brand-soft text-brand-tint">
            <FiRadio />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-2xs font-medium tracking-[0.16em] text-faint uppercase">LAN world detected</p>
            <p className="mt-1 text-sm text-dim">
              You just opened this world to your local network. Nodera can extend it so friends
              anywhere can join — they connect straight to your running game.
            </p>
          </div>
        </div>

        <ul className="mt-4 flex flex-col gap-1.5 text-sm text-dim">
          <li>
            <b className="text-text">Nothing is uploaded.</b> Your world stays on this machine; only
            the connection travels.
          </li>
          <li>
            <b className="text-text">It lasts as long as the world is open.</b> Close it to LAN and
            it leaves the network immediately.
          </li>
          <li>
            <b className="text-text">Your friends need no mod.</b> They join from their own Nodera
            app.
          </li>
        </ul>

        {error && <p className="mt-3 text-sm text-danger">{error}</p>}

    </Modal>
  );
}

/**
 * The always-visible list.
 *
 * This is what makes "later" possible, and it is also the honest answer to "is my world on the
 * network right now?" — a question the modal cannot answer once it is gone.
 */
export function LanCard(props: {
  lan: Lan;
  onChanged: () => void;
  /** Whether a snapshot has arrived. Without it, "not available" is asserted from ignorance. */
  known?: boolean;
}) {
  const { lan } = props;
  const known = props.known ?? true;
  const [busy, setBusy] = useState(0);
  const [error, setError] = useState("");

  const act = (action: LanAction, port: number) => {
    setBusy(port);
    setError("");
    lanAction(action, port)
      .then((outcome) => {
        if (outcome.ok) props.onChanged();
        else setError(outcome.error);
      })
      .catch((e: unknown) => setError(String(e)))
      .finally(() => setBusy(0));
  };

  // `known` is the difference between "your peer says it cannot" and "your peer has not said yet".
  // Without it this card asserted "Not available on this machine" on **every launch**, because
  // `EMPTY_DASHBOARD.lan.supported` is false until the first snapshot lands — a confident negative
  // claim about the user's machine, made from having no information at all.
  if (!known) {
    return (
      <Card title="Worlds open to LAN">
        <Empty icon={<FiX />} title="Waiting for your peer" />
      </Card>
    );
  }

  if (!lan.supported) {
    return (
      <Card
        title="Worlds open to LAN"
        hint="Nodera can extend a world you open to LAN so friends anywhere can join it."
      >
        <Empty icon={<FiX />} title="Not available on this machine">
          {/* The worker's own words, when it gave any. The field exists precisely to separate
              "switched off, and you can fix it" from "this machine cannot do it at all", and
              hardcoding one sentence collapsed the two into the discouraging one. */}
          {lan.reason
            ? lan.reason
            : "Your peer could not listen for Minecraft\u2019s local-network announcements, so it cannot notice a world being opened to LAN here. Sharing a world from inside the game still works."}
        </Empty>
      </Card>
    );
  }

  return (
    <Card
      title="Worlds open to LAN"
      hint="Nothing is on the network until you share it."
    >
      {lan.sessions.length === 0 ? (
        <Empty icon={<FiGlobe />} title="No world is open to LAN">
          Open a world in Minecraft and choose <b>Open to LAN</b>.
        </Empty>
      ) : (
        <div className="flex flex-col">
          {lan.sessions.map((session) => (
            <div
              key={session.port}
              className="flex flex-wrap items-center gap-2.5 border-b border-line-soft py-2.5 last:border-b-0"
            >
              <span className="min-w-0 flex-1 truncate">{session.name}</span>
              <span className="font-mono text-[11px] text-faint">port {session.port}</span>
              <Pill
                tone={
                  session.state === "shared" ? "up" : session.state === "declined" ? "muted" : "warn"
                }
              >
                {session.state === "shared"
                  ? "On the network"
                  : session.state === "declined"
                    ? "Not shared"
                    : "Waiting for you"}
              </Pill>
              {session.state === "shared" ? (
                <button
                  className={cx(BUTTON, "text-warn")}
                  disabled={busy === session.port}
                  onClick={() => act("STOP", session.port)}
                >
                  Stop sharing
                </button>
              ) : (
                <button
                  className={BUTTON}
                  disabled={busy === session.port}
                  onClick={() => act("SHARE", session.port)}
                >
                  <FiShare2 aria-hidden className="inline" /> Share
                </button>
              )}
            </div>
          ))}
        </div>
      )}
      {error && <p className="pt-2 text-sm text-danger">{error}</p>}
    </Card>
  );
}

const BUTTON =
  "rounded-sm border border-line bg-surface-2 px-2.5 py-1 text-xs hover:bg-surface-hover disabled:opacity-50";
