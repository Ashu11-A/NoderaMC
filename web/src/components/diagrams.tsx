/**
 * Six authored pictures, drawn in tokens.
 *
 * Not screenshots, and not a scroll-driven animation. Four reasons, in the order they mattered:
 * the page has to work with no third-party origin loadable at all; it has to be legible in the
 * prerendered HTML with the bundle blocked; it must not have anything to switch off under
 * `prefers-reduced-motion`; and a small picture drawn by hand is something a reviewer can check for
 * *truth*, which is not a thing you can do to a screenshot of a launcher that is being redesigned.
 *
 * Every one of them is `aria-hidden`. The caption beside it carries the meaning — a diagram that
 * needs a paragraph in an `alt` attribute is a diagram whose caption should have said it.
 */

const FRAME = "h-auto w-full";

function Machine(props: { x: number; y: number; dim?: boolean; label?: string }) {
  return (
    <g className={props.dim ? "text-faint" : "text-dim"} transform={`translate(${props.x} ${props.y})`}>
      <rect width="46" height="30" rx="4" className="fill-surface-2 stroke-line" strokeWidth="1.5" />
      <rect x="6" y="6" width="34" height="14" rx="2" className="fill-bg" />
      <path d="M -4 34 H 50" className="stroke-line" strokeWidth="3" strokeLinecap="round" />
      {props.label ? (
        <text x="23" y="48" textAnchor="middle" className="fill-current" fontSize="9">
          {props.label}
        </text>
      ) : null}
    </g>
  );
}

function Service(props: { x: number; y: number; label: string; cool?: boolean }) {
  return (
    <g transform={`translate(${props.x} ${props.y})`} className={props.cool ? "text-brand-3" : "text-brand-1"}>
      <rect width="52" height="22" rx="11" className="fill-surface stroke-current" strokeWidth="1.5" />
      <text x="26" y="15" textAnchor="middle" className="fill-current" fontSize="9">
        {props.label}
      </text>
    </g>
  );
}

function Arrow(props: { d: string; className?: string; dashed?: boolean }) {
  return (
    <path
      d={props.d}
      fill="none"
      strokeWidth="1.5"
      strokeLinecap="round"
      markerEnd="url(#nodera-arrow)"
      strokeDasharray={props.dashed ? "4 4" : undefined}
      className={props.className ?? "stroke-line"}
    />
  );
}

function Defs() {
  return (
    <defs>
      <marker id="nodera-arrow" viewBox="0 0 8 8" refX="6" refY="4" markerWidth="5" markerHeight="5" orient="auto">
        <path d="M0 0 L8 4 L0 8 z" className="fill-current" />
      </marker>
    </defs>
  );
}

/** You share a world: the machine announces it, and offers its pieces to whoever asks. */
export function Step1Share() {
  return (
    <svg viewBox="0 0 320 200" className={FRAME} aria-hidden="true">
      <Defs />
      <g className="text-line">
        <rect x="16" y="26" width="84" height="84" rx="6" className="fill-surface-2 stroke-line" strokeWidth="1.5" />
        {[0, 1, 2, 3].map((row) =>
          [0, 1, 2, 3].map((col) => (
            <rect
              key={`${row}-${col}`}
              x={24 + col * 20}
              y={34 + row * 20}
              width="16"
              height="16"
              rx="2"
              className={row + col === 2 || row === col ? "fill-brand-1" : "fill-line-soft"}
              opacity={row + col === 2 || row === col ? 0.85 : 1}
            />
          )),
        )}
      </g>
      <text x="58" y="126" textAnchor="middle" className="fill-faint" fontSize="9">
        your world, in regions
      </text>
      <Machine x="30" y="146" label="your machine" />
      <g className="text-brand-1">
        <Arrow d="M 104 60 C 150 48, 170 44, 208 42" className="stroke-brand-1" />
      </g>
      <g className="text-brand-3">
        <Arrow d="M 104 78 C 150 92, 170 100, 208 106" className="stroke-brand-3" />
      </g>
      <Service x="212" y="32" label="tracker" />
      <Service x="212" y="96" label="relay" cool />
      <text x="238" y="140" textAnchor="middle" className="fill-faint" fontSize="9">
        hints, not authority
      </text>
    </svg>
  );
}

