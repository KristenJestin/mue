// `bun run source:verify` - the integrity gate on its own, so it can be run before a
// long build and read as a checklist when a file is missing.

import { checkSource, formatSourceCheck, sourceManifest } from "../source";

const check = await checkSource();

console.log(`${sourceManifest.release} (${sourceManifest.version}), ${sourceManifest.publishedOn}`);
console.log(`  ${sourceManifest.landingPage}`);
console.log(`  looking in ${check.directory}`);
console.log();

for (const file of check.files) {
  const status = file.ok ? "ok     " : file.present ? "CHANGED" : "MISSING";
  console.log(`  ${status}  ${file.fileName}`);
  if (!file.ok && file.present) {
    console.log(`           expected ${file.expectedSha256}`);
    console.log(`           actual   ${file.actualSha256}`);
  }
}

console.log();
if (check.ok && check.actualArchiveSha256 === check.expectedArchiveSha256) {
  console.log(`archive sha256 ${check.actualArchiveSha256} matches ciqual.source.json`);
  process.exit(0);
}
console.error(formatSourceCheck(check));
process.exit(1);
