import Content, { toc } from "../../../../content/docs/using/when-the-host-leaves.mdx";
import { DocLayout } from "../../../components/docs";

export default function Page() {
  return (
    <DocLayout path="/docs/using/when-the-host-leaves" toc={toc}>
      <Content />
    </DocLayout>
  );
}
