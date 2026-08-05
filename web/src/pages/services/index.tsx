import { services } from "../../components/data";
import { PageLayout } from "../../components/docs";
import { ServiceTable } from "../../components/services";

export default function Page() {
  return (
    <PageLayout path="/services/">
      <div className="page-canvas py-14">
        <h1 className="display-type text-4xl font-bold text-text">Trackers and relays</h1>
        <p className="mt-4 text-lg leading-8 text-dim" style={{ maxWidth: "var(--prose-measure)" }}>
          The official service list, what each entry does, and how to add it — or somebody else's — to
          your app.
        </p>

        <div style={{ maxWidth: "var(--prose-measure)" }}>
          <p className="mt-8 text-body leading-7 text-dim">
            A <strong className="font-medium text-text">tracker</strong> answers the question "who is
            on this world". A <strong className="font-medium text-text">relay</strong> introduces two
            machines that are both behind a NAT and carries an encrypted circuit between them when
            there is no direct path. Neither one is authority: every service proves its own identity
            before a peer will dial it, and everything a service says is checked against signatures
            the peers already hold. The most either can do is hide a peer from you or point you at one
            that is not reachable.
          </p>
          <p className="mt-4 text-body leading-7 text-dim">
            That is why the app takes lists of services from anyone. The list below is the one this
            project publishes; it is one store among however many you add, and it is deletable like
            any other. Pressing <strong className="font-medium text-text">Add to NoderaMC</strong> only{" "}
            <em>records</em> the address — the app shows you what it fetched and what is in it before
            anything is added.
          </p>
        </div>

        <ServiceTable />

        <div style={{ maxWidth: "var(--prose-measure)" }}>
          <p className="mt-8 text-sm text-faint">
            Read from {services.source} at build time.
          </p>
          <p className="mt-6 text-body leading-7 text-dim">
            Running one is cheap, and the project wants more of them than it runs itself —{" "}
            <a href="/docs/operate/" className="text-brand-1 hover:text-text">
              here is what is involved
            </a>
            . Publishing your own list is a JSON file against a schema:{" "}
            <a href="/docs/operate/publish-a-service-list" className="text-brand-1 hover:text-text">
              the format and how to get listed
            </a>
            .
          </p>
        </div>
      </div>
    </PageLayout>
  );
}
