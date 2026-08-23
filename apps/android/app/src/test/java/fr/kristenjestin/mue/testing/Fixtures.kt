package fr.kristenjestin.mue.testing

import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate

fun measurementOf(isoDate: String, kilograms: Double): Measurement = Measurement(
    date = LocalDate.parse(isoDate),
    weight = requireNotNull(Weight.ofKilogramsOrNull(kilograms)) { "$kilograms kg is out of range" },
)
