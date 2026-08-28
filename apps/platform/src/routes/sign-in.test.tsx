import { describe, expect, test } from "bun:test";
import { readSafeNext, readSignInContinuation } from "./sign-in";

describe("what the sign-in page is willing to send the owner to afterwards", () => {
  test("a path on this origin comes back unchanged", () => {
    expect(readSafeNext("?next=%2Fsettings%2Fagents")).toBe("/settings/agents");
    expect(readSafeNext("?next=%2F")).toBe("/");
  });

  test("nothing to continue to reads as nothing", () => {
    expect(readSafeNext("")).toBeNull();
    expect(readSafeNext("?next=")).toBeNull();
    expect(readSafeNext("?client_id=agent-1")).toBeNull();
  });

  test("refuses every spelling of another origin", () => {
    // A login page that forwards where it is told is the textbook open redirect, and
    // all three of these are absolute URLs to a browser.
    expect(readSafeNext("?next=https%3A%2F%2Fevil.example%2F")).toBeNull();
    expect(readSafeNext("?next=%2F%2Fevil.example%2F")).toBeNull();
    expect(readSafeNext("?next=%2F%5Cevil.example%2F")).toBeNull();
    expect(readSafeNext("?next=javascript%3Aalert(1)")).toBeNull();
  });
});

describe("the OAuth continuation is untouched by any of that", () => {
  test("a request carrying both is still read as an OAuth continuation", () => {
    const search = "?client_id=agent-1&scope=weight%3Aread&sig=abc&next=%2Fsettings%2Fagents";
    expect(readSignInContinuation(search)?.oauthQuery).toBe(search.slice(1));
    expect(readSignInContinuation(search)?.clientId).toBe("agent-1");
  });
});
