package fr.kristenjestin.mue.data.local.database

import fr.kristenjestin.mue.domain.model.CiqualCatalogue
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The committed asset itself, parsed by the same code the phone runs.
 *
 * It reads the file off disk rather than through an `AssetManager`, so that a malformed
 * catalogue fails here — in seconds, on every `testDebugUnitTest` — instead of on a device, and
 * so that the file being *committed* is part of what is asserted.
 */
private fun assetDirectory(): File {
    var candidate: File? = File(".").absoluteFile
    while (candidate != null) {
        val direct = File(candidate, "src/main/assets/${CiqualCatalogueAsset.DIRECTORY}")
        if (direct.isDirectory) return direct
        val nested = File(candidate, "app/src/main/assets/${CiqualCatalogueAsset.DIRECTORY}")
        if (nested.isDirectory) return nested
        candidate = candidate.parentFile
    }
    error("no assets/${CiqualCatalogueAsset.DIRECTORY} directory found above ${File(".").absoluteFile}")
}

private fun catalogueFiles(): List<File> =
    assetDirectory().listFiles().orEmpty()
        .filter { it.name.startsWith("catalogue-") && it.name.endsWith(".json") }
        .sortedBy { it.name }

private fun shippedRaw(): String = catalogueFiles().last().readText()

class CiqualAssetIsShippedTest {

    @Test
    fun `a catalogue is committed under the assets directory`() {
        assertTrue(catalogueFiles().isNotEmpty(), "no catalogue-*.json in ${assetDirectory()}")
    }

    @Test
    fun `the shipped catalogue parses through the frozen parser`() {
        assertNotNull(CiqualCatalogue.fromJsonOrNull(shippedRaw()))
    }

    /** The name is what the seeding guard compares, so it has to spell the version out. */
    @Test
    fun `the file name carries the version the catalogue declares`() {
        val file = catalogueFiles().last()
        val fromName = file.name.removePrefix("catalogue-").removeSuffix(".json")

        assertEquals(assertNotNull(CiqualCatalogue.fromJsonOrNull(file.readText())).version, fromName)
    }

    @Test
    fun `the shipped catalogue yields foods`() {
        assertTrue(CiqualCatalogueAsset.foodsOf(shippedRaw()).isNotEmpty())
    }

    @Test
    fun `every food the asset yields is a read only Ciqual entry`() {
        CiqualCatalogueAsset.foodsOf(shippedRaw()).forEach { food ->
            assertEquals(FoodSource.CIQUAL, food.source)
            assertTrue(food.isReadOnly)
        }
    }

    @Test
    fun `every food carries the catalogue version in its source version`() {
        val version = assertNotNull(CiqualCatalogueAsset.versionOf(shippedRaw()))

        CiqualCatalogueAsset.foodsOf(shippedRaw()).forEach { food ->
            assertEquals(version, food.sourceVersion)
        }
    }

    /**
     * The identifiers come from the asset. `packages/ciqual` derives them once at build time, so
     * the same food has the same key on every install — a journal line's `sourceRef` and a recipe
     * ingredient's `food_id` are synchronised, and an id minted on the device would make the same
     * apple a different food on every phone.
     */
    @Test
    fun `every food takes the identifier the asset gives it`() {
        val ids = CiqualCatalogueAsset.idsByCode(shippedRaw())
        val foods = CiqualCatalogueAsset.foodsOf(shippedRaw())

        assertEquals(foods.size, ids.size)
        foods.forEach { food ->
            assertEquals(ids[assertNotNull(food.sourceId)], food.id.value)
        }
    }

    @Test
    fun `no two entries share an identifier or a code`() {
        val foods = CiqualCatalogueAsset.foodsOf(shippedRaw())

        assertEquals(foods.size, foods.map { it.id.value }.distinct().size)
        assertEquals(foods.size, foods.mapNotNull { it.sourceId }.distinct().size)
    }

    /** PRD_FOOD 8.6: a millilitre food exists and no density ever converts it into grams. */
    @Test
    fun `the asset can describe a food in millilitres`() {
        val units = CiqualCatalogueAsset.foodsOf(shippedRaw()).map { it.referenceUnit }.toSet()

        assertTrue(units.contains(ReferenceUnit.MILLILITRE), "expected a millilitre food, got $units")
    }

