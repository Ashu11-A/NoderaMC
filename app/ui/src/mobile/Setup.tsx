// First run: the two questions that cannot be answered by a default.
//
// Storage, because the peer writes other people's world data and the user is entitled to say where
// — an app that picks silently is an app that fills your phone without asking. Telemetry, because
// consent that was not given is not consent.
//
// Both are asked once, and the flow does not close until both are answered.
import { useCallback, useEffect, useState } from "react";
import { FiBatteryCharging, FiExternalLink, FiShield } from "react-icons/fi";
import { Button, Card, ListItem, cx } from "../m3/components";
import { StoragePicker } from "./Storage";
import {
  completeSetup,
  fetchBatteryPolicy,
  fetchStorageInfo,
  openBatteryHelp,
  openBatterySettings,
  setTelemetryConsent,
  type BatteryPolicy,
  type StorageInfo,
} from "../ipc";

type Step = "storage" | "telemetry" | "battery";

export function SetupFlow(props: { onDone: () => void }) {
  const [step, setStep] = useState<Step>("storage");
  const [info, setInfo] = useState<StorageInfo | null>(null);
  const [chosen, setChosen] = useState<string>("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  /** Something the person should know but that must not stop them — rendered without alarm colour. */
  const [notice, setNotice] = useState("");
  const [battery, setBattery] = useState<BatteryPolicy | null>(null);

  useEffect(() => {
    fetchBatteryPolicy().then(setBattery).catch(() => setBattery(null));
  }, []);

  const loadStorage = useCallback(() => {
    setError("");
    fetchStorageInfo()
      .then((current) => {
        setInfo(current);
        setChosen(current.current);
      })
      .catch((e: unknown) => setError(String(e)));
  }, []);

  useEffect(loadStorage, [loadStorage]);

  /**
   * Record the telemetry answer, then either show the battery step or finish.
   *
   * **The answer is never lost and the flow is never blocked.** It used to be pushed straight at the
   * worker, and a worker that had not finished starting — which on a fresh install is the normal
   * case, since first run and worker startup are the same minute — turned this screen into a dead
   * end: "your answer could not be recorded on this node (connection refused). Try again." with no
   * way past it and nothing that would ever make Try again work. The backend now records the answer
   * on this device and delivers it to the node when one answers, so what is left here is a note, not
   * a wall.
   *
   * The battery step is skipped when the OS is not restricting this app — there is nothing to ask
   * for, and a screen that congratulates you on a setting you never changed is noise.
   */
  const answerTelemetry = (telemetry: boolean) => {
    setBusy(true);
    setError("");
    setTelemetryConsent(telemetry)
      .then(
        (status) => {
          // Said plainly, and only as a note: their answer is kept and will reach the node. This is
          // the one thing they are entitled to know, and it is not a reason to stop.
          if (status.pending) {
            setNotice(
              "Your answer is saved on this device. It will be applied to your node as soon as it is running.",
            );
          }
        },
        // A rejection now means the command itself failed, not that the node was busy — the backend
        // does not reject for an unreachable worker. Still not a dead end: the person answered.
        (e: unknown) =>
          setNotice(`Your answer is saved on this device (${String(e)}).`),
      )
      .then(() => {
        // `battery` is fetched asynchronously and may still be null — answering quickly used to
        // skip the battery step entirely, which is the one thing this flow exists to say. Resolve
        // it now if it has not arrived, and only skip when the OS really is unrestricted.
        const decide = battery
          ? Promise.resolve(battery)
          : fetchBatteryPolicy().catch(() => null);
        return decide.then((policy) => {
          if (policy?.supported && policy.restricted) {
            setBattery(policy);
            setStep("battery");
            return undefined;
          }
          return finish();
        });
      })
      .catch((e: unknown) => setError(String(e)))
      .finally(() => setBusy(false));
  };

  // `busy` is set here rather than only by the telemetry step: the battery step's two buttons were
  // already `disabled={busy}` but nothing ever set it, so a double tap ran `completeSetup` twice.
  const finish = () => {
    setBusy(true);
    return completeSetup(chosen)
      .then(props.onDone)
      .catch((e: unknown) => {
        setError(String(e));
        setBusy(false);
      });
  };

  return (
    <div className="flex h-full flex-col bg-[var(--md-sys-color-surface)] text-[var(--md-sys-color-on-surface)]">
      <header className="px-6 pt-[calc(env(safe-area-inset-top)+32px)] pb-4">
        <h1 className="text-[28px] leading-9">
          {step === "storage"
            ? "Where should worlds live?"
            : step === "telemetry"
              ? "Share diagnostics?"
              : "Keep the node running"}
        </h1>
        <p className="mt-2 text-[14px] leading-5 text-[var(--md-sys-color-on-surface-variant)]">
          {step === "storage"
            ? "Your peer stores world data for other players here."
            : step === "telemetry"
              ? "Off by default. You can change this later."
              : "Android may stop Nodera in the background. This step is optional."}
        </p>
      </header>

      <div className="flex-1 overflow-y-auto px-4 pb-4">
        {step === "storage" ? (
          <StoragePicker
            info={info}
            onChanged={(next) => {
              setInfo(next);
              setChosen(next.current);
            }}
          />
        ) : step === "telemetry" ? (
          <Card variant="outlined">
            <ListItem
              leading={<FiShield />}
              headline="Anonymous diagnostics"
              supporting="Crash and performance counters. Never world data, chat, or your identity."
            />
          </Card>
        ) : (
          <div className="flex flex-col gap-2">
            <Card variant="outlined">
              <ListItem
                leading={<FiBatteryCharging />}
                headline="Battery optimisation is on"
                supporting="Other players may lose the worlds you host when the phone sleeps."
              />
            </Card>
            <Card onClick={() => void openBatteryHelp()}>
              <ListItem
                leading={<FiExternalLink />}
                headline="Don't kill my app!"
                supporting={
                  battery?.manufacturer ? `Advice for ${battery.manufacturer}` : "dontkillmyapp.com"
                }
              />
            </Card>
          </div>
        )}
        {error && (
          <p className="px-2 pt-3 text-[14px] text-[var(--md-sys-color-error)]">{error}</p>
        )}
        {notice && (
          <p className="px-2 pt-3 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">
            {notice}
          </p>
        )}
      </div>

      <footer
        className={cx("flex gap-3 px-4 pt-2")}
        style={{ paddingBottom: "calc(env(safe-area-inset-bottom) + 16px)" }}
      >
        {step === "storage" ? (
          // Without the retry, a failed `fetchStorageInfo` left `chosen` empty and Continue
          // permanently disabled — first run became a dead screen with an error and no way out,
          // and the app cannot be used at all until setup completes.
          !info && error ? (
            <Button className="flex-1" onClick={loadStorage}>
              Try again
            </Button>
          ) : (
            <Button className="flex-1" disabled={!chosen} onClick={() => setStep("telemetry")}>
              Continue
            </Button>
          )
        ) : step === "telemetry" ? (
          <>
            <Button
              variant="outlined"
              className="flex-1"
              disabled={busy}
              onClick={() => answerTelemetry(false)}
            >
              No thanks
            </Button>
            <Button className="flex-1" disabled={busy} onClick={() => answerTelemetry(true)}>
              Share
            </Button>
          </>
        ) : (
          <>
            <Button variant="outlined" className="flex-1" disabled={busy} onClick={() => void finish()}>
              Skip
            </Button>
            <Button
              className="flex-1"
              disabled={busy}
              onClick={() => {
                void openBatterySettings();
                void finish();
              }}
            >
              Open settings
            </Button>
          </>
        )}
      </footer>
    </div>
  );
}
