package com.albertcasadessus.focuslauncher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.albertcasadessus.focuslauncher.ui.theme.FocusLauncherTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activityName: String
)

class MainActivity : ComponentActivity() {
    private val allAppsState = mutableStateOf(listOf<LaunchableApp>())
    private var packageChangeReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FocusLauncherTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LauncherScreen(appsState = allAppsState, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Initial load on background thread
        if (allAppsState.value.isEmpty()) {
            lifecycleScope.launch(Dispatchers.Default) {
                val apps = loadLaunchableApps(packageManager)
                launch(Dispatchers.Main) { allAppsState.value = apps }
            }
        }

        if (packageChangeReceiver == null) {
            packageChangeReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val apps = loadLaunchableApps(packageManager)
                        launch(Dispatchers.Main) { allAppsState.value = apps }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (_: Exception) { }
    }
}

@Composable
fun LauncherScreen(appsState: MutableState<List<LaunchableApp>>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val allApps = appsState

    val filtered = remember(query, allApps.value) {
        val q = query.text.trim().lowercase()
        if (q.isEmpty()) allApps.value
        else allApps.value.filter { app ->
            val labelLower = app.label.lowercase()
            val packageLower = app.packageName.lowercase()
            labelLower.contains(q) || packageLower.contains(q)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        val roleManager = remember(context) {
            context.getSystemService(RoleManager::class.java)
        }
        val roleLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
            onResult = { /* no-op; user can press Home to verify */ }
        )

        val isHomeRoleHeld = remember(roleManager) {
            roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true &&
                roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (!isHomeRoleHeld && roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                Text(
                    text = "Set as Home",
                    modifier = Modifier
                        .padding(4.dp)
                        .clickable {
                            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                            roleLauncher.launch(intent)
                        },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Open settings",
                modifier = Modifier
                    .padding(4.dp)
                    .clickable {
                        val intent = Intent(Settings.ACTION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (query.text.isNotEmpty()) {
                    IconButton(onClick = { query = TextFieldValue("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear search"
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.packageName + "/" + it.activityName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { launchApp(context, app.packageName, app.activityName) }
                        .padding(vertical = 12.dp)
                ) {
                    Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun loadLaunchableApps(pm: PackageManager): List<LaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolved = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    return resolved
        .mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val label = info.loadLabel(pm)?.toString() ?: activityInfo.packageName
            LaunchableApp(
                label = label,
                packageName = activityInfo.packageName,
                activityName = activityInfo.name
            )
        }
        .sortedBy { it.label.lowercase() }
}

private fun launchApp(context: Context, packageName: String, activityName: String) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setClassName(packageName, activityName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}