import { lookup } from "node:dns/promises";
import { request, type RequestOptions } from "node:https";
import { isIP } from "node:net";
import { Readable } from "node:stream";
import { classifyAddress } from "./ssrf";

/**
 * The SSRF-hardened transport `@better-auth/cimd` requires.
 *
 * `CimdOptions.fetchClientMetadataResource` is a *required* option, and the
 * plugin says why in as many words: the guarantees "cannot be implemented by
 * wrapping the standard Fetch API after DNS resolution, so the application must
 * provide them at its runtime-specific network boundary". A CIMD `client_id`
 * is an attacker-chosen URL that the server fetches while holding no
 * credentials but sitting inside the private network of section 8.1. Resolving
 * the name and then handing the *name* to fetch() would leave a TOCTOU window
 * in which DNS flips to 169.254.169.254 between the check and the connection.
 *
 * The guarantees, in order:
 *   1. resolve the hostname exactly once;
 *   2. reject the answer if any address is RFC 6890 special-purpose;
 *   3. connect to the pinned address, with the original hostname as Host, SNI
 *      and certificate identity, so TLS still authenticates who was asked for;
 *   4. refuse a redirect rather than follow it to a second, unchecked URL.
 *
 * The package ships `@better-auth/cimd/node`, which does the same thing --
 * and cannot be used here. It calls the `lookup` hook back Node-style with
 * `(null, address, family)`, while Bun 1.3.13 always passes `{ all: true }` and
 * expects an array, so it throws `results.sort is not a function` on the first
 * fetch. Bun also ignores `createConnection` on `node:https` entirely, which
 * rules out the other usual pinning hook: a transport built on it would
 * silently connect to the unpinned address and pass review.
 */

/** Resolve a hostname to one or more IP literals. Injected so tests are real. */
export type HostnameResolver = (hostname: string) => Promise<readonly string[]>;

export interface PinnedFetchOptions {
  /** Defaults to `node:dns` lookup, all answers, in resolver order. */
  readonly resolve?: HostnameResolver;
  /**
   * Address admission. Defaults to RFC 6890 public-routable only. Overriding
   * it removes the SSRF boundary and exists so a test can point a real fetch
   * at a real local server; nothing in production should pass it.
   */
  readonly isAddressAllowed?: (address: string) => boolean;
  /** Extra TLS material, such as a test certificate authority. */
  readonly ca?: string | Buffer | readonly (string | Buffer)[];
  /** Refuse a body larger than this. A metadata document is a few kilobytes. */
  readonly maxResponseBytes?: number;
  readonly timeoutMs?: number;
}

const DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024;
const DEFAULT_TIMEOUT_MS = 5_000;
const BODYLESS_STATUSES = new Set([204, 205, 304]);

export class MetadataFetchError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MetadataFetchError";
  }
}

async function defaultResolve(hostname: string): Promise<readonly string[]> {
  const answers = await lookup(hostname, { all: true, verbatim: true });
  return answers.map((answer) => answer.address);
}

function toHeaders(raw: NodeJS.Dict<string | string[]>): Headers {
  const headers = new Headers();
  for (const [name, value] of Object.entries(raw)) {
    if (Array.isArray(value)) for (const item of value) headers.append(name, item);
    else if (value !== undefined) headers.append(name, value);
  }
  return headers;
}

/**
 * Build the transport. The returned function has the Fetch signature
 * `@better-auth/cimd` expects, but it is not fetch: it takes no redirects, no
 * methods beyond GET and HEAD, and no scheme but HTTPS.
 */
