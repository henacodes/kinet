package com.kirakos.kinet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.SongProject
import com.kirakos.kinet.formatFileSize
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMetadataSheet(
    project: SongProject,
    onDismiss: () -> Unit
) {
    val durationStr = remember(project.durationSeconds) {
        val m = (project.durationSeconds / 60).toInt()
        val s = (project.durationSeconds % 60).toInt()
        String.format(Locale.US, "%d:%02d", m, s)
    }

    val dateStr = remember(project.createdAt) {
        val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        sdf.format(Date(project.createdAt))
    }

    val vocalsSizeStr = remember(project.vocalsFile) {
        if (project.vocalsFile.exists()) formatFileSize(project.vocalsFile.length()) else "Unavailable"
    }

    val accompSizeStr = remember(project.accompanimentFile) {
        if (project.accompanimentFile.exists()) formatFileSize(project.accompanimentFile.length()) else "Unavailable"
    }

    val originalSizeStr = remember(project.originalFileSizeBytes) {
        if (project.originalFileSizeBytes > 0L) formatFileSize(project.originalFileSizeBytes) else "Unknown"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Text(
                text = "Track specifications",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Light,
                    fontSize = 22.sp,
                    letterSpacing = (-0.3).sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = project.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Section 1: Track Information
            Text(
                text = "Track information",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpecRow(label = "Title", value = project.title)
                    SpecRow(label = "Artist", value = project.artist.ifBlank { "Unknown artist" })
                    SpecRow(label = "Album", value = project.album.ifBlank { "Unknown album" })
                    SpecRow(label = "Genre", value = project.genre.ifBlank { project.category.ifBlank { "General" } })
                    if (project.year.isNotBlank()) {
                        SpecRow(label = "Year", value = project.year)
                    }
                    SpecRow(label = "Duration", value = "$durationStr (${project.durationSeconds.toInt()}s)")
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Section 2: Audio Engineering Specs
            Text(
                text = "Audio engineering",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpecRow(label = "Stem sample rate", value = "44,100 Hz (44.1 kHz)")
                    SpecRow(label = "Audio channels", value = "2 channels (Stereo)")
                    SpecRow(label = "Bit depth", value = "16-bit signed PCM")
                    if (project.bitrateKbps > 0) {
                        SpecRow(label = "Original bitrate", value = "${project.bitrateKbps} kbps")
                    }
                    if (project.originalFormat.isNotBlank()) {
                        SpecRow(label = "Original format", value = project.originalFormat)
                    }
                    if (project.originalFileSizeBytes > 0L) {
                        SpecRow(label = "Original file size", value = originalSizeStr)
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Section 3: Stem Storage & Model
            Text(
                text = "Stems & storage",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpecRow(label = "Vocals stem", value = "$vocalsSizeStr (WAV)")
                    SpecRow(label = "Instrumental stem", value = "$accompSizeStr (WAV)")
                    SpecRow(label = "Separated on", value = dateStr)
                    SpecRow(label = "AI engine", value = "On-device MDX-Net 44.1 kHz")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
