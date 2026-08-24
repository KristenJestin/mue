package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.SetType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A session being written, exactly as it was typed.
 *
 * Everything here is a raw input string rather than a parsed value, for two reasons. A
 * half-typed `7,` has to come back unchanged after the process is killed (PRD 16.4), and
 * PRD FR-ACTIVITY-004 keeps the values of *every* preset the person has visited so returning
 * to an earlier one restores its fields intact — while the save path reads only the kinds the
 * active preset exposes, which is what makes an abandoned incline stay unwritten with nobody
 * having to decide anything.
 *
 * The whole draft is stored as one JSON string under one `SavedStateHandle` key: a per-preset
 * map of unbounded exercise lists cannot be flattened into Bundle keys.
 */
@Serializable
data class ActivityDraft(
    /** The session being edited, or null while creating one (PRD FR-ACTIVITY-010). */
    val editingSessionId: String? = null,
    val presetId: String = ActivityPreset.DEFAULT.id,
    /** ISO `YYYY-MM-DD`; empty means "today", which the screen resolves on its own clock. */
    val startedOn: String = "",
    /** `HH:mm`, and null rather than midnight when no time was given (PRD 8.2). */
    val startedAtTime: String? = null,
    val hours: String = "",
    val minutes: String = "",
    val perceivedEffort: Int? = null,
    val notes: String = "",
    /** PRD 9.1: the reversible `Quick log` / `Detailed log` choice of a strength session. */
    val detailed: Boolean = false,
    /** Keyed by [ActivityPreset.id], one entry per preset visited so far. */
    val byPreset: Map<String, PresetDraft> = emptyMap(),
    val exercises: List<ExerciseDraft> = emptyList(),
) {
    val preset: ActivityPreset get() = ActivityPreset.fromId(presetId)

    fun presetDraft(preset: ActivityPreset = this.preset): PresetDraft =
        byPreset[preset.id] ?: PresetDraft()

    fun withPresetDraft(
        preset: ActivityPreset = this.preset,
        block: (PresetDraft) -> PresetDraft,
    ): ActivityDraft = copy(byPreset = byPreset + (preset.id to block(presetDraft(preset))))

    /** Only what the active preset shows is read back on save (PRD FR-ACTIVITY-004). */
    fun activeMetricInputs(): Map<MetricKind, String> = preset.metrics.associateWith { kind ->
        presetDraft().metrics[kind.id].orEmpty()
    }

    fun toJson(): String = format.encodeToString(serializer(), this)

    companion object {
        /**
         * Total and non-throwing: a draft that cannot be read is a draft that was never there,
         * and PRD 13.4 keeps the screen usable rather than crashing on restore.
         */
        fun fromJson(raw: String?): ActivityDraft {
            if (raw.isNullOrBlank()) return ActivityDraft()
            return runCatching { format.decodeFromString(serializer(), raw) }
                .getOrElse { ActivityDraft() }
        }

        private val format = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}

/**
 * The part of a draft that belongs to one preset and is kept while another one is on screen
 * (PRD FR-ACTIVITY-004).
 *
 * [metrics] is keyed by [MetricKind.id] and holds the raw text of each field. A pace is the one
 * value typed in two boxes: it is stored joined as `m:ss`, which `ActivityValidation.validatePace`
 * reads back, so the map keeps a single shape for every kind.
 */
@Serializable
data class PresetDraft(
    val metrics: Map<String, String> = emptyMap(),
    /** The catalogue choice of the `Other` builder (PRD FR-ACTIVITY-008). */
    val movementId: String? = null,
    /** The free name, and the only path to an `other` movement. */
    val customMovementName: String = "",
    val environmentId: String? = null,
    val equipment: List<EquipmentDraft> = emptyList(),
) {
    fun metricInput(kind: MetricKind): String = metrics[kind.id].orEmpty()

    fun withMetric(kind: MetricKind, raw: String): PresetDraft =
        copy(metrics = metrics + (kind.id to raw))
}

/** One equipment chip; [customName] is filled only for the `other` type (PRD 8.4). */
@Serializable
data class EquipmentDraft(
    val typeId: String,
    val customName: String = "",
)

/** One exercise of a detailed strength session, with the sets typed under it (PRD 9.3). */
@Serializable
data class ExerciseDraft(
    val definitionId: String,
    val name: String,
    val trackingModeId: String,
    val equipmentId: String? = null,
    val isCustom: Boolean = false,
    val notes: String = "",
    val sets: List<SetDraft> = emptyList(),
)

/**
 * One set, seeded empty (PRD 12): a missing optional value is never shown as a zero, so a new
 * row starts with every field blank rather than with a plausible-looking default.
 *
 * [durationSeconds] accepts whole seconds or `m:ss`; both go through
 * `ActivityValidation.validateSetDuration`.
 */
@Serializable
data class SetDraft(
    val setTypeId: String = SetType.WORKING.id,
    val reps: String = "",
    val loadKg: String = "",
    val durationSeconds: String = "",
    val perceivedEffort: Int? = null,
)
