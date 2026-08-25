package fr.kristenjestin.mue.testing

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Locale

/**
 * Runs a test under a chosen default locale and restores the previous one afterwards.
 *
 * PRD FR-CSV-003 makes the export format independent of the phone's language, so the
 * only way to prove it is to run the same code under a locale that would break a
 * naive implementation.
 */
class LocaleRule(private val locale: Locale) : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val previous = Locale.getDefault()
                Locale.setDefault(locale)
                try {
                    base.evaluate()
                } finally {
                    Locale.setDefault(previous)
                }
            }
        }
}
