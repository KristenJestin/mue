package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.domain.model.ScaleReading
import fr.kristenjestin.mue.domain.model.ScaleSessionState
import fr.kristenjestin.mue.domain.model.ScaleUnavailableReason
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-SCALE-020, verrouillé état par état.
 *
 * « Android maintient l'écran éveillé uniquement pendant cette session de recherche […]. Ce
 * maintien cesse dès qu'un poids stable est reçu, que le délai expire ou qu'`Entry` n'est plus
 * visible. » Les deux premières conditions d'arrêt se lisent dans la liste ci-dessous ; la
 * troisième est le fait de `EntryViewModel.onEntryHidden`, et `EntryScaleTest` la couvre.
 *
 * Les deux listes sont exhaustives sur [ScaleSessionState] à dessein : un état ajouté au contrat
 * de session sans être classé ici est un état dont personne n'aura décidé s'il garde le téléphone
 * allumé, et la panne serait une batterie vide plutôt qu'un test rouge.
 *
 * **Cette exhaustivité est tenue par le compilateur, pas par ce fichier.** `keepsScreenAwake` est un
 * `when` sans `else` : un douzième état ne compile pas tant qu'il n'a pas été rangé d'un côté ou de
 * l'autre. Ce que les deux listes ci-dessous ajoutent, c'est le *choix* fait pour chacun des onze
 * états d'aujourd'hui, et le compte ci-dessous refuse qu'un état disparaisse d'ici sans bruit.
 */
class ScaleScreenAwakeTest {

    @Test
    fun `l'écran ne reste éveillé que pendant la session de recherche`() {
        val awake = listOf(
            ScaleSessionState.Searching,
            ScaleSessionState.Connecting,
            ScaleSessionState.WaitingForStepOn,
            ScaleSessionState.Measuring(7_000),
        )
        val reading = ScaleReading(
            sessionId = "session-1",
            weightHundredthsKg = 8_575,
            isStable = true,
            impedanceOhm = null,
            receivedAt = Instant.parse("2026-08-26T07:00:00Z"),
            scaleId = "scale-hb",
        )
        val asleep = listOf(
            ScaleSessionState.Absent,
            ScaleSessionState.Idle,
            ScaleSessionState.NotFound,
            ScaleSessionState.OutOfRange(2_100),
            ScaleSessionState.Unavailable(ScaleUnavailableReason.BLUETOOTH_OFF),
            // « Ce maintien cesse dès qu'un poids stable est reçu » (FR-SCALE-020).
            ScaleSessionState.Stable(reading),
            ScaleSessionState.Complete(reading, impedanceRefused = false),
        )

        awake.forEach { assertTrue(it.keepsScreenAwake, "$it doit garder l'écran éveillé") }
        asleep.forEach { assertFalse(it.keepsScreenAwake, "$it ne doit pas garder l'écran éveillé") }

        // Les onze états du contrat de session (PRD_SCALE 21.2), chacun cité une fois exactement.
        // Le compilateur oblige `keepsScreenAwake` à tous les classer ; ce compte oblige ce test à
        // tous les *citer*, faute de quoi un état retiré de ces listes cesserait d'être éprouvé
        // sans que rien ne le signale. Le nombre est écrit en clair plutôt que lu par réflexion :
        // le dépôt ne dépend pas de `kotlin-reflect`, et un contrat gelé se recompte à la main.
        assertEquals(
            11,
            (awake + asleep).map { it::class }.distinct().size,
            "chaque état de ScaleSessionState doit être rangé dans exactement une des deux listes",
        )
    }
}
