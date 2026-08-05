import Content, { toc } from "../../../../content/docs/start/install.mdx";
import { DocLayout } from "../../../components/docs";

export default function Page() {
  return (
    <DocLayout path="/docs/start/install" toc={toc}>
      <Content />
    </DocLayout>
  );
}
