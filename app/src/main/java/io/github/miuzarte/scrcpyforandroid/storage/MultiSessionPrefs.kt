package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context
import io.github.miuzarte.scrcpyforandroid.services.SlotSessionManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 收藏的应用 + 多会话画质参数。存在 filesDir 下的一个小 JSON 里，不动 DataStore 那套。 */
object MultiSessionPrefs {
    private const val FILE_NAME = "multi_session_prefs.json"

    @Serializable
    data class FavoriteApp(
        val packageName: String,
        val label: String,
    )

    @Serializable
    data class Prefs(
        val favorites: List<FavoriteApp> = emptyList(),
        val thumbMaxSize: Int = 720,
        val thumbFps: String = "1",
        val thumbBitRate: Int = 1_000_000,
        val fullMaxSize: Int = 0,
        val fullFps: String = "",
        val fullBitRate: Int = 8_000_000,
        val ballXFraction: Float = 0.5f,
        val ballYFraction: Float = 0.5f,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): Prefs = runCatching {
        val f = file(context)
        if (f.isFile) json.decodeFromString(Prefs.serializer(), f.readText()) else Prefs()
    }.getOrDefault(Prefs())

    fun save(context: Context, prefs: Prefs) {
        runCatching { file(context).writeText(json.encodeToString(Prefs.serializer(), prefs)) }
    }

    fun addFavorite(context: Context, app: FavoriteApp): Prefs {
        val prefs = load(context)
        if (prefs.favorites.any { it.packageName == app.packageName }) return prefs
        return prefs.copy(favorites = prefs.favorites + app).also { save(context, it) }
    }

    fun removeFavorite(context: Context, packageName: String): Prefs {
        val prefs = load(context)
        return prefs.copy(favorites = prefs.favorites.filterNot { it.packageName == packageName })
            .also { save(context, it) }
    }

    /** 把持久化的画质参数灌进 [SlotSessionManager]。 */
    /** 全屏页悬浮球的位置（fraction 0..1），持久化到同一份配置里。 */
    fun saveBallPosition(context: Context, x: Float, y: Float): Prefs {
        val prefs = load(context)
        return prefs.copy(ballXFraction = x, ballYFraction = y).also { save(context, it) }
    }

    fun applyToSessionManager(prefs: Prefs) {
        SlotSessionManager.thumbMaxSize = prefs.thumbMaxSize
        SlotSessionManager.thumbFps = prefs.thumbFps
        SlotSessionManager.thumbBitRate = prefs.thumbBitRate
        SlotSessionManager.fullMaxSize = prefs.fullMaxSize
        SlotSessionManager.fullFps = prefs.fullFps
        SlotSessionManager.fullBitRate = prefs.fullBitRate
    }
}
