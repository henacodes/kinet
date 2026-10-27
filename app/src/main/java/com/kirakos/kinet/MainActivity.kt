package com.kirakos.kinet

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kirakos.kinet.ui.components.*
import com.kirakos.kinet.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private lateinit var projectRepository: ProjectRepository
    private lateinit var audioSeparator: AudioSeparator
    private val player = DualStemPlayer()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission response handled by system */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(applicationContext)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        projectRepository = ProjectRepository(applicationContext)
        audioSeparator = AudioSeparator(applicationContext)

        setContent {
            KinetTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    KinetApp(
                        modifier = Modifier.padding(innerPadding),
                        repository = projectRepository,
                        separator = audioSeparator,
                        player = player,
                        resolveFileName = { uri -> getFileNameFromUri(uri) }
                    )
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "Untitled track"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name.substringBeforeLast(".")
    }

    override fun onDestroy() {
        super.onDestroy()
        PlaybackService.stop(applicationContext)
        player.release()
        if (::audioSeparator.isInitialized) {
            audioSeparator.close()
        }
    }
}

data class SeparationCompletionInfo(
    val title: String,
    val durationSeconds: Float,
    val processingTimeSeconds: Float,
    val fileSizeBytes: Long
)

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "Unknown size"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb < 1.0) {
        val kb = bytes / 1024
        "$kb KB"
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KinetApp(
    modifier: Modifier = Modifier,
    repository: ProjectRepository,
    separator: AudioSeparator,
    player: DualStemPlayer,
    resolveFileName: (Uri) -> String
) {
    val coroutineScope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(repository.getAllProjects()) }
    var playlists by remember { mutableStateOf(repository.getAllPlaylists()) }

    var activeProject by remember { mutableStateOf<SongProject?>(null) }
    var isDeckOpen by remember { mutableStateOf(false) }

    var isSeparating by remember { mutableStateOf(false) }
    var separationProgress by remember { mutableStateOf("Ready") }
    var separationError by remember { mutableStateOf<String?>(null) }
    var separationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var completionNotice by remember { mutableStateOf<SeparationCompletionInfo?>(null) }
    var projectToExport by remember { mutableStateOf<SongProject?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportMeta by remember { mutableStateOf<ExtractedMediaMetadata?>(null) }
    var showImportModeSheet by remember { mutableStateOf(false) }

    val isPlaying by player.isPlaying.collectAsState()
    val positionMs by player.positionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(isPlaying, activeProject) {
        if (isPlaying && activeProject != null) {
            PlaybackService.start(context, player)
        }
    }

    fun startImport(uri: Uri, meta: ExtractedMediaMetadata, isFourStem: Boolean) {
        val fallbackTitle = resolveFileName(uri)
        val finalTitle = meta.title.ifBlank { fallbackTitle }
        isSeparating = true
        separationProgress = "Preparing audio..."
        completionNotice = null
        separationJob = coroutineScope.launch {
            try {
                val result = separator.separate(uri, isFourStem = isFourStem) { status ->
                    separationProgress = status
                }
                val saved = repository.saveProject(
                    title = finalTitle,
                    durationSeconds = result.durationSeconds,
                    tempVocals = result.vocalsFile,
                    tempAccomp = result.accompanimentFile,
                    tempDrums = result.drumsFile,
                    tempBass = result.bassFile,
                    tempOther = result.otherFile,
                    artist = meta.artist,
                    album = meta.album,
                    genre = meta.genre,
                    year = meta.year,
                    bitrateKbps = meta.bitrateKbps,
                    sampleRate = meta.sampleRate,
                    originalFormat = meta.mimeType,
                    originalFileSizeBytes = meta.fileSizeBytes
                )
                projects = repository.getAllProjects()
                completionNotice = SeparationCompletionInfo(
                    title = finalTitle,
                    durationSeconds = result.durationSeconds,
                    processingTimeSeconds = result.processingTimeSeconds,
                    fileSizeBytes = result.fileSizeBytes
                )
                activeProject = saved
                player.load(saved)
                player.play()
                PlaybackService.start(context, player)
                isDeckOpen = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                separationProgress = "Separation canceled"
            } catch (t: Throwable) {
                val msg = t.localizedMessage ?: t.toString()
                separationProgress = "Error: $msg"
                separationError = "Audio separation could not be completed:\n\n$msg\n\nDetails:\n${t.stackTraceToString().take(1200)}"
            } finally {
                isSeparating = false
                separationJob = null
            }
        }
    }

    fun startUpgrade(project: SongProject) {
        isSeparating = true
        separationProgress = "Reconstructing stereo mix..."
        completionNotice = null
        separationJob = coroutineScope.launch {
            try {
                val upgraded = repository.upgradeProjectToFourStems(project, separator) { status ->
                    separationProgress = status
                }
                projects = repository.getAllProjects()
                if (activeProject?.id == project.id) {
                    val wasPlaying = player.isPlaying.value
                    val currentPos = player.positionMs.value
                    activeProject = upgraded
                    player.load(upgraded)
                    player.seekTo(currentPos)
                    if (wasPlaying) player.play()
                }
                completionNotice = SeparationCompletionInfo(
                    title = upgraded.title,
                    durationSeconds = upgraded.durationSeconds,
                    processingTimeSeconds = 0f,
                    fileSizeBytes = upgraded.originalFileSizeBytes
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                separationProgress = "Upgrade canceled"
            } catch (t: Throwable) {
                val msg = t.localizedMessage ?: t.toString()
                separationProgress = "Error: $msg"
                separationError = "Upgrade could not be completed:\n\n$msg\n\nDetails:\n${t.stackTraceToString().take(1200)}"
            } finally {
                isSeparating = false
                separationJob = null
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fallbackTitle = resolveFileName(uri)
            val meta = AudioMetadataExtractor.extract(context, uri, fallbackTitle)
            pendingImportUri = uri
            pendingImportMeta = meta
            showImportModeSheet = true
        }
    }

    // Android Back Handler: Close full deck without pausing playback
    BackHandler(enabled = isDeckOpen) {
        isDeckOpen = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isDeckOpen && activeProject != null) {
            StemPlayerScreen(
                project = activeProject!!,
                player = player,
                onBack = {
                    // Navigate back to library while audio keeps playing
                    isDeckOpen = false
                },
                onExport = {
                    projectToExport = activeProject
                },
                onUpgrade = {
                    activeProject?.let { startUpgrade(it) }
                }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                SongLibraryScreen(
                    projects = projects,
                    playlists = playlists,
                    activePlayingProject = activeProject,
                    isPlaying = isPlaying,
                    onSelectProject = { proj ->
                        if (isSeparating) return@SongLibraryScreen
                        if (activeProject?.id != proj.id) {
                            activeProject = proj
                            player.load(proj)
                            player.play()
                            PlaybackService.start(context, player)
                        }
                        isDeckOpen = true
                    },
                    onDeleteProject = { id ->
                        if (isSeparating) return@SongLibraryScreen
                        if (activeProject?.id == id) {
                            player.clearProject()
                            activeProject = null
                            PlaybackService.stop(context)
                        }
                        repository.deleteProject(id)
                        projects = repository.getAllProjects()
                        playlists = repository.getAllPlaylists()
                    },
                    onRenameProject = { id, newName ->
                        if (isSeparating) return@SongLibraryScreen
                        repository.renameProject(id, newName)
                        projects = repository.getAllProjects()
                        if (activeProject?.id == id) {
                            activeProject = projects.firstOrNull { it.id == id }
                        }
                    },
                    onToggleFavorite = { id ->
                        if (isSeparating) return@SongLibraryScreen
                        repository.toggleFavorite(id)
                        projects = repository.getAllProjects()
                        if (activeProject?.id == id) {
                            activeProject = projects.firstOrNull { it.id == id }
                        }
                    },
                    onUpdateCategory = { id, cat ->
                        if (isSeparating) return@SongLibraryScreen
                        repository.updateProjectCategory(id, cat)
                        projects = repository.getAllProjects()
                        if (activeProject?.id == id) {
                            activeProject = projects.firstOrNull { it.id == id }
                        }
                    },
                    onCreatePlaylist = { name ->
                        if (isSeparating) return@SongLibraryScreen
                        repository.createPlaylist(name)
                        playlists = repository.getAllPlaylists()
                    },
                    onDeletePlaylist = { plId ->
                        if (isSeparating) return@SongLibraryScreen
                        repository.deletePlaylist(plId)
                        playlists = repository.getAllPlaylists()
                    },
                    onAddTrackToPlaylist = { plId, trackId ->
                        if (isSeparating) return@SongLibraryScreen
                        repository.addTrackToPlaylist(plId, trackId)
                        playlists = repository.getAllPlaylists()
                    },
                    onRemoveTrackFromPlaylist = { plId, trackId ->
                        if (isSeparating) return@SongLibraryScreen
                        repository.removeTrackFromPlaylist(plId, trackId)
                        playlists = repository.getAllPlaylists()
                    },
                    onImportClick = { filePicker.launch("audio/*") },
                    onExportProject = { proj -> projectToExport = proj },
                    onUpgradeProject = { proj -> startUpgrade(proj) },
                    onCancelSeparation = {
                        separationJob?.cancel()
                        separationJob = null
                        isSeparating = false
                        separationProgress = "Ready"
                    },
                    isSeparating = isSeparating,
                    separationProgress = separationProgress,
                    completionNotice = completionNotice,
                    onDismissCompletionNotice = { completionNotice = null }
                )

                // Sleek Floating Capsule "Now Playing" Bar (No shadows)
                if (activeProject != null) {
                    MiniNowPlayingBar(
                        project = activeProject!!,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        enabled = !isSeparating,
                        onTogglePlayPause = { if (!isSeparating) player.togglePlayPause() },
                        onClose = {
                            player.clearProject()
                            activeProject = null
                            PlaybackService.stop(context)
                        },
                        onBarClick = { if (!isSeparating) isDeckOpen = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                    )
                }
            }
        }

        if (separationError != null) {
            val context = androidx.compose.ui.platform.LocalContext.current
            AlertDialog(
                onDismissRequest = { separationError = null },
                title = {
                    Text(
                        text = "Separation failed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Developer Contact Banner
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Developer: @${CrashReporter.DEVELOPER_TELEGRAM}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    CrashReporter.sendCrashToTelegram(
                                        context,
                                        separationError ?: "Separation error"
                                    )
                                }
                            )
                            Text(
                                text = CrashReporter.DEVELOPER_EMAIL,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable {
                                    CrashReporter.openEmail(context, "Separation error", separationError ?: "")
                                }
                            )
                        }

                        // Scrollable, Selectable & Copiable Stack Trace
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = separationError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                CrashReporter.copyToClipboard(
                                    context,
                                    separationError ?: "",
                                    "Separation Error Log"
                                )
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Copy log", color = MaterialTheme.colorScheme.primary)
                        }

                        Button(
                            onClick = {
                                CrashReporter.sendCrashToTelegram(
                                    context,
                                    separationError ?: "Unknown separation error"
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            elevation = null
                        ) {
                            Text("Send to Telegram", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { separationError = null },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 0.dp
            )
        }

        projectToExport?.let { proj ->
            ExportStemsSheet(
                project = proj,
                onDismiss = { projectToExport = null }
            )
        }

        val currentPendingUri = pendingImportUri
        val currentPendingMeta = pendingImportMeta
        if (showImportModeSheet && currentPendingUri != null && currentPendingMeta != null) {
            val uri = currentPendingUri
            val meta = currentPendingMeta
            val title = meta.title.ifBlank { resolveFileName(uri) }

            ModalBottomSheet(
                onDismissRequest = {
                    showImportModeSheet = false
                    pendingImportUri = null
                    pendingImportMeta = null
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Separate stems",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = title + if (meta.artist.isNotBlank()) " • ${meta.artist}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Card 1: 2 Stems (Fast)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showImportModeSheet = false
                                pendingImportUri = null
                                pendingImportMeta = null
                                startImport(uri, meta, isFourStem = false)
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "2",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "2 stems",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = "Fast",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Vocals + Instrumental · Single-pass split for karaoke & practice",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Card 2: 4 Stems (Studio)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showImportModeSheet = false
                                pendingImportUri = null
                                pendingImportMeta = null
                                startImport(uri, meta, isFourStem = true)
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(HardwareCarbon),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "4",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "4 stems",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = "Deep · Studio split",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Vocals, Drums, Bass, Other · Multi-pass studio isolation",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // In-deck separation progress modal dialog
        if (isSeparating && isDeckOpen) {
            Dialog(onDismissRequest = { /* cannot dismiss while running */ }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = separationProgress,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Mini "Now Playing" Bar docked at the bottom of the Library Screen.
 * Calm, flat separation via surface tint and hairline border — no shadows.
 */
@Composable
fun MiniNowPlayingBar(
    project: SongProject,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean = true,
    onTogglePlayPause: () -> Unit,
    onClose: () -> Unit,
    onBarClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val infiniteTransition = rememberInfiniteTransition(label = "miniReel")
    val animatedAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "miniReelAngle"
    )
    val displayAngle = if (isPlaying) animatedAngle else 0f

    val outlineColor = MaterialTheme.colorScheme.outline
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val capsuleShape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(capsuleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), capsuleShape)
            .clickable(enabled = enabled) { onBarClick() }
            .then(if (!enabled) Modifier.alpha(0.6f) else Modifier)
    ) {
        // Subtle top progress line
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
        ) {
            val totalW = size.width
            val barH = size.height
            drawRect(
                color = outlineColor.copy(alpha = 0.4f),
                size = Size(totalW, barH)
            )
            drawRect(
                color = primaryColor,
                size = Size(totalW * progress, barH)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini Vinyl Disc
                Canvas(modifier = Modifier.size(34.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val r = size.minDimension / 2f - 1.dp.toPx()

                    drawCircle(color = surfaceVariantColor, radius = r, center = center)
                    drawCircle(color = HardwareCarbon, radius = r * 0.85f, center = center)

                    rotate(degrees = displayAngle, pivot = center) {
                        for (i in 0 until 3) {
                            val rad = Math.toRadians((i * 120.0))
                            val spokeLen = r * 0.55f
                            val sx = (cos(rad) * spokeLen).toFloat()
                            val sy = (sin(rad) * spokeLen).toFloat()
                            drawLine(
                                color = CreamOffWhite.copy(alpha = 0.45f),
                                start = center,
                                end = Offset(center.x + sx, center.y + sy),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }
                    drawCircle(color = WarmTerracotta, radius = r * 0.28f, center = center)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Stem deck • ${formatTime(positionMs)} / ${formatTime(durationMs)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Play / Pause Circle
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(HardwareCarbon)
                        .clickable(enabled = enabled) { onTogglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying) {
                        PauseIcon(color = CreamOffWhite, size = 12.dp)
                    } else {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = CreamOffWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Close / Dismiss Player Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close player",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Song Library Screen adhering strictly to Robi UI taste:
 * - Extralight/light display headings
 * - Sentence case everywhere
 * - No drop shadows on buttons, cards, or inputs
 * - Flat surfaces separated by border and surface tint
 * - No play button or three dots on cards; actions revealed on press
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongLibraryScreen(
    projects: List<SongProject>,
    playlists: List<Playlist>,
    activePlayingProject: SongProject?,
    isPlaying: Boolean,
    onSelectProject: (SongProject) -> Unit,
    onDeleteProject: (String) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onUpdateCategory: (String, String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onAddTrackToPlaylist: (String, String) -> Unit,
    onRemoveTrackFromPlaylist: (String, String) -> Unit,
    onExportProject: (SongProject) -> Unit,
    onImportClick: () -> Unit,
    onUpgradeProject: (SongProject) -> Unit = {},
    onCancelSeparation: () -> Unit,
    isSeparating: Boolean,
    separationProgress: String,
    completionNotice: SeparationCompletionInfo?,
    onDismissCompletionNotice: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "FAVORITES", "PLAYLIST:<id>", "CAT:<name>"

    var projectForOptions by remember { mutableStateOf<SongProject?>(null) }
    var projectToRename by remember { mutableStateOf<SongProject?>(null) }
    var projectToDelete by remember { mutableStateOf<SongProject?>(null) }
    var projectToUpgrade by remember { mutableStateOf<SongProject?>(null) }
    var projectForPlaylist by remember { mutableStateOf<SongProject?>(null) }
    var projectForCategory by remember { mutableStateOf<SongProject?>(null) }
    var projectForDetails by remember { mutableStateOf<SongProject?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showCancelConfirmation by remember { mutableStateOf(false) }

    val allCategories = remember(projects) {
        val cats = projects.map { it.category.trim() }
            .filter { it.isNotBlank() && !it.equals("General", ignoreCase = true) }
            .toSet()
        cats.sorted()
    }

    val filteredProjects = remember(projects, playlists, searchQuery, selectedFilter) {
        var list = projects

        if (selectedFilter == "FAVORITES") {
            list = list.filter { it.isFavorite }
        } else if (selectedFilter.startsWith("CAT:")) {
            val catName = selectedFilter.removePrefix("CAT:")
            list = list.filter { it.category.equals(catName, ignoreCase = true) }
        } else if (selectedFilter.startsWith("PLAYLIST:")) {
            val plId = selectedFilter.removePrefix("PLAYLIST:")
            val pl = playlists.firstOrNull { it.id == plId }
            list = if (pl != null) list.filter { pl.trackIds.contains(it.id) } else emptyList()
        }

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter { it.title.lowercase().contains(q) || it.category.lowercase().contains(q) }
        }

        list.sortedByDescending { it.createdAt }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // --- Header (Pure iconic brandmark & paired action icon buttons) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KinetLogo(
                modifier = Modifier
                    .size(38.dp, 34.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showAboutDialog = true }
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Separate New Track Icon Button (Left of Info)
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = if (isSeparating) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.size(38.dp)
                ) {
                    IconButton(
                        onClick = if (isSeparating) { { showCancelConfirmation = true } } else onImportClick,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = if (isSeparating) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = if (isSeparating) "Cancel separation" else "Separate new track",
                            tint = if (isSeparating) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Info / About App Icon Button (Right of Separate New)
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.size(38.dp)
                ) {
                    IconButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "About Kinet",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Separation Progress ---
        if (isSeparating) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = separationProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // --- Completion Notification Card ---
        if (completionNotice != null && !isSeparating) {
            val notice = completionNotice
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "Separation complete",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val durMin = (notice.durationSeconds.toInt()) / 60
                            val durSec = (notice.durationSeconds.toInt()) % 60
                            val durStr = String.format("%d:%02d", durMin, durSec)
                            val sizeStr = formatFileSize(notice.fileSizeBytes)
                            val timeStr = String.format(Locale.US, "%.1fs", notice.processingTimeSeconds)

                            Text(
                                text = "$durStr • $sizeStr • processed in $timeStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                projects.firstOrNull { it.title == notice.title }?.let { onExportProject(it) }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Export", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        }

                        IconButton(
                            onClick = onDismissCompletionNotice,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss notice",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- Search Bar (Flat, surfaceVariant tint, no shadow) ---
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    text = "Search tracks...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // --- Organization Filter Chips (Sentence case, flat pills, no shadow) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CleanFilterChip(
                label = "All stems",
                isSelected = selectedFilter == "ALL",
                onClick = { selectedFilter = "ALL" }
            )

            CleanFilterChip(
                label = "Favorites",
                isSelected = selectedFilter == "FAVORITES",
                onClick = { selectedFilter = "FAVORITES" }
            )

            allCategories.forEach { cat ->
                CleanFilterChip(
                    label = cat,
                    isSelected = selectedFilter == "CAT:$cat",
                    onClick = { selectedFilter = "CAT:$cat" }
                )
            }

            playlists.forEach { pl ->
                CleanFilterChip(
                    label = pl.name,
                    isSelected = selectedFilter == "PLAYLIST:${pl.id}",
                    onClick = { selectedFilter = "PLAYLIST:${pl.id}" }
                )
            }

            // New Playlist Action Chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showCreatePlaylistDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "New playlist",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- Projects List ---
        if (filteredProjects.isEmpty() && !isSeparating) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No tracks found",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty()) "Try a different search query." else "Import a song to isolate vocals and accompaniment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredProjects, key = { it.id }) { proj ->
                    val isCurrentlyActive = activePlayingProject?.id == proj.id
                    CleanProjectCard(
                        project = proj,
                        isCurrent = isCurrentlyActive,
                        isPlaying = isCurrentlyActive && isPlaying,
                        enabled = !isSeparating,
                        onClick = { if (!isSeparating) onSelectProject(proj) },
                        onLongClick = { if (!isSeparating) projectForOptions = proj }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(76.dp))
                }
            }
        }
    }

    // --- Options Bottom Sheet (Triggered on press / long-click, no three dots) ---
    projectForOptions?.let { proj ->
        ModalBottomSheet(
            onDismissRequest = { projectForOptions = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = proj.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Toggle Favorite
                ListItem(
                    headlineContent = { Text(if (proj.isFavorite) "Remove from favorites" else "Add to favorites", fontSize = 14.sp) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = if (proj.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.clickable {
                        onToggleFavorite(proj.id)
                        projectForOptions = null
                    }
                )

                // Add to Playlist
                ListItem(
                    headlineContent = { Text("Add to playlist", fontSize = 14.sp) },
                    leadingContent = {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    },
                    modifier = Modifier.clickable {
                        val current = proj
                        projectForOptions = null
                        projectForPlaylist = current
                    }
                )

                // Set Category
                ListItem(
                    headlineContent = { Text("Set category", fontSize = 14.sp) },
                    leadingContent = {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    },
                    modifier = Modifier.clickable {
                        val current = proj
                        projectForOptions = null
                        projectForCategory = current
                    }
                )

                // Rename Track
                ListItem(
                    headlineContent = { Text("Rename track", fontSize = 14.sp) },
                    leadingContent = {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    },
                    modifier = Modifier.clickable {
                        val current = proj
                        projectForOptions = null
                        projectToRename = current
                    }
                )

                // Upgrade to 4 Stems (if 2 stems)
                if (!proj.isFourStem) {
                    ListItem(
                        headlineContent = { Text("Upgrade to 4 stems", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text("Separate drums, bass, and other instruments", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) },
                        leadingContent = {
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        },
                        modifier = Modifier.clickable {
                            val current = proj
                            projectForOptions = null
                            projectToUpgrade = current
                        }
                    )
                }

                // Export Stems
                ListItem(
                    headlineContent = { Text("Export stems", fontSize = 14.sp) },
                    supportingContent = { Text("Save or share M4A / WAV", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingContent = {
                        Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    },
                    modifier = Modifier.clickable {
                        val current = proj
                        projectForOptions = null
                        onExportProject(current)
                    }
                )

                // Track Details
                ListItem(
                    headlineContent = { Text("Track details", fontSize = 14.sp) },
                    supportingContent = { Text("Audio specs & metadata", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingContent = {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    },
                    modifier = Modifier.clickable {
                        val current = proj
                        projectForOptions = null
                        projectForDetails = current
                    }
                )

                // Delete Track
                ListItem(
                    headlineContent = { Text("Delete track", fontSize = 14.sp, color = MaterialTheme.colorScheme.error) },
                    leadingContent = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    },
                    modifier = Modifier.clickable {
                        val current = proj
                        projectForOptions = null
                        projectToDelete = current
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // --- Dialogs ---

    // Upgrade to 4 Stems Confirmation Dialog
    if (projectToUpgrade != null) {
        val proj = projectToUpgrade!!
        AlertDialog(
            onDismissRequest = { projectToUpgrade = null },
            title = {
                Text(
                    text = "Upgrade to 4 stems",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Extract drums, bass, and other instruments using deep on-device separation for \"${proj.title}\". This multi-pass process runs locally and takes more processing time than the quick split.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val current = projectToUpgrade
                        projectToUpgrade = null
                        if (current != null) {
                            onUpgradeProject(current)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Upgrade")
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToUpgrade = null }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Create Playlist Dialog
    if (showCreatePlaylistDialog) {
        var playlistName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = {
                Text(
                    text = "Create playlist",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)
                )
            },
            text = {
                TextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            onCreatePlaylist(playlistName)
                        }
                        showCreatePlaylistDialog = false
                    }
                ) {
                    Text("Create", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Add to Playlist Dialog
    projectForPlaylist?.let { proj ->
        AlertDialog(
            onDismissRequest = { projectForPlaylist = null },
            title = {
                Text(
                    text = "Playlists",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Assign \"${proj.title}\" to:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (playlists.isEmpty()) {
                        Text(
                            text = "No playlists created yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        playlists.forEach { pl ->
                            val contains = pl.trackIds.contains(proj.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (contains) {
                                            onRemoveTrackFromPlaylist(pl.id, proj.id)
                                        } else {
                                            onAddTrackToPlaylist(pl.id, proj.id)
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = pl.name,
                                    fontSize = 14.sp,
                                    color = if (contains) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (contains) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Added",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), thickness = 0.5.dp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { projectForPlaylist = null }) {
                    Text("Done", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Set Category Dialog
    projectForCategory?.let { proj ->
        val presetCategories = listOf("General", "Practice", "Acapella", "Backing track", "Live")
        var customCategory by remember { mutableStateOf(proj.category) }

        AlertDialog(
            onDismissRequest = { projectForCategory = null },
            title = {
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presetCategories.forEach { preset ->
                            val isSel = customCategory.equals(preset, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { customCategory = preset }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = preset,
                                    fontSize = 12.sp,
                                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = customCategory,
                        onValueChange = { customCategory = it },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (customCategory.isNotBlank()) {
                            onUpdateCategory(proj.id, customCategory)
                        }
                        projectForCategory = null
                    }
                ) {
                    Text("Save", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectForCategory = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Rename Dialog
    projectToRename?.let { proj ->
        var newTitle by remember { mutableStateOf(proj.title) }
        AlertDialog(
            onDismissRequest = { projectToRename = null },
            title = {
                Text(
                    text = "Rename track",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)
                )
            },
            text = {
                TextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newTitle.isNotBlank()) {
                            onRenameProject(proj.id, newTitle)
                        }
                        projectToRename = null
                    }
                ) {
                    Text("Save", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToRename = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Delete Dialog
    projectToDelete?.let { proj ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = {
                Text(
                    text = "Delete track",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${proj.title}\"? Both separated stems will be permanently removed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProject(proj.id)
                        projectToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = {
                Text(
                    text = "Cancel separation?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to stop? Current progress will be discarded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirmation = false
                        onCancelSeparation()
                    }
                ) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmation = false }) {
                    Text("Continue", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 0.dp
        )
    }

    if (showAboutDialog) {
        AboutAppDialog(onDismiss = { showAboutDialog = false })
    }

    projectForDetails?.let { proj ->
        TrackMetadataSheet(
            project = proj,
            onDismiss = { projectForDetails = null }
        )
    }
}

/**
 * About Kinet Card dialog adhering strictly to Robi UI taste:
 * - Warm stone palette
 * - Light/extralight typography
 * - Flat surfaces with hairline border, zero drop shadows
 * - Sentence case everywhere
 * - Direct links to Kirakos's Telegram, email, and portfolio
 */
@Composable
fun AboutAppDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KinetLogo(modifier = Modifier.size(36.dp, 34.dp))
                Column {
                    Text(
                        text = "About Kinet",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Light,
                            fontSize = 22.sp,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Version 0.2.0",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "On-device 4-stem studio isolation with independent channel strips, pitch shifting, A/B looping, and chord detection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                // Creator & Social Profile Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Creator",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = CrashReporter.DEVELOPER_NAME,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        thickness = 0.5.dp
                    )

                    // Telegram Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { CrashReporter.sendCrashToTelegram(context, "Hi Kirakos, ") },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Telegram",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "@${CrashReporter.DEVELOPER_TELEGRAM}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Email Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { CrashReporter.openEmail(context) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Email",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CrashReporter.DEVELOPER_EMAIL,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Portfolio Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { CrashReporter.openPortfolio(context) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Portfolio",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "kirakos.netlify.app",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Close", color = MaterialTheme.colorScheme.primary)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp
    )
}

/**
 * Filter chip separating purely by tint and border — no shadows.
 */
@Composable
fun CleanFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Clean, flat track card:
 * - No play button
 * - No 3-dots button (options revealed on press/long-click)
 * - No elevation shadows (separates by border and surface tint)
 * - Lightweight typography
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CleanProjectCard(
    project: SongProject,
    isCurrent: Boolean,
    isPlaying: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val durationStr = remember(project.durationSeconds) {
        val m = (project.durationSeconds / 60).toInt()
        val s = (project.durationSeconds % 60).toInt()
        String.format(Locale.US, "%d:%02d", m, s)
    }
    val dateStr = remember(project.createdAt) {
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
        sdf.format(Date(project.createdAt))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (isCurrent) 1.dp else 0.5.dp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .then(if (!enabled) Modifier.alpha(0.55f) else Modifier)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                        fontSize = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val subtitleText = if (project.artist.isNotBlank()) "$durationStr • ${project.artist}" else "$durationStr • $dateStr"
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (project.isFourStem) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (project.isFourStem) "4 stems" else "2 stems",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (project.isFourStem) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (project.category.isNotBlank() && !project.category.equals("General", ignoreCase = true)) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• ${project.category}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Right-side quiet indicators (no action buttons on the card)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    PlayingWaveformEqualizer(
                        isPlaying = isPlaying,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                if (project.isFavorite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

/**
 * Full Stem Deck Screen:
 * - Song title has its own full row (no horizontal collision with chord badge)
 * - Explicit chords toggle [Chords on / off]
 * - Active chord badge only shown when chords are enabled AND a chord is detected
 * - No placeholder "Chords" text when empty
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StemPlayerScreen(
    project: SongProject,
    player: DualStemPlayer,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onUpgrade: () -> Unit = {}
) {
    val isPlaying by player.isPlaying.collectAsState()
    val positionMs by player.positionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()
    val waveform by player.waveform.collectAsState()

    val vocVol by player.vocalsVolume.collectAsState()
    val drumVol by player.drumsVolume.collectAsState()
    val bassVol by player.bassVolume.collectAsState()
    val otherVol by player.otherVolume.collectAsState()
    val accVol by player.accompVolume.collectAsState()

    val isVocMuted by player.isVocalsMuted.collectAsState()
    val isDrumMuted by player.isDrumsMuted.collectAsState()
    val isBassMuted by player.isBassMuted.collectAsState()
    val isOtherMuted by player.isOtherMuted.collectAsState()
    val isAccMuted by player.isAccompMuted.collectAsState()

    val isVocSolo by player.isVocalsSolo.collectAsState()
    val isDrumSolo by player.isDrumsSolo.collectAsState()
    val isBassSolo by player.isBassSolo.collectAsState()
    val isOtherSolo by player.isOtherSolo.collectAsState()

    val pitchSt by player.pitchSemitones.collectAsState()
    val loopA by player.loopStartMs.collectAsState()
    val loopB by player.loopEndMs.collectAsState()
    val isLooping by player.isLooping.collectAsState()
    val chordSegments by player.chordSegments.collectAsState()

    var showChords by remember { mutableStateOf(true) }
    var showChordInspector by remember { mutableStateOf(false) }
    var showMetadataSheet by remember { mutableStateOf(false) }

    val currentChord = remember(chordSegments, positionMs, pitchSt) {
        val seg = chordSegments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
        seg?.chord?.let { ChordTransposer.transpose(it, pitchSt) } ?: ""
    }

    val uniqueChords = remember(chordSegments, pitchSt) {
        chordSegments
            .map { ChordTransposer.transpose(it.chord, pitchSt) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Kinet",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (project.isFourStem) "4-stem studio deck" else "Analog stem deck",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!project.isFourStem) {
                    var showUpgradeConfirmation by remember { mutableStateOf(false) }

                    FilledTonalButton(
                        onClick = { showUpgradeConfirmation = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        elevation = null,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("4 stems", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }

                    if (showUpgradeConfirmation) {
                        AlertDialog(
                            onDismissRequest = { showUpgradeConfirmation = false },
                            title = {
                                Text(
                                    text = "Upgrade to 4 stems",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            text = {
                                Text(
                                    text = "Extract drums, bass, and other instruments using deep on-device separation. This multi-pass process will run locally and takes more processing time than the quick split.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showUpgradeConfirmation = false
                                        onUpgrade()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text("Upgrade")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showUpgradeConfirmation = false }) {
                                    Text("Cancel")
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onExport,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    elevation = null,
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Export stems",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                // Dot grid matrix accent from screenshot2.png
                DotGridMatrix(
                    rows = 3,
                    cols = 4,
                    spacing = 5.dp,
                    dotRadius = 1.2.dp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 1. Vinyl Turntable with DJ Scratching
        VinylTurntable(
            isPlaying = isPlaying,
            progress = progress,
            onScratchStart = { player.startScratch() },
            onScratch = { deltaDeg -> player.scratch(deltaDeg) },
            onScratchEnd = { player.endScratch() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Song Title & Artist
        Text(
            text = project.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Light,
                fontSize = 22.sp,
                letterSpacing = (-0.3).sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        if (project.artist.isNotBlank()) {
            Text(
                text = project.artist + if (project.album.isNotBlank()) " • ${project.album}" else "",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Subtitle row: Audio details + Specs button + Chords Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showMetadataSheet = true }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = "Stereo 44.1 kHz • ${if (pitchSt != 0) (if (pitchSt > 0) "+$pitchSt st" else "$pitchSt st") else "Standard pitch"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Track specs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(13.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Live current chord text: pure text [ Chord ], left of toggler, no card
                if (showChords && currentChord.isNotBlank()) {
                    Text(
                        text = "[ $currentChord ]",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { showChordInspector = true }
                            .padding(end = 8.dp)
                    )
                }

                // Explicit Chords Toggle Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (showChords) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            0.5.dp,
                            if (showChords) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { showChords = !showChords }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (showChords) "Chords on" else "Chords off",
                        fontSize = 11.sp,
                        color = if (showChords) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // List all distinct chords used in the song
        if (uniqueChords.isNotEmpty() && showChords) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chords in track:",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    uniqueChords.forEach { ch ->
                        val isCurrent = currentChord == ch
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable {
                                val firstSeg = chordSegments.firstOrNull { ChordTransposer.transpose(it.chord, pitchSt) == ch }
                                firstSeg?.let { player.seekTo(it.startMs) }
                            }
                        ) {
                            Text(
                                text = ch,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                ),
                                color = if (isCurrent) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Waveform Scrubber
        WaveformLoopScrubber(
            waveform = waveform,
            positionMs = positionMs,
            durationMs = durationMs,
            loopStartMs = loopA,
            loopEndMs = loopB,
            isLooping = isLooping,
            chordSegments = chordSegments,
            pitchSemitones = pitchSt,
            showChords = showChords,
            onSeekTo = { player.seekTo(it) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Pitch 3D Roller & A/B Loop Bay
        PitchAndLoopBay(
            pitchSemitones = pitchSt,
            onPitchChange = { player.setPitchSemitones(it) },
            loopStartMs = loopA,
            loopEndMs = loopB,
            isLooping = isLooping,
            onSetLoopA = { player.setLoopPointA() },
            onSetLoopB = { player.setLoopPointB() },
            onClearLoop = { player.clearLoop() },
            onJumpToA = { player.jumpToLoopStart() }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 5. Modular Stem Faders Bay
        StemFadersBay(
            isFourStem = project.isFourStem,
            vocalsVolume = vocVol,
            isVocalsMuted = isVocMuted,
            isVocalsSolo = isVocSolo,
            onVocalsVolumeChange = { player.setVocalsVolume(it) },
            onVocalsMuteToggle = { player.toggleVocalsMute() },
            onVocalsSoloToggle = { player.toggleVocalsSolo() },
            drumsVolume = drumVol,
            isDrumsMuted = isDrumMuted,
            isDrumsSolo = isDrumSolo,
            onDrumsVolumeChange = { player.setDrumsVolume(it) },
            onDrumsMuteToggle = { player.toggleDrumsMute() },
            onDrumsSoloToggle = { player.toggleDrumsSolo() },
            bassVolume = bassVol,
            isBassMuted = isBassMuted,
            isBassSolo = isBassSolo,
            onBassVolumeChange = { player.setBassVolume(it) },
            onBassMuteToggle = { player.toggleBassMute() },
            onBassSoloToggle = { player.toggleBassSolo() },
            otherVolume = otherVol,
            isOtherMuted = isOtherMuted,
            isOtherSolo = isOtherSolo,
            onOtherVolumeChange = { player.setOtherVolume(it) },
            onOtherMuteToggle = { player.toggleOtherMute() },
            onOtherSoloToggle = { player.toggleOtherSolo() },
            accompVolume = accVol,
            isAccompMuted = isAccMuted,
            onAccompVolumeChange = { player.setAccompanimentVolume(it) },
            onAccompMuteToggle = { player.toggleAccompanimentMute() }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 6. Transport Controls (Play / Pause / +/-5s)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = { player.seekRelative(-5000L) },
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = null
            ) {
                Text("-5s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }

            Spacer(modifier = Modifier.width(22.dp))

            // Main Play/Pause Knob
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(HardwareCarbon)
                    .clickable { player.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    PauseIcon(color = CreamOffWhite, size = 18.dp)
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = CreamOffWhite,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(22.dp))

            FilledTonalButton(
                onClick = { player.seekRelative(5000L) },
                shape = CircleShape,
                modifier = Modifier.size(46.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = null
            ) {
                Text("+5s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }

    // Chord Progression Inspector Sheet
    if (showChordInspector) {
        ModalBottomSheet(
            onDismissRequest = { showChordInspector = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Harmonic progression",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${chordSegments.size} measures detected • Tap to seek",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = { player.reanalyzeChords(project) }
                    ) {
                        Text("Re-analyze", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }

                // Summary Palette of All Chords Used in Track
                if (uniqueChords.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "All chords in this song (${uniqueChords.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uniqueChords.forEach { ch ->
                            val count = chordSegments.count { ChordTransposer.transpose(it.chord, pitchSt) == ch }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = ch,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${count}×",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    items(chordSegments) { seg ->
                        val isCurrent = positionMs >= seg.startMs && positionMs < seg.endMs
                        val transposed = ChordTransposer.transpose(seg.chord, pitchSt)
                        val startStr = formatTime(seg.startMs)
                        val endStr = formatTime(seg.endMs)
                        val durationSec = String.format(Locale.US, "%.1fs", (seg.endMs - seg.startMs) / 1000f)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    player.seekTo(seg.startMs)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (isCurrent) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(6.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = transposed,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                                        color = if (isCurrent) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "$startStr – $endStr",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Duration: $durationSec",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                text = "${(seg.confidence * 100).toInt()}% conf",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showMetadataSheet) {
        TrackMetadataSheet(
            project = project,
            onDismiss = { showMetadataSheet = false }
        )
    }
}

@Composable
fun PauseIcon(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val barWidth = size.toPx() * 0.28f
        val barHeight = size.toPx() * 0.9f
        val gap = size.toPx() * 0.24f
        val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())

        // Left bar
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, (size.toPx() - barHeight) / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = corner
        )
        // Right bar
        drawRoundRect(
            color = color,
            topLeft = Offset(barWidth + gap, (size.toPx() - barHeight) / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = corner
        )
    }
}

/**
 * Animated 3-bar mini audio equalizer indicator for currently playing tracks.
 */
@Composable
fun PlayingWaveformEqualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")

    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(580, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(490, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Canvas(modifier = modifier.size(width = 14.dp, height = 12.dp)) {
        val totalW = size.width
        val totalH = size.height
        val barCount = 3
        val barW = 2.4.dp.toPx()
        val spacing = (totalW - (barCount * barW)) / (barCount - 1)

        val fractions = if (isPlaying) listOf(bar1, bar2, bar3) else listOf(0.3f, 0.5f, 0.3f)

        for (i in 0 until barCount) {
            val frac = fractions[i]
            val barH = (totalH * frac).coerceAtLeast(2.dp.toPx())
            val x = i * (barW + spacing)
            val y = totalH - barH

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
