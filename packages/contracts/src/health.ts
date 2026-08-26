import { z } from "zod";

/**
 * Liveness answers one question: is this process running. It never touches a
 * dependency, so a database outage cannot get the container restarted.
 */
export const livenessReportSchema = z.object({ status: z.literal("ok") }).meta({
  id: "LivenessReport",
});

export type LivenessReport = z.infer<typeof livenessReportSchema>;

/**
 * Readiness reports one line per dependency. The name is a fixed identifier and the
 * status is an enum: PRD section 20.5 forbids personal data on these endpoints, and a
 * driver's error text is the usual way a connection string reaches an unauthenticated
 * response body.
 */
export const readinessCheckReportSchema = z
  .object({
    name: z.string().min(1).max(64),
    status: z.enum(["ok", "failed"]),
  })
  .meta({ id: "ReadinessCheckReport" });

export const readinessReportSchema = z
  .object({
    status: z.enum(["ready", "not_ready"]),
    checks: z.array(readinessCheckReportSchema),
  })
  .meta({ id: "ReadinessReport" });

export type ReadinessReport = z.infer<typeof readinessReportSchema>;
