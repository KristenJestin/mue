package fr.kristenjestin.mue.data.remote.sync

import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.sync.MeasurementPayload
import fr.kristenjestin.mue.data.sync.PAYLOAD_SCHEMA_VERSION
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The seam between `sync_mutations` and the wire. */
class SyncWireTest {

    private val origin = OriginDto(OriginDto.TYPE_ANDROID, "device-7f3c1a04")

    private fun row(
        aggregateType: String = SyncAggregateStateEntity.TYPE_MEASUREMENT,
        aggregateId: String = "2026-08-25",
        op: String = SyncMutationEntity.OP_UPSERT,
        payload: String? = """{"date":"2026-08-25","weightCg":7845}""",
        baseRevision: Long? = 3L,
        createdAt: Long = 1_774_425_124_117L,
    ) = SyncMutationEntity(
        mutationId = "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        op = op,
        baseRevision = baseRevision,
        payload = payload,
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = createdAt,
        state = SyncMutationEntity.STATE_PENDING,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )

    @Test
    fun anUpsertBecomesItsWireBranchWithTheStoredIdentifier() {
        val envelope = assertIs<MeasurementUpsertMutationDto>(SyncWire.toEnvelope(row(), origin))

        assertEquals("0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6", envelope.mutationId)
        assertEquals(WIRE_AGGREGATE_MEASUREMENT, envelope.aggregateType)
        assertEquals("2026-08-25", envelope.aggregateId)
        assertEquals("3", envelope.baseRevision)
        assertEquals(7_845, envelope.payload.weightCg)
        assertEquals(origin, envelope.origin)
    }

    /**
     * Null is PRD 12.2's "si elle existe" and not zero: zero would claim a revision the server
     * issued, and the server would refuse the mutation as a stale edit of nothing.
     */
    @Test
    fun aCreationQuotesNoBaseRevision() {
        val envelope = assertIs<MeasurementUpsertMutationDto>(
            SyncWire.toEnvelope(row(baseRevision = null), origin),
        )

        assertNull(envelope.baseRevision)
    }

    @Test
    fun aDeleteBecomesTheDeleteBranchWithANullPayload() {
        val envelope = assertIs<DeleteMutationDto>(
            SyncWire.toEnvelope(
                row(op = SyncMutationEntity.OP_DELETE, payload = null, aggregateId = "2026-08-24"),
                origin,
            ),
        )

        assertEquals("2026-08-24", envelope.aggregateId)
        assertNull(envelope.payload)
    }

