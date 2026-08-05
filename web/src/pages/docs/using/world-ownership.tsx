import { DocLayout } from "../../../components/docs";
import { Unwritten } from "../../../components/mdx";

export default function Page() {
  return (
    <DocLayout path="/docs/using/world-ownership">
      <p>
        A world carries its own signed identity, so "this is that world" is something anyone can
        check without asking the machine that made it. Hosting a world does not make you its owner,
        and the key that signs administrative changes to it is not the key that happened to open it
        today.
      </p>
      <Unwritten
        route="/docs/using/world-ownership"
        task="peer Task 8"
        taskPath="docs/peer/Task.8.md"
      >
        <p>
          Ownership transfer, operator grants, world passwords and re-keying, and the tombstones that
          make a deletion stick on machines you do not control are owned by peer Task 8 and minecraft
          Task 6. Both are in progress, and the rules a page here would state are the ones still being
          decided.
        </p>
      </Unwritten>
    </DocLayout>
  );
}
