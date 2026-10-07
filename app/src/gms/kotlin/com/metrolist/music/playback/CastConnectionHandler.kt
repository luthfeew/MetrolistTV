package com.metrolist.music.playback

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.metrolist.music.cast.MetrolistCastMediaItemConverter
import com.metrolist.music.extensions.metadata
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt
import com.metrolist.music.models.MediaMetadata as AppMediaMetadata

@UnstableApi
class CastConnectionHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val musicService: MusicService,
) {
    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _castDeviceName = MutableStateFlow<String?>(null)
    val castDeviceName: StateFlow<String?> = _castDeviceName.asStateFlow()

    private val _castPosition = MutableStateFlow(0L)
    val castPosition: StateFlow<Long> = _castPosition.asStateFlow()

    private val _castDuration = MutableStateFlow(0L)
    val castDuration: StateFlow<Long> = _castDuration.asStateFlow()

    private val _castIsPlaying = MutableStateFlow(false)
    val castIsPlaying: StateFlow<Boolean> = _castIsPlaying.asStateFlow()

    private val _castIsBuffering = MutableStateFlow(false)
    val castIsBuffering: StateFlow<Boolean> = _castIsBuffering.asStateFlow()

    private val _castVolume = MutableStateFlow(1f)
    val castVolume: StateFlow<Float> = _castVolume.asStateFlow()

    @Volatile
    var isSyncingFromCast = false
        private set

    private var castContext: CastContext? = null
    private var castPlayer: RemoteCastPlayer? = null
    private var positionJob: Job? = null
    private var loadJob: Job? = null
    private var extensionJob: Job? = null
    private var queueSyncJob: Job? = null
    private var syncResetJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val playerListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayerState()
                if (playbackState == Player.STATE_ENDED && _isCasting.value) {
                    val localPlayer = musicService.player
                    val nextIndex =
                        localPlayer.currentTimeline.getNextWindowIndex(
                            localPlayer.currentMediaItemIndex,
                            Player.REPEAT_MODE_OFF,
                            localPlayer.shuffleModeEnabled,
                        )
                    if (nextIndex != C.INDEX_UNSET) {
                        localPlayer.seekTo(nextIndex, 0L)
                        loadCurrentMedia()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.e(error, "Cast player error: ${error.errorCodeName} (${error.errorCode})")
                scope.launch {
                    delay(500)
                    if (!_isCasting.value) return@launch
                    val localPlayer = musicService.player
                    if (localPlayer.hasNextMediaItem()) {
                        localPlayer.pause()
                        localPlayer.seekToNextMediaItem()
                        loadCurrentMedia()
                    } else {
                        loadCurrentMedia()
                    }
                }
            }

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) = updatePlayerState()

            override fun onDeviceVolumeChanged(
                volume: Int,
                muted: Boolean,
            ) = updateVolume()

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                val mediaId = mediaItem?.mediaId ?: runCatching { castPlayer?.currentMediaItem?.mediaId }.getOrNull()
                mediaId?.let(::syncLocalPlayer)
                prunePlayedItems()
                appendQueueIfNeeded()
                updatePlayerState()
            }
        }

    private val sessionListener =
        object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                acquireLocks()
                _isCasting.value = true
                _castDeviceName.value =
                    castContext
                        ?.sessionManager
                        ?.currentCastSession
                        ?.castDevice
                        ?.friendlyName
                musicService.player.pause()
                startPositionUpdates()
                updatePlayerState()
                updateVolume()

                val player = castPlayer ?: return
                if (player.mediaItemCount == 0) {
                    loadCurrentMedia()
                } else {
                    val currentItem = runCatching { player.currentMediaItem }.getOrNull()
                    currentItem?.mediaId?.let(::syncLocalPlayer)
                }
            }

            override fun onCastSessionUnavailable() {
                val player = castPlayer
                if (player != null && player.currentPosition > 0) {
                    musicService.player.seekTo(player.currentPosition)
                }
                releaseLocks()
                _isCasting.value = false
                _castDeviceName.value = null
                _castIsPlaying.value = false
                _castIsBuffering.value = false
                stopPositionUpdates()
                musicService.player.pause()
            }
        }

    private val localPlayerListener =
        object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (_isCasting.value) {
                    syncQueueFromLocalPlayer()
                }
            }
        }

    fun initialize(): Boolean {
        if (castPlayer != null) return true
        return runCatching {
            castContext = CastContext.getSharedInstance(context)
            castPlayer =
                RemoteCastPlayer
                    .Builder(context)
                    .setMediaItemConverter(MetrolistCastMediaItemConverter())
                    .build()
                    .also { player ->
                        player.addListener(playerListener)
                        player.setSessionAvailabilityListener(sessionListener)
                        if (player.isCastSessionAvailable) {
                            sessionListener.onCastSessionAvailable()
                        }
                    }
            musicService.player.addListener(localPlayerListener)
            true
        }.getOrElse { error ->
            Timber.e(error, "Failed to initialize Cast")
            false
        }
    }

    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun loadCurrentMedia() {
        musicService.currentMediaMetadata.value?.let(::loadMedia)
    }

    fun loadMedia(metadata: AppMediaMetadata) {
        if (!_isCasting.value) return
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val localPlayer = musicService.player
                val centerIndex = localPlayer.indexOfMediaId(metadata.id)

                val currentItem = if (centerIndex != C.INDEX_UNSET) {
                    resolvedMediaItem(centerIndex)
                } else {
                    resolvedMediaItemFromMetadata(metadata)
                }

                if (currentItem == null) {
                    Timber.w("Unable to resolve Cast media item for ${metadata.id}")
                    return@launch
                }

                val startPosition =
                    if (centerIndex != C.INDEX_UNSET && localPlayer.currentMediaItemIndex == centerIndex) {
                        localPlayer.currentPosition
                    } else {
                        0L
                    }

                castPlayer?.apply {
                    setMediaItems(listOf(currentItem), 0, startPosition)
                    prepare()
                    play()
                }
                localPlayer.pause()

                appendQueueIfNeeded()
            }
    }

    fun play() {
        castPlayer?.play()
    }

    fun pause() {
        castPlayer?.pause()
    }

    fun seekTo(position: Long) {
        castPlayer?.seekTo(position)
    }

    fun setVolume(volume: Float) {
        val player = castPlayer ?: return
        val maxVolume = player.deviceInfo.maxVolume.takeIf { it > 0 } ?: return
        player.setDeviceVolume((volume.coerceIn(0f, 1f) * maxVolume).roundToInt(), 0)
    }

    fun navigateToMediaIfInQueue(mediaId: String): Boolean {
        val player = castPlayer ?: return false
        val count = player.mediaItemCount
        if (count <= 0) return false
        for (index in 0 until count) {
            val item = runCatching { player.getMediaItemAt(index) }.getOrNull() ?: continue
            if (item.mediaId == mediaId) {
                if (index != player.currentMediaItemIndex) {
                    runCatching { player.seekTo(index, 0L) }
                }
                musicService.player.pause()
                syncLocalPlayer(mediaId)
                return true
            }
        }
        return false
    }

    fun skipToNext() {
        val player = castPlayer ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (musicService.player.hasNextMediaItem()) {
            musicService.player.pause()
            musicService.player.seekToNextMediaItem()
            loadCurrentMedia()
        }
    }

    fun skipToPrevious() {
        val player = castPlayer ?: return
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else if (musicService.player.hasPreviousMediaItem()) {
            musicService.player.pause()
            musicService.player.seekToPreviousMediaItem()
            loadCurrentMedia()
        }
    }

    private fun syncLocalPlayer(mediaId: String) {
        if (!_isCasting.value) return
        val localPlayer = musicService.player
        val index = localPlayer.indexOfMediaId(mediaId)
        if (index == C.INDEX_UNSET) return

        localPlayer.pause()
        if (index == localPlayer.currentMediaItemIndex) return

        syncResetJob?.cancel()
        isSyncingFromCast = true
        localPlayer.seekTo(index, 0L)
        localPlayer.pause()
        syncResetJob =
            scope.launch {
                delay(300)
                isSyncingFromCast = false
            }
    }

    fun syncQueueFromLocalPlayer() {
        if (!_isCasting.value) return
        val player = castPlayer ?: return
        val localPlayer = musicService.player
        if (localPlayer.currentTimeline.isEmpty) return

        val currentCastMediaId = runCatching { player.currentMediaItem?.mediaId }.getOrNull()
        if (currentCastMediaId != null) {
            val localIndex = localPlayer.indexOfMediaId(currentCastMediaId)
            if (localIndex == C.INDEX_UNSET) {
                Timber.d("Current Cast item $currentCastMediaId was removed from queue; reloading current media")
                loadCurrentMedia()
                return
            }
        } else if (player.mediaItemCount == 0) {
            loadCurrentMedia()
            return
        }

        queueSyncJob?.cancel()
        queueSyncJob =
            scope.launch {
                val currentCastIdx = player.currentMediaItemIndex
                val castCount = player.mediaItemCount
                if (currentCastIdx !in 0 until castCount) return@launch

                val currentMediaId = runCatching { player.getMediaItemAt(currentCastIdx).mediaId }.getOrNull() ?: return@launch
                val localCenterIdx = localPlayer.indexOfMediaId(currentMediaId)
                if (localCenterIdx == C.INDEX_UNSET) {
                    loadCurrentMedia()
                    return@launch
                }

                val upcomingCastCount = castCount - 1 - currentCastIdx
                var needsRebuildUpcoming = false

                val timeline = localPlayer.currentTimeline
                val expectedUpcomingIds = mutableListOf<String>()
                var tempIdx = localCenterIdx
                while (expectedUpcomingIds.size < 3) {
                    tempIdx = timeline.getNextWindowIndex(tempIdx, Player.REPEAT_MODE_OFF, localPlayer.shuffleModeEnabled)
                    if (tempIdx == C.INDEX_UNSET) break
                    val localItem = runCatching { localPlayer.getMediaItemAt(tempIdx) }.getOrNull() ?: break
                    expectedUpcomingIds.add(localItem.mediaId)
                }

                if (upcomingCastCount != expectedUpcomingIds.size) {
                    needsRebuildUpcoming = true
                } else {
                    for (i in 0 until upcomingCastCount) {
                        val castItem = runCatching { player.getMediaItemAt(currentCastIdx + 1 + i) }.getOrNull()
                        if (castItem == null || castItem.mediaId != expectedUpcomingIds.getOrNull(i)) {
                            needsRebuildUpcoming = true
                            break
                        }
                    }
                }

                if (needsRebuildUpcoming) {
                    Timber.d("Upcoming Cast queue differs from local player; resyncing upcoming items")
                    if (upcomingCastCount > 0) {
                        runCatching { player.removeMediaItems(currentCastIdx + 1, castCount) }
                    }
                    val itemsToAdd = expectedUpcomingIds.mapNotNull { mediaId ->
                        val idx = localPlayer.indexOfMediaId(mediaId)
                        if (idx != C.INDEX_UNSET) resolvedMediaItem(idx) else null
                    }
                    if (itemsToAdd.isNotEmpty()) {
                        runCatching { player.addMediaItems(itemsToAdd) }
                    }
                }
            }
    }

    private fun prunePlayedItems() {
        val player = castPlayer ?: return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex > 2) {
            val toRemove = currentIndex - 2
            runCatching { player.removeMediaItems(0, toRemove) }
        }
    }

    fun appendQueueIfNeeded() {
        val player = castPlayer ?: return
        val count = player.mediaItemCount
        val currentIndex = player.currentMediaItemIndex
        if (extensionJob?.isActive == true || count <= 0) return
        val upcomingCount = count - 1 - currentIndex
        if (upcomingCount >= 3) return

        extensionJob =
            scope.launch {
                val lastMediaId = runCatching {
                    val currentCount = player.mediaItemCount
                    if (currentCount <= 0) null
                    else player.getMediaItemAt(currentCount - 1).mediaId
                }.getOrNull() ?: return@launch

                val localPlayer = musicService.player
                var index = localPlayer.indexOfMediaId(lastMediaId)
                if (index == C.INDEX_UNSET) {
                    index = localPlayer.currentMediaItemIndex
                }
                if (index == C.INDEX_UNSET || localPlayer.currentTimeline.isEmpty) return@launch

                val needed = 3 - upcomingCount
                val indices = mutableListOf<Int>()
                while (indices.size < needed) {
                    index =
                        localPlayer.currentTimeline.getNextWindowIndex(
                            index,
                            Player.REPEAT_MODE_OFF,
                            localPlayer.shuffleModeEnabled,
                        )
                    if (index == C.INDEX_UNSET) break
                    indices += index
                }
                val items = indices.mapNotNull { resolvedMediaItem(it) }
                if (items.isNotEmpty()) {
                    runCatching { player.addMediaItems(items) }
                }
            }
    }

    private suspend fun resolvedMediaItem(index: Int): MediaItem? =
        runCatching {
            val player = musicService.player
            if (index !in 0 until player.mediaItemCount) return null
            val item = player.getMediaItemAt(index)
            val metadata = item.metadata ?: return null
            val stream = musicService.getCastStream(metadata.id) ?: return null
            val castMetadata =
                item.mediaMetadata
                    .buildUpon()
                    .setTitle(metadata.title)
                    .setArtist(metadata.artists.joinToString(", ") { it.name })
                    .setAlbumTitle(metadata.album?.title)
                    .setArtworkUri(metadata.thumbnailUrl?.resize(1080, 1080)?.let(Uri::parse))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            item
                .buildUpon()
                .setUri(Uri.parse(stream.url))
                .setMimeType(stream.mimeType)
                .setMediaMetadata(castMetadata)
                .build()
        }.getOrNull()

    private suspend fun resolvedMediaItemFromMetadata(metadata: AppMediaMetadata): MediaItem? =
        runCatching {
            val stream = musicService.getCastStream(metadata.id) ?: return null
            val castMetadata =
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(metadata.title)
                    .setArtist(metadata.artists.joinToString(", ") { it.name })
                    .setAlbumTitle(metadata.album?.title)
                    .setArtworkUri(metadata.thumbnailUrl?.resize(1080, 1080)?.let(Uri::parse))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            MediaItem.Builder()
                .setMediaId(metadata.id)
                .setUri(Uri.parse(stream.url))
                .setMimeType(stream.mimeType)
                .setMediaMetadata(castMetadata)
                .build()
        }.getOrNull()

    private fun Player.indexOfMediaId(mediaId: String): Int {
        val count = mediaItemCount
        if (count <= 0) return C.INDEX_UNSET
        for (index in 0 until count) {
            val item = runCatching { getMediaItemAt(index) }.getOrNull() ?: continue
            if (item.mediaId == mediaId) return index
        }
        return C.INDEX_UNSET
    }

    private fun updatePlayerState() {
        val player = castPlayer ?: return
        _castIsBuffering.value = player.playbackState == Player.STATE_BUFFERING
        _castIsPlaying.value =
            player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        _castDuration.value = player.duration.takeUnless { it == C.TIME_UNSET } ?: 0L
    }

    private fun updateVolume() {
        val player = castPlayer ?: return
        val maxVolume = player.deviceInfo.maxVolume
        if (maxVolume > 0) _castVolume.value = player.deviceVolume.toFloat() / maxVolume
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Metrolist:CastWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(3 * 60 * 60 * 1000L)
            }
            if (wifiLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Metrolist:CastWifiLock")
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }.onFailure { Timber.e(it, "Failed to acquire Cast wake/wifi locks") }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }.onFailure { Timber.e(it, "Failed to release Cast wake/wifi locks") }
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob =
            scope.launch {
                while (isActive && _isCasting.value) {
                    castPlayer?.let { player ->
                        _castPosition.value = player.currentPosition
                        _castDuration.value = player.duration.takeUnless { it == C.TIME_UNSET } ?: 0L
                    }
                    delay(500)
                }
            }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    fun release() {
        loadJob?.cancel()
        extensionJob?.cancel()
        queueSyncJob?.cancel()
        syncResetJob?.cancel()
        stopPositionUpdates()
        releaseLocks()
        musicService.player.removeListener(localPlayerListener)
        castPlayer?.removeListener(playerListener)
        castPlayer?.release()
        castPlayer = null
    }
}