    /**
     * The health profile, with the values the owner's phone had been holding.
     *
     * This is the row that could never leave: it was journalled at every save, and
     * `AGGREGATE_TYPES` in `packages/contracts` was `["measurement"]`, so [SyncWire.toEnvelope]
     * answered null and `Data & sync` counted a change that could not fall. The assertion is on
     * the real height and the real birth date rather than on the shape, because the contract
     * constrains both values and a shape check would pass on either.
     */
    @Test
    fun theHealthProfileBecomesItsOwnUpsertBranch() {
        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    aggregateId = "me",
                    payload = """{"heightCm":171,"birthDate":"1998-11-18"}""",
                    baseRevision = null,
                ),
                origin,
            ),
        )

        assertEquals(WIRE_AGGREGATE_HEALTH_PROFILE, envelope.aggregateType)
        assertEquals(WIRE_HEALTH_PROFILE_AGGREGATE_ID, envelope.aggregateId)
        assertEquals(WIRE_OP_UPSERT, envelope.op)
        assertNull(envelope.baseRevision)
        assertEquals(171, envelope.payload.heightCm)
        assertEquals("1998-11-18", envelope.payload.birthDate)
        assertEquals(origin, envelope.origin)
    }

    /**
     * The aggregate identifier is the contract's constant and not the outbox row's.
     *
     * PRD 13.4 gives an account one profile, so a row that somehow carried another identifier
     * must not be able to open a rival aggregate on the server. The DTO's default is the only
     * value that can appear.
     */
    @Test
    fun aProfileRowCannotSmuggleARivalAggregateIdOntoTheWire() {
        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    aggregateId = "me-2",
                    payload = """{"heightCm":171,"birthDate":null}""",
                ),
                origin,
            ),
        )

        assertEquals(WIRE_HEALTH_PROFILE_AGGREGATE_ID, envelope.aggregateId)
    }

    /** A cleared field is `null` on the wire, and the key is written rather than dropped. */
    @Test
    fun aClearedProfileFieldTravelsAsAStatedNull() {
        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    aggregateId = "me",
                    payload = """{"heightCm":null,"birthDate":"1998-11-18"}""",
                ),
                origin,
            ),
        )

        val text = SyncJson.instance.encodeToString(
            MutationEnvelopeSerializer,
            envelope as MutationEnvelopeDto,
        )
        assertTrue(text.contains("\"heightCm\":null"), "a cleared height is stated: $text")
        assertTrue(text.contains("\"op\":\"upsert\""), "op is written as a field now: $text")
        assertTrue(
            text.contains("\"aggregateType\":\"healthProfile\""),
            "the second discriminator has to be on the wire: $text",
        )
    }

    /**
     * Nothing is deferred any more, and this is what changed.
     *
     * The food journal used to map to null here — PRD 10.1 synchronised it, `SyncOutbox` wrote a
     * row at every meal, and `AGGREGATE_TYPES` had no branch for it, so the row stayed `pending`
     * for ever. It goes out now, and an activity session, which was not even journalled, goes out
     * with it.
     *
     * What still maps to null is an aggregate name no contract has ever carried. That branch is
     * the one that keeps a *future* aggregate journalled ahead of its wire shape from being
     * refused rather than held, so it is asserted rather than deleted.
     */
    @Test
    fun anAggregateTypeTheContractHasNoBranchForMapsToNothing() {
        assertNotNull(
            SyncWire.toEnvelope(
                row(
                    aggregateType = FoodAggregates.TYPE_FOOD_LOG_ENTRY,
                    payload = """
                        {"id":"3d60ba59-8e12-4f41-8690-7b2c5d8e3f16","consumedOn":"2026-08-25",
                         "consumedAt":"12:30","slot":"lunch","kind":"quick","title":"Riz",
                         "estimation":"measured","weighedCooked":false}
                    """.trimIndent(),
                ),
                origin,
            ),
        )
        assertNotNull(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
                    op = SyncMutationEntity.OP_DELETE,
                    payload = null,
                ),
                origin,
            ),
        )
        assertNull(
            SyncWire.toEnvelope(
                row(aggregateType = "sleepSession", payload = """{"hours":7}"""),
                origin,
            ),
        )
    }

    /**
     * A `healthProfile` delete has no wire branch even though the type is sendable, which is
     * why the queue filter and [SyncWire.toEnvelope] are two guards and not one.
     *
     * PRD 13.4 gives the profile no deletion and `SyncOutbox` mints none, so this row can only
     * come from a downgrade or a hand-written database. Null keeps it, which is what the engine
     * does with anything it cannot shape.
     */
    @Test
    fun aHealthProfileDeleteHasNoWireBranchAndIsHeldRatherThanSent() {
        assertNull(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    aggregateId = "me",
                    op = SyncMutationEntity.OP_DELETE,
                    payload = null,
                ),
                origin,
            ),
        )
    }

    /** An upsert with no payload is a corrupt row, not a mutation the wire can carry. */
    @Test
    fun anUpsertWithoutAPayloadIsRefusedRatherThanSentEmpty() {
        val failure = runCatching { SyncWire.toEnvelope(row(payload = null), origin) }
            .exceptionOrNull()

        assertIs<SerializationException>(failure)
    }

    @Test
    fun aStoredPayloadThatIsNotJsonIsRefused() {
        val failure = runCatching { SyncWire.toEnvelope(row(payload = "{not json"), origin) }
            .exceptionOrNull()

        assertIs<SerializationException>(failure)
    }

    // --- counters and instants ----------------------------------------------------------

    @Test
    fun aCanonicalDecimalCounterBecomesALong() {
        assertEquals(0L, SyncWire.counterOrNull("0"))
        assertEquals(9_007_199_254_740_993L, SyncWire.counterOrNull("9007199254740993"))
        assertEquals(Long.MAX_VALUE, SyncWire.counterOrNull("9223372036854775807"))
    }

    /**
     * The contract sizes a counter as unsigned 64-bit and `sync_aggregate_state.revision` is a
     * signed SQLite integer. Above 2^63 there is no truthful local value, so there is no value.
     */
    @Test
    fun aCounterPastTheSignedRangeHasNoLocalRepresentation() {
        assertNull(SyncWire.counterOrNull("18446744073709551615"))
        assertNull(SyncWire.counterOrNull("9223372036854775808"))
    }

    /** Anything that is not canonical decimal is not a counter, including a signed one. */
    @Test
    fun anythingThatIsNotCanonicalDecimalIsNotACounter() {
        assertNull(SyncWire.counterOrNull(""))
        assertNull(SyncWire.counterOrNull("-1"))
        assertNull(SyncWire.counterOrNull("4.0"))
        assertNull(SyncWire.counterOrNull("0x10"))
        assertNull(SyncWire.counterOrNull(" 4"))
    }

    @Test
    fun instantsRoundTripThroughEpochMilliseconds() {
        assertEquals("2026-08-25T06:12:04.117Z", SyncWire.toInstantText(1_787_638_324_117L))
        assertEquals(1_787_638_324_117L, SyncWire.toEpochMillisOrNull("2026-08-25T06:12:04.117Z"))
        assertNull(SyncWire.toEpochMillisOrNull(null))
        assertNull(SyncWire.toEpochMillisOrNull("not an instant"))
    }

    /** The two vocabularies are translated rather than assumed equal. */
    @Test
    fun aWireAggregateTypeIsTranslatedToItsLocalName() {
        assertEquals(
            SyncAggregateStateEntity.TYPE_MEASUREMENT,
            SyncWire.localAggregateType(WIRE_AGGREGATE_MEASUREMENT),
        )
        assertEquals(
            SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
            SyncWire.localAggregateType(WIRE_AGGREGATE_HEALTH_PROFILE),
        )
        assertEquals(
            SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
            SyncWire.localAggregateType(WIRE_AGGREGATE_ACTIVITY_SESSION),
        )
        assertEquals(
            SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE,
            SyncWire.localAggregateType(WIRE_AGGREGATE_CUSTOM_EXERCISE),
        )
        assertEquals(FoodAggregates.TYPE_FOOD, SyncWire.localAggregateType(WIRE_AGGREGATE_FOOD))
        assertEquals(FoodAggregates.TYPE_RECIPE, SyncWire.localAggregateType(WIRE_AGGREGATE_RECIPE))
        assertEquals(
            FoodAggregates.TYPE_FOOD_LOG_ENTRY,
            SyncWire.localAggregateType(WIRE_AGGREGATE_FOOD_LOG_ENTRY),
        )
        assertEquals(
            FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
            SyncWire.localAggregateType(WIRE_AGGREGATE_MEAL_PLAN_ENTRY),
        )
        // `recipe` used to be the example of a type with no local home. It has one now, so the
        // example has to be a type nothing has ever synchronised.
        assertNull(SyncWire.localAggregateType("sleepSession"))
    }

    /**
     * A received profile always lands on [HealthProfileEntity.ROW_ID], whatever else is around.
     * That is the client half of "un agrégat unique": there is no branch that inserts a second
     * profile row, so a second device converges on the first one's row by construction.
     */
    @Test
    fun aReceivedProfileAlwaysLandsOnTheOneLocalRow() {
        val entity = SyncWire.healthProfileEntity(
            HealthProfilePayloadV1Dto(heightCm = 171, birthDate = "1998-11-18"),
        )

        assertEquals(HealthProfileEntity.ROW_ID, entity.id)
        assertEquals(171, entity.heightCm)
        assertEquals("1998-11-18", entity.birthDate)
    }

    // --- PRD_SCALE 22 : ce qui ne quittait pas le téléphone ---------------------------------

    /**
     * **Le défaut que ce module ferme.** Le sexe était journalisé par `SyncOutbox` à chaque
     * enregistrement de profil et n'atteignait jamais le réseau : [SyncWire.toEnvelope] relit le
     * payload stocké à travers [HealthProfilePayloadV1Dto], qui ne déclarait ni `sex`, et
     * [SyncJson] a `ignoreUnknownKeys = true` — donc la clé était **retirée**, sans exception,
     * sans journal, sans rien à voir nulle part.
     *
     * Le test part de l'outbox et va jusqu'aux octets, parce que c'est la seule forme qui aurait
     * échoué : chaque moitié prise séparément était correcte. L'outbox écrivait bien le sexe, le
     * DTO se sérialisait bien — c'est le passage de l'un à l'autre qui le perdait.
     */
    @Test
    fun `le sexe du profil quitte le téléphone`() {
        val outbox = SyncOutbox(newMutationId = { MUTATION_ID }, now = { 1_774_425_124_902L })
        val stored = outbox.healthProfileUpsert(
            heightCm = 171,
            birthDate = LocalDate.of(1998, 11, 18),
            sex = Sex.MALE,
        )

        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(stored, origin),
        )
        assertEquals(Sex.MALE.wireValue, envelope.payload.sex)

        val body = SyncJson.instance.encodeToString(MutationEnvelopeSerializer, envelope)
        assertTrue(body.contains("\"sex\":\"male\""), "le sexe doit être sur le fil : $body")
    }

    /**
     * L'autre moitié : un sexe non renseigné est **absent** du corps et non `null`.
     *
     * Le schéma Zod dit `.optional()` et non `.nullable()`, donc un `"sex": null` serait refusé.
     * L'outbox, elle, écrit toujours les trois champs — c'est son format de stockage — et c'est
     * le défaut de [HealthProfilePayloadV1Dto.sex] qui fait disparaître la clé à la sortie.
     * `heightCm` et `birthDate` restent écrits, `null` compris, parce qu'eux sont `.nullable()`
     * et que la fusion champ par champ de PRD 13.4 doit distinguer « effacé » de « pas mentionné ».
     */
    @Test
    fun `un sexe non renseigné est absent du corps et non nul`() {
        val outbox = SyncOutbox(newMutationId = { MUTATION_ID }, now = { 1_774_425_124_902L })
        val stored = outbox.healthProfileUpsert(heightCm = null, birthDate = null, sex = null)

        val envelope = assertNotNull(SyncWire.toEnvelope(stored, origin))
        val body = SyncJson.instance.encodeToString(MutationEnvelopeSerializer, envelope)

        assertTrue(!body.contains("sex"), "un sexe absent ne s'écrit pas : $body")
        assertTrue(body.contains("\"heightCm\":null"), "une taille effacée s'énonce : $body")
        assertTrue(body.contains("\"birthDate\":null"), "une naissance effacée s'énonce : $body")
    }

    /**
     * La mesure complète traverse : provenance, impédance et composition (PRD_SCALE 22).
     *
     * L'assertion qui compte est la dernière. `sourceScaleId` n'apparaît pas dans le corps parce
     * qu'aucune des deux formes n'a de champ pour lui — ni `MeasurementPayload`, ni
     * [MeasurementPayloadV1Dto] — ce qui est la forme que PRD_SCALE 16.2 et 22 demandent : une
     * absence de champ, pas un champ qu'il faut se souvenir de ne pas remplir.
     */
    @Test
    fun `une pesée de balance traverse avec son impédance et sa composition`() {
        val outbox = SyncOutbox(newMutationId = { MUTATION_ID }, now = { 1_774_425_124_117L })
        val stored = outbox.measurementUpsert(
            Measurement(
                date = LocalDate.of(2026, 8, 25),
                weight = Weight.ofHundredthsOrNull(7_845)!!,
                source = MeasurementSource.SCALE,
                sourceScaleId = "scale-1",
                impedanceOhm = 520,
                bodyComposition = BodyComposition(
                    date = LocalDate.of(2026, 8, 25),
                    formulaId = "mue-foot-to-foot-v1",
                    formulaVersion = 1,
                    inputWeightCg = 7_845,
                    inputHeightCm = 171,
                    inputAgeYears = 27,
                    inputSex = Sex.MALE,
                    bodyFatDeciPercent = 290,
                    fatFreeMassCg = 5_567,
                    bodyWaterDeciPercent = 519,
                    restingEnergyKcal = 1_723,
                ),
            ),
        )

        val envelope = assertIs<MeasurementUpsertMutationDto>(SyncWire.toEnvelope(stored, origin))
        assertEquals(MeasurementSource.SCALE.wireValue, envelope.payload.sourceType)
        assertEquals(520, envelope.payload.impedanceOhm)
        assertEquals(5_567, envelope.payload.bodyComposition?.fatFreeMassCg)
        assertEquals(Sex.MALE.wireValue, envelope.payload.bodyComposition?.inputSex)

        val body = SyncJson.instance.encodeToString(MutationEnvelopeSerializer, envelope)
        assertTrue(
            !body.contains("scale-1") && !body.contains("sourceScaleId"),
            "PRD_SCALE 16.2 : l'identifiant de la balance ne quitte pas le téléphone : $body",
        )

        // Les deux chemins entre l'outbox et le fil doivent donner le même payload : celui-ci,
        // qui relit le JSON stocké à travers le DTO, et [SyncWire.measurementPayload], qui
        // convertit la forme de l'outbox directement. Deux formes écrites à la main dans deux
        // fichiers ne restent d'accord que si quelque chose le vérifie.
        val mapped = SyncWire.measurementPayload(
            Json.decodeFromString(MeasurementPayload.serializer(), checkNotNull(stored.payload)),
        )
        assertEquals(envelope.payload, mapped)
    }

    /**
     * Une saisie manuelle sans rien de plus reste octet pour octet ce qu'elle était avant ce
     * module — la moitié Kotlin de l'argument qui fait étendre la version 1 plutôt que la
     * remplacer. Les trois champs sont `.optional()`, donc absents et non `null`.
     */
    @Test
    fun `une saisie manuelle n'écrit aucun des trois champs ajoutés`() {
        val outbox = SyncOutbox(newMutationId = { MUTATION_ID }, now = { 1_774_425_124_117L })
        val stored = outbox.measurementUpsert(
            Measurement(
                date = LocalDate.of(2026, 8, 25),
                weight = Weight.ofHundredthsOrNull(7_845)!!,
            ),
        )

        val envelope = assertNotNull(SyncWire.toEnvelope(stored, origin))
        val body = SyncJson.instance.encodeToString(MutationEnvelopeSerializer, envelope)

        assertTrue(!body.contains("impedanceOhm"), "une impédance absente ne s'écrit pas : $body")
        assertTrue(!body.contains("bodyComposition"), "une composition absente non plus : $body")
        assertTrue(!body.contains("sourceType"), "une provenance `manual` non plus : $body")
    }

    /**
     * Une descente sans provenance devient `server` et non `manual`.
     *
     * `manual` affirmerait une saisie à la main que personne n'a faite ; `server` est la seule
     * chose que ce build sache d'un payload muet sur sa provenance. `sourceScaleId` est annulé
     * parce que le fil ne peut rien en dire (PRD_SCALE 16.2, 22) et que BR-SCALE-010 rend déjà
     * cette colonne annulable.
     */
    @Test
    fun `une mesure descendue sans provenance est marquée server`() {
        val bare = SyncWire.measurementEntity(
            MeasurementPayloadV1Dto(date = "2026-08-27", weightCg = 7_845),
        )

        assertEquals(MeasurementSource.SERVER.wireValue, bare.sourceType)
        assertNull(bare.sourceScaleId)
        assertNull(bare.impedanceOhm)
        assertNull(SyncWire.bodyCompositionEntity(MeasurementPayloadV1Dto("2026-08-27", 7_845)))
    }

    /**
     * BR-SCALE-015 tenue par reprise et non par vérification.
     *
     * Un DTO écrit à la main ne porte pas les `refine` du schéma Zod, donc rien n'empêcherait un
     * corps de dire `inputWeightCg: 7840` sous un `weightCg: 7845`. Reprendre les deux champs du
     * parent rend l'écart impossible à écrire ici — la seule forme qu'une règle de ce genre puisse
     * prendre, puisqu'aucune requête ne la vérifierait après coup.
     */
    @Test
    fun `la composition descendue reprend la date et le poids de sa mesure parente`() {
        val composition = assertNotNull(
            SyncWire.bodyCompositionEntity(
                MeasurementPayloadV1Dto(
                    date = "2026-08-27",
                    weightCg = 7_845,
                    sourceType = MeasurementSource.SCALE.wireValue,
                    impedanceOhm = 520,
                    bodyComposition = BodyCompositionV1Dto(
                        formulaId = "mue-foot-to-foot-v1",
                        formulaVersion = 1,
                        // Contredit délibérément le parent : c'est la valeur qui ne doit pas survivre.
                        inputWeightCg = 7_840,
                        inputHeightCm = 171,
                        inputAgeYears = 27,
                        inputSex = Sex.MALE.wireValue,
                        bodyFatDeciPercent = 290,
                        fatFreeMassCg = 5_567,
                        bodyWaterDeciPercent = 519,
                        restingEnergyKcal = 1_723,
                    ),
                ),
            ),
        )

        assertEquals("2026-08-27", composition.date)
        assertEquals(7_845, composition.inputWeightCg)
        assertEquals(5_567, composition.fatFreeMassCg)
    }

    private companion object {
        const val MUTATION_ID = "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6"
    }
}
