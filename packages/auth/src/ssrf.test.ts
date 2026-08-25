import { describe, expect, test } from "bun:test";
import { classifyAddress, isPubliclyRoutable, parseIpv6 } from "./ssrf";

describe("RFC 6890 classification", () => {
  const forbidden: readonly [string, string][] = [
    ["0.0.0.0", "this network"],
    ["10.1.2.3", "private-use"],
    ["100.64.7.7", "shared address space"],
    ["127.0.0.1", "loopback"],
    ["169.254.169.254", "link-local"],
    ["172.16.0.1", "private-use"],
    ["172.31.255.254", "private-use"],
    ["192.0.0.8", "IETF protocol assignments"],
    ["192.0.2.1", "documentation"],
    ["192.168.1.1", "private-use"],
    ["198.18.0.1", "benchmarking"],
    ["198.51.100.1", "documentation"],
    ["203.0.113.1", "documentation"],
    ["224.0.0.1", "multicast"],
    ["240.0.0.1", "reserved"],
    ["255.255.255.255", "limited broadcast"],
    ["::", "unspecified"],
    ["::1", "loopback"],
    ["fc00::1", "unique-local"],
    ["fd12:3456::1", "unique-local"],
    ["fe80::1", "link-local"],
    ["ff02::1", "multicast"],
    ["2001:db8::1", "documentation"],
    ["2002::1", "6to4"],
    ["64:ff9b::7f00:1", "IPv4-IPv6 translation"],
  ];

  for (const [address, reason] of forbidden) {
    test(`rejects ${address}`, () => {
      const verdict = classifyAddress(address);
      expect(verdict.routable).toBe(false);
      if (!verdict.routable) expect(verdict.reason).toContain(reason);
    });
  }

  test("rejects an IPv4-mapped private address, not just the bare one", () => {
    // The classic bypass: ::ffff:169.254.169.254 is link-local wearing IPv6.
    expect(isPubliclyRoutable("::ffff:169.254.169.254")).toBe(false);
    expect(isPubliclyRoutable("::ffff:127.0.0.1")).toBe(false);
    expect(isPubliclyRoutable("[::ffff:10.0.0.1]")).toBe(false);
  });

  test("rejects anything that is not an address at all", () => {
    expect(isPubliclyRoutable("metadata.google.internal")).toBe(false);
    expect(isPubliclyRoutable("999.1.1.1")).toBe(false);
    expect(isPubliclyRoutable("")).toBe(false);
  });

  test("accepts a public address", () => {
    for (const address of ["1.1.1.1", "93.184.215.14", "2606:4700::1111", "2a00:1450:4007::68"]) {
      expect(isPubliclyRoutable(address)).toBe(true);
    }
  });

  test("parses compressed and embedded IPv6 forms", () => {
    expect(parseIpv6("::1")?.at(15)).toBe(1);
    expect(parseIpv6("2001:db8::")?.slice(0, 4)).toEqual([0x20, 0x01, 0x0d, 0xb8]);
    expect(parseIpv6("::ffff:1.2.3.4")?.slice(12)).toEqual([1, 2, 3, 4]);
    expect(parseIpv6("fe80::1%eth0")?.at(0)).toBe(0xfe);
    expect(parseIpv6("1:2:3")).toBeNull();
    expect(parseIpv6("::1::2")).toBeNull();
  });
});
