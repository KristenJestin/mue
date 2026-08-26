/**
 * The documented, testable half of FR-SYNC-005: run the retention policy.
 * `MUE_RETENTION_DAYS` sets the window; PLATFORM-CONTRACT decision 6 fixes the
 * default at 180 days. Run it from a schedule or by hand -- never at startup,
 * where it would compete with a migration.
 */
import { createDatabase } from "../src/client";
import { purgeExpired } from "../src/retention";

const handle = createDatabase();
try {
  const report = await purgeExpired(handle);
  console.log(
    `retention ${handle.config.retentionDays} days, cutoff ${report.cutoff.toISOString()}`,
  );
  for (const [table, count] of Object.entries(report.deleted)) {
    console.log(`  ${table}: ${count} row(s) purged`);
  }
} finally {
  await handle.close();
}
