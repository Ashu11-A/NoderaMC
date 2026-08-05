// React, Vite, TypeScript and Tailwind. Never Vue.
//
// A two-line grep, and it is kept for one specific reason rather than out of superstition: both
// design documents this site was built from studied a Vue codebase closely — mihon's docs site — and
// a paste from it is a plausible accident rather than a hypothetical one. `knip` is not run in this
// pull request, so nothing else in the tree would notice a Vue dependency arriving.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { packageDirectories, repositoryDirectory } from "nodera-ui/layout";
import { sourceFiles } from "nodera-ui/token-audit";
import { siteDirectory } from "./layout.mjs";

const BANNED = ["vue", "vitepress", "@vitejs/plugin-vue", "vuepress", "nuxt"];

test("no workspace package depends on Vue", () => {
  const manifests = ["package.json", ...packageDirectories().map((dir) => `${dir}/package.json`)];
  for (const relative of manifests) {
    const manifest = JSON.parse(readFileSync(path.join(repositoryDirectory, relative), "utf8"));
    const declared = Object.keys({
      ...manifest.dependencies,
      ...manifest.devDependencies,
      ...manifest.peerDependencies,
    });
    for (const banned of BANNED) {
      assert.ok(!declared.includes(banned), `${relative} declares ${banned}`);
    }
  }
});

test("no .vue file exists under the site", () => {
  const offenders = sourceFiles(path.join(siteDirectory, "src"), /\.vue$/);
  assert.deepEqual(offenders, []);
});
