package com.example.appsorter

import android.content.Context
import android.content.Intent
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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val EXTRA_CATEGORY = "category"

data class AppItem(
    val label: String,
    val pkg: String,
    val category: String,
    val overridden: Boolean
)

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
    "Other" to CategoryStyle("📦", 0xFF9E9E9E.toInt())
)

fun styleOf(cat: String): CategoryStyle =
    categoryStyles[cat] ?: CategoryStyle("📦", 0xFF9E9E9E.toInt())

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

fun categoryIcon(cat: String): Icon {
    val st = styleOf(cat)
    val size = 432
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(st.color)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.textSize = 170f
    paint.textAlign = Paint.Align.CENTER
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(st.emoji, size / 2f, y, paint)
    return Icon.createWithAdaptiveBitmap(bmp)
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
        .setIcon(categoryIcon(category))
        .setIntent(intent)
        .build()
    sm.requestPinShortcut(info, null)
}

@Composable
fun SorterScreen(startCategory: String?, refreshTick: Int) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("overrides", Context.MODE_PRIVATE) }
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var localTick by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(refreshTick, localTick) {
        apps = withContext(Dispatchers.Default) { loadApps(context) }
    }

    val loaded = apps != null
    LaunchedEffect(startCategory, loaded) {
        val list = apps
        if (startCategory != null && list != null) {
            expanded = setOf(startCategory)
            val idx = categoryOrder.filter { c -> list.any { it.category == c } }.indexOf(startCategory)
            if (idx >= 0) listState.animateScrollToItem(idx + 1)
        }
    }

    Scaffold { pad ->
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val filtered = if (query.isBlank()) list
            else list.filter { it.label.contains(query.trim(), ignoreCase = true) }
            val groups = filtered.groupBy { it.category }
            val ordered = categoryOrder.filter { it in groups }.map { it to groups.getValue(it) }
            val searching = query.isNotBlank()

            LazyColumn(
                state = listState,
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
                            list.size.toString() + " apps in " +
                                list.map { it.category }.distinct().size + " categories",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        val trailing: (@Composable () -> Unit)? = if (query.isNotEmpty()) {
                            {
                                Text(
                                    "✕",
                                    modifier = Modifier.clickable { query = "" }.padding(12.dp)
                                )
                            }
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
                    }
                }
                if (ordered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No apps match your search",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ordered.forEach { (cat, catApps) ->
                    item(key = cat) {
                        CategoryCard(
                            cat = cat,
                            apps = catApps,
                            open = searching || cat in expanded,
                            onToggle = {
                                expanded = if (cat in expanded) expanded - cat else expanded + cat
                            },
                            onPin = { pinCategoryShortcut(context, cat) },
                            onLaunch = { app ->
                                context.packageManager.getLaunchIntentForPackage(app.pkg)
                                    ?.let { context.startActivity(it) }
                            },
                            onMove = { app, newCat ->
                                prefs.edit().putString(app.pkg, newCat).apply()
                                localTick++
                            },
                            onReset = { app ->
                                prefs.edit().remove(app.pkg).apply()
                                localTick++
                            },
                            onInfo = { app ->
                                val i = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + app.pkg)
                                )
                                context.startActivity(i)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryCard(
    cat: String,
    apps: List<AppItem>,
    open: Boolean,
    onToggle: () -> Unit,
    onPin: () -> Unit,
    onLaunch: (AppItem) -> Unit,
    onMove: (AppItem, String) -> Unit,
    onReset: (AppItem) -> Unit,
    onInfo: (AppItem) -> Unit
) {
    val st = styleOf(cat)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(st.color).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(st.emoji, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(cat, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    apps.size.toString() + (if (apps.size == 1) " app" else " apps"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onPin,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) { Text("📌 Pin") }
            Spacer(Modifier.width(8.dp))
            Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
                apps.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { app ->
                            AppCell(
                                app = app,
                                modifier = Modifier.weight(1f),
                                onLaunch = { onLaunch(app) },
                                onMove = { c -> onMove(app, c) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCell(
    app: AppItem,
    modifier: Modifier,
    onLaunch: () -> Unit,
    onMove: (String) -> Unit,
    onReset: () -> Unit,
    onInfo: () -> Unit
) {
    val context = LocalContext.current
    val icon = remember(app.pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(app.pkg).toBitmap(128, 128).asImageBitmap()
        }.getOrNull()
    }
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
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(52.dp))
            else Spacer(Modifier.size(52.dp))
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
                DropdownMenuItem(text = { Text("Move to category…") }, onClick = { moving = true })
                if (app.overridden) {
                    DropdownMenuItem(
                        text = { Text("Reset to automatic") },
                        onClick = { menu = false; onReset() }
                    )
                }
                DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; onInfo() })
            } else {
                categoryOrder.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(styleOf(c).emoji + "  " + c) },
                        onClick = { menu = false; moving = false; onMove(c) }
                    )
                }
            }
        }
    }
}
