// Where a crate's or a package's sources live, according to `/layout.properties`.
//
// The tests in this folder assert over Rust sources as well as TypeScript ones, and those files
// moved once already — from the shell crate into `nodera-core`. A relative path spelled out in each
// test would have made that move a silent skip rather than a failure, which is the one outcome a
// source-text assertion cannot survive.
//
// The resolver itself moved into `nodera-ui/layout` when the website's generators became a third
// Node consumer of the same table. This file stays because every test in this folder imports it by
// name, and because a re-export is the cheapest possible way to say "there is one of these".
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
