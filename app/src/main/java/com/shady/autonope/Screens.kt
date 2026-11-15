package com.shady.autonope

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.shady.autonope.ScanViewModel.State

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun AutoNopeApp(vm: ScanViewModel) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
                when (val s = vm.state) {
                    is State.Done -> ReportScreen(s, onRescan = vm::scan)
                    else -> ScanScreen(s, onScan = vm::scan)
                }
            }
        }
    }
}

private fun hasSms(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

private fun copy(context: Context, clipboard: androidx.compose.ui.platform.ClipboardManager, text: String, what: String) {
    clipboard.setText(AnnotatedString(text))
    Toast.makeText(context, "$what copied", Toast.LENGTH_SHORT).show()
}

// ---------- Scan ----------

@Composable
private fun ScanScreen(state: State, onScan: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var granted by remember { mutableStateOf(hasSms(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }

    // Coming back from App info: check again.
    LifecycleResumeEffect(Unit) {
        granted = hasSms(context)
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("AutoNope", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Test build. Reads the bank SMS on this phone and lists everything that auto-debits you. " +
                "No internet permission, nothing saved, SMS from people skipped unread.",
        )
        when {
            state is State.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 16.dp))
                Text(state.step)
            }
            !granted -> {
                Button(onClick = { ask.launch(Manifest.permission.READ_SMS) }) { Text("Allow SMS") }
                if (denied) RestrictedHelp(context)
            }
            else -> Button(onClick = onScan) { Text("Scan") }
        }
        if (state is State.Failed) {
            Text("The scan crashed. Copy this and paste it back to Claude:", color = MaterialTheme.colorScheme.error)
            Text(state.error, style = Mono)
            OutlinedButton(onClick = { copy(context, clipboard, state.error, "Error") }) { Text("Copy error") }
        }
    }
}

@Composable
private fun RestrictedHelp(context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "If nothing popped up, Android is blocking SMS because this app isn't from the Play Store. Allow it once:",
            fontWeight = FontWeight.SemiBold,
        )
        listOf(
            "Tap Open App info below",
            "Tap ⋮ at the top right, then Allow restricted settings, and confirm",
            "Go to Permissions, then SMS, then Allow",
            "Come back here and tap Scan",
        ).forEachIndexed { i, step -> Text("${i + 1}. $step") }
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text("Open App info") }
    }
}

// ---------- Report ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportScreen(s: State.Done, onRescan: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val r = s.result
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(query, s) { Debug.search(query, s.inbox, r) }
    val searching = query.trim().length >= 2

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${r.subscriptions.size} autopays found", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${"%,d".format(r.stats.total)} messages. ${"%,d".format(r.stats.personalSkipped)} from people skipped unread. " +
                        "${r.suspects.size} couldn't be read. Took ${"%.1f".format(s.seconds)} s.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRescan) { Text("Rescan") }
                OutlinedButton(onClick = { copy(context, clipboard, Debug.reportForCopy(r), "Report") }) { Text("Copy report") }
                if (r.suspects.isNotEmpty()) {
                    OutlinedButton(onClick = { copy(context, clipboard, Debug.unreadableForCopy(r), "Unreadable SMS") }) {
                        Text("Copy unreadable ones (masked)")
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Look for a company, like netflix") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (searching) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${hits.size}${if (hits.size == Debug.LIMIT) "+" else ""} SMS mention \"${query.trim()}\"",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (hits.isNotEmpty()) {
                        TextButton(onClick = { copy(context, clipboard, Debug.hitsForCopy(query, hits), "Search") }) {
                            Text("Copy masked")
                        }
                    }
                }
            }
            items(hits, key = { it.sms.id }) { h ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${Debug.stamp(h.sms)}  ${h.sms.sender}", style = MaterialTheme.typography.labelMedium)
                    Text(h.what, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(h.sms.body, style = Mono)
                }
            }
        } else {
            item { SelectionContainer { Text(s.report, style = Mono) } }
        }
    }
}
