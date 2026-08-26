package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.domain.logic.BodyCompositionResult
import fr.kristenjestin.mue.ui.progress.BodyCompositionMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words PRD_SCALE writes out in English, locked character for character.
 *
 * These are not style choices a copywriting pass may revisit: each one is quoted by a numbered
 * requirement, several of them inside a table of states, and a screen that renders a
 * paraphrase of one no longer implements the requirement that names it. The middle dot in the
 * three status lines is part of the quotation and is asserted as `·`, so a build that
 * mangles the file's encoding fails here rather than on a device.
 *
 * The remaining constants are authored rather than quoted, so only their existence is checked —
 * an empty one would silently render a blank card.
 */
class ScaleMessagesTest {

    @Test
    fun `les libelles du flux d'appairage sont ceux du PRD`() {
        assertEquals("Scales", ScaleMessages.SCALES)
        assertEquals("Add a scale", ScaleMessages.ADD_A_SCALE)
        assertEquals("Forget this scale", ScaleMessages.FORGET_THIS_SCALE)
        assertEquals("Never connected", ScaleMessages.NEVER_CONNECTED)
    }

    @Test
    fun `les etats de mesure sont ceux du PRD`() {
        assertEquals("Connecting", ScaleMessages.CONNECTING)
        assertEquals("Step on the scale", ScaleMessages.STEP_ON_THE_SCALE)
        assertEquals("Measuring", ScaleMessages.MEASURING)
        assertEquals(
            "This measurement is outside the range Mue records",
            ScaleMessages.MEASUREMENT_OUT_OF_RANGE,
        )
    }

    /**
     * PRD_SCALE 18.5 spells these three with a middle dot, `·`, and not with a hyphen, a
     * bullet or an interpunct look-alike. Written out as an escape here so the expected value
     * cannot be corrupted by the same accident as the value under test.
     */
    @Test
    fun `les trois lignes d'etat actionnables portent le point median exact`() {
        assertEquals("Scale not found · Try again", ScaleMessages.SCALE_NOT_FOUND)
        assertEquals("Bluetooth is off · Enable", ScaleMessages.BLUETOOTH_IS_OFF)
        assertEquals("Scale unavailable · Open settings", ScaleMessages.SCALE_UNAVAILABLE)
    }

    @Test
    fun `les quatre grandeurs de composition portent les libelles du PRD`() {
        assertEquals("Body fat", ScaleMessages.BODY_FAT)
        assertEquals("Fat-free mass", ScaleMessages.FAT_FREE_MASS)
        assertEquals("Body water", ScaleMessages.BODY_WATER)
        assertEquals("Resting energy", ScaleMessages.RESTING_ENERGY)
    }

    @Test
    fun `l'absence d'estimation est expliquee dans les termes du PRD`() {
        assertEquals(
            "Body composition estimates are not available for this profile",
            ScaleMessages.ESTIMATES_UNAVAILABLE,
        )
    }

    @Test
    fun `les deux valeurs du champ sexe sont celles du PRD`() {
        assertEquals("Female", ScaleMessages.FEMALE)
        assertEquals("Male", ScaleMessages.MALE)
    }

    /**
     * Every string of the object, reached by reflection rather than by a list this test would
     * have to be told to update — a constant added and left empty is exactly the kind of
     * omission a hand-written list hides.
     */
    @Test
    fun `aucune constante n'est vide`() {
        val strings = ScaleMessages::class.java.declaredFields
            .filter { it.type == String::class.java }

        assertTrue("aucune constante n'a été trouvée", strings.size > 30)

        strings.forEach { field ->
            field.isAccessible = true
            val value = field.get(ScaleMessages) as String?
            assertFalse("`${field.name}` est vide", value.isNullOrBlank())
        }
    }

    /** The two counted lines of FR-SCALE-010 and PRD_SCALE 18.4 read as sentences at one. */
    @Test
    fun `les libelles comptes s'accordent au singulier`() {
        assertEquals("1 scale paired", ScaleMessages.scalesPaired(1))
        assertEquals("2 scales paired", ScaleMessages.scalesPaired(2))
        assertEquals("1 past weigh-in can be completed", ScaleMessages.pastWeighInsToComplete(1))
        assertEquals("3 past weigh-ins can be completed", ScaleMessages.pastWeighInsToComplete(3))
    }