    /** PRD_FOOD 8.6: the ratio is deduced at import, never typed, and the asset carries it. */
    @Test
    fun `the asset can describe a cooked ratio in both directions`() {
        val ratios = CiqualCatalogueAsset.foodsOf(shippedRaw()).mapNotNull { it.cookedRatio }

        assertTrue(ratios.any { it.absorbsWater }, "expected a food that absorbs water")
        assertTrue(ratios.any { !it.absorbsWater }, "expected a food that loses water")
    }

    /** The whole point of the nullable columns, visible in the source data itself. */
    @Test
    fun `the asset can leave a metric unknown while another is a genuine zero`() {
        val foods = CiqualCatalogueAsset.foodsOf(shippedRaw())

        assertTrue(
            foods.any { it.per100.fibre == null },
            "expected at least one food with no reported fibre",
        )
        assertTrue(
            foods.any { it.per100.carbs?.milligrams == 0 },
            "expected at least one food with a measured zero",
        )
    }
}

class CiqualAssetParsingTest {

    private val minimal = """
        {"version":"t1","entries":[
          {"id":"aaaa","code":"1","name":"One","energyMilliKcal":1000},
          {"id":"bbbb","code":"2","name":"Two"}
        ]}
    """.trimIndent()

    @Test
    fun `the ids are read by code`() {
        assertEquals(mapOf("1" to "aaaa", "2" to "bbbb"), CiqualCatalogueAsset.idsByCode(minimal))
    }

    @Test
    fun `an entry with no id in the asset is skipped rather than given one`() {
        val raw = """{"version":"t1","entries":[{"code":"1","name":"One"}]}"""

        assertEquals(emptyMap(), CiqualCatalogueAsset.idsByCode(raw))
        assertEquals(emptyList(), CiqualCatalogueAsset.foodsOf(raw))
    }

    @Test
    fun `an entry with a blank id is skipped too`() {
        val raw = """{"version":"t1","entries":[{"id":"  ","code":"1","name":"One"}]}"""

        assertEquals(emptyList(), CiqualCatalogueAsset.foodsOf(raw))
    }

    @Test
    fun `a non textual id is not taken`() {
        val raw = """{"version":"t1","entries":[{"id":7,"code":"1","name":"One"}]}"""

        assertEquals(emptyMap(), CiqualCatalogueAsset.idsByCode(raw))
    }

    @Test
    fun `an unreadable catalogue yields no ids and no foods rather than throwing`() {
        assertEquals(emptyMap(), CiqualCatalogueAsset.idsByCode("not json"))
        assertEquals(emptyList(), CiqualCatalogueAsset.foodsOf("not json"))
        assertNull(CiqualCatalogueAsset.versionOf("not json"))
    }

    /** One bad row out of five hundred must not leave a phone with no catalogue at all. */
    @Test
    fun `one invalid entry is dropped and the rest still install`() {
        val raw = """
            {"version":"t1","entries":[
              {"id":"aaaa","code":"1","name":"One","energyMilliKcal":1000},
              {"id":"bbbb","code":"2","name":"","energyMilliKcal":1000},
              {"id":"cccc","code":"3","name":"Three","energyMilliKcal":9999999}
            ]}
        """.trimIndent()

        val foods = CiqualCatalogueAsset.foodsOf(raw)

        assertEquals(listOf("One"), foods.map { it.name })
    }

    @Test
    fun `an unknown key in the asset is ignored, so the real one may carry more`() {
        val raw = """
            {"version":"t1","note":"anything","entries":[
              {"id":"aaaa","code":"1","name":"One","group":"fruits"}
            ]}
        """.trimIndent()

        assertEquals(listOf("One"), CiqualCatalogueAsset.foodsOf(raw).map { it.name })
    }

    @Test
    fun `the asset path is built from the version alone`() {
        assertEquals("ciqual/catalogue-2025.1.json", CiqualCatalogueAsset.pathOf("2025.1"))
    }
}
