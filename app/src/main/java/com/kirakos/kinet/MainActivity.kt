package com.kirakos.kinet

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.ui.components.*
import com.kirakos.kinet.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var projectRepository: ProjectRepository
    private lateinit var audioSeparator: AudioSeparator
    private val player = DualStemPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
        player.release()
    }
}

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
    var activeProject by remember { mutableStateOf<SongProject?>(null) }
    var isSeparating by remember { mutableStateOf(false) }
    var separationProgress by remember { mutableStateOf("Ready") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val title = resolveFileName(uri)
            isSeparating = true
            separationProgress = "Starting separation..."
            coroutineScope.launch {
                try {
                    val result = separator.separate(uri) { status ->
                        separationProgress = status
                    }
                    val saved = repository.saveProject(
                        title = title,
                        durationSeconds = result.durationSeconds,
                        tempVocals = result.vocalsFile,
                        tempAccomp = result.accompanimentFile
                    )
                    projects = repository.getAllProjects()
                    activeProject = saved
                    player.load(saved)
                } catch (e: Exception) {
                    separationProgress = "Error: ${e.message}"
                } finally {
                    isSeparating = false
                }
            }
        }
    }

    BackHandler(enabled = activeProject != null) {
        player.pause()
        activeProject = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (activeProject != null) {
            StemPlayerScreen(
                project = activeProject!!,
                player = player,
                onBack = {
                    player.pause()
                    activeProject = null
                }
            )
        } else {
            SongLibraryScreen(
                projects = projects,
                onSelectProject = { proj ->
                    activeProject = proj
                    player.load(proj)
                },
                onDeleteProject = { id ->
                    repository.deleteProject(id)
                    projects = repository.getAllProjects()
                },
                onRenameProject = { id, newName ->
                    repository.renameProject(id, newName)
                    projects = repository.getAllProjects()
                },
                onImportClick = { filePicker.launch("audio/*") },
                isSeparating = isSeparating,
                separationProgress = separationProgress
            )
        }
    }
}

@Composable
fun SongLibraryScreen(
    projects: List<SongProject>,
    onSelectProject: (SongProject) -> Unit,
    onDeleteProject: (String) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onImportClick: () -> Unit,
    isSeparating: Boolean,
    separationProgress: String
) {
    var projectToRename by remember { mutableStateOf<SongProject?>(null) }
    var projectToDelete by remember { mutableStateOf<SongProject?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Kinet",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Light,
                        fontSize = 30.sp,
                        letterSpacing = (-0.5).sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Stem library",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(
                onClick = onImportClick,
                enabled = !isSeparating,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onBackground
                )
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Separate new", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Separation Progress
        if (isSeparating) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
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

        // Projects List (Clean, compact rows with dividers instead of huge cards)
        if (projects.isEmpty() && !isSeparating) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No separated tracks yet",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Import a song to isolate vocals and accompaniment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(projects, key = { it.id }) { proj ->
                    ProjectRow(
                        project = proj,
                        onClick = { onSelectProject(proj) },
                        onRenameClick = { projectToRename = proj },
                        onDeleteClick = { projectToDelete = proj }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        thickness = 0.5.dp
                    )
                }
            }
        }
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

    // Delete Confirmation Dialog
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
}

@Composable
fun ProjectRow(
    project: SongProject,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val dateStr = remember(project.createdAt) {
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
        sdf.format(Date(project.createdAt))
    }
    val durationStr = remember(project.durationSeconds) {
        val m = (project.durationSeconds / 60).toInt()
        val s = (project.durationSeconds % 60).toInt()
        String.format(Locale.US, "%d:%02d", m, s)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "$durationStr • $dateStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                DropdownMenuItem(
                    text = { Text("Rename", fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onRenameClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", fontSize = 14.sp, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDeleteClick()
                    }
                )
            }
        }
    }
}

@Composable
fun StemPlayerScreen(
    project: SongProject,
    player: DualStemPlayer,
    onBack: () -> Unit
) {
    val isPlaying by player.isPlaying.collectAsState()
    val positionMs by player.positionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()
    val waveform by player.waveform.collectAsState()

    val vocVol by player.vocalsVolume.collectAsState()
    val accVol by player.accompVolume.collectAsState()
    val isVocMuted by player.isVocalsMuted.collectAsState()
    val isAccMuted by player.isAccompMuted.collectAsState()

    val pitchSt by player.pitchSemitones.collectAsState()
    val loopA by player.loopStartMs.collectAsState()
    val loopB by player.loopEndMs.collectAsState()
    val isLooping by player.isLooping.collectAsState()

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
                        text = "Analog stem deck",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Technical Dot Matrix Accent in top right corner (screenshot2.png)
            DotGridMatrix(
                rows = 3,
                cols = 5,
                spacing = 5.dp,
                dotRadius = 1.2.dp,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 1. Single Rotating Vinyl Turntable with Mechanical Tonearm
        VinylTurntable(
            isPlaying = isPlaying,
            progress = progress
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Song Metadata (Title + Duration/Pitch tag)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = project.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Light,
                    fontSize = 20.sp,
                    letterSpacing = (-0.3).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = "Stereo 44.1 kHz • ${if (pitchSt != 0) (if (pitchSt > 0) "+$pitchSt" else "$pitchSt") else "Standard pitch"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Waveform Scrubber with [   ] Loop Brackets (screenshot.png)
        WaveformLoopScrubber(
            waveform = waveform,
            positionMs = positionMs,
            durationMs = durationMs,
            loopStartMs = loopA,
            loopEndMs = loopB,
            isLooping = isLooping,
            onSeekTo = { player.seekTo(it) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Card-less Pitch 3D Roller & A/B Loop Controls
        PitchAndLoopBay(
            pitchSemitones = pitchSt,
            onPitchChange = { player.setPitchSemitones(it) },
            loopStartMs = loopA,
            loopEndMs = loopB,
            isLooping = isLooping,
            onSetLoopA = { player.setLoopPointA() },
            onSetLoopB = { player.setLoopPointB() },
            onClearLoop = { player.clearLoop() }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 5. Modular Stem Faders Bay (Vocals & Music)
        StemFadersBay(
            vocalsVolume = vocVol,
            isVocalsMuted = isVocMuted,
            onVocalsVolumeChange = { player.setVocalsVolume(it) },
            onVocalsMuteToggle = { player.toggleVocalsMute() },
            accompVolume = accVol,
            isAccompMuted = isAccMuted,
            onAccompVolumeChange = { player.setAccompanimentVolume(it) },
            onAccompMuteToggle = { player.toggleAccompanimentMute() }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 6. Transport Controls (Play / Pause / 5s scrub)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = { player.seekTo(maxOf(0L, positionMs - 5000L)) },
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text("-5s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }

            Spacer(modifier = Modifier.width(22.dp))

            // Main Tactile Play/Pause Knob
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
                onClick = { player.seekTo(minOf(durationMs, positionMs + 5000L)) },
                shape = CircleShape,
                modifier = Modifier.size(46.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text("+5s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
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

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
