package fr.kristenjestin.mue.di

import android.content.Context

/**
 * Manual dependency container. Repositories and data sources are registered here
 * as they are implemented, and read by ViewModel factories.
 */
class AppContainer(private val applicationContext: Context) {
    val appContext: Context get() = applicationContext
}
