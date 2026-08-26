package fr.kristenjestin.mue.data.scale.ble

import fr.kristenjestin.mue.data.scale.protocol.MueScaleDrivers
import fr.kristenjestin.mue.data.scale.protocol.UNRELATED_ADVERTISEMENT
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * L'identité composite de FR-SCALE-001, sans Bluetooth ni Android (PRD_SCALE 21.3).
 *
 * La règle « ne coûte rien tant qu'elle ne sert pas, et évite un mode de panne inexplicable le jour
 * où elle sert » : le jour où les piles sont remplacées, l'adresse statique aléatoire de la balance
 * peut être régénérée et la balance enregistrée devient invisible (PRD_SCALE 10.1). Ces tests sont
 * ce qui garantit que ce jour-là quelque chose sera proposé — et rien de plus que proposé.
 */
class ScaleMatchingTest {

    private val scale = pairedScale(id = "scale-hb", address = "FF:10:00:1F:52:C3")

    @Test
    fun `une balance enregistrée est reconnue par son adresse, sans égard à la casse`() {
        assertEquals(
            scale,
            ScaleMatching.matchByAddress(listOf(scale), advertisementOf(address = "FF:10:00:1F:52:C3")),
        )
        assertEquals(
            scale,
            ScaleMatching.matchByAddress(listOf(scale), advertisementOf(address = "ff:10:00:1f:52:c3")),
        )
        assertNull(
            ScaleMatching.matchByAddress(listOf(scale), advertisementOf(address = "11:22:33:44:55:66")),
        )
    }

    /**
     * FR-SCALE-001 : « lorsque aucune balance enregistrée ne correspond par l'adresse, et qu'un
     * appareil découvert présente le même nom annoncé et le même pilote qu'une balance enregistrée,
     * Mue propose de le rattacher à cette balance. »
     */
    @Test
    fun `une adresse inconnue au même nom et au même pilote produit une proposition`() {
        val moved = advertisementOf(address = "C0:FF:EE:00:11:22", name = "HB BODY FAT")

        val proposal = ScaleMatching.proposeReattachment(listOf(scale), moved, MueScaleDrivers)

        assertNotNull(proposal)
        assertEquals(listOf(scale), proposal.candidates)
        assertEquals("hb9027", proposal.driver.id)
        assertEquals(moved, proposal.advertisement)
    }

    /** Le nom annoncé se compare bord à bord et sans casse, comme le pilote le fait déjà. */
    @Test
    fun `la comparaison du nom annoncé ignore la casse et les espaces de bord`() {
        val moved = advertisementOf(address = "C0:FF:EE:00:11:22", name = "  hb body fat ")

        assertNotNull(ScaleMatching.proposeReattachment(listOf(scale), moved, MueScaleDrivers))
    }

    /** Rien à proposer lorsque l'adresse répond : il n'y a aucun problème à résoudre. */
    @Test
    fun `une adresse qui répond ne déclenche aucune proposition`() {
        assertNull(
            ScaleMatching.proposeReattachment(listOf(scale), advertisementOf(), MueScaleDrivers),
        )
    }

    /** Un appareil qu'aucun pilote ne revendique n'est pas une balance, quel que soit son nom. */
    @Test
    fun `un appareil sans pilote n'est jamais proposé au rattachement`() {
        assertNull(
            ScaleMatching.proposeReattachment(
                listOf(scale),
                UNRELATED_ADVERTISEMENT,
                MueScaleDrivers,
            ),
        )
    }

    /** Un nom annoncé différent, même pilote : ce n'est pas la même balance. */
    @Test
    fun `un nom annoncé différent ne produit aucune proposition`() {
        val renamed = pairedScale(id = "scale-hb", advertisedName = "HB SOMETHING ELSE")
        val moved = advertisementOf(address = "C0:FF:EE:00:11:22", name = "HB BODY FAT")

        assertNull(ScaleMatching.proposeReattachment(listOf(renamed), moved, MueScaleDrivers))
    }

    /**
     * FR-SCALE-001 : « deux balances identiques dans un même foyer ne doivent pas fusionner à
     * l'insu de l'utilisateur. » La couche rend les deux candidates plutôt que d'en choisir une.
     */
    @Test
    fun `deux balances identiques produisent deux candidates et jamais un choix`() {
        val first = pairedScale(id = "scale-a", address = "FF:10:00:1F:52:C3")
        val second = pairedScale(id = "scale-b", address = "FF:10:00:1F:52:C4")
        val moved = advertisementOf(address = "C0:FF:EE:00:11:22", name = "HB BODY FAT")

        val proposal = ScaleMatching.proposeReattachment(listOf(first, second), moved, MueScaleDrivers)

        assertNotNull(proposal)
        assertEquals(listOf("scale-a", "scale-b"), proposal.candidates.map { it.id })
    }

    /** Une annonce anonyme ne peut rien prouver : aucune proposition. */
    @Test
    fun `une annonce sans nom ne produit aucune proposition`() {
        assertNull(
            ScaleMatching.proposeReattachment(
                listOf(scale),
                advertisementOf(address = "C0:FF:EE:00:11:22", name = null),
                MueScaleDrivers,
            ),
        )
    }
}
