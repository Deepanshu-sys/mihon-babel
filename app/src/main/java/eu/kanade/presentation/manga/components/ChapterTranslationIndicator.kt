package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.toast
import mihon.feature.translation.ChapterTranslationState
import mihon.feature.translation.TranslationProgress
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.roundedfilled.CheckCircle

enum class TranslationUiAction {
    START,
    RETRANSLATE,
    CANCEL,
    DELETE,
    VIEW_DIAGNOSTICS,
}

@Composable
fun ChapterTranslationIndicator(
    translationState: ChapterTranslationState,
    onClick: (TranslationUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChapterTranslationIndicator(
        translationProgress = TranslationProgress(
            chapterId = 0L,
            state = translationState,
        ),
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
fun ChapterTranslationIndicator(
    translationProgress: TranslationProgress,
    onClick: (TranslationUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val state = translationProgress.state

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable {
                when (state) {
                    ChapterTranslationState.NOT_TRANSLATED -> onClick(TranslationUiAction.START)
                    ChapterTranslationState.QUEUED,
                    ChapterTranslationState.DOWNLOADING,
                    ChapterTranslationState.SCANNING,
                    ChapterTranslationState.TRANSLATING,
                    ChapterTranslationState.RENDERING -> showMenu = true
                    ChapterTranslationState.TRANSLATED -> showMenu = true
                    ChapterTranslationState.ERROR -> {
                        val reason = translationProgress.error?.ifBlank { "Translation failed" } ?: "Translation failed"
                        context.toast(reason)
                        onClick(TranslationUiAction.START)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            state == ChapterTranslationState.NOT_TRANSLATED -> {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Public,
                    contentDescription = "Translate chapter",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            state == ChapterTranslationState.QUEUED -> {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Static (not spinning) ring
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    )
                    Text(
                        text = "Queued",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Remove from queue") },
                        onClick = {
                            showMenu = false
                            onClick(TranslationUiAction.CANCEL)
                        },
                    )
                }
            }
            state.isActive -> { // Running: DOWNLOADING, SCANNING, TRANSLATING, RENDERING
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val progressFraction = (translationProgress.percent / 100f).coerceIn(0f, 1f)
                    if (progressFraction > 0f) {
                        CircularProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Text(
                        text = "${translationProgress.percent}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (translationProgress.percent >= 100) 7.sp else 8.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Cancel") },
                        onClick = {
                            showMenu = false
                            onClick(TranslationUiAction.CANCEL)
                        },
                    )
                }
            }
            state == ChapterTranslationState.TRANSLATED -> {
                Icon(
                    imageVector = MaterialSymbols.RoundedFilled.CheckCircle,
                    contentDescription = "Translated",
                    modifier = Modifier.size(20.dp),
                    tint = Color(0xFF4CAF50),
                )

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Re-translate") },
                        onClick = {
                            showMenu = false
                            onClick(TranslationUiAction.RETRANSLATE)
                        },
                    )
                    if (BuildConfig.DEBUG) {
                        DropdownMenuItem(
                            text = { Text("View Diagnostics") },
                            onClick = {
                                showMenu = false
                                onClick(TranslationUiAction.VIEW_DIAGNOSTICS)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete translation") },
                        onClick = {
                            showMenu = false
                            onClick(TranslationUiAction.DELETE)
                        },
                    )
                }
            }
            state == ChapterTranslationState.ERROR -> {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Error,
                    contentDescription = "Translation error (tap to retry)",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
