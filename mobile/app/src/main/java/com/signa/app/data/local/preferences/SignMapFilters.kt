package com.signa.app.data.local.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.signa.app.BuildConfig
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class SignMapFilters @Inject constructor(private val store: DataStore<Preferences>) {
    private val key = stringSetPreferencesKey(
        if (BuildConfig.USE_FAKE_BACKEND) "map_sample_categories" else "map_sign_categories"
    )
    val categories = store.data.map { it[key].orEmpty() }.catch {
        if (it is IOException) emit(emptySet()) else throw it
    }

    suspend fun toggle(code: String) {
        store.edit { preferences ->
            val current = preferences[key].orEmpty()
            preferences[key] = if (code in current) current - code else current + code
        }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }
}
