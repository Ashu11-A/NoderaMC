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
      <p>
        Authority here is a keypair, never a username and never a UUID. An operator grant is a
        signed statement bound to the recipient's peer key, re-verified by everyone who receives it,
        so "the server said so" is not evidence of anything. The commands that use it — setting a
        world's password, granting and revoking operators, deleting a world everywhere — are on{" "}
        <a href="/docs/start/share-and-join#administering-a-world">share a world, and join one</a>.
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
