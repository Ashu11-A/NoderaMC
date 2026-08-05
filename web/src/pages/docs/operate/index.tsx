import Content, { toc } from "../../../../content/docs/operate/index.mdx";
import { DocLayout } from "../../../components/docs";

export default function Page() {
  return (
    <DocLayout path="/docs/operate/" toc={toc}>
      <Content />
    </DocLayout>
  );
}
