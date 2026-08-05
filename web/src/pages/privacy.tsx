import Content from "../../content/privacy.mdx";
import { PageLayout, Prose } from "../components/docs";

export default function Page() {
  return (
    <PageLayout path="/privacy" narrow>
      <div style={{ maxWidth: "var(--prose-measure)" }}>
        <Prose>
          <Content />
        </Prose>
      </div>
    </PageLayout>
  );
}
