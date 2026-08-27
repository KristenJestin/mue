import { AGGREGATE_TYPES, type AggregateType } from "@mue/contracts";
import { healthProfileHandler } from "./health-profile";
import { measurementHandler } from "./measurement";
import type { AggregateHandler } from "./types";

/**
 * The aggregates this build synchronises, from PRD section 10.1's matrix.
 *
 * Everything above this map is written against `AggregateHandler`, so an
 * aggregate is a new handler and a new entry rather than a new branch in the
 * push or pull path — which is what adding `healthProfile` demonstrated: the
 * batching, idempotence, cursor and upgrade rules learned nothing about it.
 *
 * `Record<AggregateType, …>` is exhaustive on purpose. A type added to
 * `AGGREGATE_TYPES` with no handler here stops the build, instead of reaching
 * `applyOne`'s "unreachable" branch at runtime as a mutation the contract
 * accepts and nothing can apply.
 */
export const AGGREGATE_HANDLERS: Readonly<Record<AggregateType, AggregateHandler>> = {
  healthProfile: healthProfileHandler,
  measurement: measurementHandler,
};

export function handlerFor(aggregateType: string): AggregateHandler | undefined {
  return (AGGREGATE_TYPES as readonly string[]).includes(aggregateType)
    ? AGGREGATE_HANDLERS[aggregateType as AggregateType]
    : undefined;
}
