import Content, { toc } from "../../../../content/docs/start/what-noderamc-is.mdx";
import { DocLayout } from "../../../components/docs";

export default function Page() {
  return (
    <DocLayout path="/docs/start/what-noderamc-is" toc={toc}>
      <Content />
    </DocLayout>
  );
}
