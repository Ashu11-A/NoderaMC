// Choosing where the peer keeps world data.
//
// Two ways in, because Android has two kinds of answer:
//
//  * the **app's own directories** — internal and per-volume — which always work and need no
//    permission at all;
//  * **any folder you like**, chosen in the system file manager, which is what people expect and
//    which Android may still refuse to let a file-writing process use.
//
// The second is offered honestly: the picker runs, the grant is persisted, and then a real write is
// attempted. If Android refuses, the folder is shown as chosen-but-unusable with the reason, rather
// than saved as a setting that quietly stores nothing.
import { useEffect, useRef, useState } from "react";
import { FiAlertTriangle, FiCheck, FiFolder, FiHardDrive } from "react-icons/fi";
import { Button, Card, ListItem, cx } from "../m3/components";
import { Stagger, StaggerItem } from "../m3/motion";
import {
  fetchSettings,
  fetchStorageInfo,
  pickStorageFolder,
  pickedFolder,
  saveSettings,
  type PickedFolder,
  type StorageInfo,
} from "../ipc";

/** How long to wait for the picker's answer before giving up on it. */
const PICK_TIMEOUT_MS = 120_000;
const POLL_MS = 700;

export function StoragePicker(props: {
  info: StorageInfo | null;
  onChanged: (next: StorageInfo) => void;
}) {
  const { info } = props;
  const [busy, setBusy] = useState("");
  const [picked, setPicked] = useState<PickedFolder | null>(null);
  const [waiting, setWaiting] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearInterval(timer.current), []);

  const use = (path: string) => {
    setBusy(path);
    fetchSettings()
      .then((current) =>
        saveSettings({ ...current, storage: { ...current.storage, peer_worlds_dir: path } }),
      )
      .then(fetchStorageInfo)
      .then(props.onChanged)
      .catch(() => {})
      .finally(() => setBusy(""));
  };

  /** Open the picker, then watch for the answer the activity writes. */
  const choose = () => {
    setPicked(null);
    setWaiting(true);
    const started = Date.now();
    void pickStorageFolder().catch((e: unknown) => {
      // `waiting` has to be cleared here too. It was not, so a picker that failed to open left the
      // card reading "Waiting for your choice…" — and, because `onClick` is dropped while waiting,
      // completely untappable — for the full two-minute timeout, with the error never shown.
      window.clearInterval(timer.current);
      setWaiting(false);
      setPicked({ pending: false, uri: "", label: "", path: "", writable: false, error: String(e) });
    });
    window.clearInterval(timer.current);
    timer.current = window.setInterval(() => {
      if (Date.now() - started > PICK_TIMEOUT_MS) {
        window.clearInterval(timer.current);
        setWaiting(false);
        // Say so. A silent revert to "Choose a folder…" is indistinguishable from never having
        // pressed it, which is the one reading that stops the user trying again.
        setPicked({
          pending: false,
          uri: "",
          label: "",
          path: "",
          writable: false,
          error: "The folder picker did not come back. Try again.",
        });
        return;
      }
      void pickedFolder()
        .then((result) => {
          if (result.pending) return;
          window.clearInterval(timer.current);
          setWaiting(false);
          // An empty answer is a cancelled picker: nothing to report, nothing changed.
          if (result.uri) setPicked(result);
        })
        .catch(() => {});
    }, POLL_MS);
  };

  if (!info) return <p className="p-2 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">…</p>;

  return (
    <Stagger className="flex flex-col gap-2">
      <StaggerItem>
        <Card variant="outlined" onClick={waiting ? undefined : choose}>
          <ListItem
            leading={<FiFolder />}
            headline={waiting ? "Waiting for your choice…" : "Choose a folder…"}
            supporting="Opens your file manager"
          />
        </Card>
      </StaggerItem>

      {picked && picked.path !== info.current && (
        <StaggerItem>
          <Card variant={picked.writable ? "filled" : "outlined"}>
            <ListItem
              leading={picked.writable ? <FiCheck /> : <FiAlertTriangle />}
              headline={picked.label || "Chosen folder"}
              supporting={picked.writable ? picked.path : picked.error}
            />
            {picked.writable && (
              <div className="pt-2">
                <Button onClick={() => use(picked.path)} disabled={busy === picked.path}>
                  Use this folder
                </Button>
              </div>
            )}
          </Card>
        </StaggerItem>
      )}

      {info.options.map((option) => (
        <StaggerItem key={option.path}>
          <Card
            variant={info.current === option.path ? "filled" : "outlined"}
            onClick={option.writable ? () => use(option.path) : undefined}
          >
            <ListItem
              leading={<FiHardDrive />}
              headline={option.label}
              supporting={
                option.free_bytes === null ? option.detail : `${gib(option.free_bytes)} free`
              }
              trailing={
                busy === option.path ? (
                  <span className="text-[13px] text-[var(--md-sys-color-on-surface-variant)]">…</span>
                ) : info.current === option.path ? (
                  <span className="text-[13px] text-[var(--md-sys-color-primary)]">In use</span>
                ) : undefined
              }
            />
            <p
              className={cx(
                "font-mono text-[11px] break-all",
                "text-[var(--md-sys-color-on-surface-variant)]",
              )}
            >
              {option.path}
            </p>
          </Card>
        </StaggerItem>
      ))}

      <StaggerItem>
        <p className="px-1 pt-1 text-[12px] text-[var(--md-sys-color-on-surface-variant)]">
          Applies when the peer restarts.
        </p>
      </StaggerItem>
    </Stagger>
  );
}

/** Binary units under a binary name — the function was already dividing by 1024. */
function gib(bytes: number): string {
  const gibibytes = bytes / 1024 ** 3;
  return gibibytes >= 1
    ? `${gibibytes.toFixed(1)} GiB`
    : `${Math.round(bytes / 1024 ** 2)} MiB`;
}