    /** PRD_SCALE 20: the announcement carries the value the screen is showing, unaltered. */
    @Test
    fun `l'annonce d'une mesure recue porte sa valeur`() {
        assertEquals(
            "74.3 kg received from your scale",
            ScaleMessages.measurementReceived("74.3 kg"),
        )
    }

    /**
     * The sentence of PRD_SCALE 18.4 exists **once**.
     *
     * [ScaleMessages.PROFILE_INCOMPLETE_BODY] is the three-input form of
     * [ScaleMessages.profileIncompleteBody] and not a second literal, so this asserts an identity
     * rather than a coincidence: were the wording ever forked in two, the specific sentence and
     * the general one would drift apart on the same screen.
     */
    @Test
    fun `la phrase du profil incomplet n'existe qu'une fois`() {
        assertEquals(
            ScaleMessages.PROFILE_INCOMPLETE_BODY,
            ScaleMessages.profileIncompleteBody(
                BodyCompositionResult.ProfileInput.entries.toSet(),
            ),
        )
        assertEquals(
            "Estimates need your height, your date of birth and your sex. Mue kept the impedance " +
                "it already measured, so past weigh-ins can be completed too.",
            ScaleMessages.PROFILE_INCOMPLETE_BODY,
        )
    }

    /**
     * PRD_SCALE 18.4 names only what is actually missing, and the list reads as a sentence at one,
     * two and three items — no comma before the `and`, as everywhere else in Mue.
     */
    @Test
    fun `seules les entrees manquantes sont nommees`() {
        assertEquals(
            "Estimates need your sex. " +
                "Mue kept the impedance it already measured, so past weigh-ins can be " +
                "completed too.",
            ScaleMessages.profileIncompleteBody(setOf(BodyCompositionResult.ProfileInput.SEX)),
        )
        assertTrue(
            ScaleMessages.profileIncompleteBody(
                setOf(
                    BodyCompositionResult.ProfileInput.HEIGHT,
                    BodyCompositionResult.ProfileInput.SEX,
                ),
            ).startsWith("Estimates need your height and your sex."),
        )
    }

    /**
     * The lines a scan builds around a name (FR-SCALE-001, FR-SCALE-011). Each one has to read as
     * a sentence with the name inside it, not as a label with a value glued on.
     */
    @Test
    fun `les lignes du scan portent le nom de la balance`() {
        assertEquals(
            "Already paired as Bathroom scale",
            ScaleMessages.alreadyPairedAs("Bathroom scale"),
        )
        assertEquals("Might be Bathroom scale", ScaleMessages.mightBe("Bathroom scale"))
        assertTrue(
            ScaleMessages.reattachBody("Bathroom scale")
                .startsWith("Mue knows a scale called Bathroom scale that is no longer answering"),
        )
    }

    /**
     * FR-BODY-005: a change and a value are meaningless without the date they belong to, so both
     * carry it and neither says it twice.
     */
    @Test
    fun `l'ecart et la valeur portent leur date`() {
        assertEquals("Change since Aug 20, 2026", ScaleMessages.changeSince("Aug 20, 2026"))
        assertEquals("Measured on Aug 20, 2026", ScaleMessages.measuredOn("Aug 20, 2026"))
    }

    /**
     * PRD_SCALE 20: the four cards are announced with their unit spelled out, the label lowercased
     * and the date left alone — `Aug 20, 2026` lowercased reads as one word to a synthesiser.
     */
    @Test
    fun `l'annonce d'une carte epelle son unite et garde sa date`() {
        assertEquals(
            "Body fat estimate 22.4 percent, measured on Aug 20, 2026",
            ScaleMessages.valueDescription(
                BodyCompositionMetric.BODY_FAT,
                value = "22.4",
                date = "Aug 20, 2026",
            ),
        )
        assertEquals(
            "Resting energy estimate unavailable for this period",
            ScaleMessages.valueUnavailableDescription(BodyCompositionMetric.RESTING_ENERGY),
        )
        assertEquals(
            "Change since Aug 20, 2026, +0.3 kilograms",
            ScaleMessages.changeDescription(
                BodyCompositionMetric.FAT_FREE_MASS,
                change = "+0.3",
                previousDate = "Aug 20, 2026",
            ),
        )
    }
}
