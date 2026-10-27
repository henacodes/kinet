package com.kirakos.kinet.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.ExportFormat
import com.kirakos.kinet.SongProject
import com.kirakos.kinet.StemExporter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportStemsSheet(
    project: SongProject,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedFormat by remember { mutableStateOf(ExportFormat.M4A) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Title & Subtitle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export stems",
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Format Selection Section
            Text(
                text = "Audio format",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // M4A Option
                FormatOptionCard(
                    format = ExportFormat.M4A,
                    badge = "Recommended",
                    isSelected = selectedFormat == ExportFormat.M4A,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedFormat = ExportFormat.M4A }
                )

                // WAV Option
                FormatOptionCard(
                    format = ExportFormat.WAV,
                    badge = "Lossless",
                    isSelected = selectedFormat == ExportFormat.WAV,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedFormat = ExportFormat.WAV }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Actions Section
            Text(
                text = "Stems to export",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Processing indicator banner
            if (isProcessing) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = statusMessage ?: "Preparing stem...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Helper function to export a single stem
            fun exportStem(rawWav: java.io.File, stemName: String, isShare: Boolean) {
                isProcessing = true
                val actionName = if (isShare) "Sharing" else "Saving"
                statusMessage = if (selectedFormat == ExportFormat.M4A) "Encoding $stemName to M4A..." else "$actionName $stemName..."
                coroutineScope.launch {
                    val file = StemExporter.prepareStemFile(
                        context = context,
                        rawWavFile = rawWav,
                        songTitle = project.title,
                        stemType = stemName,
                        format = selectedFormat
                    )
                    isProcessing = false
                    statusMessage = null
                    if (isShare) {
                        StemExporter.shareStem(
                            context = context,
                            file = file,
                            displayTitle = project.title,
                            stemType = stemName,
                            format = selectedFormat
                        )
                        onDismiss()
                    } else {
                        val success = StemExporter.saveStemToDevice(
                            context = context,
                            file = file,
                            displayTitle = project.title,
                            stemType = stemName,
                            format = selectedFormat
                        )
                        if (success) {
                            Toast.makeText(context, "Saved $stemName to Music/Kinet", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } else {
                            Toast.makeText(context, "Failed to save $stemName", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            if (project.isFourStem && project.drumsFile != null && project.bassFile != null && project.otherFile != null) {
                // 1. Vocals Stem
                StemActionRow(
                    title = "Vocals stem",
                    subtitle = if (selectedFormat == ExportFormat.M4A) "Acapella lead & backing (.m4a)" else "Acapella track (.wav)",
                    enabled = !isProcessing,
                    onSave = { exportStem(project.vocalsFile, "Vocals", false) },
                    onShare = { exportStem(project.vocalsFile, "Vocals", true) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Drums Stem
                StemActionRow(
                    title = "Drums stem",
                    subtitle = if (selectedFormat == ExportFormat.M4A) "Isolated percussion & beat (.m4a)" else "Drums track (.wav)",
                    enabled = !isProcessing,
                    onSave = { exportStem(project.drumsFile, "Drums", false) },
                    onShare = { exportStem(project.drumsFile, "Drums", true) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Bass Stem
                StemActionRow(
                    title = "Bass stem",
                    subtitle = if (selectedFormat == ExportFormat.M4A) "Bass guitar & low synth (.m4a)" else "Bass track (.wav)",
                    enabled = !isProcessing,
                    onSave = { exportStem(project.bassFile, "Bass", false) },
                    onShare = { exportStem(project.bassFile, "Bass", true) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 4. Other / Melody Stem
                StemActionRow(
                    title = "Other stem (Melody)",
                    subtitle = if (selectedFormat == ExportFormat.M4A) "Guitars, keys & synths (.m4a)" else "Melody track (.wav)",
                    enabled = !isProcessing,
                    onSave = { exportStem(project.otherFile, "Other", false) },
                    onShare = { exportStem(project.otherFile, "Other", true) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 5. Full Instrumental
                StemActionRow(
                    title = "Instrumental mix",
                    subtitle = "Drums + Bass + Melody without vocals",
                    enabled = !isProcessing,
                    onSave = { exportStem(project.accompanimentFile, "Instrumental", false) },
                    onShare = { exportStem(project.accompanimentFile, "Instrumental", true) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 6. All 4 Stems Pack
                StemActionRow(
                    title = "All 4 stems pack",
                    subtitle = "Vocals, Drums, Bass, and Other",
                    enabled = !isProcessing,
                    onSave = {
                        isProcessing = true
                        statusMessage = if (selectedFormat == ExportFormat.M4A) "Encoding 4 stems to M4A..." else "Saving 4 stems..."
                        coroutineScope.launch {
                            val vF = StemExporter.prepareStemFile(context, project.vocalsFile, project.title, "Vocals", selectedFormat)
                            val dF = StemExporter.prepareStemFile(context, project.drumsFile, project.title, "Drums", selectedFormat)
                            val bF = StemExporter.prepareStemFile(context, project.bassFile, project.title, "Bass", selectedFormat)
                            val oF = StemExporter.prepareStemFile(context, project.otherFile, project.title, "Other", selectedFormat)

                            val s1 = StemExporter.saveStemToDevice(context, vF, project.title, "Vocals", selectedFormat)
                            val s2 = StemExporter.saveStemToDevice(context, dF, project.title, "Drums", selectedFormat)
                            val s3 = StemExporter.saveStemToDevice(context, bF, project.title, "Bass", selectedFormat)
                            val s4 = StemExporter.saveStemToDevice(context, oF, project.title, "Other", selectedFormat)

                            isProcessing = false
                            statusMessage = null
                            if (s1 && s2 && s3 && s4) {
                                Toast.makeText(context, "Saved all 4 stems to Music/Kinet", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "Partially saved stems", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShare = {
                        isProcessing = true
                        statusMessage = if (selectedFormat == ExportFormat.M4A) "Encoding 4 stems to M4A..." else "Preparing share..."
                        coroutineScope.launch {
                            val vF = StemExporter.prepareStemFile(context, project.vocalsFile, project.title, "Vocals", selectedFormat)
                            val dF = StemExporter.prepareStemFile(context, project.drumsFile, project.title, "Drums", selectedFormat)
                            val bF = StemExporter.prepareStemFile(context, project.bassFile, project.title, "Bass", selectedFormat)
                            val oF = StemExporter.prepareStemFile(context, project.otherFile, project.title, "Other", selectedFormat)

                            isProcessing = false
                            statusMessage = null
                            StemExporter.shareMultipleStems(context, listOf(vF, dF, bF, oF), project.title, selectedFormat)
                            onDismiss()
                        }
                    }
                )
            } else {
                // 2-Stem Legacy Mode: Vocals Stem Row
                StemActionRow(
                    title = "Vocals stem",
                    subtitle = if (selectedFormat == ExportFormat.M4A) "Acapella track (.m4a)" else "Acapella track (.wav)",
                    enabled = !isProcessing,
                    onSave = { exportStem(project.vocalsFile, "Vocals", false) },
                    onShare = { exportStem(project.vocalsFile, "Vocals", true) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Instrumental Stem Row
                StemActionRow(
                    title = "Instrumental stem",
                    subtitle = if (selectedFormat == ExportFormat.M4A) "Accompaniment track (.m4a)" else "Accompaniment track (.wav)",
                    enabled = !isProcessing,
                    onSave = { exportStem(project.accompanimentFile, "Instrumental", false) },
                    onShare = { exportStem(project.accompanimentFile, "Instrumental", true) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Both Stems Row
                StemActionRow(
                    title = "Both stems",
                    subtitle = "Vocals and Instrumental together",
                    enabled = !isProcessing,
                    onSave = {
                        isProcessing = true
                        statusMessage = if (selectedFormat == ExportFormat.M4A) "Encoding stems to M4A..." else "Saving both stems..."
                        coroutineScope.launch {
                            val vocFile = StemExporter.prepareStemFile(context, project.vocalsFile, project.title, "Vocals", selectedFormat)
                            val accFile = StemExporter.prepareStemFile(context, project.accompanimentFile, project.title, "Instrumental", selectedFormat)
                            val s1 = StemExporter.saveStemToDevice(context, vocFile, project.title, "Vocals", selectedFormat)
                            val s2 = StemExporter.saveStemToDevice(context, accFile, project.title, "Instrumental", selectedFormat)
                            isProcessing = false
                            statusMessage = null
                            if (s1 && s2) {
                                Toast.makeText(context, "Saved both stems to Music/Kinet", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "Partially saved stems", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShare = {
                        isProcessing = true
                        statusMessage = if (selectedFormat == ExportFormat.M4A) "Encoding stems to M4A..." else "Preparing share..."
                        coroutineScope.launch {
                            val vocFile = StemExporter.prepareStemFile(context, project.vocalsFile, project.title, "Vocals", selectedFormat)
                            val accFile = StemExporter.prepareStemFile(context, project.accompanimentFile, project.title, "Instrumental", selectedFormat)
                            isProcessing = false
                            statusMessage = null
                            StemExporter.shareBothStems(context, vocFile, accFile, project.title, selectedFormat)
                            onDismiss()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FormatOptionCard(
    format: ExportFormat,
    badge: String,
    isSelected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(BorderStroke(if (isSelected) 1.5.dp else 0.5.dp, borderColor), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = format.label,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = format.description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StemActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onSave,
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Save", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }

                FilledTonalButton(
                    onClick = onShare,
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = null
                ) {
                    Text("Share", fontSize = 12.sp)
                }
            }
        }
    }
}
