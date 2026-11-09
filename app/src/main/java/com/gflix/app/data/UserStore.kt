package com.gflix.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.userDataStore by preferencesDataStore(name = "gflix_user")

/** Profiller, Listem, izleme ilerlemesi ve dil tercihleri cihazda saklanır. */
class UserStore(private val context: Context) {

    private val key = stringPreferencesKey("state")

    val state: Flow<UserState> = context.userDataStore.data.map { decode(it[key]) }

    suspend fun current(): UserState = state.first()

    suspend fun update(transform: (UserState) -> UserState) {
        context.userDataStore.edit { prefs ->
            prefs[key] = AppJson.encodeToString(UserState.serializer(), transform(decode(prefs[key])))
        }
    }

    private fun decode(raw: String?): UserState =
        raw?.let { runCatching { AppJson.decodeFromString(UserState.serializer(), it) }.getOrNull() } ?: UserState()

    private suspend fun updateData(profileId: String, transform: (ProfileData) -> ProfileData) = update { s ->
        s.copy(data = s.data + (profileId to transform(s.dataFor(profileId))))
    }

    // ---- Profiller ----

    suspend fun addProfile(name: String, avatar: Int, kids: Boolean): Profile {
        val profile = Profile(UUID.randomUUID().toString(), name.trim(), avatar, kids)
        update { it.copy(profiles = it.profiles + profile) }
        return profile
    }

    suspend fun updateProfile(profile: Profile) = update { s ->
        s.copy(profiles = s.profiles.map { if (it.id == profile.id) profile else it })
    }

    suspend fun deleteProfile(id: String) = update { s ->
        s.copy(
            profiles = s.profiles.filterNot { it.id == id },
            data = s.data - id,
            activeProfileId = if (s.activeProfileId == id) null else s.activeProfileId,
        )
    }

    suspend fun selectProfile(id: String) = update { it.copy(activeProfileId = id) }

    suspend fun setToken(token: String?) = update { it.copy(token = token?.trim()?.ifBlank { null }) }

    // ---- Profil verisi ----

    suspend fun toggleMyList(profileId: String, titleId: String) = updateData(profileId) { d ->
        if (titleId in d.myList) d.copy(myList = d.myList - titleId) else d.copy(myList = listOf(titleId) + d.myList)
    }

    suspend fun toggleLiked(profileId: String, titleId: String) = updateData(profileId) { d ->
        if (titleId in d.liked) d.copy(liked = d.liked - titleId) else d.copy(liked = listOf(titleId) + d.liked)
    }

    suspend fun saveProgress(profileId: String, progress: WatchProgress) = updateData(profileId) { d ->
        d.copy(progress = d.progress + (Library.progressKey(progress.titleId, progress.season, progress.episode) to progress))
    }

    suspend fun removeFromContinueWatching(profileId: String, titleId: String) = updateData(profileId) { d ->
        d.copy(progress = d.progress.filterValues { it.titleId != titleId })
    }

    suspend fun setLanguages(profileId: String, audio: String?, subtitle: String?) = updateData(profileId) { d ->
        d.copy(audioLang = audio, subtitleLang = subtitle)
    }
}
