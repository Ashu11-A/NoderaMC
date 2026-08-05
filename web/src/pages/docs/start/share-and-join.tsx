import Content, { toc } from "../../../../content/docs/start/share-and-join.mdx";
import { DocLayout } from "../../../components/docs";

export default function Page() {
  return (
    <DocLayout path="/docs/start/share-and-join" toc={toc}>
      <Content />
    </DocLayout>
  );
}
