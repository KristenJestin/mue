package fr.kristenjestin.mue.data.local.database

import android.content.res.AssetManager
import fr.kristenjestin.mue.domain.model.CiqualCatalogue
import fr.kristenjestin.mue.domain.model.Food
import java.io.IOException

/**
 * Reads the embedded Ciqual subset out of `assets/ciqual/catalogue-<version>.json` (PRD_FOOD
 * 9.1, 20.2), the file `packages/ciqual` generates.
 *
 * **The version is discovered from the file name, never from the file.** That is what lets the
 * seeding guard run on every cold start for the price of a directory listing: knowing whether
 * there is work to do must not mean parsing three hundred kilobytes of JSON, any more than it
 * must mean opening Room. It is also why regenerating the subset under a new version is a file
 * drop and not a code change — no constant here names a version. If more than one catalogue is
 * present the greatest name wins, so a newer one supersedes an older without anyone having to
 * remember to delete it first.
 *
 * Parsing is [CiqualCatalogue.fromJsonOrNull] and nothing else, and every range is checked by
 * `CiqualEntry.toFoodOrNull`. This object adds exactly one rule of its own, [foodsOf]'s.
 */
internal object CiqualCatalogueAsset {

    const val DIRECTORY = "ciqual"
    private const val PREFIX = "catalogue-"
    private const val SUFFIX = ".json"

    /**
     * The version of the catalogue shipped in this APK, or null if none is. A directory listing:
     * no file is opened and no JSON is parsed.
     */
    fun availableVersion(assets: AssetManager): String? = try {
        assets.list(DIRECTORY)
            .orEmpty()
            .filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            .maxOrNull()
            ?.removePrefix(PREFIX)
            ?.removeSuffix(SUFFIX)
            ?.takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }

    fun pathOf(version: String): String = "$DIRECTORY/$PREFIX$version$SUFFIX"

    fun readOrNull(assets: AssetManager, version: String): String? = try {
        assets.open(pathOf(version)).use { it.readBytes().decodeToString() }
    } catch (_: IOException) {
        null
    }

    /** The identifier the asset gives each entry, keyed by its Ciqual code. */
    fun idsByCode(raw: String): Map<String, String> =
        CiqualCatalogue.fromJsonOrNull(raw)
            ?.entries
            ?.mapNotNull { entry -> entry.id?.takeIf { it.isNotBlank() }?.let { entry.code to it } }
            ?.toMap()
            .orEmpty()

    /**
     * The foods of one catalogue, in asset order, minus every entry the frozen validator rejects
     * — and minus every entry that carries no identifier of its own.
     *
     * **The identifiers come from the asset; none is minted here.** `packages/ciqual` writes a
     * name-based UUID of `"ciqual:$code"` into each entry precisely so that the same food has the
     * same key on every install: a journal line's `sourceRef` and a recipe ingredient's `food_id`
     * are synchronised, so an id rolled on the device would make the same apple a different food
     * on every phone. `CiqualEntry.toFoodOrNull` falls back to `FoodId.random()` when the asset
     * gives none, which is right for a hand-written fixture and wrong for a shipped catalogue —
     * so the entry is skipped instead of being handed a random key that would then be
     * synchronised and could never be reconciled.
     *
     * A malformed row is dropped and the rest of the subset still installs: losing one food out
     * of a thousand must not leave a phone with no catalogue at all.
     */
    fun foodsOf(raw: String): List<Food> {
        val catalogue = CiqualCatalogue.fromJsonOrNull(raw) ?: return emptyList()
        return catalogue.entries
            .filter { !it.id.isNullOrBlank() }
            .mapNotNull { it.toFoodOrNull(sourceVersion = catalogue.version) }
    }

    /** The version the catalogue declares inside itself, which is what each food carries. */
    fun versionOf(raw: String): String? = CiqualCatalogue.fromJsonOrNull(raw)?.version
}
