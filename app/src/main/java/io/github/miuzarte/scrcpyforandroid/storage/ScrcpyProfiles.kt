package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context
import android.os.Build
import android.os.Parcel
import android.util.Base64
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.miuzarte.scrcpyforandroid.storage.ScrcpyOptions.Companion.GLOBAL_PROFILE_ID
import io.github.miuzarte.scrcpyforandroid.storage.ScrcpyOptions.Companion.GLOBAL_PROFILE_NAME
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ScrcpyProfiles(context: Context) : Settings(context, "ScrcpyProfiles") {
    companion object {
        private val PROFILES_JSON = Pair(
            stringPreferencesKey("profiles_json"),
            "",
        )
    }

    data class Profile(
        val id: String,
        val name: String,
        val bundle: ScrcpyOptions.Bundle,
        val isBuiltinGlobal: Boolean = false,
    )

    data class State(
        val profiles: List<Profile>,
    ) {
        val globalProfile: Profile get() = profiles.first()
    }

    private val profilesJson by setting(PROFILES_JSON)

    val state: StateFlow<State> = createBundleState(::stateFromPreferences)

    private fun stateFromPreferences(preferences: androidx.datastore.preferences.core.Preferences): State {
        val raw = preferences.read(PROFILES_JSON)
        return normalizeState(
            runCatching { decodeState(raw) }.getOrNull() ?: State(emptyList())
        )
    }

    suspend fun loadState() = loadBundle(::stateFromPreferences)

    suspend fun saveState(newState: State) {
        profilesJson.set(encodeState(normalizeState(newState)))
    }

    suspend fun getProfiles(): List<Profile> = loadState().profiles

    suspend fun getProfile(id: String): Profile? = loadState().profiles.firstOrNull { it.id == id }

    suspend fun getProfileOrGlobal(id: String?): Profile {
        val state = loadState()
        return state.profiles.firstOrNull { it.id == id } ?: state.globalProfile
    }

    suspend fun getBundleOrGlobal(id: String?): ScrcpyOptions.Bundle =
        getProfileOrGlobal(id).bundle

    suspend fun updateBundle(id: String, bundle: ScrcpyOptions.Bundle): State {
        val current = loadState()
        val profiles = current.profiles.map {
            if (it.id == id) it.copy(bundle = bundle)
            else it
        }
        val next = normalizeState(current.copy(profiles = profiles))
        saveState(next)
        return next
    }

    suspend fun createProfile(
        requestedName: String,
        bundle: ScrcpyOptions.Bundle = ScrcpyOptions.defaultBundle(),
    ): Profile {
        val current = loadState()
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = ensureUniqueName(current, requestedName),
            bundle = bundle,
        )
        val next = normalizeState(current.copy(profiles = current.profiles + profile))
        saveState(next)
        return next.profiles.first { it.id == profile.id }
    }

    suspend fun duplicateProfile(
        sourceId: String,
        requestedName: String = "",
    ): Profile? {
        val current = loadState()
        val source = current.profiles.firstOrNull { it.id == sourceId } ?: return null
        return createProfile(
            requestedName = requestedName.ifBlank { source.name },
            bundle = source.bundle,
        )
    }

    suspend fun renameProfile(id: String, requestedName: String): Profile? {
        if (id == GLOBAL_PROFILE_ID) return null
        val current = loadState()
        val existing = current.profiles.firstOrNull { it.id == id } ?: return null
        val updated = existing.copy(
            name = ensureUniqueName(current, requestedName, excludeId = id),
        )
        val next = normalizeState(
            current.copy(
                profiles = current.profiles.map {
                    if (it.id == id) updated else it
                }
            )
        )
        saveState(next)
        return next.profiles.firstOrNull { it.id == id }
    }

    suspend fun deleteProfile(id: String): Boolean {
        if (id == GLOBAL_PROFILE_ID) return false
        val current = loadState()
        if (current.profiles.none { it.id == id }) return false
        val next = normalizeState(
            current.copy(
                profiles = current.profiles.filterNot { it.id == id }
            )
        )
        saveState(next)
        return true
    }

    suspend fun moveProfile(fromIndex: Int, toIndex: Int): State {
        val current = loadState()
        if (fromIndex !in current.profiles.indices || toIndex !in current.profiles.indices) {
            return current
        }
        if (fromIndex == toIndex || fromIndex == 0 || toIndex == 0) {
            return current
        }
        val mutable = current.profiles.toMutableList()
        val item = mutable.removeAt(fromIndex)
        val target = if (toIndex > fromIndex) toIndex - 1 else toIndex
        mutable.add(target, item)
        val next = normalizeState(current.copy(profiles = mutable))
        saveState(next)
        return next
    }

    fun ensureUniqueName(
        state: State,
        requestedName: String,
        excludeId: String? = null,
    ): String {
        val baseName = requestedName.trim().ifBlank { "配置" }
        if (baseName == GLOBAL_PROFILE_NAME) return ensureUniqueName(state, "配置", excludeId)
        val existingNames = state.profiles
            .filterNot { it.id == excludeId }
            .map { it.name }
            .toSet()
        if (baseName !in existingNames) return baseName
        var suffix = 1
        while (true) {
            val candidate = "$baseName ($suffix)"
            if (candidate !in existingNames) return candidate
            suffix++
        }
    }

    private fun normalizeState(state: State): State {
        val global = state.profiles.firstOrNull { it.id == GLOBAL_PROFILE_ID }
            ?.copy(name = GLOBAL_PROFILE_NAME, isBuiltinGlobal = true)
            ?: Profile(
                id = GLOBAL_PROFILE_ID,
                name = GLOBAL_PROFILE_NAME,
                bundle = ScrcpyOptions.defaultBundle(),
                isBuiltinGlobal = true,
            )
        val usedNames = linkedSetOf(global.name)
        val others = buildList {
            state.profiles
                .asSequence()
                .filterNot { it.id == GLOBAL_PROFILE_ID }
                .forEach { profile ->
                    val baseName = profile.name.trim().ifBlank { "配置" }
                        .takeUnless { it == GLOBAL_PROFILE_NAME }
                        ?: "配置"
                    var normalizedName = baseName
                    if (normalizedName in usedNames) {
                        var suffix = 1
                        while (true) {
                            val candidate = "$baseName ($suffix)"
                            if (candidate !in usedNames) {
                                normalizedName = candidate
                                break
                            }
                            suffix++
                        }
                    }
                    usedNames += normalizedName
                    add(profile.copy(name = normalizedName, isBuiltinGlobal = false))
                }
        }
        return State(listOf(global) + others)
    }

    private fun encodeState(state: State): String {
        val array = JSONArray()
        for (profile in state.profiles) {
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("bundle", encodeBundle(profile.bundle))
            )
        }
        return array.toString()
    }

    private fun decodeState(raw: String): State {
        if (raw.isBlank()) return State(emptyList())
        val array = JSONArray(raw)
        val profiles = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                val bundleRaw = item.optString("bundle")
                if (id.isBlank()) continue
                add(
                    Profile(
                        id = id,
                        name = name.ifBlank { "配置" },
                        bundle = decodeBundle(bundleRaw),
                        isBuiltinGlobal = id == GLOBAL_PROFILE_ID,
                    )
                )
            }
        }
        return State(profiles)
    }

    private fun encodeBundle(bundle: ScrcpyOptions.Bundle): String {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(bundle, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    private fun decodeBundle(raw: String): ScrcpyOptions.Bundle {
        if (raw.isBlank()) return ScrcpyOptions.defaultBundle()
        val bytes = runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()
            ?: return ScrcpyOptions.defaultBundle()
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                parcel.readParcelable(
                    ScrcpyOptions.Bundle::class.java.classLoader,
                    ScrcpyOptions.Bundle::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                parcel.readParcelable(ScrcpyOptions.Bundle::class.java.classLoader)
            } ?: ScrcpyOptions.defaultBundle()
        } catch (_: Throwable) {
            ScrcpyOptions.defaultBundle()
        } finally {
            parcel.recycle()
        }
    }
}
