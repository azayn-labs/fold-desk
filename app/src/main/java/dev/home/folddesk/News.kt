package dev.home.folddesk

import android.app.Notification
import android.app.PendingIntent
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale

/**
 * A headline a news app sent as a notification, kept for the Today page.
 * FoldDesk has no internet, so this is where its news comes from.
 */
data class NewsStory(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    val postTime: Long,
    /** The story's picture, or the notification's large icon. Not kept across restarts. */
    val picture: ImageBitmap? = null,
    /** Opens the story; gone after a restart, when the app itself is opened instead. */
    val intent: PendingIntent? = null,
    val autoCancel: Boolean = true,
)

object News {
    /** News apps that don't declare the news category. */
    private val KNOWN = setOf(
        "com.google.android.apps.magazines", // Google News
        GOOGLE_APP, // its Discover updates
        "de.axelspringer.yana", // upday / Samsung News
        "com.microsoft.amp.apps.bingnews",
        "flipboard.app",
        "bbc.mobile.news.ww",
        "bbc.mobile.news.uk",
    )

    /** Social apps that call themselves news apps; their posts aren't stories. */
    private val SOCIAL = setOf(
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.instagram.barcelona", // Threads
        "com.facebook.katana",
    )

    private val isNews = HashMap<String, Boolean>()

    fun isNewsApp(pm: PackageManager, pkg: String): Boolean = isNews.getOrPut(pkg) {
        when (pkg) {
            in SOCIAL -> false
            in KNOWN -> true
            else -> try {
                pm.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_NEWS
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /** The news apps' current notifications, one story each. */
    fun stories(context: Context, active: List<StatusBarNotification>): List<NewsStory> = active
        .filter { it.isClearable && !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        .filter { isNewsApp(context.packageManager, it.packageName) }
        .mapNotNull { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString().orEmpty().trim()
            if (title.isEmpty() && text.isEmpty()) return@mapNotNull null
            NewsStory(
                key = sbn.key,
                pkg = sbn.packageName,
                title = title.ifEmpty { text },
                text = if (title.isEmpty()) "" else text,
                postTime = sbn.postTime,
                picture = picture(context, sbn.notification),
                intent = sbn.notification.contentIntent,
                autoCancel = sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
            )
        }

    private fun picture(context: Context, n: Notification): ImageBitmap? {
        val extras = n.extras
        val bitmap = extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            ?: extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)?.let { icon(context, it) }
            ?: n.getLargeIcon()?.let { icon(context, it) }
            ?: return null
        // Cards are at most about this wide; full-size pictures would just use memory.
        val scaled = if (bitmap.width > MAX_PICTURE_PX) {
            bitmap.scale(MAX_PICTURE_PX, bitmap.height * MAX_PICTURE_PX / bitmap.width)
        } else bitmap
        return scaled.asImageBitmap()
    }

    private fun icon(context: Context, icon: Icon): Bitmap? =
        runCatching { icon.loadDrawable(context)?.toBitmap() }.getOrNull()

    /**
     * The first installed news app that offers a widget, to suggest on the Today
     * page. Not the Google app: its widgets are search and At a Glance, not stories.
     */
    fun suggestedWidget(context: Context): AppWidgetProviderInfo? = Widgets.manager.installedProviders
        .filter { it.provider.packageName != GOOGLE_APP && isNewsApp(context.packageManager, it.provider.packageName) }
        .minByOrNull { if (it.provider.packageName == "com.google.android.apps.magazines") 0 else 1 }

    private const val MAX_PICTURE_PX = 900
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
}
