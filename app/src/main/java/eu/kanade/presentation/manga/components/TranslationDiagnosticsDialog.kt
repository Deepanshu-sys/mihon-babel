package eu.kanade.presentation.manga.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.translation.model.ChapterTranslationManifest

@Composable
fun TranslationDiagnosticsDialog(
    manifest: ChapterTranslationManifest,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val json = Json { prettyPrint = true }
    val manifestJson = json.encodeToString(manifest)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Translation Diagnostics",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Chapter Summary",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("• Total Pages: ${manifest.totalPages}")
                Text("• Completed Pages: ${manifest.completedPages.size}")
                Text("• Source Language: ${manifest.sourceLanguage.uppercase()} -> ${manifest.targetLanguage.uppercase()}")
                Text("• Translation model: ${manifest.translatorModelId.ifBlank { "Unknown" }}")
                Text("• Model selection: ${manifest.translatorSelectionReason.ifBlank { "Not recorded" }}")
                Text("• Detector: ${manifest.detectorModelId.ifBlank { "Unknown" }}")
                Text("• OCR: ${manifest.ocrModelId.ifBlank { "Unknown" }}")
                Text("• Inpainting: ${manifest.inpainterModelId.ifBlank { "Unknown" }}")

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Raw Diagnostic JSON",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = manifestJson,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Translation Diagnostics", manifestJson)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Diagnostics copied to clipboard!", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text("Copy Report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Close")
            }
        },
    )
}
