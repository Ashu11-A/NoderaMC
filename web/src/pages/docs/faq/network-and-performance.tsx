import { DocLayout } from "../../../components/docs";
import { Unwritten } from "../../../components/mdx";

export default function Page() {
  return (
    <DocLayout path="/docs/faq/network-and-performance">
      <p>
        Two shapes of traffic, and they are worth telling apart. A world's{" "}
        <strong>whole archive</strong> is a cold bootstrap for a peer that has nothing at all: it is
        seeded when the world is shared, again when the server stops, and otherwise on a slow
        cadence. <strong>Live change</strong> does not ride it — committed region snapshots go to the
        worker as they happen, which moves the chunks that changed rather than the world that
        contains them.
      </p>
      <p>
        Copies are whole rather than fragmented, and{" "}
        <a href="/docs/using/when-the-host-leaves#how-many-copies-exist">how many the network keeps</a>{" "}
        is arithmetic over how often a home machine is switched on, capped by the number of peers
        that exist.
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
