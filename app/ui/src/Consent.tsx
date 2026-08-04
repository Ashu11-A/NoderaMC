// App task 5: the first-run telemetry question, and the Privacy card that lets someone change
// their mind.
//
// Three rules are structural here, not stylistic, and a change that breaks one is a bug:
//
//   1. Neither answer is pre-selected, primary-styled, or given a larger hit area. A dialog
//      engineered to make "yes" easier produces consent that is worth nothing.
//   2. Declining is final. The modal is gated on "has this installation ever answered", which is
//      set by BOTH answers — there is no "not now" that really means "ask again next week".
//   3. The state shown is what the WORKER confirmed. The app never renders its own intent.
import { useEffect, useState } from "react";
import { FiShield, FiCheck, FiX, FiAlertCircle } from "react-icons/fi";
import { Button, Card, Toggle, StatusBadge, Disclosure, Modal, cx, MONO } from "./components";
import {
  fetchCollectedSchema,
  fetchTelemetryStatus,
  setTelemetryConsent,
  EMPTY_TELEMETRY,
  type TelemetryStatus,
} from "./ipc";

/** What is collected, in the words a person can check against the running service. */
const BUNDLED_SUMMARY = [
  "Which build you run, on which OS and CPU architecture",
  "Whether shares, joins and re-hosts succeed, and over which path (direct, punched, relayed)",
  "How much traffic and storage a node uses, in buckets — never exact figures",
  "Tick health and how many regions this node validates",
  "Which features get used, as a fixed list of names",
  "Crashes, as a fingerprint — never a message or a stack trace",
  "Your country and network operator, derived at the collector from an address it then discards",
];

const NEVER_COLLECTED = [
  "World names, seeds, or contents",
  "Player names, UUIDs, or chat",
  "Coordinates or anything about where you are in a world",
  "File paths, IP addresses, or your node's identity key",
  "Free text of any kind — the collector cannot store it",
];

/**
 * The first-run modal.
 *
 * Rendered only when the node has never been asked. Answering either way records the answer and
 * closes it for good.
 */
