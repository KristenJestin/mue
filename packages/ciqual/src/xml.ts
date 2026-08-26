// A reader for Ciqual's XML, and nothing more general than that.
//
// The four tables share one shape: a `<TABLE>` root holding flat records whose
// children are all leaves. There is no nesting, no namespace, no mixed content and no
// attribute that carries data — an absent value is written `<min missing=" " />`,
// which this reader reports as `null` exactly like an empty element.
//
// A dependency-free reader is a deliberate choice, not an omission: this package is
// the one place in the monorepo that must import nothing, and `compo_2025_11_03.xml`
// is 69 MB, where a DOM parser costs more than the scan it replaces.

/** One record: field name to text, `null` where the source wrote no value. */
export type XmlRecord = Readonly<Record<string, string | null>>;

const ENTITIES: Readonly<Record<string, string>> = {
  lt: "<",
  gt: ">",
  amp: "&",
  apos: "'",
  quot: '"',
  nbsp: "\u00a0",
};

export function decodeEntities(text: string): string {
  if (!text.includes("&")) return text;
  return text.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (whole, body: string) => {
    if (body.startsWith("#x") || body.startsWith("#X")) {
      return String.fromCodePoint(Number.parseInt(body.slice(2), 16));
    }
    if (body.startsWith("#")) return String.fromCodePoint(Number.parseInt(body.slice(1), 10));
    return ENTITIES[body.toLowerCase()] ?? whole;
  });
}

const FIELD = /<([\w.:-]+)((?:\s[^>]*?)?)(\/?)>/g;

function readFields(block: string): XmlRecord {
  const record: Record<string, string | null> = {};
  FIELD.lastIndex = 0;
  let match = FIELD.exec(block);
  while (match !== null) {
    const [, name = "", , selfClosing = ""] = match;
    if (selfClosing === "/") {
      // `<alim_nom_sci missing=" " />`: the source's way of writing "no value".
      record[name] = null;
      match = FIELD.exec(block);
      continue;
    }
    const close = block.indexOf(`</${name}>`, FIELD.lastIndex);
    if (close === -1) {
      record[name] = null;
      match = FIELD.exec(block);
      continue;
    }
    const text = decodeEntities(block.slice(FIELD.lastIndex, close)).trim();
    record[name] = text === "" ? null : text;
    FIELD.lastIndex = close + name.length + 3;
    match = FIELD.exec(block);
  }
  return record;
}

/**
 * Streams the `<tag>` records of a Ciqual table.
 *
 * A generator rather than an array so `compo` can be folded into the six constituents
 * this package keeps without ever holding 257 816 records at once.
 */
export function* readRecords(xml: string, tag: string): Generator<XmlRecord> {
  const open = `<${tag}>`;
  const close = `</${tag}>`;
  let cursor = xml.indexOf(open);
  while (cursor !== -1) {
    const end = xml.indexOf(close, cursor);
    if (end === -1) return;
    yield readFields(xml.slice(cursor + open.length, end));
    cursor = xml.indexOf(open, end + close.length);
  }
}

/** A required field, or a thrown error naming the table — a shape change must stop the build. */
export function requireField(record: XmlRecord, field: string, tag: string): string {
  const value = record[field];
  if (value === null || value === undefined) {
    throw new Error(`Ciqual ${tag} record is missing required field ${field}`);
  }
  return value;
}
