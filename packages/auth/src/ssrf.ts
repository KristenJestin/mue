/**
 * RFC 6890 special-purpose address classification.
 *
 * `@better-auth/cimd` requires the application to supply a transport that
 * "MUST resolve the hostname exactly once, reject RFC 6890 special-use
 * addresses, pin the approved address for the connection, and refuse
 * redirects". This module is the address half of that: given an IP literal, is
 * it globally routable, or does it point back inside the private network the
 * PRD calls a private server (section 8.1)?
 *
 * The list is written out rather than reduced to "not private" because the
 * addresses that matter are not the obvious ones. 169.254.169.254 is a cloud
 * metadata endpoint, 0.0.0.0 is the local host on Linux, 100.64.0.0/10 is
 * carrier NAT, and ::ffff:127.0.0.1 is loopback wearing an IPv6 hat.
 */

interface Block {
  readonly name: string;
  readonly bytes: readonly number[];
  readonly prefix: number;
}

function v4(a: number, b: number, c: number, d: number, prefix: number, name: string): Block {
  return { name, bytes: [a, b, c, d], prefix };
}

/** RFC 6890 section 2.2.2, plus RFC 6598 shared address space. */
const IPV4_BLOCKS: readonly Block[] = [
  v4(0, 0, 0, 0, 8, "this network"),
  v4(10, 0, 0, 0, 8, "private-use"),
  v4(100, 64, 0, 0, 10, "shared address space"),
  v4(127, 0, 0, 0, 8, "loopback"),
  v4(169, 254, 0, 0, 16, "link-local"),
  v4(172, 16, 0, 0, 12, "private-use"),
  v4(192, 0, 0, 0, 24, "IETF protocol assignments"),
  v4(192, 0, 2, 0, 24, "documentation TEST-NET-1"),
  v4(192, 31, 196, 0, 24, "AS112-v4"),
  v4(192, 52, 193, 0, 24, "AMT"),
  v4(192, 88, 99, 0, 24, "6to4 relay anycast"),
  v4(192, 168, 0, 0, 16, "private-use"),
  v4(192, 175, 48, 0, 24, "direct delegation AS112"),
  v4(198, 18, 0, 0, 15, "benchmarking"),
  v4(198, 51, 100, 0, 24, "documentation TEST-NET-2"),
  v4(203, 0, 113, 0, 24, "documentation TEST-NET-3"),
  v4(224, 0, 0, 0, 4, "multicast"),
  // Before 240.0.0.0/4, which contains it: the more specific name is the
  // useful one in an error message.
  v4(255, 255, 255, 255, 32, "limited broadcast"),
  v4(240, 0, 0, 0, 4, "reserved"),
];

function v6(hex: string, prefix: number, name: string): Block {
  const parsed = parseIpv6(hex);
  if (parsed === null) throw new Error(`bad IPv6 block literal: ${hex}`);
  return { name, bytes: parsed, prefix };
}

/** RFC 6890 section 2.2.3. */
const IPV6_BLOCKS: readonly Block[] = [
  v6("::", 128, "unspecified"),
  v6("::1", 128, "loopback"),
  v6("64:ff9b::", 96, "IPv4-IPv6 translation"),
  v6("64:ff9b:1::", 48, "local-use IPv4-IPv6 translation"),
  v6("100::", 64, "discard-only"),
  v6("2001::", 23, "IETF protocol assignments"),
  v6("2001:db8::", 32, "documentation"),
  v6("2002::", 16, "6to4"),
  v6("fc00::", 7, "unique-local"),
  v6("fe80::", 10, "link-local unicast"),
  v6("ff00::", 8, "multicast"),
];

export function parseIpv4(value: string): number[] | null {
  const parts = value.split(".");
  if (parts.length !== 4) return null;
  const bytes: number[] = [];
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part)) return null;
    const byte = Number(part);
    if (byte > 255) return null;
    bytes.push(byte);
  }
  return bytes;
}

export function parseIpv6(value: string): number[] | null {
  const zone = value.indexOf("%");
  const address = zone === -1 ? value : value.slice(0, zone);
  const halves = address.split("::");
  if (halves.length > 2) return null;

  const expand = (group: string): number[] | null => {
    if (group === "") return [];
    const out: number[] = [];
    const parts = group.split(":");
    for (const [position, part] of parts.entries()) {
      // A trailing IPv4 literal, as in ::ffff:127.0.0.1.
      if (part.includes(".")) {
        if (position !== parts.length - 1) return null;
        const embedded = parseIpv4(part);
        if (embedded === null) return null;
        out.push(...embedded);
        continue;
      }
      if (!/^[0-9a-fA-F]{1,4}$/.test(part)) return null;
      const word = Number.parseInt(part, 16);
      out.push(word >>> 8, word & 0xff);
    }
    return out;
  };

  const head = expand(halves[0] ?? "");
  const tail = halves.length === 2 ? expand(halves[1] ?? "") : [];
  if (head === null || tail === null) return null;
  if (halves.length === 1) return head.length === 16 ? head : null;
  const gap = 16 - head.length - tail.length;
  if (gap < 0) return null;
  return [...head, ...Array.from<number>({ length: gap }).fill(0), ...tail];
}

function inBlock(bytes: readonly number[], block: Block): boolean {
  if (bytes.length !== block.bytes.length) return false;
  let remaining = block.prefix;
  for (let index = 0; index < bytes.length && remaining > 0; index += 1) {
    const width = Math.min(8, remaining);
    const mask = width === 8 ? 0xff : (0xff << (8 - width)) & 0xff;
    if (((bytes[index] ?? 0) & mask) !== ((block.bytes[index] ?? 0) & mask)) return false;
    remaining -= width;
  }
  return true;
}

export type AddressVerdict =
  | { readonly routable: true }
  | { readonly routable: false; readonly reason: string };

const ROUTABLE: AddressVerdict = { routable: true };

/**
 * Classify an IP literal. An address this rejects is one a metadata fetch must
 * never reach, whoever put it in DNS.
 */
export function classifyAddress(literal: string): AddressVerdict {
  const bare = literal.replace(/^\[/, "").replace(/\]$/, "");
  const asV4 = parseIpv4(bare);
  if (asV4 !== null) {
    for (const block of IPV4_BLOCKS) {
      if (inBlock(asV4, block)) return { routable: false, reason: `${bare} is ${block.name}` };
    }
    return ROUTABLE;
  }

  const asV6 = parseIpv6(bare);
  if (asV6 === null) return { routable: false, reason: `${literal} is not an IP address` };

  // An IPv4-mapped address is an IPv4 address. Classifying it as IPv6 would let
  // ::ffff:169.254.169.254 through every check above.
  const mappedPrefix = asV6.slice(0, 12);
  const isMapped =
    mappedPrefix.slice(0, 10).every((byte) => byte === 0) &&
    mappedPrefix[10] === 0xff &&
    mappedPrefix[11] === 0xff;
  if (isMapped) return classifyAddress(asV6.slice(12).join("."));

  for (const block of IPV6_BLOCKS) {
    if (inBlock(asV6, block)) return { routable: false, reason: `${bare} is ${block.name}` };
  }
  return ROUTABLE;
}

export function isPubliclyRoutable(literal: string): boolean {
  return classifyAddress(literal).routable;
}
