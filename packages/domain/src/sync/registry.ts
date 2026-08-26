import { AGGREGATE_TYPES, type AggregateType } from "@mue/contracts";
import { measurementHandler } from "./measurement";
import type { AggregateHandler } from "./types";

/**
 * The aggregates this build synchronises.
 *
 * V1 carries one on purpose (PRD section 10.1 lists the rest as the next
 * phase). Everything above this map is written against `AggregateHandler`, so
 * a second aggregate is a new handler and a new entry, not a new branch in the
 * push or pull path.
 */
export const AGGREGATE_HANDLERS: Readonly<Record<AggregateType, AggregateHandler>> = {
  measurement: measurementHandler,
};

export function handlerFor(aggregateType: string): AggregateHandler | undefined {
  return (AGGREGATE_TYPES as readonly string[]).includes(aggregateType)
    ? AGGREGATE_HANDLERS[aggregateType as AggregateType]
    : undefined;
}
