package fr.kristenjestin.mue.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.CookedRatio
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.repository.FoodDeletion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomFoodCatalogueRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var preferencesFile: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: RoomFoodCatalogueRepository

    @Before
    fun createRepository() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MueDatabase::class.java).build()
        preferencesFile = File(context.cacheDir, "food_cat_${System.nanoTime()}.preferences_pb")
        store = PreferenceDataStoreFactory.create { preferencesFile }
        repository = RoomFoodCatalogueRepository(
            database = database,
            dao = database.foodDao(),
            catalogueDataStore = store,
            outbox = SyncOutbox(),
        )
    }

    @After
    fun closeDatabase() {
        database.close()
        preferencesFile.delete()
    }

    private fun food(
        id: String = "food-1",
        name: String = "Huile d'olive",
        source: FoodSource = FoodSource.CUSTOM,
        per100: Nutrients = Nutrients.UNKNOWN,
    ): Food = Food(id = FoodId(id), name = name, source = source, per100 = per100)

    @Test
    fun roundTripsAFoodThroughTheRealDaoWithoutLosingAField() = runTest {
        val original = Food(
            id = FoodId("food-full"),
            name = "Yaourt nature",
            source = FoodSource.OPEN_FOOD_FACTS,
            referenceUnit = ReferenceUnit.MILLILITRE,
            per100 = Nutrients(
                energy = Energy.ofMilliKcalOrNull(62_700),
                protein = Macro.ofMilligramsOrNull(3_800),
            ),
            brand = "Marque",
            barcode = "3245390110019",
            sourceId = "3245390110019",
            sourceVersion = "v3.6",
            servingLabel = "pot",
            servingSize = Quantity.ofThousandthsOrNull(125_000),
            cookedRatio = CookedRatio.ofThousandthsOrNull(720),
            rawLabel = "Cru",
            cookedLabel = "Cuit",
            imageRef = "images/yaourt.webp",
        )

        assertTrue(repository.save(original))

        assertEquals(original, repository.findById(FoodId("food-full")))
        assertEquals(original, repository.observeById(FoodId("food-full")).first())
        assertEquals(original, repository.findByBarcode("3245390110019"))
        assertEquals(
            original,
            repository.findBySourceId(FoodSource.OPEN_FOOD_FACTS, "3245390110019"),
        )
    }

    /**
     * The proof PRD_FOOD 9.2 asks for, through SQLite rather than through a mapper: an
     * incomplete product sheet keeps its holes, and a measured zero keeps its zero.
     */
    @Test
    fun anUnknownProteinComesBackNullAndNotZero() = runTest {
        repository.save(
            food(
                per100 = Nutrients(
                    energy = Energy.ofMilliKcalOrNull(899_000),
                    protein = Macro.ofMilligramsOrNull(0),
                ),
            ),
        )

        val read = requireNotNull(repository.findById(FoodId("food-1")))
        assertEquals(Macro.ZERO, read.per100.protein)
        assertNull(read.per100.carbs)
        assertNull(read.per100.fat)
        assertNull(read.per100.fibre)
    }

    /** And the column itself is `NULL`, not `0` — read straight out of SQLite. */
    @Test
    fun anUnknownProteinIsStoredAsNullInTheColumn() = runTest {
        repository.save(food(per100 = Nutrients(energy = Energy.ofMilliKcalOrNull(899_000))))

        database.openHelper.readableDatabase
            .query("SELECT protein_milligrams, energy_milli_kcal FROM food WHERE id = 'food-1'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("protein must be NULL, not 0", cursor.isNull(0))
                assertEquals(899_000, cursor.getInt(1))
            }
    }

    @Test
    fun aStoredZeroStaysZeroInTheColumn() = runTest {
        repository.save(food(per100 = Nutrients(protein = Macro.ofMilligramsOrNull(0))))

        database.openHelper.readableDatabase
            .query("SELECT protein_milligrams FROM food WHERE id = 'food-1'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse("a measured zero must not become NULL", cursor.isNull(0))
                assertEquals(0, cursor.getInt(0))
            }
    }

    @Test
    fun savingAFoodJournalsAnUpsertMutation() = runTest {
        repository.save(food())

        val pending = database.syncDao().pendingMutations(10)
        assertEquals(1, pending.size)
        assertEquals(FoodAggregates.TYPE_FOOD, pending.single().aggregateType)
        assertEquals("food-1", pending.single().aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, pending.single().op)
        assertNotNull(pending.single().payload)
    }

    @Test
    fun deletingAFoodLeavesATombstoneBehindIt() = runTest {
        repository.save(food())

        assertEquals(FoodDeletion.Deleted, repository.delete(FoodId("food-1")))

        assertNull(repository.findById(FoodId("food-1")))
        val tombstones = database.syncDao().tombstones(FoodAggregates.TYPE_FOOD)
        assertEquals(listOf("food-1"), tombstones.map { it.aggregateId })
        assertNotNull(tombstones.single().deletedAt)
    }

    @Test
    fun deletingAFoodThatIsNotThereChangesNothing() = runTest {
        assertEquals(FoodDeletion.NotFound, repository.delete(FoodId("absent")))

        assertEquals(0, database.syncDao().pendingMutations(10).size)
        assertEquals(emptyList<String>(), database.syncDao()
            .tombstones(FoodAggregates.TYPE_FOOD).map { it.aggregateId })
    }

    /** PRD_FOOD 9.1: a Ciqual entry is neither modifiable nor deletable. */
    @Test
    fun aCiqualEntryCannotBeSaved() = runTest {
        assertFalse(repository.save(food(source = FoodSource.CIQUAL)))

        assertNull(repository.findById(FoodId("food-1")))
        assertEquals(0, database.syncDao().pendingMutations(10).size)
    }

    @Test
    fun aCiqualEntryCannotBeDeleted() = runTest {
        repository.seedCiqual(listOf(food(source = FoodSource.CIQUAL)), "v1")

        assertEquals(FoodDeletion.ReadOnly, repository.delete(FoodId("food-1")))
        assertNotNull(repository.findById(FoodId("food-1")))
    }

    @Test
    fun aCiqualEntryCannotBeOverwrittenByACustomFoodOfTheSameId() = runTest {
        repository.seedCiqual(listOf(food(source = FoodSource.CIQUAL, name = "Pomme")), "v1")

        assertFalse(repository.save(food(source = FoodSource.CUSTOM, name = "Ma pomme")))
        assertEquals("Pomme", requireNotNull(repository.findById(FoodId("food-1"))).name)
    }

    /** PRD_FOOD 9.3: the recipes are named so the caller can say which ones to edit first. */
    @Test
    fun aFoodUsedByARecipeCannotBeDeletedAndTheRecipesAreNamed() = runTest {
        repository.save(food())
        saveRecipeUsing("food-1", "Dahl de lentilles")

        val outcome = repository.delete(FoodId("food-1"))

        assertEquals(FoodDeletion.UsedByRecipes(listOf("Dahl de lentilles")), outcome)
        assertNotNull(repository.findById(FoodId("food-1")))
        assertEquals(listOf("Dahl de lentilles"), repository.recipeNamesUsing(FoodId("food-1")))
    }

    @Test
    fun aRefusedDeleteJournalsNothing() = runTest {
        repository.save(food())
        saveRecipeUsing("food-1", "Dahl de lentilles")
        val before = database.syncDao().pendingMutations(50).size

        repository.delete(FoodId("food-1"))

        assertEquals(before, database.syncDao().pendingMutations(50).size)
        assertEquals(0, database.syncDao().tombstones(FoodAggregates.TYPE_FOOD).size)
    }

    /** PRD_FOOD 21.1: the embedded catalogue is reference data and is never synchronised. */
    @Test
    fun seedingTheCiqualCatalogueJournalsNothing() = runTest {
        repository.seedCiqual(
            listOf(
                food(id = "c1", name = "Pomme", source = FoodSource.CIQUAL),
                food(id = "c2", name = "Banane", source = FoodSource.CIQUAL),
            ),
            "v1",
        )

        assertEquals(0, database.syncDao().pendingMutations(10).size)
        assertEquals(2, database.foodDao().countBySource(FoodSource.CIQUAL.id))
    }

    @Test
    fun seedingRecordsTheInstalledVersionOutsideRoom() = runTest {
        assertNull(repository.installedCiqualVersion())

        repository.seedCiqual(listOf(food(source = FoodSource.CIQUAL)), "2025.1")

        assertEquals("2025.1", repository.installedCiqualVersion())
    }

    @Test
    fun seedingTwiceKeepsOneRowPerFood() = runTest {
        repository.seedCiqual(listOf(food(source = FoodSource.CIQUAL, name = "Pomme")), "v1")
        repository.seedCiqual(listOf(food(source = FoodSource.CIQUAL, name = "Pomme crue")), "v2")

        assertEquals(1, database.foodDao().countBySource(FoodSource.CIQUAL.id))
        assertEquals("Pomme crue", requireNotNull(repository.findById(FoodId("food-1"))).name)
    }

    /** PRD_FOOD 20.2: an update never modifies a custom food. */
    @Test
    fun seedingLeavesCustomFoodsAlone() = runTest {
        repository.save(food(id = "mine", name = "Mon granola"))

        repository.seedCiqual(listOf(food(id = "c1", source = FoodSource.CIQUAL)), "v1")

        assertEquals("Mon granola", requireNotNull(repository.findById(FoodId("mine"))).name)
    }

    @Test
    fun seedingRefusesToWriteAnythingThatIsNotACiqualEntry() = runTest {
        repository.seedCiqual(listOf(food(id = "sneaky", source = FoodSource.CUSTOM)), "v1")

        assertNull(repository.findById(FoodId("sneaky")))
    }

    /**
     * A regenerated subset is the whole subset. PRD_FOOD 9.5 splits the catalogue only where the
     * numbers change, and merging entries would achieve nothing if the ones it replaced survived
     * — read-only, so no user could ever remove them, and still answering the search.
     */
    @Test
    fun aSupersededCatalogueDropsTheEntriesItNoLongerNames() = runTest {
        repository.seedCiqual(
            listOf(
                food(id = "keep", name = "Pomme", source = FoodSource.CIQUAL),
                food(id = "gone", name = "Pomme Golden", source = FoodSource.CIQUAL),
            ),
            "v1",
        )

        repository.seedCiqual(
            listOf(food(id = "keep", name = "Pomme", source = FoodSource.CIQUAL)),
            "v2",
        )

        assertEquals(1, database.foodDao().countBySource(FoodSource.CIQUAL.id))
        assertNull(repository.findById(FoodId("gone")))
        assertNotNull(repository.findById(FoodId("keep")))
        assertEquals("v2", repository.installedCiqualVersion())
    }

    /**
     * The replacement may never be handed an empty subset: it would clear the catalogue and put
     * nothing in its place, and record a version the table does not hold.
     */
    @Test
    fun anEmptySeedLeavesTheInstalledCatalogueStanding() = runTest {
        repository.seedCiqual(listOf(food(id = "c1", source = FoodSource.CIQUAL)), "v1")

        repository.seedCiqual(emptyList(), "v2")

        assertEquals(1, database.foodDao().countBySource(FoodSource.CIQUAL.id))
        assertEquals("v1", repository.installedCiqualVersion())
    }

    /** PRD_FOOD 20.2: replacing the subset never reaches a custom food or a copied product. */
    @Test
    fun replacingTheSubsetSparesEveryFoodTheUserOwns() = runTest {
        repository.save(food(id = "mine", name = "Mon granola"))
        repository.save(
            Food(id = FoodId("scanned"), name = "Skyr", source = FoodSource.OPEN_FOOD_FACTS),
        )
        repository.seedCiqual(listOf(food(id = "c1", source = FoodSource.CIQUAL)), "v1")

        repository.seedCiqual(listOf(food(id = "c2", source = FoodSource.CIQUAL)), "v2")

        assertNotNull(repository.findById(FoodId("mine")))
        assertNotNull(repository.findById(FoodId("scanned")))
        assertNull(repository.findById(FoodId("c1")))
        assertNotNull(repository.findById(FoodId("c2")))
    }

    @Test
    fun searchIsInsensitiveToCaseAndAccentsAndFindsBrandsToo() = runTest {
        repository.save(food(id = "a", name = "Crème fraîche"))
        repository.save(food(id = "b", name = "Yaourt"))
        repository.save(
            Food(id = FoodId("c"), name = "Biscuit", source = FoodSource.CUSTOM, brand = "Créme"),
        )

        assertEquals(listOf("a", "c"), repository.search("CREME", null, 10).first().map { it.id.value })
    }

    @Test
    fun searchCanBeRestrictedToOneSource() = runTest {
        repository.save(food(id = "a", name = "Pomme de terre"))
        repository.seedCiqual(listOf(food(id = "b", name = "Pomme", source = FoodSource.CIQUAL)), "v1")

        val ciqual = repository.search("pomme", FoodSource.CIQUAL, 10).first()

        assertEquals(listOf("b"), ciqual.map { it.id.value })
        assertEquals(2, repository.search("pomme", null, 10).first().size)
    }

    /** A user typing `100%` searches for `100%`, not for everything. */
    @Test
    fun searchTreatsTheWildcardCharactersAsText() = runTest {
        repository.save(food(id = "a", name = "Chocolat 100% cacao"))
        repository.save(food(id = "b", name = "Chocolat au lait"))

        assertEquals(listOf("a"), repository.search("100%", null, 10).first().map { it.id.value })
        assertEquals(listOf("a"), repository.search("%", null, 10).first().map { it.id.value })
    }

    /**
     * PRD_FOOD 9.4's insensitivity, on the letter NFD refuses to take apart.
     *
     * `œ` is a letter and not an `o` carrying a mark, so `Food.fold` leaves it whole and
     * `Bœuf sauté` is stored as `bœuf saute` — which `boeuf` used to miss entirely. The
     * equivalence is carried by the **query**, in a second `LIKE`, precisely so that no stored
     * `name_folded` has to change and no migration is needed on a schema pinned at version 6.
     *
     * Both directions, and both columns: a row written with the ligature is found without one,
     * and a row written without it is found with one.
     */
    @Test
    fun searchFindsALigatureByEitherOfItsSpellings() = runTest {
        repository.save(food(id = "a", name = "Bœuf sauté"))
        repository.save(food(id = "b", name = "Boeuf bourguignon"))
        repository.save(food(id = "c", name = "Poulet rôti"))

        assertEquals(
            listOf("b", "a"),
            repository.search("boeuf", null, 10).first().map { it.id.value },
        )
        assertEquals(
            listOf("b", "a"),
            repository.search("bœuf", null, 10).first().map { it.id.value },
        )
        // And it widens nothing else: a term with no ligature in it still matches only itself.
        assertEquals(listOf("c"), repository.search("poulet", null, 10).first().map { it.id.value })
    }

    @Test
    fun searchPutsAPrefixMatchFirst() = runTest {
        repository.save(food(id = "a", name = "Yaourt grec"))
        repository.save(food(id = "b", name = "Grec, yaourt de brebis"))

        assertEquals(listOf("b", "a"), repository.search("grec", null, 10).first().map { it.id.value })
    }

    @Test
    fun searchHonoursItsLimit() = runTest {
        repeat(5) { index -> repository.save(food(id = "f$index", name = "Pain $index")) }

        assertEquals(2, repository.search("pain", null, 2).first().size)
    }

    @Test
    fun findingManyFoodsAtOnceIsEmptyForAnEmptyRequest() = runTest {
        repository.save(food(id = "a"))
        repository.save(food(id = "b"))

        assertEquals(emptyList<Food>(), repository.findByIds(emptyList()))
        assertEquals(2, repository.findByIds(listOf(FoodId("a"), FoodId("b"), FoodId("z"))).size)
    }

    @Test
    fun anUnknownBarcodeAndAnUnknownSourceIdFindNothing() = runTest {
        assertNull(repository.findByBarcode("0000000000000"))
        assertNull(repository.findBySourceId(FoodSource.OPEN_FOOD_FACTS, "0000000000000"))
    }

    private suspend fun saveRecipeUsing(foodId: String, name: String) {
        RoomRecipeRepository(database.recipeDao(), SyncOutbox()).save(
            RecipeDetail(
                recipe = Recipe(
                    id = RecipeId("recipe-1"),
                    name = name,
                    type = RecipeType.MAIN,
                    baseServings = 4,
                ),
                ingredients = listOf(
                    RecipeIngredient(
                        id = RecipeIngredientId("ing-1"),
                        foodId = FoodId(foodId),
                        quantity = requireNotNull(Quantity.ofThousandthsOrNull(250_000)),
                        unit = ReferenceUnit.GRAM,
                        position = 0,
                    ),
                ),
            ),
        )
    }
}
