// The Ciqual catalogue generator (PRD_FOOD 9.1).
//
// This package imports nothing from `@mue/contracts`, `@mue/db`, `@mue/domain` or
// `@mue/api`, and nothing imports it. That is the point rather than an accident: it runs
// once per Ciqual release, on a developer's machine, and writes a file. Coupling it to
// the server's schema would make a table nobody controls a build dependency of an app
// that ships without it.

export * from "./catalogue";
export * from "./pairing";
export * from "./pipeline";
export * from "./portions";
export * from "./preparation";
export * from "./report";
export * from "./source";
export * from "./subset";
export * from "./table";
export * from "./teneur";
export * from "./units";
export * from "./uuid";
export * from "./xml";
