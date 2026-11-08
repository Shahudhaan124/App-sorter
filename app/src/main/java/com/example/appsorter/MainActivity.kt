package com.example.appsorter

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

const val EXTRA_CATEGORY = "category"
const val RARELY = "Rarely used"

data class AppItem(
    val label: String,
    val pkg: String,
    val category: String,
    val overridden: Boolean
)

data class EditTarget(val name: String, val isNew: Boolean)

class CategoryStyle(val emoji: String, val color: Int)

val categoryOrder = listOf(
    "Games", "Social", "Media & Entertainment", "Photos & Camera", "Communication",
    "Banking & Finance", "Shopping", "Maps & Travel", "Productivity", "Education",
    "Health & Fitness", "News", "Tools", "Other"
)

private val categoryStyles = mapOf(
    "Games" to CategoryStyle("🎮", 0xFF7E57C2.toInt()),
    "Social" to CategoryStyle("💬", 0xFF29B6F6.toInt()),
    "Media & Entertainment" to CategoryStyle("🎬", 0xFFEF5350.toInt()),
    "Photos & Camera" to CategoryStyle("📷", 0xFFEC407A.toInt()),
    "Communication" to CategoryStyle("📞", 0xFF26A69A.toInt()),
    "Banking & Finance" to CategoryStyle("💳", 0xFF66BB6A.toInt()),
    "Shopping" to CategoryStyle("🛍️", 0xFFFFA726.toInt()),
    "Maps & Travel" to CategoryStyle("🧭", 0xFF42A5F5.toInt()),
    "Productivity" to CategoryStyle("📝", 0xFF5C6BC0.toInt()),
    "Education" to CategoryStyle("🎓", 0xFFFFCA28.toInt()),
    "Health & Fitness" to CategoryStyle("💪", 0xFFFF7043.toInt()),
    "News" to CategoryStyle("📰", 0xFF8D6E63.toInt()),
    "Tools" to CategoryStyle("🔧", 0xFF78909C.toInt()),
    "Other" to CategoryStyle("📦", 0xFF9E9E9E.toInt()),
    RARELY to CategoryStyle("🕸️", 0xFF607D8B.toInt())
)

private val customPalette = listOf(
    0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFFFA726, 0xFF8E24AA, 0xFF00897B
).map { it.toInt() }

fun styleOf(cat: String): CategoryStyle =
    categoryStyles[cat]
        ?: CategoryStyle("📁", customPalette[(cat.hashCode() and 0x7fffffff) % customPalette.size])

