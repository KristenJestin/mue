package fr.kristenjestin.mue.data.scale.protocol

import fr.kristenjestin.mue.domain.model.ScaleDriver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Le registre des pilotes (PRD_SCALE 9.2, 15).
 *
 * Ces tests portent moins sur le code du registre — quinze lignes — que sur la propriété que ce
 * code doit garantir : **ajouter un pilote suffit à le rendre découvrable**, et le pilote fictif
 * n'existe que dans une variante déboguable.
 */
class MueScaleDriversTest {

    @Test
    fun `le registre reconnaît HB BODY FAT`() {
        val driver = MueScaleDrivers.recognise(REAL_HB9027_ADVERTISEMENT)

        assertSame(Hb9027Driver, driver)
    }

    @Test
    fun `le registre ignore un appareil quelconque`() {
        assertNull(MueScaleDrivers.recognise(UNRELATED_ADVERTISEMENT))
        assertNull(MueScaleDrivers.recognise(advertisementNamed(null)))
        assertNull(MueScaleDrivers.recognise(advertisementNamed("")))
    }

    @Test
    fun `un identifiant de pilote se relit, un identifiant retiré rend null`() {
        assertSame(Hb9027Driver, MueScaleDrivers.byId("hb9027"))
        assertNull(
            MueScaleDrivers.byId("un-pilote-retire-par-une-version-ulterieure"),
            "une balance appairée par une version antérieure doit se lire, pas planter",
        )
    }

    /**
     * Le pilote fictif est **inerte hors build déboguable** : le registre utilisé tel quel, celui
     * que l'application obtient sans rien préciser, ne le contient pas et ne peut donc reconnaître
     * aucun appareil qui s'annoncerait sous son nom.
     */
    @Test
    fun `le pilote fictif est absent du registre par défaut`() {
        assertFalse(MueScaleDrivers.drivers.any { it.id == FakeScaleDriver.id })
        assertNull(MueScaleDrivers.recognise(advertisementNamed("MUE FAKE SCALE")))
        assertNull(MueScaleDrivers.byId(FakeScaleDriver.id))
    }

    @Test
    fun `une build de production n'expose que les pilotes matériels`() {
        val registry = MueScaleDrivers.forBuild(debuggable = false)

        assertEquals(listOf<ScaleDriver>(Hb9027Driver), registry.drivers)
    }

    /**
     * PRD_SCALE 23 : « ajouter un pilote fictif au registre le rend découvrable sans modifier un
     * seul écran ». C'est exactement ce que ce test exécute — l'ajout au registre suffit.
     */
    @Test
    fun `une build déboguable rend le pilote fictif découvrable`() {
        val registry = MueScaleDrivers.forBuild(debuggable = true)

        assertSame(FakeScaleDriver, registry.recognise(advertisementNamed("MUE FAKE SCALE")))
        assertSame(FakeScaleDriver, registry.byId(FakeScaleDriver.id))
        assertSame(
            Hb9027Driver,
            registry.recognise(REAL_HB9027_ADVERTISEMENT),
            "ajouter un pilote ne modifie aucun pilote existant (FR-SCALE-030)",
        )
    }

    /**
     * FR-SCALE-030 : « un pilote qui ne fournit pas l'impédance déclare simplement ne pas en
     * fournir ». Le pilote correspondant n'est enregistré qu'ici : sa seule raison d'être est de
     * prouver que le registre le traite exactement comme les autres, sans qu'une ligne de plus
     * n'ait été écrite nulle part.
     */
    @Test
    fun `un pilote sans impédance s'enregistre comme les autres`() {
        val registry = ScaleDriverList(MueScaleDrivers.drivers + FakeWeightOnlyScaleDriver)

        val recognised = registry.recognise(advertisementNamed("MUE FAKE SCALE LITE"))

        assertSame(FakeWeightOnlyScaleDriver, recognised)
        assertTrue(FakeWeightOnlyScaleDriver.capabilities.providesWeight)
        assertFalse(FakeWeightOnlyScaleDriver.capabilities.providesImpedance)
    }

    /** « Le premier qui répond oui » : le registre n'arbitre rien d'autre que l'ordre. */
    @Test
    fun `la reconnaissance rend le premier pilote qui répond oui`() {
        val registry = ScaleDriverList(listOf(FakeScaleDriver, FakeWeightOnlyScaleDriver))

        assertSame(FakeScaleDriver, registry.recognise(advertisementNamed("MUE FAKE SCALE")))
        assertSame(
            FakeWeightOnlyScaleDriver,
            registry.recognise(advertisementNamed("MUE FAKE SCALE LITE")),
        )
    }

    @Test
    fun `deux pilotes ne partagent jamais un identifiant`() {
        val all = MueScaleDrivers.forBuild(debuggable = true).drivers + FakeWeightOnlyScaleDriver

        assertEquals(all.size, all.map { it.id }.toSet().size, "identifiants : ${all.map { it.id }}")
    }
}
