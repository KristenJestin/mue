package fr.kristenjestin.mue

import android.app.Application
import fr.kristenjestin.mue.di.AppContainer

/**
 * Owns the single dependency container for the whole app.
 *
 * Mue deliberately uses manual dependency injection: three screens do not justify
 * the build-time cost of an annotation-processed framework.
 */
class MueApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
