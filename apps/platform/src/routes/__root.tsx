import { HeadContent, Outlet, Scripts, createRootRoute } from "@tanstack/react-router";
import type { ReactElement, ReactNode } from "react";

/**
 * The HTML document every page of the platform shell is served inside.
 *
 * PRD_WEB section 5 asks for pages that are "propres et utilisables" while leaving the
 * real design system -- Tailwind V4, `packages/ui`, `packages/design-tokens`, shadcn on
 * Base UI -- to the Web product. None of those packages exist yet, so the styling here
 * is a single inline sheet: it ships inside the server-rendered HTML, which means the
 * sign-in and consent pages are legible on the first byte, with no stylesheet request
 * and no flash of unstyled content to hide a security decision behind.
 */
const STYLES = `
  :root {
    color-scheme: dark;
    --bg: #16130f;
    --surface: #211c16;
    --line: #3a3128;
    --text: #f2ece3;
    --muted: #b3a695;
    --accent: #e8a33d;
    --danger: #e2704a;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--text);
    font: 16px/1.6 ui-sans-serif, system-ui, "Segoe UI", sans-serif;
  }
  main {
    max-width: 34rem;
    margin: 0 auto;
    padding: 3rem 1.25rem 4rem;
  }
  h1 { font-size: 1.6rem; line-height: 1.25; margin: 0 0 .5rem; }
  p { color: var(--muted); margin: 0 0 1rem; }
  a { color: var(--accent); }
  code { background: var(--surface); padding: .1rem .35rem; border-radius: .25rem; }
  form, fieldset {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: .75rem;
    padding: 1.25rem;
    margin: 0 0 1rem;
  }
  fieldset { margin: 0 0 1.25rem; }
  form > fieldset { background: none; border: 0; border-radius: 0; padding: 0; }
  legend { color: var(--muted); font-size: .85rem; padding: 0 .4rem; }
  label { display: block; margin: 0 0 1rem; color: var(--text); }
  label > span { display: block; font-size: .85rem; color: var(--muted); margin: 0 0 .35rem; }
  input[type="email"], input[type="password"] {
    width: 100%;
    padding: .6rem .7rem;
    background: var(--bg);
    border: 1px solid var(--line);
    border-radius: .4rem;
    color: var(--text);
    font: inherit;
  }
  input:focus-visible, button:focus-visible, a:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  ul { list-style: none; padding: 0; margin: 0; }
  li { margin: 0 0 .75rem; }
  li label { display: flex; gap: .6rem; align-items: flex-start; margin: 0; }
  button {
    font: inherit;
    padding: .55rem 1.1rem;
    border-radius: .4rem;
    border: 1px solid var(--line);
    background: var(--surface);
    color: var(--text);
    cursor: pointer;
  }
  button[type="submit"], button.primary {
    background: var(--accent);
    border-color: var(--accent);
    color: #1a1409;
    font-weight: 600;
  }
  button[disabled] { opacity: .55; cursor: progress; }
  [role="alert"] { color: var(--danger); }
  .quiet { font-size: .85rem; }
`;

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: "utf-8" },
      { name: "viewport", content: "width=device-width, initial-scale=1" },
      // Section 16: these pages carry an authorization decision in their query string.
      // Nothing about them belongs in a search index or in a referrer sent off-origin.
      { name: "robots", content: "noindex, nofollow" },
      { name: "referrer", content: "same-origin" },
      { title: "Mue Platform" },
    ],
  }),
  component: RootComponent,
});

function RootComponent(): ReactElement {
  return (
    <RootDocument>
      <Outlet />
    </RootDocument>
  );
}

function RootDocument({ children }: Readonly<{ children: ReactNode }>): ReactElement {
  return (
    <html lang="en">
      <head>
        <HeadContent />
        <style>{STYLES}</style>
      </head>
      <body>
        {children}
        <Scripts />
      </body>
    </html>
  );
}
