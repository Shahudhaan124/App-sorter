package com.example.appsorter

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val EXTRA_CATEGORY = "category"

data class AppItem(val label: String, val pkg: String, val category: String)

class MainActivity : ComponentActivity() {
    private var startCategory by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startCategory = intent?.getStringExtra(EXTRA_CATEGORY)
        setContent { MaterialTheme { SorterScreen(startCategory) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startCategory = intent.getStringExtra(EXTRA_CATEGORY)
    }
}

private val keywordRules: List<Pair<String, List<String>>> = listOf(
    "Social" to listOf("facebook", "instagram", "whatsapp", "telegram", "snapchat", "twitter", "tiktok",
        "messenger", "discord", "linkedin", "reddit", "signal", "pinterest", "wechat", "threads"),
    "Banking & Finance" to listOf("bank", "pay", "wallet", "finance", "paypal", "upi", "bhim", "invest",
        "crypto", "trading", "money"),
    "Shopping" to listOf("amazon", "shop", "flipkart", "ebay", "aliexpress", "walmart", "myntra"),
    "Media & Entertainment" to listOf("youtube", "netflix", "spotify", "music", "video", "player",
        "primevideo", "hotstar", "radio", "podcast", "movie"),
    "Photos & Camera" to listOf("camera", "gallery", "photo"),
    "Maps & Travel" to listOf("maps", "uber", "travel", "airbnb", "booking", "flight", "taxi", "transit",
        "navigation"),
    "Communication" to listOf("dialer", "contacts", "messages", "mms", "mail", "sms", "zoom", "teams",
        "meet", "phone"),
    "Productivity" to listOf("office", "docs", "excel", "sheets", "notes", "calendar", "drive", "pdf",
        "notion", "slack"),
    "Health & Fitness" to listOf("health", "fitness", "workout", "yoga", "sleep"),
    "Education" to listOf("learn", "duolingo", "course", "study", "school", "khan"),
    "News" to listOf("news"),
    "Tools" to listOf("calculator", "clock", "settings", "files", "flashlight", "cleaner", "vpn",
        "chrome", "browser", "firefox")
)

@Suppress("DEPRECATION")
fun categorize(label: String, ai: ApplicationInfo): String {
    if (ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0)
        return "Games"
    val text = (label + " " + ai.packageName).lowercase()
    for ((cat, words) in keywordRules) {
        if (words.any { text.contains(it) }) return cat
    }
    return when (ai.category) {
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Media & Entertainment"
        ApplicationInfo.CATEGORY_IMAGE -> "Photos & Camera"
        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
        ApplicationInfo.CATEGORY_NEWS -> "News"
        ApplicationInfo.CATEGORY_MAPS -> "Maps & Travel"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
        else -> "Other"
    }
}

fun loadApps(pm: PackageManager): List<AppItem> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { ri ->
            val label = ri.loadLabel(pm).toString()
            AppItem(label, ri.activityInfo.packageName, categorize(label, ri.activityInfo.applicationInfo))
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

fun pinCategoryShortcut(context: Context, category: String) {
    val sm = context.getSystemService(ShortcutManager::class.java)
    if (sm == null || !sm.isRequestPinShortcutSupported) {
        Toast.makeText(context, "Your launcher doesn't support pinned shortcuts", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(EXTRA_CATEGORY, category)
    val info = ShortcutInfo.Builder(context, "cat_" + category.replace(" ", "_"))
        .setShortLabel(category)
        .setIcon(Icon.createWithResource(context, R.drawable.ic_folder))
        .setIntent(intent)
        .build()
    sm.requestPinShortcut(info, null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SorterScreen(startCategory: String?) {
    val context = LocalContext.current
    val apps by produceState<List<AppItem>?>(null) {
        value = withContext(Dispatchers.Default) { loadApps(context.packageManager) }
    }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(startCategory) {
        if (startCategory != null) expanded = setOf(startCategory)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("App Sorter") }) }) { pad ->
        val list = apps
        if (list == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val groups = list.groupBy { it.category }
                .toSortedMap(compareBy { if (it == "Other") "zzz" else it })
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                groups.forEach { (cat, items) ->
                    item(key = "header_$cat") {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    expanded = if (cat in expanded) expanded - cat else expanded + cat
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (cat in expanded) "▾ " else "▸ ") + cat + " (" + items.size + ")",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { pinCategoryShortcut(context, cat) }) {
                                Text("Add shortcut")
                            }
                        }
                    }
                    if (cat in expanded) {
                        items(items, key = { "app_" + it.pkg }) { app ->
                            AppRow(app) {
                                context.packageManager.getLaunchIntentForPackage(app.pkg)
                                    ?.let { context.startActivity(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppRow(app: AppItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 32.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(16.dp))
        Text(app.label, style = MaterialTheme.typography.bodyLarge)
    }
}
