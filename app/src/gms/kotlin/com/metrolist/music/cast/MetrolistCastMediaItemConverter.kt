package com.metrolist.music.cast

import android.net.Uri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

/**
 * Custom MediaItemConverter that properly configures Google Cast MediaInfo for TV receivers.
 *
 * The default Media3 DefaultMediaItemConverter sets contentId to mediaItem.mediaId instead of
 * the playable stream URL, causing Google Cast TV receivers (like Default Media Receiver CC1AD845)
 * to fail resolving the stream, resulting in a blank screen.
 */
@UnstableApi
class MetrolistCastMediaItemConverter : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val uri = mediaItem.localConfiguration?.uri?.toString()?.takeIf { it.isNotEmpty() }
            ?: mediaItem.mediaId
        val mimeType = mediaItem.localConfiguration?.mimeType ?: MimeTypes.AUDIO_MP4

        val castMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            mediaItem.mediaMetadata.title?.let { putString(MediaMetadata.KEY_TITLE, it.toString()) }
            mediaItem.mediaMetadata.artist?.let { putString(MediaMetadata.KEY_ARTIST, it.toString()) }
            mediaItem.mediaMetadata.albumTitle?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
            mediaItem.mediaMetadata.artworkUri?.let { addImage(WebImage(it)) }
        }

        val customData = JSONObject().apply {
            put("mediaId", mediaItem.mediaId)
            put("uri", uri)
            put("mimeType", mimeType)
        }

        val mediaInfo = MediaInfo.Builder(uri)
            .setContentUrl(uri)
            .setContentType(mimeType)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(castMetadata)
            .setCustomData(customData)
            .build()

        return MediaQueueItem.Builder(mediaInfo)
            .setAutoplay(true)
            .build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val mediaInfo = mediaQueueItem.media ?: return MediaItem.EMPTY
        val customData = mediaInfo.customData
        val mediaId = customData?.optString("mediaId")?.takeIf { it.isNotEmpty() }
            ?: mediaInfo.contentId.orEmpty()
        val uri = customData?.optString("uri")?.takeIf { it.isNotEmpty() }
            ?: mediaInfo.contentUrl ?: mediaInfo.contentId.orEmpty()
        val mimeType = customData?.optString("mimeType")?.takeIf { it.isNotEmpty() }
            ?: mediaInfo.contentType ?: MimeTypes.AUDIO_MP4

        val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
        val castMetadata = mediaInfo.metadata
        if (castMetadata != null) {
            if (castMetadata.containsKey(MediaMetadata.KEY_TITLE)) {
                metadataBuilder.setTitle(castMetadata.getString(MediaMetadata.KEY_TITLE))
            }
            if (castMetadata.containsKey(MediaMetadata.KEY_ARTIST)) {
                metadataBuilder.setArtist(castMetadata.getString(MediaMetadata.KEY_ARTIST))
            }
            if (castMetadata.containsKey(MediaMetadata.KEY_ALBUM_TITLE)) {
                metadataBuilder.setAlbumTitle(castMetadata.getString(MediaMetadata.KEY_ALBUM_TITLE))
            }
            if (castMetadata.hasImages()) {
                metadataBuilder.setArtworkUri(castMetadata.images[0].url)
            }
        }
        metadataBuilder.setMediaType(MEDIA_TYPE_MUSIC)

        val uriObj = if (uri.isNotEmpty()) Uri.parse(uri) else Uri.EMPTY
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uriObj)
            .setMimeType(mimeType)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}
