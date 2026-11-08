package com.example.appsorter

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderPopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = intent?.getStringExtra(EXTRA_CATEGORY)
        if (folder == null) {
            finish()
            return
        }
        setContent { AppTheme { PopupContent(folder) { finish() } } }
    }
}

@Composable
fun PopupContent(folder: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("overrides", Context.MODE_PRIVATE) }
    val folderPrefs = remember { context.getSharedPreferences("folders", Context.MODE_PRIVATE) }
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        apps = withContext(Dispatchers.Default) { loadApps(context) }
    }
    val list = apps ?: return
    val folderApps = remember(list, folder) { appsForFolder(context, folder, list) }
    val menuFolders = categoryOrder.filter { it != "Other" } + loadNames(folderPrefs) + "Other"

    FolderDialog(
        name = folder,
        apps = folderApps,
        folders = menuFolders,
        onDismiss = onClose,
        onPin = {},
        onEdit = {
            context.startActivity(
                Intent(context, MainActivity::class.java).putExtra(EXTRA_CATEGORY, folder)
            )
            onClose()
        },
        onLaunch = { app ->
            context.packageManager.getLaunchIntentForPackage(app.pkg)
                ?.let { context.startActivity(it) }
            onClose()
        },
        onMove = { app, f ->
            prefs.edit().putString(app.pkg, f).apply()
            tick++
        },
        onReset = { app ->
            prefs.edit().remove(app.pkg).apply()
            tick++
        },
        onInfo = { app ->
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + app.pkg)
                )
            )
            onClose()
        },
        showPin = false,
        showEdit = folder != RARELY
    )
}

fun folderTileBitmap(context: Context, apps: List<AppItem>, folder: String): Bitmap {
    val size = 480
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    bg.color = 0xCC2E3036.toInt()
    canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 110f, 110f, bg)
    if (apps.isEmpty()) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 200f
        paint.textAlign = Paint.Align.CENTER
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(styleOf(folder).emoji, size / 2f, y, paint)
    } else {
        val pad = 48
        val cell = (size - 2 * pad) / 3
        val iconSize = 104
        val gap = (cell - iconSize) / 2
        val pm = context.packageManager
        apps.take(9).forEachIndexed { i, app ->
            val ib = runCatching {
                pm.getApplicationIcon(app.pkg).toBitmap(iconSize, iconSize)
            }.getOrNull()
            if (ib != null) {
                val left = pad + (i % 3) * cell + gap
                val top = pad + (i / 3) * cell + gap
                canvas.drawBitmap(ib, left.toFloat(), top.toFloat(), null)
            }
        }
    }
    return bmp
}

fun updateFolderWidget(
    context: Context,
    manager: AppWidgetManager,
    id: Int,
    apps: List<AppItem>
) {
    val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    val folder = prefs.getString("w" + id, null) ?: return
    val folderApps = appsForFolder(context, folder, apps)
    val views = RemoteViews(context.packageName, R.layout.widget_folder)
    views.setImageViewBitmap(R.id.widget_tile, folderTileBitmap(context, folderApps, folder))
    views.setTextViewText(R.id.widget_label, folder)
    val intent = Intent(context, FolderPopupActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(EXTRA_CATEGORY, folder)
    val pi = PendingIntent.getActivity(
        context,
        id,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.widget_root, pi)
    manager.updateAppWidget(id, views)
}

fun updateAllWidgets(context: Context, apps: List<AppItem>) {
    val mgr = AppWidgetManager.getInstance(context)
    val ids = mgr.getAppWidgetIds(ComponentName(context, FolderWidgetProvider::class.java))
    ids.forEach { updateFolderWidget(context, mgr, it, apps) }
}

class FolderWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val apps = loadApps(context)
        appWidgetIds.forEach { updateFolderWidget(context, appWidgetManager, it, apps) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val ed = context.getSharedPreferences("widgets", Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { ed.remove("w" + it) }
        ed.apply()
    }
}

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            AppTheme {
                WidgetConfigContent(
                    onPick = { folder ->
                        applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)
                            .edit().putString("w" + widgetId, folder).apply()
                        updateFolderWidget(
                            applicationContext,
                            AppWidgetManager.getInstance(applicationContext),
                            widgetId,
                            loadApps(applicationContext)
                        )
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        )
                        finish()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }
}

@Composable
fun WidgetConfigContent(onPick: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val folderPrefs = remember { context.getSharedPreferences("folders", Context.MODE_PRIVATE) }
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadApps(context) }
    }
    val list = apps ?: return
    val custom = loadNames(folderPrefs)
    val names = (categoryOrder.filter { it != "Other" } + custom + "Other" + RARELY)
        .filter { n -> n == RARELY || n in custom || list.any { it.category == n } }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Choose a folder",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(names, key = { it }) { n ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(n) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(styleOf(n).emoji + "  " + n, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
