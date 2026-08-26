import { describe, expect, test } from "bun:test";

import { CIQUAL_NAMESPACE, ciqualEntryId, DNS_NAMESPACE, uuidV5 } from "./uuid";

describe("uuidV5", () => {
  test("matches RFC 4122's own worked example", () => {
    // The canonical vector: uuidv5("www.example.com", DNS).
    expect(uuidV5("www.example.com", DNS_NAMESPACE)).toBe("2ed6657d-e927-568b-95e1-2665a8aea6a2");
  });

  test("sets the version and variant bits", () => {
    const id = uuidV5("anything", DNS_NAMESPACE);
    expect(id[14]).toBe("5");
    expect("89ab").toContain(id[19] as string);
  });
});

describe("the Mue Ciqual namespace", () => {
  test("is itself derivable, so it is checkable rather than magic", () => {
    expect(uuidV5("mue.kristenjestin.fr/food/ciqual", DNS_NAMESPACE)).toBe(CIQUAL_NAMESPACE);
  });
});

describe("ciqualEntryId", () => {
  test("is the same on every machine and every regeneration", () => {
    // This is `ExerciseCatalogSeed.kt`'s written-down-ids argument at 500x scale: an id
    // generated on the device differs per install, and PRD_FOOD 21's synchronised `Food`
    // aggregate cannot reconcile rows whose key depends on which phone created them.
    expect(ciqualEntryId("9100")).toBe("499912cd-40e2-5752-bf0f-937ff674a803");
    expect(ciqualEntryId("9100")).toBe(ciqualEntryId("9100"));
  });

  test("is keyed on the code, not on the name", () => {
    // A label is retranslated between releases; a code is not. An id that moved when a
    // name was corrected would orphan every journal line that referenced it.
    expect(ciqualEntryId("9100")).not.toBe(ciqualEntryId("9104"));
    expect(ciqualEntryId("36017")).toBe(uuidV5("ciqual:36017", CIQUAL_NAMESPACE));
  });

  test("collides for no pair of Ciqual codes in the plausible range", () => {
    const seen = new Set<string>();
    for (let code = 1000; code < 80_000; code += 7) seen.add(ciqualEntryId(String(code)));
    expect(seen.size).toBe(Math.ceil((80_000 - 1000) / 7));
  });
});
