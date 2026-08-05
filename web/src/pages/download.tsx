import { PageLayout, Prose } from "../components/docs";
import { AssetTable, ChecksumBlock, PlatformPicker, ReleaseHeading } from "../components/download";
import { release } from "../components/data";

export default function Page() {
  return (
    <PageLayout path="/download">
      <>
        <ReleaseHeading />

        <PlatformPicker />

        <section className="mt-16" style={{ maxWidth: "var(--prose-measure)" }}>
          <h2 className="display-type text-2xl font-bold text-text">What changed</h2>
          <Prose>
            <div dangerouslySetInnerHTML={{ __html: release.whatChanged }} />
          </Prose>
          <p className="mt-4 text-sm">
            <a href={release.htmlUrl} rel="noopener noreferrer" className="text-brand-1 hover:text-text">
              Full release notes on GitHub →
            </a>
          </p>
        </section>

        <section className="mt-16">
          <h2 className="display-type text-2xl font-bold text-text">Everything this release publishes</h2>
          <p className="mt-3 text-body text-dim" style={{ maxWidth: "var(--prose-measure)" }}>
            Most people want an installer and nothing else. The rest is here because a release that
            hides half of what it built is a release nobody can audit: the two service binaries are
            what a tracker or relay operator runs, and the jars are for a machine you administer
            rather than play on.
          </p>
          <AssetTable />
        </section>

        <section className="mt-16" style={{ maxWidth: "var(--prose-measure)" }}>
          <h2 className="display-type text-2xl font-bold text-text">What you need</h2>
          <Prose>
            <ul>
              <li>
                <strong>Minecraft 1.21.1</strong> with <strong>NeoForge 21.1.238</strong>, and{" "}
                <strong>Java 21</strong>.
              </li>
              <li>
                The companion app, running. The mod <strong>requires</strong> it and aborts startup
                with an install prompt if nothing answers on <code>127.0.0.1:25610</code>.
              </li>
              <li>
                Every player in a world runs the mod. There is no vanilla-client population and no
                second-class lane — the handshake enforces it. If you want to play with someone who
                will not install anything, that is a different feature and it is{" "}
                <a href="/docs/start/play-without-the-mod">the LAN tunnel</a>.
              </li>
            </ul>
          </Prose>
        </section>

        <section id="verify" className="mt-16 scroll-mt-24" style={{ maxWidth: "var(--prose-measure)" }}>
          <h2 className="display-type text-2xl font-bold text-text">Verify what you downloaded</h2>
          <p className="mt-3 text-body leading-7 text-dim">
            Download <code className="font-mono text-sm text-text">SHA256SUMS</code> from the table
            above into the same directory as the files you got, then:
          </p>
          <ChecksumBlock />
        </section>

        <section id="no-macos" className="mt-16 scroll-mt-24" style={{ maxWidth: "var(--prose-measure)" }}>
          <h2 className="display-type text-2xl font-bold text-text">There is no macOS build</h2>
          <p className="mt-3 text-body leading-7 text-dim">
            There was, and the arm64 half worked on the first attempt. The x86-64 half did not:
            GitHub's last Intel macOS image is deprecated, and a job asking for one sat waiting for a
            runner for fifteen hours without ever starting — which held the entire release, including
            the Windows and Linux installers that had built in minutes.
          </p>
          <p className="mt-4 text-body leading-7 text-dim">
            Shipping only the Apple-silicon build was the other option, and it is worse than shipping
            neither: an Intel Mac user downloading the one macOS file on the page gets a binary that
            cannot run, and nothing on the page tells them which Mac it was for. A platform is
            supported or it is not. Reviving it needs one matrix entry and a runner that can actually
            be scheduled, or a decision to cross-build — the packaging path is known to work.
          </p>
        </section>
      </>
    </PageLayout>
  );
}
