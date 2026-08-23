package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate

/**
 * One row per calendar day (PRD 11.1, 20.3).
 *
 * The date is the primary key, so "one measurement per date" (PRD BR-001) is a
 * SQLite constraint rather than a UI convention, as PRD 16.3 demands. Storing it as
 * ISO text also makes lexicographic order equal chronological order, so every query
 * sorts on the key itself.
 *
 * The weight is an integer count of hundredths of a kilogram: no float ever touches the
 * database, so no rounding can drift. The column carries its unit in its name, so a row read
 * by any tool is unambiguous — and the change of unit in version 2 could not be silent.
 */
@Entity(tableName = MeasurementEntity.TABLE_NAME)
data class MeasurementEntity(
    @PrimaryKey
    @ColumnInfo(name = "date")
    val date: String,

    @ColumnInfo(name = MeasurementEntity.WEIGHT_COLUMN)
    val weightCg: Int,
) {
    companion object {
        const val TABLE_NAME = "measurements"
        const val WEIGHT_COLUMN = "weight_cg"
    }
}

fun MeasurementEntity.toDomain(): Measurement = Measurement(
    date = LocalDate.parse(date),
    weight = Weight.ofHundredthsClamped(weightCg),
)

fun Measurement.toEntity(): MeasurementEntity = MeasurementEntity(
    date = date.toString(),
    weightCg = weight.hundredthsKg,
)