fun loadNames(prefs: SharedPreferences): List<String> {
    val raw = prefs.getString("names", "[]") ?: "[]"
    return try {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
}

fun saveNames(prefs: SharedPreferences, names: List<String>) {
    prefs.edit().putString("names", JSONArray(names).toString()).apply()
}

class MainActivity : ComponentActivity() {
    private var startCategory by mutableStateOf<String?>(null)
    private var refreshTick by mutableIntStateOf(0)
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startCategory = intent?.getStringExtra(EXTRA_CATEGORY)
        setContent { AppTheme { SorterScreen(startCategory, refreshTick) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startCategory = intent.getStringExtra(EXTRA_CATEGORY)
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) firstResume = false else refreshTick++
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
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

fun loadApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    val prefs = context.getSharedPreferences("overrides", Context.MODE_PRIVATE)
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { ri ->
            val label = ri.loadLabel(pm).toString()
            val pkg = ri.activityInfo.packageName
            val saved = prefs.getString(pkg, null)
            AppItem(
                label,
                pkg,
                saved ?: categorize(label, ri.activityInfo.applicationInfo),
                saved != null
            )
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@Suppress("DEPRECATION")
fun hasUsageAccess(context: Context): Boolean {
    return try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }
}

fun rarelyUsed(context: Context, apps: List<AppItem>): List<AppItem> {
    return try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val stats = usm.queryAndAggregateUsageStats(now - 90 * day, now)
        val cutoff = now - 30 * day
        val pm = context.packageManager
        apps.filter { app ->
            val last = stats[app.pkg]?.lastTimeUsed ?: 0L
            val installed = try {
                pm.getPackageInfo(app.pkg, 0).firstInstallTime
            } catch (e: Exception) {
                0L
            }
            last < cutoff && installed < cutoff
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun appsForFolder(context: Context, name: String, all: List<AppItem>): List<AppItem> {
    if (name == RARELY) {
        return if (hasUsageAccess(context)) rarelyUsed(context, all) else emptyList()
    }
    return all.filter { it.category == name }
}

fun exportBackup(context: Context): String {
    val prefs = context.getSharedPreferences("overrides", Context.MODE_PRIVATE)
    val folderPrefs = context.getSharedPreferences("folders", Context.MODE_PRIVATE)
    val obj = JSONObject()
    obj.put("names", JSONArray(loadNames(folderPrefs)))
    val ov = JSONObject()
    prefs.all.forEach { (k, v) -> if (v is String) ov.put(k, v) }
    obj.put("overrides", ov)
    return obj.toString(2)
}

fun importBackup(context: Context, json: String): Boolean {
    return try {
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("names")
        val names = (0 until arr.length()).map { arr.getString(it) }
        val ov = obj.getJSONObject("overrides")
        val ed = context.getSharedPreferences("overrides", Context.MODE_PRIVATE).edit()
        ed.clear()
        ov.keys().forEach { k -> ed.putString(k, ov.getString(k)) }
        ed.apply()
        saveNames(context.getSharedPreferences("folders", Context.MODE_PRIVATE), names)
        true
    } catch (e: Exception) {
        false
    }
}

fun categoryIcon(context: Context, cat: String, apps: List<AppItem>): Icon {
    val size = 432
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFF2E3036.toInt())
    if (apps.isEmpty()) {
        val st = styleOf(cat)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 170f
        paint.textAlign = Paint.Align.CENTER
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(st.emoji, size / 2f, y, paint)
    } else {
        val start = 84
        val cell = 88
        val iconSize = 72
        val gap = (cell - iconSize) / 2
        val pm = context.packageManager
        apps.take(9).forEachIndexed { i, app ->
            val ib = runCatching {
                pm.getApplicationIcon(app.pkg).toBitmap(iconSize, iconSize)
            }.getOrNull()
            if (ib != null) {
                val left = start + (i % 3) * cell + gap
                val top = start + (i / 3) * cell + gap
                canvas.drawBitmap(ib, left.toFloat(), top.toFloat(), null)
            }
        }
    }
    return Icon.createWithAdaptiveBitmap(bmp)
}

fun pinCategoryShortcut(context: Context, category: String, apps: List<AppItem>) {
    val sm = context.getSystemService(ShortcutManager::class.java)
    if (sm == null || !sm.isRequestPinShortcutSupported) {
        Toast.makeText(context, "Your launcher doesn't support pinned shortcuts", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(context, FolderPopupActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(EXTRA_CATEGORY, category)
    val info = ShortcutInfo.Builder(context, "cat_" + category.replace(" ", "_"))
        .setShortLabel(category)
        .setIcon(categoryIcon(context, category, apps))
        .setIntent(intent)
        .build()
    sm.requestPinShortcut(info, null)
}

fun refreshPinnedShortcuts(context: Context, apps: List<AppItem>) {
    try {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val updated = sm.pinnedShortcuts.mapNotNull { sc ->
            val scIntent = sc.intent ?: return@mapNotNull null
            val cat = scIntent.getStringExtra(EXTRA_CATEGORY) ?: return@mapNotNull null
            ShortcutInfo.Builder(context, sc.id)
                .setShortLabel(sc.shortLabel ?: cat)
                .setIcon(categoryIcon(context, cat, appsForFolder(context, cat, apps)))
                .setIntent(scIntent)
                .build()
        }
        if (updated.isNotEmpty()) sm.updateShortcuts(updated)
    } catch (e: Exception) {
    }
}

@Composable
fun SorterScreen(startCategory: String?, refreshTick: Int) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("overrides", Context.MODE_PRIVATE) }
    val folderPrefs = remember { context.getSharedPreferences("folders", Context.MODE_PRIVATE) }
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var rarely by remember { mutableStateOf<List<AppItem>?>(null) }
    var customNames by remember { mutableStateOf(loadNames(folderPrefs)) }
    var localTick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var openFolder by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(exportBackup(context).toByteArray())
                }
                true
            } catch (e: Exception) {
                false
            }
            Toast.makeText(context, if (ok) "Backup saved" else "Backup failed", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
            val ok = text != null && importBackup(context, text)
            if (ok) {
                customNames = loadNames(folderPrefs)
                localTick++
            }
            Toast.makeText(context, if (ok) "Backup restored" else "Restore failed", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(refreshTick, localTick) {
        val result = withContext(Dispatchers.Default) {
            val l = loadApps(context)
            val r = if (hasUsageAccess(context)) rarelyUsed(context, l) else null
            Pair(l, r)
        }
        apps = result.first
        rarely = result.second
    }

    LaunchedEffect(apps) {
        val l = apps
        if (l != null) {
            withContext(Dispatchers.Default) {
                refreshPinnedShortcuts(context, l)
                updateAllWidgets(context, l)
            }
        }
    }

    val loaded = apps != null
    LaunchedEffect(startCategory, loaded) {
        if (startCategory != null && loaded) openFolder = startCategory
    }

    Scaffold { pad ->
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val baseNames = categoryOrder.filter { it != "Other" } + customNames + "Other"
            val groups = list.groupBy { it.category }
            val extra = groups.keys.filter { it !in baseNames }
            val menuFolders = baseNames + extra
            val folders = menuFolders
                .filter { it in groups || it in customNames }
                .map { it to (groups[it] ?: emptyList()) }
            val tiles = folders + (RARELY to (rarely ?: emptyList()))

            val searching = query.isNotBlank()
            val results = if (searching) list.filter { it.label.contains(query.trim(), ignoreCase = true) }
            else emptyList()

            val launch: (AppItem) -> Unit = { app ->
                context.packageManager.getLaunchIntentForPackage(app.pkg)
                    ?.let { context.startActivity(it) }
            }
            val move: (AppItem, String) -> Unit = { app, folder ->
                prefs.edit().putString(app.pkg, folder).apply()
                localTick++
            }
            val reset: (AppItem) -> Unit = { app ->
                prefs.edit().remove(app.pkg).apply()
                localTick++
            }
            val info: (AppItem) -> Unit = { app ->
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.pkg)
                    )
                )
            }

            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = pad.calculateTopPadding() + 8.dp,
                        bottom = pad.calculateBottomPadding() + 24.dp
                    )
                ) {
                    item(key = "header") {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Text(
                                "App Sorter",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                list.size.toString() + " apps in " + folders.size + " folders",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            val trailing: (@Composable () -> Unit)? = if (query.isNotEmpty()) {
                                ({
                                    Text(
                                        "✕",
                                        modifier = Modifier.clickable { query = "" }.padding(12.dp)
                                    )
                                })
                            } else null
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search apps") },
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp),
                                trailingIcon = trailing,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(onClick = { editing = EditTarget("", true) }) {
                                    Text("＋ New folder")
                                }
                                FilledTonalButton(onClick = {
                                    exportLauncher.launch("app-sorter-backup.json")
                                }) { Text("⬆ Backup") }
                                FilledTonalButton(onClick = {
                                    importLauncher.launch(arrayOf("*/*"))
                                }) { Text("⬇ Restore") }
                            }
                        }
                    }
                    if (searching) {
                        if (results.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    "No apps match your search",
                                    modifier = Modifier.padding(24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        results.chunked(4).forEachIndexed { i, row ->
                            item(key = "result_" + i) {
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { app ->
                                        AppCell(
                                            app = app,
                                            modifier = Modifier.weight(1f),
                                            folders = menuFolders,
                                            onLaunch = { launch(app) },
                                            onMove = { f -> move(app, f) },
                                            onReset = { reset(app) },
                                            onInfo = { info(app) }
                                        )
                                    }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    } else {
                        tiles.chunked(2).forEach { pair ->
                            item(key = "folders_" + pair.first().first) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    pair.forEach { (n, a) ->
                                        val needsAccess = n == RARELY && rarely == null
                                        FolderTile(
                                            name = n,
                                            apps = a,
                                            modifier = Modifier.weight(1f),
                                            subtitle = if (needsAccess) "Tap to allow usage access" else null,
                                            onClick = {
                                                if (needsAccess) {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                                    )
                                                } else {
                                                    openFolder = n
                                                }
                                            }
                                        )
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                val open = openFolder
                if (open != null) {
                    val openApps = if (open == RARELY) (rarely ?: emptyList())
                    else list.filter { it.category == open }
                    FolderDialog(
                        name = open,
                        apps = openApps,
                        folders = menuFolders,
                        onDismiss = { openFolder = null },
                        onPin = { pinCategoryShortcut(context, open, openApps) },
                        onEdit = { editing = EditTarget(open, false) },
                        onLaunch = launch,
                        onMove = move,
                        onReset = reset,
                        onInfo = info,
                        showEdit = open != RARELY
                    )
                }

                val edit = editing
                if (edit != null) {
                    val custom = edit.isNew || edit.name in customNames
                    val folderApps = list.filter { it.category == edit.name }
                    val deleteAction: (() -> Unit)? = if (custom && !edit.isNew) {
                        ({
                            val ed = prefs.edit()
                            folderApps.forEach { ed.remove(it.pkg) }
                            ed.apply()
                            val names = customNames.filter { it != edit.name }
                            customNames = names
                            saveNames(folderPrefs, names)
                            editing = null
                            openFolder = null
                            localTick++
                        })
                    } else null
                    EditFolderDialog(
                        initialName = edit.name,
                        isNew = edit.isNew,
                        custom = custom,
                        allApps = list,
                        initialSelected = if (edit.isNew) emptySet()
                        else folderApps.map { it.pkg }.toSet(),
                        takenNames = menuFolders.filter { it != edit.name },
                        onDismiss = { editing = null },
                        onSave = { newName, selected ->
                            val oldName = edit.name
                            val byPkg = list.associateBy { it.pkg }
                            val current: Set<String> =
                                if (edit.isNew) emptySet() else folderApps.map { it.pkg }.toSet()
                            val ed = prefs.edit()
                            if (custom) {
                                val names = if (edit.isNew) customNames + newName
                                else customNames.map { if (it == oldName) newName else it }
                                customNames = names
                                saveNames(folderPrefs, names)
                            }
                            selected.forEach { pkg ->
                                if (oldName != newName || byPkg[pkg]?.category != newName) {
                                    ed.putString(pkg, newName)
                                }
                            }
                            (current - selected).forEach { pkg ->
                                if (custom) ed.remove(pkg) else ed.putString(pkg, "Other")
                            }
                            ed.apply()
                            localTick++
                            editing = null
                            openFolder = newName
                        },
                        onDelete = deleteAction
                    )
                }
            }
        }
    }
}

@Composable
fun AppIcon(pkg: String, modifier: Modifier) {
    val context = LocalContext.current
    val icon = remember(pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) Image(icon, contentDescription = null, modifier = modifier)
    else Spacer(modifier)
}

@Composable
fun FolderTile(
    name: String,
    apps: List<AppItem>,
    modifier: Modifier,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val st = styleOf(name)
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (apps.isEmpty()) {
                Text(st.emoji, style = MaterialTheme.typography.displayMedium)
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    for (r in 0 until 3) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for (c in 0 until 3) {
                                val a = apps.getOrNull(r * 3 + c)
                                Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp)) {
                                    if (a != null) AppIcon(a.pkg, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle ?: (apps.size.toString() + (if (apps.size == 1) " app" else " apps")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun FolderDialog(
    name: String,
    apps: List<AppItem>,
    folders: List<String>,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onLaunch: (AppItem) -> Unit,
    onMove: (AppItem, String) -> Unit,
    onReset: (AppItem) -> Unit,
    onInfo: (AppItem) -> Unit,
    showPin: Boolean = true,
    showEdit: Boolean = true
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        styleOf(name).emoji + " " + name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (showEdit) TextButton(onClick = onEdit) { Text("✎ Edit") }
                    if (showPin) TextButton(onClick = onPin) { Text("📌") }
                }
                Spacer(Modifier.height(8.dp))
                if (apps.isEmpty()) {
                    Text(
                        if (name == RARELY) "Nothing here. You've used all your apps recently."
                        else "No apps yet. Tap Edit to add some.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        apps.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { app ->
                                    AppCell(
                                        app = app,
                                        modifier = Modifier.weight(1f),
                                        folders = folders,
                                        onLaunch = { onLaunch(app) },
                                        onMove = { f -> onMove(app, f) },
                                        onReset = { onReset(app) },
                                        onInfo = { onInfo(app) }
                                    )
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditFolderDialog(
    initialName: String,
    isNew: Boolean,
    custom: Boolean,
    allApps: List<AppItem>,
    initialSelected: Set<String>,
    takenNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, Set<String>) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initialName) }
    var selected by remember { mutableStateOf(initialSelected) }
    var nameError by remember { mutableStateOf(false) }
    val sortedApps = remember {
        allApps.sortedWith(
            compareByDescending<AppItem> { it.pkg in initialSelected }.thenBy { it.label.lowercase() }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (isNew) "New folder" else "Edit folder",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (custom) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; nameError = false },
                        label = { Text("Folder name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                } else {
                    Text(
                        initialName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    "Choose apps (" + selected.size + " selected)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(sortedApps, key = { it.pkg }) { a ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selected = if (a.pkg in selected) selected - a.pkg else selected + a.pkg
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = a.pkg in selected, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            AppIcon(a.pkg, Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                a.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (nameError) {
                    Text(
                        "Pick a unique folder name",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onDelete != null) {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Delete") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = {
                        val n = name.trim()
                        if (custom) {
                            val clash = takenNames.any { it.equals(n, ignoreCase = true) }
                            if (n.isEmpty() || clash) nameError = true
                            else onSave(n, selected)
                        } else {
                            onSave(initialName, selected)
                        }
                    }) { Text("Save") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCell(
    app: AppItem,
    modifier: Modifier,
    folders: List<String>,
    onLaunch: () -> Unit,
    onMove: (String) -> Unit,
    onReset: () -> Unit,
    onInfo: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }

    Box(modifier) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onLaunch, onLongClick = { menu = true })
                .padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppIcon(app.pkg, Modifier.size(52.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                app.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false; moving = false }
        ) {
            if (!moving) {
                DropdownMenuItem(text = { Text("Move to folder…") }, onClick = { moving = true })
                if (app.overridden) {
                    DropdownMenuItem(
                        text = { Text("Reset to automatic") },
                        onClick = { menu = false; onReset() }
                    )
                }
                DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; onInfo() })
            } else {
                folders.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(styleOf(f).emoji + "  " + f) },
                        onClick = { menu = false; moving = false; onMove(f) }
                    )
                }
            }
        }
    }
}
