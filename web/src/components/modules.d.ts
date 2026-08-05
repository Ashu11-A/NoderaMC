/**
 * The one module shape the site imports that TypeScript cannot infer on its own.
 *
 * It is declared here rather than in `web/tsconfig.json` because this is the half of the site that
 * consumes it, and a declaration living next to its consumers is one that gets deleted when the
 * consumers do. (`*?raw` and `import.meta.env` come from `vite/client`, which the tsconfig already
 * pulls in; a second declaration of either would be a conflict rather than a belt and braces.)
 */

/**
 * An authored prose page.
 *
 * The pipeline is fixed in `web/vite.config.ts`: `remark-gfm`, front matter, `remark-directive`
 * (which gives the `:::note` / `:::warning` syntax `docs/` already uses), heading slugs, autolinked
 * headings and Shiki. It exports the component, the front matter, and the headings the table of
 * contents is built from.
 */
declare module "*.mdx" {
  import type { ComponentType } from "react";

  export const frontmatter: {
    title: string;
    description: string;
    task?: string;
    taskPath?: string;
  };
  export const toc: { depth: 2 | 3; id: string; text: string }[];
  const Content: ComponentType<{ components?: Record<string, ComponentType<Record<string, unknown>>> }>;
  export default Content;
}
