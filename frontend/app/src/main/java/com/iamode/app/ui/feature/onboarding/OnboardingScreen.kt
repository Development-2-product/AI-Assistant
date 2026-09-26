package com.iamode.app.ui.feature.onboarding

import com.iamode.app.core.i18n.tr

import android.content.Intent
import androidx.activity.compose.BackHandler
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamode.app.core.permissions.AppPermission
import com.iamode.app.core.permissions.AppPermissions
import com.iamode.app.domain.model.Gender
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Script
import com.iamode.app.ui.components.ChipRow
import com.iamode.app.ui.components.SectionTitle
import com.iamode.app.ui.theme.IAColors
import com.iamode.app.ui.theme.Motion

@Composable
fun OnboardingScreen(onFinished: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable(s.myName) { mutableStateOf(s.myName) }
    BackHandler(enabled = step == 1) { step = 0 } // back returns to the previous step, not out of the app

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)) { dir * it / 5 } + fadeIn(tween(Motion.MEDIUM))) togetherWith
                        (slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EmphasizedAccelerate)) { -dir * it / 8 } + fadeOut(tween(Motion.SHORT)))
                },
                label = "onboardingStep",
            ) { current ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (current == 0) {
                        Text(tr("IA Mode"), style = MaterialTheme.typography.headlineSmall)
                        Text(tr("When you're driving, in a meeting, gaming or asleep, IA Mode replies for you on WhatsApp, ") +
                            tr("Gmail and missed calls, in each person's own language. Clients and business contacts get ") +
                            tr("replies automatically; friends, family and your partner need your OK first."),
                            style = MaterialTheme.typography.bodyMedium)

                        SectionTitle(tr("About you"))
                        OutlinedTextField(name, { name = it }, label = { Text(tr("Your name (for email sign-offs)")) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(tr("Gender (so Hindi and other languages use the right verb forms)"), style = MaterialTheme.typography.bodyMedium)
                        ChipRow {
                            Gender.entries.forEach { g -> FilterChip(s.gender == g, { vm.setGender(g) }, { Text(tr(g.label)) }) }
                        }

                        SectionTitle(tr("Default language for missed-call texts"))
                        Text(tr("Used when IA Mode doesn't know a caller's language yet."), style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
                        ChipRow {
                            LanguageCode.entries.forEach { l -> FilterChip(s.defaultLanguage == l, { vm.setLanguage(l) }, { Text(tr(l.label)) }) }
                        }
                        if (s.defaultLanguage != LanguageCode.EN) {
                            ChipRow {
                                Script.entries.forEach { sc -> FilterChip(s.defaultScript == sc, { vm.setScript(sc) }, { Text(tr(sc.label)) }) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.setName(name.trim()); step = 1 }, modifier = Modifier.fillMaxWidth()) { Text(tr("Continue")) }
                    } else {
                        PermissionList()
                        Button(onClick = { vm.finish(onFinished) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Finish setup")) }
                        Text(tr("You can grant anything you skip later from Settings."), style = MaterialTheme.typography.labelSmall,
                            color = IAColors.Grey)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Permissions")) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
        })
    }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState())) { PermissionList() }
    }
}

@Composable
fun PermissionList() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var guidedSetupActive by rememberSaveable { mutableStateOf(false) }
    var guidedPermissionIndex by rememberSaveable { mutableIntStateOf(0) }
    val permissionEntries = remember { AppPermission.entries.toList() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val guidedRuntimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        guidedPermissionIndex++
        refresh++
    }
    val guidedSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        guidedPermissionIndex++
        refresh++
    }

    fun openSpecialAccess(p: AppPermission, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
        AppPermissions.settingsIntent(context, p)?.let { intent ->
            // Some OEMs do not implement the direct battery request. Their system-wide battery
            // page remains a safe fallback; Android always owns the final permission decision.
            runCatching { launcher.launch(intent) }.getOrElse {
                launcher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    LaunchedEffect(guidedSetupActive, guidedPermissionIndex, refresh) {
        if (!guidedSetupActive) return@LaunchedEffect
        val next = permissionEntries.indexOfFirst { it.ordinal >= guidedPermissionIndex && !AppPermissions.isGranted(context, it) }
        if (next < 0) {
            guidedSetupActive = false
        } else if (next != guidedPermissionIndex) {
            guidedPermissionIndex = next
        } else {
            val permission = permissionEntries[next]
            AppPermissions.runtimePermission(permission)?.let(guidedRuntimeLauncher::launch)
                ?: openSpecialAccess(permission, guidedSettingsLauncher)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("IA Mode needs a few permissions"), style = MaterialTheme.typography.titleMedium)
        Text(tr("Everything is processed on your phone. Only the few messages needed to write a reply are sent to the AI, and nothing is stored on the server."),
            style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = {
                guidedPermissionIndex = 0
                guidedSetupActive = true
            },
            enabled = !guidedSetupActive && permissionEntries.any { !AppPermissions.isGranted(context, it) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(tr("Allow all (guided)")) }
        if (guidedSetupActive) {
            val current = permissionEntries.getOrNull(guidedPermissionIndex)
            Text(
                tr("Android will ask for each access one at a time. You can decline any request.") +
                    current?.let { " " + tr("Next: %1\$s", (tr(it.title))) }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = IAColors.Grey,
            )
            OutlinedButton(onClick = { guidedSetupActive = false }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Stop guided setup"))
            }
        }
        AppPermission.entries.forEach { p ->
            val granted = remember(refresh) { AppPermissions.isGranted(context, p) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(p.title) + if (p.required) "" else tr(" (optional)"), style = MaterialTheme.typography.bodyMedium)
                    Text(tr(p.why), style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
                }
                if (granted) {
                    Icon(Icons.Filled.CheckCircle, tr("Granted"), tint = IAColors.Green)
                } else {
                    OutlinedButton(onClick = {
                        val runtime = AppPermissions.runtimePermission(p)
                        if (runtime != null) runtimeLauncher.launch(runtime)
                        else openSpecialAccess(p, settingsLauncher)
                    }) { Text(tr("Allow")) }
                }
            }
        }
        if (AppPermissions.needsAutostartHint()) {
            Text(tr("On %1\$s phones, also turn on Autostart for IA Mode (Settings › Apps › IA Mode) ", (android.os.Build.MANUFACTURER)) +
                tr("and set Battery to No restrictions, or the phone may stop IA Mode in the background."),
                style = MaterialTheme.typography.labelSmall, color = IAColors.Amber)
        }
        Text(tr("On Android 13+, if Notification access is greyed out: open App info › ⋮ › Allow restricted settings, then try again."),
            style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text(tr("Open App info")) }
    }
}
