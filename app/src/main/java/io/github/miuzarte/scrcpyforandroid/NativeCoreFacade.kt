package io.github.miuzarte.scrcpyforandroid

import android.util.Log
import android.view.Surface
import io.github.miuzarte.scrcpyforandroid.nativecore.PersistentVideoRenderer
import io.github.miuzarte.scrcpyforandroid.nativecore.VideoDecoderController
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Facade that centralizes video rendering and decoder management.
 *
 * Acts as a thin front-end over [VideoDecoderController] (decoder lifecycle, bootstrap
 * cache, error detection) and [PersistentVideoRenderer] (EGL / surface management).
 *
 * The facade owns the session lifecycle mutex and coordinates surface attach/detach
 * with decoder creation. It also publishes video size / FPS to listeners.
 */
object NativeCoreFacade {
    private val sessionLifecycleMutex = Mutex()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val renderer = PersistentVideoRenderer()
    private val controller = VideoDecoderController(renderer)

    @Volatile
    private var activeSurfaceId: Int? = null

    @Volatile
    private var recordingSurfaceAttached = false

    // Reference to Scrcpy for reading currentSessionState (set by onScrcpySessionStarted)
    @Volatile
    private var scrcpyRef: Scrcpy? = null

    @Volatile
    private var packetCount: Long = 0

    suspend fun close() {
        sessionLifecycleMutex.withLock {
            controller.releaseAll()
            renderer.release()
        }
    }

    /**
     * Register the current rendering [surface].
     *
     * - If the surface is already active and the decoder exists, this is a no-op.
     * - If a decoder exists but cannot switch output surface, a new decoder is created
     *   and bound to the supplied surface.
     */
    suspend fun attachVideoSurface(surface: Surface) {
        sessionLifecycleMutex.withLock {
            if (!surface.isValid) {
                Log.w(TAG, "attachVideoSurface(): skip invalid surface")
                return
            }
            val newId = System.identityHashCode(surface)
            if (activeSurfaceId == newId && controller.isDecoderUsable()) {
                return
            }
            Log.i(TAG, "attachVideoSurface(): surfaceId=$newId oldSurfaceId=$activeSurfaceId")
            activeSurfaceId = newId
            controller.attachDisplaySurface(surface)
            val session = controller.getCurrentSessionInfo() ?: return
            if (controller.isDecoderUsable()) {
                Log.i(TAG, "attachVideoSurface(): try switch decoder output to persistent surface")
                if (controller.trySwitchDecoderSurface()) {
                    return
                }
            }
            controller.ensureDecoder(session)
        }
    }

    /**
     * Unregister the active rendering [surface].
     *
     * - If a stale surface reference is supplied (identity mismatch), the request is ignored.
     * - When [releaseDecoder] is false, only the active display target is cleared so a future
     *   surface can attempt to rebind via `setOutputSurface()`.
     * - When [releaseDecoder] is true, the current decoder is also released because the backing
     *   surface is being destroyed for real.
     */
    suspend fun detachVideoSurface(surface: Surface? = null, releaseDecoder: Boolean = false) {
        sessionLifecycleMutex.withLock {
            val currentId = activeSurfaceId
            val requestId = surface?.let { System.identityHashCode(it) }
            if (requestId != null && currentId != null && requestId != currentId) {
                Log.i(
                    TAG,
                    "detachVideoSurface(): skip stale request requestSurfaceId=$requestId currentSurfaceId=$currentId",
                )
                return
            }
            Log.i(
                TAG,
                "detachVideoSurface(): surfaceId=$requestId releaseDecoder=$releaseDecoder",
            )
            activeSurfaceId = null
            controller.detachDisplaySurface(surface, releaseDecoder)
        }
    }

    suspend fun attachRecordingSurface(
        surface: Surface,
        width: Int,
        height: Int,
        onFrameRendered: ((Long) -> Unit)? = null,
    ) {
        sessionLifecycleMutex.withLock {
            recordingSurfaceAttached = true
            controller.attachRecordSurface(surface, width, height, onFrameRendered)
            val session = controller.getCurrentSessionInfo()
            if (session != null && !controller.isDecoderUsable()) {
                controller.ensureDecoder(session)
            }
        }
    }

    suspend fun detachRecordingSurface(
        surface: Surface? = null,
        releaseSurface: Boolean = false,
    ) {
        sessionLifecycleMutex.withLock {
            recordingSurfaceAttached = false
            controller.detachRecordSurface(surface, releaseSurface)
            if (activeSurfaceId == null) {
                controller.releaseAll()
            }
        }
    }

    fun addVideoSizeListener(listener: (Int, Int) -> Unit) = controller.addVideoSizeListener(listener)
    fun removeVideoSizeListener(listener: (Int, Int) -> Unit) = controller.removeVideoSizeListener(listener)
    fun addVideoFpsListener(listener: (Float) -> Unit) = controller.addVideoFpsListener(listener)
    fun removeVideoFpsListener(listener: (Float) -> Unit) = controller.removeVideoFpsListener(listener)

    /**
     * Called by Scrcpy.kt when a session starts.
     * Sets up video decoders for registered surfaces.
     */
    suspend fun onScrcpySessionStarted(
        session: Scrcpy.Session.SessionInfo,
        sessionMgr: Scrcpy.Session,
        scrcpy: Scrcpy,
    ) = sessionLifecycleMutex.withLock {
        scrcpyRef = scrcpy
        controller.releaseAll()
        controller.resetBootstrap()
        if (activeSurfaceId != null || recordingSurfaceAttached) {
            // v4.0: width/height come from first video session packet, not from initial metadata
            if (session.width > 0 && session.height > 0) {
                Log.i(TAG, "onScrcpySessionStarted(): bind decoder to persistent surface")
                controller.ensureDecoder(session)
            } else {
                Log.i(TAG, "onScrcpySessionStarted(): defer decoder until first video session packet (v4.0)")
            }
        }
        packetCount = 0
        sessionMgr.attachVideoConsumer { packet ->
            cacheAndFeed(packet)
        }
    }

    /**
     * Called by Scrcpy when a video session packet arrives with new dimensions.
     * Launches a coroutine to create or rebuild the decoder under the lifecycle mutex.
     */
    fun onVideoSizeChanged(width: Int, height: Int) {
        lifecycleScope.launch {
            sessionLifecycleMutex.withLock {
                val scrcpy = scrcpyRef ?: return@withLock
                val info = scrcpy.currentSessionState.value ?: return@withLock
                if (info.width <= 0 || info.height <= 0) return@withLock
                controller.rebuildDecoderForSize(info)
            }
        }
    }

    /**
     * Called by Scrcpy.kt when a session stops.
     * Cleans up decoders and resets state.
     */
    suspend fun onScrcpySessionStopped() = sessionLifecycleMutex.withLock {
        controller.releaseAll()
        scrcpyRef = null
        recordingSurfaceAttached = false
    }

    private fun cacheAndFeed(packet: Scrcpy.Session.VideoPacket) {
        packetCount += 1
        if (packetCount == 1L || packetCount % 120L == 0L) {
            Log.i(
                TAG,
                "videoFeed(): packets=$packetCount key=${packet.isKeyFrame} cfg=${packet.isConfig} usable=${controller.isDecoderUsable()}",
            )
        }
        val usable = controller.feed(packet)
        if (!usable) {
            Log.e(TAG, "videoFeed(): decoder became unusable after feed, packets=$packetCount")
        }
    }

    private const val TAG = "NativeCoreFacade"
}
