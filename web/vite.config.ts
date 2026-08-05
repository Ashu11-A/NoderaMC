import { defineConfig } from "vite";
import { noderaVite } from "nodera-ui/vite";
import mdx from "@mdx-js/rollup";
import rehypeShiki from "@shikijs/rehype";
import rehypeAutolinkHeadings from "rehype-autolink-headings";
import rehypeSlug from "rehype-slug";
import remarkDirective from "remark-directive";
import remarkFrontmatter from "remark-frontmatter";
import remarkGfm from "remark-gfm";
import remarkMdxFrontmatter from "remark-mdx-frontmatter";
import { remarkCallouts, remarkToc } from "./scripts/mdx-plugins.mjs";

// The website's build.
//
// The root is `src/`, not the package directory. That is what keeps `web/index.html` and
// `web/add-store.html` — the two hand-written pages that predate this build — out of the module
// graph entirely: `add-store.html` ships as the bytes that were reviewed, installed by
// `scripts/build-site.sh`, and a build that could pick it up as an entry is a build that could
// silently re-render it.
//
// React, Tailwind and the platform seam come from `nodera-ui/vite`, which also aliases the seam's
// implementation module to the browser host — so `@tauri-apps` is absent from this bundle's module
// graph rather than present and unreachable.
export default defineConfig(({ isSsrBuild }) => ({
  root: "src",
  // `web/public`, not `web/src/public`: the root moved, the convention did not.
  publicDir: "../public",
  plugins: [
    // `enforce: "pre"` so MDX runs before the React plugin, which needs JSX rather than markdown.
    { enforce: "pre", ...mdx({
      remarkPlugins: [
        remarkGfm,
        remarkFrontmatter,
        remarkMdxFrontmatter,
        remarkDirective,
        remarkCallouts,
        remarkToc,
      ],
      rehypePlugins: [
        rehypeSlug,
        [rehypeAutolinkHeadings, { behavior: "wrap" }],
        // Two themes, resolved by CSS custom properties rather than by re-highlighting: the site
        // repaints from one `data-theme` attribute write, and a code block that needed JavaScript to
        // follow it would be the one element on the page that lags the rest.
        [rehypeShiki, { themes: { light: "github-light", dark: "github-dark" }, defaultColor: "light" }],
      ],
      providerImportSource: "@mdx-js/react",
    }) },
    ...noderaVite({ platform: "browser" }),
  ],
  build: {
    outDir: isSsrBuild ? "../.ssr" : "../dist",
    emptyOutDir: true,
    // The polyfill is an INLINE `<script>` that Vite injects into `index.html`. One inline script is
    // all it takes to force the deployment lane's `script-src` to carry a hash, which would mean
    // something outside this repository reading a build artefact to write a security policy. The
    // browsers that need the polyfill do not run this site's `es2021` output anyway.
    modulePreload: { polyfill: false },
  },
  // Every asset is served from the site root, so a page three directories deep resolves the same
  // bundle as the landing page.
  base: "/",
}));
