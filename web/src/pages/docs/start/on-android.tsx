import { DocLayout } from "../../../components/docs";
import { Unwritten } from "../../../components/mdx";

export default function Page() {
  return (
    <DocLayout path="/docs/start/on-android">
      <p>
        The Android companion is not a remote control for a desktop node. It runs the same headless
        worker the desktop runs, on the phone, which makes the phone a peer that holds pieces and
        validates regions like any other.
      </p>
      <p>
        It ships as <code>nodera-app-android-universal.apk</code> — one file carrying every ABI,
        because per-ABI splits exist to shrink store downloads and this is sideloaded; there is no
        store listing, so offering two APKs would only ask you a question about your own phone. The
        phone gets its own node identity, and it holds and serves worlds on its own account rather
        than on any desktop's.
      </p>
      <Unwritten
        route="/docs/start/on-android"
        task="frontend Task 16"
        taskPath="docs/frontend/Task.16.md"
      >
        <p>
          What a page here would have to state — how the worker survives the system killing it, what
          it does on a metered connection, and what it costs a battery over an evening — is exactly
          the part still moving.
        </p>
      </Unwritten>
    </DocLayout>
  );
}
