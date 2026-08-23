package fr.kristenjestin.mue.domain.logic

/**
 * A validated input: either the parsed value, or the exact message PRD 15.3 wants
 * on screen. Invalid input keeps what the user typed, so no value is carried here.
 */
sealed interface Validated<out T> {

    data class Valid<out T>(val value: T) : Validated<T>

    data class Invalid(val message: String) : Validated<Nothing>
}

val <T> Validated<T>.valueOrNull: T?
    get() = when (this) {
        is Validated.Valid -> value
        is Validated.Invalid -> null
    }

val Validated<*>.errorMessage: String?
    get() = when (this) {
        is Validated.Valid -> null
        is Validated.Invalid -> message
    }

val Validated<*>.isValid: Boolean get() = this is Validated.Valid

inline fun <T, R> Validated<T>.map(transform: (T) -> R): Validated<R> = when (this) {
    is Validated.Valid -> Validated.Valid(transform(value))
    is Validated.Invalid -> this
}