/** Other players find it: a tracker answers, a relay bridges two machines behind NAT. */
export function Step2Find() {
  return (
    <svg viewBox="0 0 320 200" className={FRAME} aria-hidden="true">
      <Defs />
      <rect x="10" y="30" width="86" height="96" rx="6" className="fill-none stroke-line-soft" strokeWidth="1.5" strokeDasharray="5 4" />
      <text x="53" y="46" textAnchor="middle" className="fill-faint" fontSize="9">
        NAT
      </text>
      <rect x="224" y="30" width="86" height="96" rx="6" className="fill-none stroke-line-soft" strokeWidth="1.5" strokeDasharray="5 4" />
      <text x="267" y="46" textAnchor="middle" className="fill-faint" fontSize="9">
        NAT
      </text>
      <Machine x="30" y="60" label="a player" />
      <Machine x="244" y="60" label="another player" />
      <Service x="134" y="26" label="tracker" />
      <Service x="134" y="112" label="relay" cool />
      <g className="text-line">
        <Arrow d="M 82 62 C 110 48, 118 42, 130 39" />
        <Arrow d="M 238 62 C 210 48, 202 42, 190 39" />
      </g>
      <g className="text-brand-3">
        <Arrow d="M 78 96 C 106 116, 118 122, 130 123" className="stroke-brand-3" />
        <Arrow d="M 242 96 C 214 116, 202 122, 190 123" className="stroke-brand-3" />
      </g>
      <text x="160" y="166" textAnchor="middle" className="fill-faint" fontSize="9">
        every answer is checked against signatures the peers already hold
      </text>
    </svg>
  );
}

/** Regions get committees: one simulates, the others re-execute and compare. */
export function Step3Committee() {
  const chips = [
    { x: 26, tone: "stroke-up text-up", label: "peer A" },
    { x: 124, tone: "stroke-up text-up", label: "peer B" },
    { x: 222, tone: "stroke-up text-up", label: "peer C" },
  ];
  return (
    <svg viewBox="0 0 320 200" className={FRAME} aria-hidden="true">
      <Defs />
      <rect x="112" y="14" width="96" height="52" rx="6" className="fill-surface-2 stroke-line" strokeWidth="1.5" />
      {Array.from({ length: 8 }).map((_, col) =>
        Array.from({ length: 4 }).map((__, row) => (
          <rect
            key={`${col}-${row}`}
            x={118 + col * 11}
            y={20 + row * 11}
            width="9"
            height="9"
            rx="1.5"
            className="fill-line-soft"
          />
        )),
      )}
      <text x="160" y="80" textAnchor="middle" className="fill-faint" fontSize="9">
        one 8×8-chunk region
      </text>
      <g className="text-line">
        <Arrow d="M 140 84 C 120 96, 90 100, 62 104" />
        <Arrow d="M 160 84 V 104" />
        <Arrow d="M 180 84 C 200 96, 230 100, 258 104" />
      </g>
      {chips.map((chip) => (
        <g key={chip.label} transform={`translate(${chip.x} 110)`} className={chip.tone}>
          <rect width="72" height="44" rx="6" className="fill-surface stroke-current" strokeWidth="1.5" />
          <text x="36" y="18" textAnchor="middle" className="fill-dim" fontSize="9">
            {chip.label}
          </text>
          <text x="36" y="33" textAnchor="middle" className="fill-current" fontSize="10" fontFamily="monospace">
            0x9c4f…
          </text>
        </g>
      ))}
      <text x="160" y="176" textAnchor="middle" className="fill-faint" fontSize="9">
        the same StateRoot, or the batch does not commit
      </text>
    </svg>
  );
}

/** Nobody has to stay: peers holding the pieces re-host the world. */
export function Step4Rehost() {
  return (
    <svg viewBox="0 0 320 200" className={FRAME} aria-hidden="true">
      <Defs />
      <g opacity="0.4">
        <Machine x="14" y="76" dim label="the machine that started it" />
      </g>
      <path d="M 20 78 L 66 112 M 66 78 L 20 112" className="stroke-danger" strokeWidth="1.5" strokeLinecap="round" />
      {[40, 90, 140].map((y, i) => (
        <g key={y} className="text-brand-1">
          <rect x="118" y={y} width="46" height="26" rx="4" className="fill-surface stroke-line" strokeWidth="1.5" />
          <text x="141" y={y + 17} textAnchor="middle" className="fill-dim" fontSize="9">
            peer {i + 1}
          </text>
          <Arrow d={`M 168 ${y + 13} C 196 ${y + 13}, 210 100, 236 100`} className="stroke-brand-1" />
        </g>
      ))}
      <g>
        <rect x="240" y="76" width="64" height="48" rx="6" className="fill-surface-2 stroke-brand-1" strokeWidth="1.5" />
        <text x="272" y="96" textAnchor="middle" className="fill-text" fontSize="9">
          new host
        </text>
        <text x="272" y="110" textAnchor="middle" className="fill-faint" fontSize="9">
          from pieces
        </text>
      </g>
      <text x="160" y="188" textAnchor="middle" className="fill-faint" fontSize="9">
        every peer that has played holds part of the world
      </text>
    </svg>
  );
}

