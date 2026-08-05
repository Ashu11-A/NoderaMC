// The repository layout table, for the site's tests.
//
// A re-export, deliberately. Every rule under `web/tests` is about a path — where the built site is,
// where the docs it mirrors are, where the launcher's stylesheet lives — and a suite that spelled
// one of them would be the sixth copy of the table `/layout.properties` exists to be the only one
// of. `web/dist` is composed here rather than looked up, because the manifest holds directories and
// says in its own header that a build output is a property of a build system.
import path from "node:path";
import { dir } from "nodera-ui/layout";

export {
  crate,
  dir,
  frontendRoots,
  layoutValue,
  packageDirectories,
  pkg,
  readCrate,
  repositoryDirectory,
} from "nodera-ui/layout";

/** The site's source directory. */
export const siteDirectory = dir("web");

/** Where a finished build lands. Composed, never keyed — see the manifest's own header. */
export const distDirectory = path.join(siteDirectory, "dist");

/** The public origin. The one place it is written; `add-store.test.mjs` holds the README to it. */
export const SITE_ORIGIN = "https://noderamc.org";
