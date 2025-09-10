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
import android.net.Uri
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
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
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
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activityName: String
)

private enum class SortMode { ALPHABETICAL, RECENT }

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
    var sortMode by remember { mutableStateOf(SortMode.RECENT) }

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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = { openDialer(context) }, modifier = Modifier.padding(0.dp)) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Open phone",
                        modifier = Modifier
                            .height(24.dp)
                    )
                }
                IconButton(onClick = { openMaps(context) }, modifier = Modifier.padding(0.dp)) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "Open maps",
                        modifier = Modifier
                            .height(24.dp)
                    )
                }
                IconButton(onClick = { openSms(context) }, modifier = Modifier.padding(0.dp)) {
                    Icon(
                        imageVector = Icons.Default.Message,
                        contentDescription = "Open messages",
                        modifier = Modifier
                            .height(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { openSettings(context) }, modifier = Modifier.padding(2.dp)) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Open settings",
                    modifier = Modifier
                        .height(24.dp)
                )
            }
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

        Spacer(modifier = Modifier.height(8.dp))

        val options = listOf("A–Z", "Recent")
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, label ->
                val selected = when (index) {
                    0 -> sortMode == SortMode.ALPHABETICAL
                    else -> sortMode == SortMode.RECENT
                }
                SegmentedButton(
                    selected = selected,
                    onClick = {
                        sortMode = if (index == 0) SortMode.ALPHABETICAL else SortMode.RECENT
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                ) {
                    Text(label)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val hasUsageAccess = remember { hasUsageAccess(context) }
        if (sortMode == SortMode.RECENT && !hasUsageAccess) {
            Text(
                text = "Grant usage access in Settings for smarter sorting",
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .clickable {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        val usageMap = remember(hasUsageAccess, sortMode) { if (sortMode == SortMode.RECENT) getLastUsedMap(context) else emptyMap() }
        val sorted = remember(filtered, usageMap, sortMode) {
            when (sortMode) {
                SortMode.ALPHABETICAL -> filtered.sortedBy { it.label.lowercase() }
                SortMode.RECENT -> if (usageMap.isEmpty()) filtered.sortedBy { it.label.lowercase() } else filtered.sortedWith(
                    compareByDescending<LaunchableApp> { usageMap[it.packageName] ?: 0L }.thenBy { it.label.lowercase() }
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(sorted, key = { it.packageName + "/" + it.activityName }) { app ->
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

private fun hasUsageAccess(context: Context): Boolean {
    return try {
        val appOps = android.app.AppOpsManager.OPSTR_GET_USAGE_STATS
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOpsManager.unsafeCheckOpNoThrow(appOps, android.os.Process.myUid(), context.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}

private fun getLastUsedMap(context: Context): Map<String, Long> {
    return try {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 1000L * 60L * 60L * 24L * 30L // last 30 days
        val stats: List<UsageStats> = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        stats.groupBy { it.packageName }
            .mapValues { (_, list) -> list.maxOfOrNull { it.lastTimeUsed } ?: 0L }
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun openDialer(context: Context) {
    val intent = Intent(Intent.ACTION_DIAL).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

private fun openMaps(context: Context) {
    val geoUri = Uri.parse("geo:0,0?q=")
    val intent = Intent(Intent.ACTION_VIEW, geoUri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

private fun openSms(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("smsto:")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

private fun openSettings(context: Context) {
    val intent = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}
