import { services } from "../../components/data";
import { PageLayout } from "../../components/docs";
import { ServiceTable } from "../../components/services";

/**
 * `/services/` — the published list, and what each address was last measured doing.
 *
 * The prose here is deliberately three sentences. It used to be four paragraphs explaining what a
 * tracker is, what a relay is, why neither is an authority, what pressing the button does and what
 * it does not — an essay in front of the two rows somebody came to read. Each of those explanations
 * still exists on the page whose subject it is, and this one links to them instead of restating
 * them.
 *
 * What replaced the paragraphs is not more copy. It is the thing the page was missing: whether the
 * services are answering, how quickly, and when that was last checked.
 */
export default function Page() {
  return (
    <PageLayout path="/services/">
      <>
        <p className="mt-8 text-body leading-7 text-dim" style={{ maxWidth: "var(--prose-measure)" }}>
          A <strong className="font-medium text-text">tracker</strong> answers "who is on this
          world"; a <strong className="font-medium text-text">relay</strong> carries an encrypted
          circuit between two machines that cannot reach each other directly. Neither is an
          authority — the most either can do is hide a peer, or point you at one that is not there.
        </p>

        <ServiceTable />

        <p className="mt-8 text-sm text-faint" style={{ maxWidth: "var(--prose-measure)" }}>
          List read from {services.source} at build time.{" "}
          <a href="/docs/operate/" className="text-brand-1 hover:text-text">
            Run a service
          </a>{" "}
          ·{" "}
          <a href="/docs/operate/publish-a-service-list" className="text-brand-1 hover:text-text">
            Publish a list of your own
          </a>
        </p>
      </>
    </PageLayout>
  );
}
