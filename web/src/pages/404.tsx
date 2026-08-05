import { PageLayout } from "../components/docs";

export default function Page() {
  return (
    <PageLayout path="/404" narrow>
      <div className="mt-8 text-body leading-7 text-dim" style={{ maxWidth: "var(--prose-measure)" }}>
        <p>
          Two things end up here. A link to <code className="font-mono text-sm text-text">/add-store</code>{" "}
          that lost its query string, in which case the address you wanted is in{" "}
          <a href="/services/" className="text-brand-1 hover:text-text">
            the service list
          </a>
          .
        </p>
        <p className="mt-4">
          Or a documentation URL copied out of an older README. The whole tree is indexed from{" "}
          <a href="/docs/" className="text-brand-1 hover:text-text">
            the documentation home
          </a>
          , and every page on the site is in the sidebar there.
        </p>
      </div>
    </PageLayout>
  );
}
