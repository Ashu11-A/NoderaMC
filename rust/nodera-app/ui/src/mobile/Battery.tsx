// Battery restrictions, said once at launch and available afterwards in Settings.
//
// The modal is deliberately dismissible and the exemption is deliberately not requested
// automatically: it is the user's battery. What the app owes them is the knowledge that Android may
// stop their node without telling them, which is not something anyone would otherwise guess.
import { useEffect, useState } from "react";
import { FiBatteryCharging, FiExternalLink } from "react-icons/fi";
import { Button, Card, ListItem } from "../m3/components";
import { DialogTransition } from "../m3/motion";
import {
  fetchBatteryPolicy,
  openBatteryHelp,
  openBatterySettings,
  type BatteryPolicy,
} from "../ipc";

/** Remembers a dismissal for this install, so the modal is a notice and not a nag. */
const DISMISSED = "nodera.battery.dismissed";

/**
 * The OS's current battery policy for this app, re-read whenever the app comes back to the front.
 *
 * The whole purpose of this screen is to send someone to a *system settings page* and have them
 * return. Reading the policy once per mount meant they came back to a screen still insisting
 * "Optimisation is on" after they had just turned it off — which reads as the change not having
 * worked, and is the fastest way to lose someone's trust in a permissions prompt.
 */
export function useBatteryPolicy(): BatteryPolicy | null {
  const [policy, setPolicy] = useState<BatteryPolicy | null>(null);

  useEffect(() => {
    let alive = true;
    const read = () => {
      fetchBatteryPolicy()
        .then((next) => alive && setPolicy(next))
        .catch(() => alive && setPolicy(null));
    };
    read();
    const onVisible = () => {
      if (document.visibilityState === "visible") read();
    };
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", onVisible);
    return () => {
      alive = false;
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", onVisible);
    };
  }, []);

  return policy;
}

/** The launch notice. Renders nothing unless the OS really is restricting this app. */
export function BatteryModal(props: { policy: BatteryPolicy | null }) {
  const [dismissed, setDismissed] = useState(
    () => window.localStorage?.getItem(DISMISSED) === "1",
  );
  const policy = props.policy;
  const open = Boolean(policy?.supported && policy.restricted && !dismissed);

  const close = () => {
    try {
      window.localStorage?.setItem(DISMISSED, "1");
    } catch {
      /* a dismissal that cannot be stored is still a dismissal for this session */
    }
    setDismissed(true);
  };

  return (
    <DialogTransition open={open}>
      <div className="rounded-[28px] bg-[var(--md-sys-color-surface-container-high)] p-6">
        <div className="text-[24px] text-[var(--md-sys-color-primary)]">
          <FiBatteryCharging />
        </div>
        <h2 className="mt-3 text-[22px] leading-7 text-[var(--md-sys-color-on-surface)]">
          Android can stop your node
        </h2>
        <p className="mt-2 text-[14px] leading-5 text-[var(--md-sys-color-on-surface-variant)]">
          Battery optimisation is on for Nodera. Other players may lose the worlds you are keeping
          online when the phone sleeps.
        </p>
        <div className="mt-5 flex flex-wrap justify-end gap-2">
          <Button variant="text" onClick={() => void openBatteryHelp()}>
            {policy?.manufacturer || "Learn more"}
          </Button>
          <Button variant="text" onClick={close}>
            Not now
          </Button>
          <Button
            onClick={() => {
              void openBatterySettings();
              close();
            }}
          >
            Open settings
          </Button>
        </div>
      </div>
    </DialogTransition>
  );
}

/** The Settings › Battery page. */
export function BatteryPage() {
  // The same hook the shell uses, rather than a second fetch of its own: two independent reads of
  // one fact can disagree, and this page and the launch modal disagreeing about whether the OS is
  // restricting the node is exactly the kind of contradiction nobody can act on. It also brings the
  // refresh-on-resume with it, which is what this page most needs.
  const policy = useBatteryPolicy();

  if (!policy) return <p className="p-2 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">…</p>;
  if (!policy.supported) {
    return (
      <Card>
        <ListItem
          headline="Not applicable"
          supporting={
            // The probe's own failure, when there was one. Discarding it here meant a platform
            // check that broke looked identical to a platform that has no such setting.
            policy.error || "This platform does not restrict background apps"
          }
        />
      </Card>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <Card>
        <ListItem
          leading={<FiBatteryCharging />}
          headline={policy.restricted ? "Optimisation is on" : "Optimisation is off"}
          supporting={
            policy.error
              ? policy.error
              : policy.restricted
                ? "Android may stop the node in the background"
                : "The node can keep running in the background"
          }
        />
        {policy.restricted && (
          <div className="pt-2">
            <Button onClick={() => void openBatterySettings()}>Disable battery optimisation</Button>
          </div>
        )}
      </Card>
      <Card onClick={() => void openBatteryHelp()}>
        <ListItem
          leading={<FiExternalLink />}
          headline="Don't kill my app!"
          supporting={policy.manufacturer ? `Advice for ${policy.manufacturer}` : "dontkillmyapp.com"}
        />
      </Card>
    </div>
  );
}
