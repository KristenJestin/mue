import { LATEST_PROTOCOL_VERSION } from "@modelcontextprotocol/sdk/types.js";

/**
 * The MCP revision this server actually negotiates.
 *
 * PRD sections 8.3, 15.1, 21 and 22.4 all name revision `2026-07-28`. No shipping
 * SDK can negotiate it: `@modelcontextprotocol/sdk` 1.30.0 and the renamed V2
 * packages `@modelcontextprotocol/{core,client,server}` 2.0.0 both export
 * `LATEST_PROTOCOL_VERSION = "2025-11-25"`, and `2026-07-28` appears in those
 * packages only inside doc comments. PLATFORM-CONTRACT decision 4 settles it:
 * use the v1 SDK and whatever revision it advertises, and do not block the
 * vertical slice on a revision that does not exist.
 *
 * So the constant is read from the SDK rather than written down here: the day a
 * release ships `2026-07-28`, upgrading the package is the whole change, and
 * `PRD_REQUESTED_PROTOCOL_VERSION` below is what the test compares against so
 * the divergence stays visible instead of quietly becoming permanent.
 */
export const MUE_MCP_PROTOCOL_VERSION: string = LATEST_PROTOCOL_VERSION;

/** The revision the PRD asks for. Kept so the gap is asserted, not forgotten. */
export const PRD_REQUESTED_PROTOCOL_VERSION = "2026-07-28";

/** Name and version announced in `initialize`. No provider name appears here (section 14.1). */
export const MUE_MCP_SERVER_INFO = {
  name: "mue",
  title: "Mue",
  version: "1.0.0",
} as const;

/**
 * What an agent is told the server is for, before it has listed a single tool.
 * Deliberately free of any model or vendor name: section 14.1 forbids depending
 * on one, and a hint like "ask Claude to..." is exactly such a dependency.
 */
export const MUE_MCP_INSTRUCTIONS = [
  "Mue is a private, self-hosted health log: weight measurements, finished activity sessions,",
  "custom exercises and a small health profile.",
  "",
  "Every list is cursor-paginated and imposes no time window, so the whole history can be walked.",
  "Results carry the aggregate revision, its provenance, and `lastAndroidSyncAt`: the last moment",
  "the server saw the Android phone synchronise. Anything the phone recorded after that instant is",
  "not here yet, so never present a reading as current without checking it.",
  "",
  "Write tools create final records, not drafts. When a required field is missing the tool returns",
  "a structured error naming the field: ask the person for it and call the tool again. Never guess",
  "a required value, and leave an optional one out rather than inventing it.",
].join("\n");
