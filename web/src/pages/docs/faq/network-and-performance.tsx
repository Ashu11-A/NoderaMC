import { DocLayout } from "../../../components/docs";
import { Unwritten } from "../../../components/mdx";

export default function Page() {
  return (
    <DocLayout path="/docs/faq/network-and-performance">
      <p>
        A world reads as degraded when fewer peers are present than its regions want for a quorum. It
        keeps running and says so, which is the honest arrangement; the fix is more peers rather than
        a setting.
      </p>
      <Unwritten
        route="/docs/faq/network-and-performance"
        task="network Task 2"
        taskPath="docs/network/Task.2.md"
      >
        <p>
          The rest of this page would be numbers: how often a direct path is found, what a relayed
          circuit costs, how long a region handoff takes under load. The cross-internet soak that
          would produce them has not been run, and quoting figures measured on one machine's loopback
          would be worse than saying so.
        </p>
      </Unwritten>
    </DocLayout>
  );
}
