// Navigation that the system back gesture understands.
//
// A phone user presses back to go up one level; they do not look for a chevron. The WebView already
// has a history stack, and wry maps the back gesture onto it (`MainActivity.handleBackNavigation`),
// so the interface's job is to put its own levels INTO that stack with `pushState` and to listen
// for `popstate` when the user leaves one.
//
// Doing it this way rather than with an in-memory stack means back behaves correctly at the root
// too: there is nothing left to pop, so Android closes the app instead of trapping the user.
import { useCallback, useEffect, useState } from "react";

/**
 * A stack of screens on top of a base screen.
 *
 * @param base the screen shown when nothing is pushed.
 * @returns the current screen, plus `push` and `back`.
 */
export function useBackStack<T>(base: T): {
  current: T;
  depth: number;
  push: (screen: T) => void;
  back: () => void;
} {
  const [stack, setStack] = useState<T[]>([]);

  useEffect(() => {
    // `popstate` fires for every entry the user leaves, including ones pushed before this
    // component mounted, so the handler pops one level rather than clearing the stack.
    const onPop = () => setStack((current) => current.slice(0, -1));
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  const push = useCallback((screen: T) => {
    // The state object is not read back — the stack lives in React. What matters is that an entry
    // exists for the back gesture to consume.
    window.history.pushState({ nodera: true }, "");
    setStack((current) => [...current, screen]);
  }, []);

  const back = useCallback(() => {
    // Delegated to the browser so the on-screen control and the system gesture take the same path;
    // popping our own state directly would leave a stale history entry behind and make the next
    // back press do nothing.
    window.history.back();
  }, []);

  return {
    current: stack.length > 0 ? stack[stack.length - 1] : base,
    depth: stack.length,
    push,
    back,
  };
}
