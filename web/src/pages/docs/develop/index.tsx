import Content, { toc } from "../../../../content/docs/develop/index.mdx";
import { DocLayout } from "../../../components/docs";

export default function Page() {
  return (
    <DocLayout path="/docs/develop/" toc={toc}>
      <Content />
    </DocLayout>
  );
}
