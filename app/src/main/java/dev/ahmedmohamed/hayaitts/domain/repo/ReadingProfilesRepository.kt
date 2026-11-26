package dev.ahmedmohamed.hayaitts.domain.repo

import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import kotlinx.coroutines.flow.Flow

/**
 * The languages the user reads and each one's engine/voice priority list.
 * Empty until first seeded; the TTS service then falls back to the best
 * installed local voice for the requested locale.
 */
interface ReadingProfilesRepository {
    val profiles: Flow<List<LanguageProfile>>
    suspend fun setProfiles(profiles: List<LanguageProfile>)

    /** Atomic read-modify-write, so concurrent editors (settings sync, Reading screen) can't lose each other's edits. */
    suspend fun updateProfiles(transform: (List<LanguageProfile>) -> List<LanguageProfile>)

    /** Master switch for engines that send text off the device (Edge). */
    val onlineEnginesEnabled: Flow<Boolean>
    suspend fun setOnlineEnginesEnabled(enabled: Boolean)

    /** Whether first-run defaults have been written (so an emptied list stays empty). */
    val seeded: Flow<Boolean>
    suspend fun markSeeded()
}
