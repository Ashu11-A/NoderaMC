// Motion, in the Material 3 sense: movement that says where something came from.
//
// The interface was static, and a static interface makes a phone feel like a web page. These are the
// three transitions M3 actually specifies for this shape of app — a forward push, a fade-through
// between peer destinations, and a container that grows in — with M3's own easing and durations
// rather than invented ones.
//
// Every one of them respects `prefers-reduced-motion`: the movement is a hint about hierarchy, and
// for someone who has asked their device to stop moving things it is only nausea.
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import type { ReactNode } from "react";

/** M3 "emphasized decelerate" — the standard entrance curve. */
const ENTER = [0.05, 0.7, 0.1, 1] as const;
/** M3 "emphasized accelerate" — the standard exit curve. */
const EXIT = [0.3, 0, 0.8, 0.15] as const;

/** M3 medium durations, in seconds. */
const IN = 0.3;
const OUT = 0.2;

/**
 * A forward/back push, for entering a sub-screen.
 *
 * @param screenKey changes when the screen does; that is what drives the transition.
 * @param depth     how deep the screen is, so going back slides the other way.
 */
export function PushTransition(props: { screenKey: string; depth: number; children: ReactNode }) {
  const reduced = useReducedMotion();
  const distance = reduced ? 0 : 24;
  return (
    <AnimatePresence initial={false} mode="popLayout">
      <motion.div
        key={props.screenKey}
        className="flex min-h-0 flex-1 flex-col"
        initial={{ opacity: 0, x: distance }}
        animate={{ opacity: 1, x: 0 }}
        exit={{ opacity: 0, x: -distance }}
        transition={{ duration: reduced ? 0 : IN, ease: ENTER }}
      >
        {props.children}
      </motion.div>
    </AnimatePresence>
  );
}

/**
 * A fade-through, for switching between peer destinations.
 *
 * M3 uses this rather than a slide between navigation-bar tabs precisely because they are peers:
 * a slide would imply one sits beside the other in an order the user should remember.
 */
export function FadeThrough(props: { screenKey: string; children: ReactNode }) {
  const reduced = useReducedMotion();
  return (
    <AnimatePresence initial={false} mode="wait">
      <motion.div
        key={props.screenKey}
        className="flex min-h-0 flex-1 flex-col"
        initial={{ opacity: 0, scale: reduced ? 1 : 0.97 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={{
          opacity: 0,
          scale: 1,
          transition: { duration: reduced ? 0 : OUT, ease: EXIT },
        }}
        transition={{ duration: reduced ? 0 : IN, ease: ENTER }}
      >
        {props.children}
      </motion.div>
    </AnimatePresence>
  );
}

/**
 * A staggered entrance for a list.
 *
 * The stagger is small on purpose. Its job is to make a list read as arriving in order rather than
 * appearing all at once; anything longer turns waiting for the screen into part of the interaction.
 */
export function Stagger(props: { children: ReactNode; className?: string }) {
  const reduced = useReducedMotion();
  return (
    <motion.div
      className={props.className}
      initial="hidden"
      animate="shown"
      variants={{
        hidden: {},
        shown: { transition: { staggerChildren: reduced ? 0 : 0.04 } },
      }}
    >
      {props.children}
    </motion.div>
  );
}

/** One item of a {@link Stagger}. */
export function StaggerItem(props: { children: ReactNode; className?: string }) {
  const reduced = useReducedMotion();
  return (
    <motion.div
      className={props.className}
      variants={{
        hidden: { opacity: 0, y: reduced ? 0 : 8 },
        shown: { opacity: 1, y: 0 },
      }}
      transition={{ duration: reduced ? 0 : IN, ease: ENTER }}
    >
      {props.children}
    </motion.div>
  );
}

/** A dialog that grows in from the bottom, as M3 bottom sheets and dialogs do. */
export function DialogTransition(props: { open: boolean; children: ReactNode }) {
  const reduced = useReducedMotion();
  return (
    <AnimatePresence>
      {props.open && (
        <motion.div
          className="fixed inset-0 z-50 flex items-end p-4"
          initial={{ backgroundColor: "rgba(0,0,0,0)" }}
          animate={{ backgroundColor: "rgba(0,0,0,0.5)" }}
          exit={{ backgroundColor: "rgba(0,0,0,0)" }}
          transition={{ duration: reduced ? 0 : OUT }}
        >
          <motion.div
            className="w-full"
            initial={{ opacity: 0, y: reduced ? 0 : 32, scale: reduced ? 1 : 0.96 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: reduced ? 0 : 32, scale: reduced ? 1 : 0.96 }}
            transition={{ duration: reduced ? 0 : IN, ease: ENTER }}
          >
            {props.children}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
