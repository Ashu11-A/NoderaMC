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
   * The battery step is skipped when the OS is not restricting this app — there is nothing to ask
   * for, and a screen that congratulates you on a setting you never changed is noise.
   */
  const answerTelemetry = (telemetry: boolean) => {
    setBusy(true);
    setError("");
    setTelemetryConsent(telemetry)
      // The failure used to be swallowed and the flow completed anyway, so someone could believe
      // they had answered a question the node never recorded. Reported, and the flow stops here.
      .then(
        () => true,
        (e: unknown) => {
          setError(
            `Your answer could not be recorded on this node (${String(e)}). Try again.`,
          );
          return false;
        },
      )
      .then((recorded) => {
        if (!recorded) return undefined;
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