export function ConsentModal(props: {
  status: TelemetryStatus;
  onAnswered: (next: TelemetryStatus) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // Nothing to ask: either this installation already answered, or the worker cannot record an
  // answer at all — and asking a question whose answer cannot be stored would be theatre.
  if (props.status.asked || !props.status.supported) {
    return null;
  }

  async function answer(granted: boolean) {
    setBusy(true);
    setError("");
    try {
      const status = await setTelemetryConsent(granted);
      props.onAnswered(status);
      // Cleared even on success. This used to stay `true`, on the assumption that a successful
      // answer always unmounts the modal — but the gate above is `status.asked`, and a worker that
      // accepted the call without recording the answer returns `asked: false`. The modal then
      // stayed on screen as a full-viewport `z-50` overlay with both buttons permanently disabled,
      // and nothing else in the app was reachable.
      if (!status.asked) {
        setError(
          "your answer was sent, but this node did not record it — try again, or check the peer console",
        );
      }
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="Help improve NoderaMC?"
      width="md"
      busy={busy}
      footer={
        <div className="grid w-full grid-cols-2 gap-3">
          {/* Equal variants and dimensions: neither consent answer is visually preferred. */}
          <Button variant="secondary" size="md" disabled={busy} onClick={() => answer(false)}>
            <FiX /> Don't share
          </Button>
          <Button variant="secondary" size="md" disabled={busy} onClick={() => answer(true)}>
            <FiCheck /> Share telemetry
          </Button>
        </div>
      }
    >
        <div className="mb-4 flex items-center gap-3 rounded-md border border-line-soft bg-surface-2 p-3">
          <span className="grid size-10 place-items-center rounded-md border border-brand-2/30 bg-brand-2/8">
            <FiShield className="text-brand-1" size={20} />
          </span>
          <div>
            <p className="text-[10px] font-semibold tracking-[0.18em] text-faint uppercase">First launch</p>
            <p className="text-sm text-dim">Anonymous, optional, and reversible in Settings.</p>
          </div>
        </div>

        <p className="mb-3 text-sm text-dim">
          NoderaMC has no central server, which means nobody can see whether it actually works on
          real machines — whether peers reach each other, whether the engine stays in step, what a
          node costs to run. Sharing anonymous telemetry is how that becomes knowable.
        </p>
        <p className="mb-4 text-sm text-dim">
          It is off unless you turn it on, it never includes anything that identifies you or your
          worlds, and you can change your mind at any time under <b>Settings → Privacy</b>.
        </p>

        <Disclosure title="What exactly would be shared">
          <ul className="mb-3 list-disc pl-5 text-sm text-dim">
            {BUNDLED_SUMMARY.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          <div className="text-sm font-medium">Never shared</div>
          <ul className="list-disc pl-5 text-sm text-dim">
            {NEVER_COLLECTED.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </Disclosure>

        {error && (
          <div className="mt-3 flex items-start gap-2 text-sm text-danger">
            <FiAlertCircle className="mt-0.5 flex-none" />
            <span>{error}</span>
          </div>
        )}

    </Modal>
  );
}

/** Settings → Privacy: the toggle, what the node confirmed, and what is collected. */
export function PrivacyCard(props: {
  status: TelemetryStatus;
  onChanged: (next: TelemetryStatus) => void;
}) {
  const { status } = props;
  const [error, setError] = useState("");
  const [schema, setSchema] = useState<{ text: string; live: boolean } | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchCollectedSchema(status.endpoint)
      .then((text) => !cancelled && setSchema({ text, live: true }))
      // The fallback is labelled as one: a bundled copy is correct at build time and may not be
      // correct at this deployment's time (app LIMITATIONS L-78).
      .catch(() => !cancelled && setSchema({ text: "", live: false }));
    return () => {
      cancelled = true;
    };
  }, [status.endpoint]);

  async function toggle(next: boolean) {
    setError("");
    try {
      props.onChanged(await setTelemetryConsent(next));
    } catch (e) {
      setError(String(e));
      // Re-read: the node is the source of truth even when the write failed.
      fetchTelemetryStatus().then(props.onChanged).catch(() => {});
    }
  }

  return (
    <Card
      title="Privacy"
      hint="Telemetry is off unless you turn it on, and it never identifies you or your worlds."
      right={
        status.supported ? (
          <StatusBadge
            tone={status.consent === "granted" ? "info" : "muted"}
            label={status.consent === "granted" ? "sharing" : "not sharing"}
            title={
              status.consent === "granted"
                ? "The node confirmed it is collecting and sending."
                : "Nothing is collected on this node."
            }
          />
        ) : (
          // Distinct from "off" on purpose: one is a choice, the other is a worker to upgrade.
          <StatusBadge
            tone="warn"
            label="worker cannot"
            title="This worker does not support the telemetry verb — restart it to pick up the bundled version."
          />
        )
      }
    >
      <Toggle
        label="Share anonymous telemetry"
        hint={
          status.supported
            ? "Counts and buckets only. Turning this off stops collection on the node, clears anything queued, and forgets this installation's identifier."
            : "This worker cannot record a telemetry decision — restart it to pick up the bundled version."
        }
        checked={status.consent === "granted"}
        disabled={!status.supported}
        onChange={toggle}
      />

      {error && <div className="mt-2 text-sm text-danger">{error}</div>}

      {/* An answer this app holds but the node has not taken. Not an error — the answer is kept and
          re-offered until a worker accepts it — but it must not be rendered as though it were in
          force, which is why the badge above still follows the node's own `consent`. */}
      {status.pending && (
        <div className="mt-2 text-sm text-warn">
          Your answer ({status.answer ? "share" : "don't share"}) is saved here and will be applied
          to the node as soon as it is running.
        </div>
      )}

      {status.consent === "granted" && (
        <div className={cx("mt-3 grid gap-1 text-sm text-dim", MONO)}>
          <div>collector: {status.endpoint || "not configured on this node"}</div>
          <div>
            queued: {status.queued} · sent: {status.sent}
          </div>
          {status.last_error && <div className="text-warn">last attempt: {status.last_error}</div>}
        </div>
      )}

      <div className="mt-4">
        <Disclosure
          title={
            schema?.live
              ? "What is collected (read from the collector this node reports to)"
              : "What is collected (this app's bundled copy — the collector was unreachable)"
          }
        >
          {/* When the collector answered, show WHAT IT SAID. The previous version fetched that
              answer, labelled the section "read from the collector", and then rendered the bundled
              list anyway — a live claim over static content, which is the one thing a privacy
              disclosure must not be. */}
          {schema?.live && schema.text ? (
            <pre
              className={cx(
                MONO,
                "max-h-[320px] overflow-auto rounded-sm border border-line bg-surface-2 p-2.5",
                "break-words whitespace-pre-wrap text-dim",
              )}
            >
              {schema.text}
            </pre>
          ) : (
            <>
              <ul className="mb-3 list-disc pl-5 text-sm text-dim">
                {BUNDLED_SUMMARY.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
              <div className="text-sm font-medium">Never collected</div>
              <ul className="list-disc pl-5 text-sm text-dim">
                {NEVER_COLLECTED.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </>
          )}
        </Disclosure>
      </div>
    </Card>
  );
}

/** Load the status once and keep it in state — used by both the modal and the Privacy card. */
export function useTelemetryStatus() {
  const [status, setStatus] = useState<TelemetryStatus>(EMPTY_TELEMETRY);
  useEffect(() => {
    fetchTelemetryStatus().then(setStatus).catch(() => {});
  }, []);
  return [status, setStatus] as const;
}
