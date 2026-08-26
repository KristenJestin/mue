import { describe, expect, test } from "bun:test";
import { readConsentRequest } from "./oauth-consent";

describe("the consent request", () => {
  const search =
    "?response_type=code&client_id=agent-1&scope=offline_access+weight%3Aread+activity%3Awrite" +
    "&redirect_uri=http%3A%2F%2F127.0.0.1%3A9876%2Fcallback&resource=https%3A%2F%2Fmue.home%2Fmcp&sig=abc";

  test("reads what the agent asked for", () => {
    const request = readConsentRequest(search);
    expect(request).not.toBeNull();
    expect(request!.clientId).toBe("agent-1");
    expect(request!.scopes).toEqual(["offline_access", "weight:read", "activity:write"]);
    expect(request!.resource).toBe("https://mue.home/mcp");
  });

  test("hands the signed query back byte for byte", () => {
    // The signature covers these parameters. Rebuilding the query rather than echoing
    // it is how a consent page silently starts failing verification.
    expect(readConsentRequest(search)!.oauthQuery).toBe(search.slice(1));
  });

  test("shows nothing when opened directly", () => {
    expect(readConsentRequest("")).toBeNull();
    expect(readConsentRequest("?scope=weight%3Aread")).toBeNull();
  });
});
