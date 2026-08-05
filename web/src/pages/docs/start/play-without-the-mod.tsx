import Content, { toc } from "../../../../content/docs/start/play-without-the-mod.mdx";
import { DocLayout } from "../../../components/docs";

export default function Page() {
  return (
    <DocLayout path="/docs/start/play-without-the-mod" toc={toc}>
      <Content />
    </DocLayout>
  );
}
