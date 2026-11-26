package dev.ahmedmohamed.hayaitts.data.routing

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.repo.ReadingProfilesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.readingDataStore by preferencesDataStore(name = "hayai_reading")

class ReadingProfilesRepositoryImpl(private val context: Context) : ReadingProfilesRepository {

    private val store get() = context.readingDataStore
    private val serializer = ListSerializer(LanguageProfile.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    override val profiles: Flow<List<LanguageProfile>> = store.data.map { prefs ->
        prefs[KEY_PROFILES]?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }.orEmpty()
    }

    override suspend fun setProfiles(profiles: List<LanguageProfile>) {
        store.edit { it[KEY_PROFILES] = json.encodeToString(serializer, profiles) }
    }

    override val onlineEnginesEnabled: Flow<Boolean> = store.data.map { it[KEY_ONLINE] ?: true }

    override suspend fun setOnlineEnginesEnabled(enabled: Boolean) {
        store.edit { it[KEY_ONLINE] = enabled }
    }

    override val seeded: Flow<Boolean> = store.data.map { it[KEY_SEEDED] ?: false }

    override suspend fun markSeeded() {
        store.edit { it[KEY_SEEDED] = true }
    }

    private companion object {
        val KEY_PROFILES = stringPreferencesKey("reading_profiles_v1")
        val KEY_ONLINE = booleanPreferencesKey("online_engines_enabled")
        val KEY_SEEDED = booleanPreferencesKey("reading_profiles_seeded")
    }
}