export function createPinnedMetadataFetch(options: PinnedFetchOptions = {}) {
  const resolve = options.resolve ?? defaultResolve;
  const isAllowed = options.isAddressAllowed ?? ((address) => classifyAddress(address).routable);
  const maxBytes = options.maxResponseBytes ?? DEFAULT_MAX_RESPONSE_BYTES;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  return async function fetchClientMetadataResource(
    input: string | URL | Request,
    init?: RequestInit,
  ): Promise<Response> {
    // The three Fetch input shapes, read without building a Request: this
    // function never delegates to fetch, so a Request would only be a carrier.
    const source = typeof input === "string" || input instanceof URL ? undefined : input;
    const url = new URL(source === undefined ? String(input) : source.url);
    const method = (init?.method ?? source?.method ?? "GET").toUpperCase();
    const requestHeaders = new Headers(init?.headers ?? source?.headers ?? {});

    if (url.protocol !== "https:") {
      throw new MetadataFetchError(`metadata URL must be https, got ${url.protocol}`);
    }
    if (method !== "GET" && method !== "HEAD") {
      throw new MetadataFetchError(`metadata fetch supports GET and HEAD, not ${method}`);
    }

    // The plugin cancels a fetch it no longer needs; honour that as well as
    // the transport's own timeout.
    const signal = init?.signal ?? source?.signal ?? undefined;
    const hostname = url.hostname.replace(/^\[/, "").replace(/\]$/, "");

    // Resolve once. A literal IP is already its own answer; resolving it would
    // be a second chance to answer differently.
    const addresses = isIP(hostname) !== 0 ? [hostname] : await resolve(hostname);
    if (addresses.length === 0) {
      throw new MetadataFetchError(`${hostname} returned no DNS answer`);
    }

    // Every answer must pass, not just the one that is used: a name that also
    // answers with a private address is a rebinding target either way.
    for (const address of addresses) {
      if (!isAllowed(address)) {
        const verdict = classifyAddress(address);
        const reason = verdict.routable ? "not allowed by policy" : verdict.reason;
        throw new MetadataFetchError(`${hostname} resolves to a forbidden address: ${reason}`);
      }
    }
    const pinned = addresses[0] as string;
    const pinnedFamily = isIP(pinned);

    const headers: Record<string, string> = {};
    for (const [name, value] of requestHeaders.entries()) headers[name] = value;
    headers.host = url.host;

    const requestOptions: RequestOptions = {
      // No agent, so no pooled socket to a host resolved by someone else.
      agent: false,
      method,
      headers,
      timeout: timeoutMs,
      ...(signal === undefined ? {} : { signal }),
      // TLS still authenticates the name that was asked for, not the address.
      servername: isIP(hostname) === 0 ? hostname : undefined,
      ...(options.ca === undefined
        ? {}
        : { ca: (Array.isArray(options.ca) ? options.ca : [options.ca]) as (string | Buffer)[] }),
      lookup: (_hostname, lookupOptions, callback) => {
        // Node passes `(err, address, family)` unless it asked for `all`; Bun
        // always asks for `all` and sorts the array it gets back.
        const all = typeof lookupOptions === "object" && lookupOptions?.all === true;
        if (all) {
          (callback as unknown as (e: null, a: { address: string; family: number }[]) => void)(
            null,
            [{ address: pinned, family: pinnedFamily }],
          );
        } else {
          (callback as unknown as (e: null, a: string, f: number) => void)(
            null,
            pinned,
            pinnedFamily,
          );
        }
      },
    };

    const response = await new Promise<Response>((settle, fail) => {
      const clientRequest = request(url, requestOptions, (incoming) => {
        const status = incoming.statusCode ?? 502;

        // Refuse, do not follow. The redirect target is a URL nobody checked,
        // and following it would hand back everything guaranteed above.
        if (status >= 300 && status < 400) {
          incoming.destroy();
          fail(
            new MetadataFetchError(
              `metadata fetch refuses redirects: ${url.href} answered ${status}`,
            ),
          );
          return;
        }

        const contentLength = Number(incoming.headers["content-length"] ?? "0");
        if (Number.isFinite(contentLength) && contentLength > maxBytes) {
          incoming.destroy();
          fail(new MetadataFetchError(`metadata document larger than ${maxBytes} bytes`));
          return;
        }

        if (method === "HEAD" || BODYLESS_STATUSES.has(status)) {
          incoming.resume();
          settle(new Response(null, { status, headers: toHeaders(incoming.headers) }));
          return;
        }

        let seen = 0;
        incoming.on("data", (chunk: Buffer) => {
          seen += chunk.length;
          if (seen > maxBytes) {
            incoming.destroy(
              new MetadataFetchError(`metadata document larger than ${maxBytes} bytes`),
            );
          }
        });
        settle(
          // Through `unknown` because this file is also compiled by `apps/platform`,
          // whose tsconfig adds `lib.dom` for the consent page. `ReadableStream` then
          // resolves to the DOM declaration instead of Bun's, and the two disagree on
          // the `getReader()` overload set, so a direct assertion is rejected there
          // and accepted here. Runtime is unaffected: `Readable.toWeb` already
          // returns a web stream of `Uint8Array` chunks.
          new Response(Readable.toWeb(incoming) as unknown as ReadableStream<Uint8Array>, {
            status,
            headers: toHeaders(incoming.headers),
          }),
        );
      });

      clientRequest.once("error", fail);
      clientRequest.once("timeout", () => {
        clientRequest.destroy(
          new MetadataFetchError(`metadata fetch timed out after ${timeoutMs}ms`),
        );
      });
      clientRequest.end();
    });

    return response;
  };
}

/** The production transport: system DNS, RFC 6890 policy, system trust store. */
export const fetchClientMetadataResource = createPinnedMetadataFetch();
