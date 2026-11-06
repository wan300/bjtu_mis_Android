@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package cn.edu.bjtu.mis.ui.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.data.repository.CourseReplayRepository
import cn.edu.bjtu.mis.model.CourseReplayPlaybackInfo
import cn.edu.bjtu.mis.model.CourseReplayStreamChoice
import cn.edu.bjtu.mis.ui.theme.AppHapticEvent
import cn.edu.bjtu.mis.ui.theme.LocalAppEffects
import cn.edu.bjtu.mis.ui.theme.LocalAppHaptics
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ReplaySeekStepMs = 10_000L
private const val ReplayControlsAutoHideDelayMs = 3_000L
private val ReplayPlaybackSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

enum class CourseReplayPlayerMode {
    Preview,
    Dedicated,
}

@Composable
fun CourseReplayNativePlayer(
    playback: CourseReplayPlaybackInfo,
    title: String?,
    subtitle: String?,
    initialState: CourseReplayPlayerResult?,
    mode: CourseReplayPlayerMode,
    repository: CourseReplayRepository,
    okHttpClient: OkHttpClient,
    modifier: Modifier = Modifier,
    requestedStreamKind: String? = null,
    onOpenDedicated: ((CourseReplayPlayerResult) -> Boolean)? = null,
    onExit: (() -> Unit)? = null,
    onStateChanged: (CourseReplayPlayerResult) -> Unit = {},
    onPlaybackMetadataChanged: (Boolean, Float) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val reduceTransparency = LocalAppEffects.current.reduceTransparency
    val activity = context.findActivity()
    val streams = playback.streams
    val initialStreamKind = initialState?.selectedStreamKind
        ?.takeIf { kind -> streams.any { it.kind == kind } }
        ?: streams.firstOrNull()?.kind.orEmpty()
    var selectedStreamKind by remember(playback.courseSchedId) { mutableStateOf(initialStreamKind) }
    var loadedStreamUrl by remember(playback.courseSchedId) { mutableStateOf<String?>(null) }
    var playbackSpeed by remember(playback.courseSchedId) {
        mutableStateOf(initialState?.playbackSpeed?.takeIf { it > 0f } ?: 1f)
    }
    var volume by remember(playback.courseSchedId) {
        mutableStateOf(initialState?.volume?.coerceIn(0f, 1f) ?: 1f)
    }
    var resizeMode by remember(playback.courseSchedId) {
        mutableStateOf(initialState?.resizeMode ?: CourseReplayResizeMode.Fit)
    }
    var zoomScale by remember(playback.courseSchedId) {
        mutableStateOf(if (initialState?.resizeMode == CourseReplayResizeMode.Zoom) 1.25f else 1f)
    }
    var playerError by remember(playback.courseSchedId) { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(initialState?.positionMs?.coerceAtLeast(0L) ?: 0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var videoAspectRatio by remember(playback.courseSchedId) { mutableStateOf(16f / 9f) }
    var audioRendererRecoveryCount by remember(playback.courseSchedId, selectedStreamKind) { mutableStateOf(0) }
    var audioTrackDisabled by remember(playback.courseSchedId, selectedStreamKind) { mutableStateOf(false) }
    var gestureMessage by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember(mode) { mutableStateOf(true) }
    val activeStream = streams.firstOrNull { it.kind == selectedStreamKind } ?: streams.firstOrNull()

    LaunchedEffect(requestedStreamKind, streams) {
        requestedStreamKind
            ?.takeIf { kind -> streams.any { it.kind == kind } }
            ?.takeIf { it != selectedStreamKind }
            ?.let { selectedStreamKind = it }
    }

    if (activeStream == null) {
        Box(
            modifier = modifier
                .background(Color.Black)
                .fillMaxWidth()
                .aspectRatio(16 / 9f),
            contentAlignment = Alignment.Center,
        ) {
            Text("No playable stream", color = Color.White)
        }
        return
    }

    val trackSelector = remember(playback.courseSchedId) { DefaultTrackSelector(context) }
    val player = remember(playback.courseSchedId, playback.referer) {
        val requestProperties = buildMap {
            playback.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(requestProperties)
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }
    val audioFocusController = remember(player, mode) {
        if (mode == CourseReplayPlayerMode.Dedicated) {
            PlaybackAudioFocusController(context, player)
        } else {
            null
        }
    }

    fun snapshot(playWhenReadyOverride: Boolean? = null): CourseReplayPlayerResult =
        CourseReplayPlayerResult(
            selectedStreamKind = activeStream.kind,
            positionMs = player.currentPosition.coerceAtLeast(positionMs).coerceAtLeast(0L),
            playWhenReady = playWhenReadyOverride ?: (player.playWhenReady || player.isPlaying),
            playbackSpeed = playbackSpeed,
            volume = volume.coerceIn(0f, 1f),
            resizeMode = resizeMode,
        )

    fun showGestureMessage(message: String) {
        gestureMessage = message
        controlsVisible = true
    }

    fun playWithFocus() {
        if (audioFocusController?.request() != false) {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            playWithFocus()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.isAudioRendererFailure()) {
                    val disableAudio = audioRendererRecoveryCount > 0
                    audioRendererRecoveryCount += 1
                    audioTrackDisabled = disableAudio
                    playerError = if (disableAudio) {
                        "Audio decode failed; continuing without audio"
                    } else {
                        "Audio decode failed; retrying playback"
                    }
                    val recoveryPositionMs = player.currentPosition.coerceAtLeast(positionMs).coerceAtLeast(0L)
                    player.setMediaItem(MediaItem.fromUri(activeStream.hlsUrl))
                    player.prepare()
                    if (recoveryPositionMs > 0L) {
                        player.seekToBounded(recoveryPositionMs)
                    }
                    player.playWhenReady = true
                } else {
                    playerError = error.toCourseReplayMessage()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width.takeIf { it > 0 } ?: return
                val height = videoSize.height.takeIf { it > 0 } ?: return
                val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                val aspectRatio = width * pixelRatio / height
                if (aspectRatio.isFinite() && aspectRatio > 0f) {
                    videoAspectRatio = aspectRatio
                }
            }
        }
        player.addListener(listener)
        onDispose {
            onStateChanged(snapshot(playWhenReadyOverride = player.playWhenReady || player.isPlaying))
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(audioFocusController) {
        onDispose { audioFocusController?.release() }
    }

    DisposableEffect(player, mode) {
        val mediaSession = if (mode == CourseReplayPlayerMode.Dedicated) {
            MediaSession.Builder(context, player).build()
        } else {
            null
        }
        onDispose { mediaSession?.release() }
    }

    DisposableEffect(player, mode) {
        if (mode != CourseReplayPlayerMode.Dedicated) {
            onDispose { }
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                        player.pause()
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    LaunchedEffect(trackSelector, audioTrackDisabled) {
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audioTrackDisabled),
        )
    }

    LaunchedEffect(player, activeStream.hlsUrl) {
        val previousStreamUrl = loadedStreamUrl
        val targetPositionMs = if (previousStreamUrl == null) {
            initialState?.positionMs?.coerceAtLeast(0L) ?: 0L
        } else {
            player.currentPosition.coerceAtLeast(0L)
        }
        val shouldPlay = if (previousStreamUrl == null) {
            initialState?.playWhenReady ?: true
        } else {
            player.playWhenReady || player.isPlaying
        }
        audioRendererRecoveryCount = 0
        audioTrackDisabled = false
        playerError = null
        player.setMediaItem(MediaItem.fromUri(activeStream.hlsUrl))
        player.prepare()
        if (targetPositionMs > 0L) {
            player.seekToBounded(targetPositionMs)
        }
        player.setPlaybackSpeed(playbackSpeed)
        player.volume = volume.coerceIn(0f, 1f)
        loadedStreamUrl = activeStream.hlsUrl
        if (shouldPlay) {
            playWithFocus()
        } else {
            player.pause()
        }
    }

    LaunchedEffect(player, playbackSpeed) {
        player.setPlaybackSpeed(playbackSpeed)
    }

    LaunchedEffect(player, volume) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    LaunchedEffect(player, activeStream.kind, playbackSpeed, volume, resizeMode) {
        while (true) {
            isPlaying = player.isPlaying
            isBuffering = player.playbackState == Player.STATE_BUFFERING
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.normalizedDurationMs()
            onPlaybackMetadataChanged(player.isPlaying, videoAspectRatio)
            onStateChanged(snapshot())
            delay(500L)
        }
    }

    LaunchedEffect(gestureMessage) {
        if (gestureMessage != null) {
            delay(900L)
            gestureMessage = null
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, mode) {
        if (mode == CourseReplayPlayerMode.Dedicated && controlsVisible && isPlaying) {
            delay(ReplayControlsAutoHideDelayMs)
            controlsVisible = false
        }
    }

    LaunchedEffect(player, playback.courseSchedId, activeStream.hlsUrl) {
        val listenUserId = playback.listenUserId ?: return@LaunchedEffect
        val timetableId = playback.timeTableId ?: return@LaunchedEffect
        val courseId = playback.courseId ?: return@LaunchedEffect
        while (true) {
            delay(60_000L)
            val seconds = player.currentPosition / 1000L
            if (seconds > 0 && player.playbackState != Player.STATE_IDLE) {
                runCatching {
                    repository.reportListen(
                        userId = listenUserId,
                        timetableId = timetableId,
                        courseId = courseId,
                        listenTimeSeconds = seconds,
                    )
                }
            }
        }
    }

    val frameModifier = if (mode == CourseReplayPlayerMode.Preview) {
        modifier
            .fillMaxWidth()
            .aspectRatio(16 / 9f)
            .heightIn(min = 180.dp)
    } else {
        modifier.fillMaxSize()
    }
    val surfaceScale = if (resizeMode == CourseReplayResizeMode.Zoom) zoomScale else 1f
    val exoPlayer = player
    val interactionSource = remember { MutableInteractionSource() }
    val previewClickModifier = if (mode == CourseReplayPlayerMode.Preview) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
        ) {
            controlsVisible = !controlsVisible
        }
    } else {
        Modifier
    }
    Box(
        modifier = frameModifier
            .background(Color.Black)
            .courseReplayGestureInput(
                enabled = mode == CourseReplayPlayerMode.Dedicated,
                activity = activity,
                context = context,
                player = player,
                showControls = { controlsVisible = true },
                showMessage = ::showGestureMessage,
                setResizeMode = {
                    resizeMode = it
                    if (it != CourseReplayResizeMode.Zoom) {
                        zoomScale = 1f
                    }
                },
                zoomScale = zoomScale,
                setZoomScale = {
                    zoomScale = it.coerceIn(1f, 2.5f)
                    resizeMode = if (zoomScale > 1.02f) CourseReplayResizeMode.Zoom else CourseReplayResizeMode.Fit
                },
            )
            .then(previewClickModifier),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = {
                (LayoutInflater.from(it).inflate(R.layout.course_replay_player_view, null) as PlayerView).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    this.player = exoPlayer
                }
            },
            update = {
                it.useController = false
                it.resizeMode = when (resizeMode) {
                    CourseReplayResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    CourseReplayResizeMode.Crop,
                    CourseReplayResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                it.keepScreenOn = mode == CourseReplayPlayerMode.Dedicated && isPlaying
                if (it.player !== exoPlayer) {
                    it.player = exoPlayer
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = surfaceScale, scaleY = surfaceScale),
        )

        if (isBuffering) {
            Text(
                text = "Loading...",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = if (reduceTransparency) 0.94f else 0.56f),
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        gestureMessage?.let { message ->
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = if (reduceTransparency) 0.94f else 0.62f),
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        playerError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(
                        Color.Black.copy(alpha = if (reduceTransparency) 0.96f else 0.72f),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        if (controlsVisible) {
            CourseReplayPlayerControls(
                mode = mode,
                title = title,
                subtitle = subtitle,
                streams = streams,
                selectedStream = activeStream,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                volume = volume,
                resizeMode = resizeMode,
                onPlayPause = ::togglePlayPause,
                onSeekBy = { delta -> player.seekByBounded(delta) },
                onSeek = { target -> player.seekToBounded(target) },
                onSpeedChange = { playbackSpeed = it },
                onVolumeChange = { volume = it.coerceIn(0f, 1f) },
                onToggleMute = { volume = if (volume <= 0f) 1f else 0f },
                onStreamChange = { selectedStreamKind = it },
                onResizeModeChange = {
                    resizeMode = it
                    if (it != CourseReplayResizeMode.Zoom) {
                        zoomScale = 1f
                    }
                },
                onOpenDedicated = onOpenDedicated?.let { open ->
                    {
                        val shouldPlay = player.playWhenReady || player.isPlaying
                        val result = snapshot(playWhenReadyOverride = shouldPlay)
                        if (open(result)) {
                            player.pause()
                        } else {
                            showGestureMessage("Cannot open full player")
                        }
                    }
                },
                onExit = onExit,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun CourseReplayPlayerControls(
    mode: CourseReplayPlayerMode,
    title: String?,
    subtitle: String?,
    streams: List<CourseReplayStreamChoice>,
    selectedStream: CourseReplayStreamChoice,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    volume: Float,
    resizeMode: CourseReplayResizeMode,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onStreamChange: (String) -> Unit,
    onResizeModeChange: (CourseReplayResizeMode) -> Unit,
    onOpenDedicated: (() -> Unit)?,
    onExit: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reduceTransparency = LocalAppEffects.current.reduceTransparency
    val haptics = LocalAppHaptics.current
    val safeDuration = durationMs.coerceAtLeast(0L)
    var isSeeking by remember(safeDuration) { mutableStateOf(false) }
    var seekPosition by remember(safeDuration) { mutableStateOf(positionMs.coerceAtMost(safeDuration).toFloat()) }
    val displayPosition = if (isSeeking) seekPosition.toLong() else positionMs.coerceAtMost(safeDuration)

    LaunchedEffect(positionMs, isSeeking, safeDuration) {
        if (!isSeeking) {
            seekPosition = positionMs.coerceIn(0L, safeDuration.coerceAtLeast(positionMs)).toFloat()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Color.Black.copy(
                    alpha = if (reduceTransparency) {
                        0.96f
                    } else if (mode == CourseReplayPlayerMode.Dedicated) {
                        0.76f
                    } else {
                        0.66f
                    },
                ),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (mode == CourseReplayPlayerMode.Dedicated) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title ?: "Course replay", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { onExit?.invoke() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatPlaybackTime(displayPosition), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(
                if (safeDuration > 0L) formatPlaybackTime(safeDuration) else "--:--",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Slider(
            value = displayPosition.coerceIn(0L, safeDuration.coerceAtLeast(displayPosition)).toFloat(),
            onValueChange = {
                isSeeking = true
                seekPosition = it
            },
            onValueChangeFinished = {
                isSeeking = false
                haptics.perform(AppHapticEvent.Snap)
                onSeek(seekPosition.toLong())
            },
            valueRange = 0f..safeDuration.coerceAtLeast(1L).toFloat(),
        )
        if (mode == CourseReplayPlayerMode.Preview && onOpenDedicated != null) {
            TextButton(
                onClick = onOpenDedicated,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Filled.Fullscreen, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text("Full player", color = Color.White)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = { onSeekBy(-ReplaySeekStepMs) }) {
                Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = Color.White)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                )
            }
            IconButton(onClick = { onSeekBy(ReplaySeekStepMs) }) {
                Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White)
            }
            PlaybackSpeedMenu(playbackSpeed, onSpeedChange)
            PlaybackStreamMenu(streams, selectedStream, onStreamChange)
            ResizeModeMenu(resizeMode, onResizeModeChange)
            IconButton(onClick = onToggleMute) {
                Icon(
                    if (volume <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Mute",
                    tint = Color.White,
                )
            }
            Slider(
                value = volume.coerceIn(0f, 1f),
                onValueChange = onVolumeChange,
                modifier = Modifier.width(110.dp),
            )
        }
    }
}

@Composable
private fun PlaybackSpeedMenu(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(formatPlaybackSpeed(currentSpeed), color = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReplayPlaybackSpeeds.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(formatPlaybackSpeed(speed)) },
                    onClick = {
                        expanded = false
                        onSpeedChange(speed)
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaybackStreamMenu(
    streams: List<CourseReplayStreamChoice>,
    selectedStream: CourseReplayStreamChoice,
    onStreamChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = streams.size > 1 }) {
            Text(selectedStream.label, color = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            streams.forEach { stream ->
                DropdownMenuItem(
                    text = { Text(stream.label) },
                    onClick = {
                        expanded = false
                        onStreamChange(stream.kind)
                    },
                )
            }
        }
    }
}

@Composable
private fun ResizeModeMenu(
    resizeMode: CourseReplayResizeMode,
    onResizeModeChange: (CourseReplayResizeMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(resizeMode.name, color = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CourseReplayResizeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name) },
                    onClick = {
                        expanded = false
                        onResizeModeChange(mode)
                    },
                )
            }
        }
    }
}

private fun Modifier.courseReplayGestureInput(
    enabled: Boolean,
    activity: Activity?,
    context: Context,
    player: ExoPlayer,
    showControls: () -> Unit,
    showMessage: (String) -> Unit,
    setResizeMode: (CourseReplayResizeMode) -> Unit,
    zoomScale: Float,
    setZoomScale: (Float) -> Unit,
): Modifier {
    if (!enabled) return this
    return this
        .pointerInput(player) {
            detectTapGestures(
                onTap = { showControls() },
                onDoubleTap = { offset ->
                    if (offset.x < size.width / 2f) {
                        player.seekByBounded(-ReplaySeekStepMs)
                        showMessage("-10s")
                    } else {
                        player.seekByBounded(ReplaySeekStepMs)
                        showMessage("+10s")
                    }
                },
            )
        }
        .pointerInput(player) {
            var dragStart = Offset.Zero
            var dragTotal = Offset.Zero
            detectDragGestures(
                onDragStart = {
                    dragStart = it
                    dragTotal = Offset.Zero
                    showControls()
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragTotal += dragAmount
                    if (abs(dragTotal.x) > abs(dragTotal.y)) {
                        val delta = (dragAmount.x / size.width.toFloat() * 75_000L).roundToInt().toLong()
                        if (delta != 0L) {
                            player.seekByBounded(delta)
                            showMessage(if (delta > 0L) "+${abs(delta) / 1000}s" else "-${abs(delta) / 1000}s")
                        }
                    } else {
                        val fraction = -dragAmount.y / size.height.toFloat()
                        if (dragStart.x < size.width / 2f) {
                            adjustWindowBrightness(activity, fraction)
                            showMessage("Brightness")
                        } else {
                            adjustSystemVolume(context, fraction)
                            showMessage("Volume")
                        }
                    }
                },
            )
        }
        .pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                val nextScale = (zoomScale * zoom).coerceIn(1f, 2.5f)
                setZoomScale(nextScale)
                setResizeMode(if (nextScale > 1.02f) CourseReplayResizeMode.Zoom else CourseReplayResizeMode.Fit)
                showMessage("${(nextScale * 100).roundToInt()}%")
            }
        }
}

private class PlaybackAudioFocusController(
    context: Context,
    private val player: ExoPlayer,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.volume = (player.volume * 0.35f).coerceIn(0f, 1f)
            AudioManager.AUDIOFOCUS_GAIN -> player.volume = player.volume.coerceAtLeast(0.35f)
        }
    }
    private var request: AudioFocusRequest? = null

    fun request(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
                .also { request = it }
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
    }
}

private fun adjustWindowBrightness(activity: Activity?, delta: Float) {
    val window = activity?.window ?: return
    val attributes = window.attributes
    val current = attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
    attributes.screenBrightness = (current + delta).coerceIn(0.05f, 1f)
    window.attributes = attributes
}

private fun adjustSystemVolume(context: Context, delta: Float) {
    if (abs(delta) < 0.012f) return
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.adjustStreamVolume(
        AudioManager.STREAM_MUSIC,
        if (delta > 0f) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
        0,
    )
}

private fun PlaybackException.isAudioRendererFailure(): Boolean {
    if (this is ExoPlaybackException && rendererName?.contains("Audio", ignoreCase = true) == true) {
        return true
    }
    return generateSequence(this as Throwable?) { it.cause }.any { throwable ->
        val className = throwable::class.java.name
        val message = throwable.message.orEmpty()
        className.contains("AudioRenderer", ignoreCase = true) ||
            message.contains("AudioRenderer", ignoreCase = true) ||
            message.contains("audio/mp4a-latm", ignoreCase = true)
    }
}

private fun PlaybackException.toCourseReplayMessage(): String =
    when {
        message?.contains("Unable to connect", ignoreCase = true) == true -> "Network connection failed"
        message?.contains("404", ignoreCase = true) == true -> "Replay stream is unavailable"
        else -> "Playback failed; try another stream"
    }

private fun ExoPlayer.normalizedDurationMs(): Long {
    val value = duration
    return if (value == C.TIME_UNSET || value < 0L) 0L else value
}

private fun ExoPlayer.seekToBounded(positionMs: Long) {
    val durationMs = normalizedDurationMs()
    val target = if (durationMs > 0L) {
        positionMs.coerceIn(0L, durationMs)
    } else {
        positionMs.coerceAtLeast(0L)
    }
    seekTo(target)
}

private fun ExoPlayer.seekByBounded(deltaMs: Long) {
    seekToBounded(currentPosition + deltaMs)
}

private fun formatPlaybackSpeed(speed: Float): String {
    val value = if (speed == speed.toInt().toFloat()) {
        String.format(Locale.US, "%.1f", speed)
    } else {
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
    return "${value}x"
}

private fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
