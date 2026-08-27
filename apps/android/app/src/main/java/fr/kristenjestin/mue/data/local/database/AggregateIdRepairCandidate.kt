package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo

/**
 * One outbox row as a repair pass on **aggregate identifiers** needs to see it.
 *
 * A projection and not [SyncMutationEntity], for the reason [OutboxRepairCandidate] gives: the
 * difference is the payload, and an outbox that has not drained in a year holds a year of them.
 * The question here is about a single column, so a single column is what is read.
 *
 * `aggregate_type` is carried even though the query already filters on it. The rule lives in
 * `MealPlanIdRepair.verdict`, which is a pure function a JVM test can drive with no database, and
 * a rule that could not see the type it decides on would be a rule half-stated in SQL.
 */
data class AggregateIdRepairCandidate(
    @ColumnInfo(name = "mutation_id")
    val mutationId: String,

    @ColumnInfo(name = "aggregate_type")
    val aggregateType: String,

    @ColumnInfo(name = "aggregate_id")
    val aggregateId: String,

    @ColumnInfo(name = "state")
    val state: String,
)
