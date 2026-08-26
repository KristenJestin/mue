package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.CookedRatio
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.ReferenceUnit

/**
 * One row per catalogue entry, whatever its provenance (PRD_FOOD 8.2, 9.1 to 9.3).
 *
 * The three sources share a table rather than splitting into three. PRD_FOOD 9.4 asks a single
 * search bar to reach all of them and to filter by source afterwards, which is a `WHERE` on one
 * table and a three-way `UNION` on three; and a journal line's `sourceRef` has to resolve
 * against exactly one place, not against whichever of three tables happens to hold it.
 *
 * **No synchronisation column lives here**, and that is a decision rather than an omission.
 * PRD_FOOD 20.1 asks each of the five tables to carry the metadata of the sync PRD's section
 * 12.1 from its first migration. That metadata already exists, once, in `sync_aggregate_state`,
 * keyed by `(aggregate_type, aggregate_id)` — and `FoodAggregates` already names the four types
 * this module contributes. Copying revision, tombstone, origin and last-mutation into five more
 * tables would give the same facts five representations to disagree in. The reason 20.1 gives
 * for its own rule — never migrating a populated food journal a second time — is served better
 * by the generic table, which needs no migration at all when a sixth aggregate appears.
 *
 * `name_folded` and `brand_folded` are stored rather than computed per query: PRD_FOOD 9.4 wants
 * a search insensitive to case and accents, `Food.fold` is the one definition of that, and an
 * index cannot cover a function SQLite does not know.
 *
 * Every number is an integer of its canonical unit. [NutrientColumns] carries the five nullable
 * metrics; `serving_thousandths` and `cooked_ratio_thousandths` are the thousandths that
 * `Quantity` and `CookedRatio` already hold. No `REAL` column exists in this schema.
 */
@Entity(
    tableName = FoodEntity.TABLE_NAME,
    indices = [
        Index(value = ["name_folded"]),
        Index(value = ["barcode"]),
        Index(value = ["source", "source_id"]),
    ],
)
data class FoodEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "name_folded")
    val nameFolded: String,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "reference_unit")
    val referenceUnit: String,

    @Embedded
    val per100: NutrientColumns,

    @ColumnInfo(name = "brand")
    val brand: String? = null,

    @ColumnInfo(name = "brand_folded")
    val brandFolded: String? = null,

    @ColumnInfo(name = "barcode")
    val barcode: String? = null,

    @ColumnInfo(name = "source_id")
    val sourceId: String? = null,

    @ColumnInfo(name = "source_version")
    val sourceVersion: String? = null,

    @ColumnInfo(name = "serving_label")
    val servingLabel: String? = null,

    @ColumnInfo(name = "serving_thousandths")
    val servingThousandths: Int? = null,

    @ColumnInfo(name = "cooked_ratio_thousandths")
    val cookedRatioThousandths: Int? = null,

    @ColumnInfo(name = "raw_label")
    val rawLabel: String,

    @ColumnInfo(name = "cooked_label")
    val cookedLabel: String,

    @ColumnInfo(name = "image_ref")
    val imageRef: String? = null,

    /**
     * PRD_FOOD 8.2 asks for both. They are business timestamps, not the sync metadata of 20.1:
     * nothing here is a revision, an origin or a tombstone. They give every list a deterministic
     * final tiebreak, which two foods created in the same second would otherwise lack.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val TABLE_NAME = "food"
    }
}

/**
 * Every optional column reads back through an `…OrNull` factory. A serving size or a cooked
 * ratio outside the range its unit accepts becomes absent rather than clamped: PRD_FOOD 8.6
 * gives both a meaning the user can see, and a silently corrected one would be a value the user
 * never entered. `Food.hasUsualServing` is what decides whether the label and the size together
 * amount to a usable input aid.
 */
fun FoodEntity.toDomain(): Food = Food(
    id = FoodId(id),
    name = name,
    source = FoodSource.fromId(source),
    referenceUnit = ReferenceUnit.fromId(referenceUnit),
    per100 = per100.toDomain(),
    brand = brand,
    barcode = barcode,
    sourceId = sourceId,
    sourceVersion = sourceVersion,
    servingLabel = servingLabel,
    servingSize = servingThousandths?.let { Quantity.ofThousandthsOrNull(it.toLong()) },
    cookedRatio = cookedRatioThousandths?.let { CookedRatio.ofThousandthsOrNull(it.toLong()) },
    rawLabel = rawLabel,
    cookedLabel = cookedLabel,
    imageRef = imageRef,
)

fun Food.toEntity(createdAt: Long, updatedAt: Long): FoodEntity = FoodEntity(
    id = id.value,
    name = name,
    nameFolded = nameFolded,
    source = source.id,
    referenceUnit = referenceUnit.id,
    per100 = per100.toColumns(),
    brand = brand,
    brandFolded = brandFolded,
    barcode = barcode,
    sourceId = sourceId,
    sourceVersion = sourceVersion,
    servingLabel = servingLabel,
    servingThousandths = servingSize?.thousandths,
    cookedRatioThousandths = cookedRatio?.thousandths,
    rawLabel = rawLabel,
    cookedLabel = cookedLabel,
    imageRef = imageRef,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
