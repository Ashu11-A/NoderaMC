/** One indexed page, as `build-search-index.mjs` writes it. Keys are short because 22 of these ship. */
export interface SearchDoc {
  /** Route path. */
  p: string;
  /** `<title>`, from the route table. */
  t: string;
  /** `<meta name="description">`, from the route table. */
  d: string;
  /** `[id, text]` per indexed heading, for a deep link into the page. */
  h: [string, string][];
  /** Alternating term id and weight. A unit vector, capped at the document's strongest terms. */
  v: number[];
}

export interface SearchIndex {
  /** Bumped when this shape changes. A reader that does not know it must refuse rather than guess. */
  version: number;
  /** Sorted ascending — the query side binary-searches it for prefix ranges. */
  terms: string[];
  /** Inverse document frequency, parallel to `terms`. */
  idf: number[];
  docs: SearchDoc[];
}

export interface SearchResult {
  doc: SearchDoc;
  /** Cosine similarity, in (0, 1]. */
  score: number;
  /** The heading a query word landed in, when one did. */
  section: { id: string; text: string } | null;
}

/**
 * Split text into search terms. See `search.mjs` — the build script and the search box must import
 * this same function, or the index and the query disagree about what a term is and the box silently
 * matches nothing.
 */
export declare function tokenize(text: string): string[];

/** `[start, end)` of a prefix in a SORTED term list, by binary search. */
export declare function prefixRange(terms: readonly string[], prefix: string): [number, number];

/** Cosine similarity between the query and every document, ranked, capped at eight. */
export declare function rank(index: SearchIndex, query: string): SearchResult[];
