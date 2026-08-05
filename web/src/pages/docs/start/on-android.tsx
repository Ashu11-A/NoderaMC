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
