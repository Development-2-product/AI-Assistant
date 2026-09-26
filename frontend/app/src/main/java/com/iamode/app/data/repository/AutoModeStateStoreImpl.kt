package com.iamode.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.iamode.app.core.datastore.PreferenceKeys
import com.iamode.app.domain.repository.AutoModeStateStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoModeStateStoreImpl @Inject constructor(private val store: DataStore<Preferences>) : AutoModeStateStore {
    override suspend fun suppressedKey(): String? = store.data.first()[PreferenceKeys.AUTO_SUPPRESSED]?.ifBlank { null }

    override suspend fun setSuppressedKey(key: String?) {
        store.edit { if (key == null) it.remove(PreferenceKeys.AUTO_SUPPRESSED) else it[PreferenceKeys.AUTO_SUPPRESSED] = key }
    }
}
