// The two things the MDX pipeline needs that no published plugin does for us.
//
// Both are small on purpose. Every other step in `vite.config.ts`'s chain is a plugin somebody else
// maintains; these two exist because the prose in `web/content/**` has to use the *same* syntax the
// files in `docs/` already use, and because a page needs a table of contents that cannot disagree
// with its own headings.
import Slugger from "github-slugger";
import { valueToEstree } from "estree-util-value-to-estree";
import { visit } from "unist-util-visit";

/**
 * `:::note` / `:::tip` / `:::warning` / `:::danger` / `:::details` become components.
 *
 * The syntax is not chosen here — it is the syntax `docs/` has used since before this site existed,
 * and a documentation tree that reads one way in the repository and another way on the web is a
 * tree people stop keeping in sync. `remark-directive` parses it; this maps it onto the component
 * W2 writes, by setting the hast name so the MDX compiler resolves it out of the component scope.
 */
export function remarkCallouts() {
  return (tree, file) => {
    visit(tree, (node) => {
      if (node.type !== "containerDirective") return;
      const kind = node.name;
      if (!["note", "tip", "warning", "danger", "details"].includes(kind)) {
        // Fail closed. An unknown directive renders as literal `:::whatever` text today, which is a
        // typo that ships. Naming the file and the directive is cheaper than finding it in review.
        file.fail(`unknown directive :::${kind}`, node);
      }
      node.data = {
        ...node.data,
        hName: "Callout",
        hProperties: { kind, ...(node.attributes ?? {}) },
      };
    });
  };
}

/**
 * `export const toc` — every H2 and H3, with the id `rehype-slug` will give it.
 *
 * Depth stops at 3 because a table of contents deeper than two levels is a second navigation
 * component pretending to be an outline.
 *
 * The ids are computed with the same `github-slugger` instance semantics `rehype-slug` uses, so a
 * heading appearing twice gets `-1` in both places. They are computed here rather than read back
 * from the rehype tree because this plugin runs on markdown, where the heading text is still one
 * unambiguous string.
 */
export function remarkToc() {
  return (tree) => {
    const slugger = new Slugger();
    const toc = [];
    visit(tree, "heading", (node) => {
      if (node.depth !== 2 && node.depth !== 3) return;
      const text = textOf(node);
      toc.push({ depth: node.depth, id: slugger.slug(text), text });
    });
    tree.children.unshift(esmExport("toc", toc));
  };
}

/** The plain text of a markdown node, ignoring emphasis, links and inline code fences. */
function textOf(node) {
  if (typeof node.value === "string") return node.value;
  return (node.children ?? []).map(textOf).join("");
}

/** `export const <name> = <value>;` as an MDX ESM node the compiler will emit verbatim. */
function esmExport(name, value) {
  return {
    type: "mdxjsEsm",
    value: `export const ${name} = ${JSON.stringify(value)};`,
    data: {
      estree: {
        type: "Program",
        sourceType: "module",
        body: [
          {
            type: "ExportNamedDeclaration",
            specifiers: [],
            source: null,
            declaration: {
              type: "VariableDeclaration",
              kind: "const",
              declarations: [
                {
                  type: "VariableDeclarator",
                  id: { type: "Identifier", name },
                  init: valueToEstree(value),
                },
              ],
            },
          },
        ],
      },
    },
  };
}
