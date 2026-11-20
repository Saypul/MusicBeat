package com.musicbeat.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.musicbeat.app.R
import com.musicbeat.app.data.settings.AppSettings
import com.musicbeat.app.data.settings.AutoEqRepository
import com.musicbeat.app.playback.AutoEqEntry
import com.musicbeat.app.playback.AutoEqIndex
import com.musicbeat.app.ui.icons.SfIcons
import kotlinx.coroutines.launch

/**
 * Finds a headphone in AutoEQ's results and puts its correction on the
 * parametric tab. A pick applies at once, is kept among the saved profiles
 * under the headphone's name, and [onApplied] then leaves the page.
 */
@Composable
fun AutoEqSearchPage(
    onApplied: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<AutoEqEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<AutoEqEntry?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        failed = false
        entries = runCatching { AutoEqRepository.index(context.applicationContext) }
            .onFailure { failed = true }
            .getOrNull()
    }
    val results = remember(entries, query) { entries?.let { AutoEqIndex.search(it, query) }.orEmpty() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.equalizer_autoeq))
        SettingsGroup(footer = stringResource(R.string.equalizer_autoeq_footer), topSpacing = 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = ROW_INSET, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.equalizer_autoeq_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onBackground,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        when {
            failed -> SettingsGroup {
                SettingsRow(
                    icon = SfIcons.Warning,
                    title = stringResource(R.string.equalizer_autoeq_offline),
                    subtitle = stringResource(R.string.equalizer_autoeq_retry),
                    onClick = { attempt++ },
                )
            }
            entries == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            query.isNotBlank() && results.isEmpty() -> SettingsGroup {
                SettingsRow(title = stringResource(R.string.equalizer_autoeq_none))
            }
            results.isNotEmpty() -> SettingsGroup {
                results.forEachIndexed { index, entry ->
                    if (index > 0) RowDivider()
                    SettingsRow(
                        icon = SfIcons.Bluetooth,
                        title = entry.name,
                        subtitle = entry.source,
                        enabled = applying == null,
                        trailing = if (applying == entry) {
                            { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                        } else {
                            null
                        },
                        onClick = {
                            applying = entry
                            scope.launch {
                                runCatching { AutoEqRepository.correction(entry) }
                                    .onSuccess { correction ->
                                        AppSettings.applyEqualizerImport(entry.name, correction)
                                        Toast.makeText(
                                            context,
                                            importMessage(context, entry.name, correction.skipped),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        onApplied()
                                    }
                                    .onFailure {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.equalizer_autoeq_failed, entry.name),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                applying = null
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

internal fun importMessage(context: android.content.Context, name: String, skipped: Int): String =
    if (skipped > 0) {
        context.getString(R.string.equalizer_import_applied_skipped, name, skipped)
    } else {
        context.getString(R.string.equalizer_import_applied, name)
    }