/** The tunnel: a guest names a session, and the game connects to its own machine. */
export function NoModTunnel() {
  return (
    <svg viewBox="0 0 320 200" className={FRAME} aria-hidden="true">
      <Defs />
      <g>
        <rect x="12" y="34" width="104" height="70" rx="6" className="fill-surface stroke-line" strokeWidth="1.5" />
        <text x="64" y="54" textAnchor="middle" className="fill-dim" fontSize="9">
          the host
        </text>
        <text x="64" y="70" textAnchor="middle" className="fill-faint" fontSize="9">
          Open to LAN
        </text>
        <rect x="34" y="78" width="60" height="16" rx="3" className="fill-surface-2 stroke-line-soft" strokeWidth="1.2" />
        <text x="64" y="90" textAnchor="middle" className="fill-faint" fontSize="8">
          the save stays here
        </text>
      </g>
      <g className="text-brand-1">
        <Arrow d="M 120 68 H 196" className="stroke-brand-1" />
        <text x="158" y="60" textAnchor="middle" className="fill-faint" fontSize="9">
          one encrypted circuit
        </text>
      </g>
      <g>
        <rect x="200" y="34" width="108" height="70" rx="6" className="fill-surface stroke-line" strokeWidth="1.5" />
        <text x="254" y="54" textAnchor="middle" className="fill-dim" fontSize="9">
          your machine
        </text>
        <rect x="216" y="64" width="76" height="18" rx="3" className="fill-surface-2 stroke-brand-3" strokeWidth="1.2" />
        <text x="254" y="77" textAnchor="middle" className="fill-brand-3" fontSize="9" fontFamily="monospace">
          127.0.0.1
        </text>
        <text x="254" y="96" textAnchor="middle" className="fill-faint" fontSize="8">
          unmodified Minecraft
        </text>
      </g>
      <text x="160" y="134" textAnchor="middle" className="fill-faint" fontSize="9">
        you pick a session by name — never an address
      </text>
      <text x="160" y="150" textAnchor="middle" className="fill-faint" fontSize="9">
        nothing is installed, and no save is ever downloaded
      </text>
    </svg>
  );
}

/** The hero figure: one world, a handful of machines, three regions lit. */
export function HeroFigure() {
  return (
    <svg viewBox="0 0 400 300" className="h-auto w-full max-w-md" aria-hidden="true">
      <Defs />
      <g transform="translate(122 74)">
        <rect width="156" height="156" rx="10" className="fill-surface-2 stroke-line" strokeWidth="1.5" />
        {[0, 1, 2, 3].map((row) =>
          [0, 1, 2, 3].map((col) => {
            const lit = (row === 0 && col === 1) || (row === 1 && col === 2) || (row === 2 && col === 0);
            return (
              <rect
                key={`${row}-${col}`}
                x={12 + col * 34}
                y={12 + row * 34}
                width="30"
                height="30"
                rx="4"
                className={lit ? "fill-brand-1" : "fill-line-soft"}
              />
            );
          }),
        )}
      </g>
      <g className="text-line">
        <Arrow d="M 60 60 C 96 74, 116 92, 146 104" />
        <Arrow d="M 356 96 C 320 108, 300 120, 268 130" />
        <Arrow d="M 200 272 C 200 260, 200 250, 200 236" />
      </g>
      {[
        { x: 18, y: 30 },
        { x: 322, y: 62 },
        { x: 176, y: 244 },
      ].map((p) => (
        <g key={`${p.x}-${p.y}`} transform={`translate(${p.x} ${p.y})`}>
          <rect width="50" height="32" rx="5" className="fill-surface stroke-line" strokeWidth="1.5" />
          <rect x="7" y="7" width="36" height="14" rx="2" className="fill-bg" />
        </g>
      ))}
    </svg>
  );
}
