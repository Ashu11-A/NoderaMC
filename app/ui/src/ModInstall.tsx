// Minecraft mod — install the bundled jar into a Minecraft this machine has.
//
// Installs only the jar this build shipped with, so the mod and the worker always speak the same
// protocol, and removes only a jar it recognises as ours.
import { useCallback, useEffect, useState } from "react";
import { FiAlertTriangle, FiCheck, FiFolder, FiPackage, FiTrash2 } from "react-icons/fi";
import { Card, Empty, MONO, Pill, cx } from "./components";
import { installMod, modInstallStatus, uninstallMod, type ModInstallStatus } from "./play";

export function ModInstallScreen() {
  const [status, setStatus] = useState<ModInstallStatus | null>(null);
  const [busy, setBusy] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  const refresh = useCallback(() => {
    modInstallStatus()
      .then(setStatus)
      .catch((e: unknown) => setError(String(e)));
  }, []);

  useEffect(refresh, [refresh]);

  const run = (
    action: (dir: string) => Promise<string>,
    dir: string,
    describe: (result: string) => string,
  ) => {
    setBusy(dir);
    setError("");
    setMessage("");
    action(dir)
      .then((result) => setMessage(describe(result)))
      .catch((e: unknown) => setError(String(e)))
      .finally(() => {
        setBusy("");
        refresh();
      });
  };

  return (
    // Two cards that answer one question between them — what this build carries, and where it can
    // go — so they sit side by side once there is room for both and stack when there is not. As one
    // full-width column on a 1400px settings canvas, each was a band with a label at one edge and a
    // pill at the other and a thousand pixels of nothing in between.
    <div className="grid gap-4 grid-cols-[repeat(auto-fit,minmax(min(100%,440px),1fr))] [&>*]:min-w-0">
      {status === null ? (
        <Card title="Installations">
          {/* The error paragraph used to live *inside* the `status !== null` branch, so a failing
              `mod_install_status` left this screen saying "Looking for Minecraft…" forever with the
              reason it had already been given sitting unread in state. */}
          {error ? (
            <p className="py-3 text-sm text-danger">{error}</p>
          ) : (
            <p className="py-3 text-sm text-faint">Looking for Minecraft…</p>
          )}
        </Card>
      ) : (
        <>
          <Card
            title="What will be installed"
            right={
              <Pill tone={status.bundled_available ? "up" : "warn"}>
                {status.bundled_available ? `v${status.bundled_version}` : "not bundled"}
              </Pill>
            }
            hint="The build this app shipped with. Never downloaded."
          >
            {!status.bundled_available && (
              <p className="flex items-start gap-2 py-2 text-sm text-warn">
                <FiAlertTriangle aria-hidden className="mt-0.5 flex-none" />
                This build carries no mod jar, so there is nothing to install.
              </p>
            )}
          </Card>

          <Card
            title="Installations"
            hint="Found on this machine. Install into the profile your launcher uses."
          >
            {status.installs.length === 0 ? (
              <Empty icon={<FiFolder />} title="No Minecraft installation found">
                Set <code className={MONO}>NODERA_MINECRAFT_DIR</code> to point at yours.
              </Empty>
            ) : (
              <div className="flex flex-col">
                {status.installs.map((install) => (
                  <div
                    key={install.path}
                    className="flex flex-wrap items-center gap-2.5 border-b border-line-soft py-2.5 last:border-b-0"
                  >
                    <span className="min-w-0 flex-1">
                      <span className="block truncate">{install.path}</span>
                      <span className={cx(MONO, "block text-[11px] text-faint")}>
                        {install.has_mods_folder ? install.mods_path : "no mods folder yet"}
                      </span>
                    </span>

                    {install.installed_jar ? (
                      <Pill tone={install.up_to_date ? "up" : "warn"}>
                        {install.up_to_date ? (
                          <>
                            <FiCheck aria-hidden /> installed
                          </>
                        ) : (
                          install.installed_jar
                        )}
                      </Pill>
                    ) : (
                      <Pill tone="muted">not installed</Pill>
                    )}

                    <button
                      className={BUTTON}
                      disabled={!status.bundled_available || busy === install.path}
                      onClick={() =>
                        run(installMod, install.path, (file) => `Installed to ${file}`)
                      }
                    >
                      <FiPackage aria-hidden className="inline" />{" "}
                      {install.installed_jar && !install.up_to_date ? "Update" : "Install"}
                    </button>
                    {install.installed_jar && (
                      <button
                        className={cx(BUTTON, "text-warn")}
                        disabled={busy === install.path}
                        onClick={() =>
                          run(uninstallMod, install.path, (file) => `Removed ${file}`)
                        }
                        title="Removes only a NoderaMC jar — never anything else in your mods folder"
                      >
                        <FiTrash2 aria-hidden className="inline" /> Remove
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
            {message && <p className="pt-2 text-sm text-up">{message}</p>}
            {error && <p className="pt-2 text-sm text-danger">{error}</p>}
          </Card>

        </>
      )}
    </div>
  );
}

const BUTTON =
  "rounded-sm border border-line bg-surface-2 px-2.5 py-1 text-xs hover:bg-surface-hover disabled:opacity-50";
