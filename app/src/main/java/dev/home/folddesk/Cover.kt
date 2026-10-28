package dev.home.folddesk

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.service.notification.StatusBarNotification
import android.util.Log

/** One app's notifications as the cover screen shows them: the newest, plus how many there are. */
data class CoverNotification(
    val pkg: String,
    val keys: List<String>,
    val title: String,
    val text: String,
    val postTime: Long,
    val count: Int,
    val color: Int,
    val intent: PendingIntent?,
    val autoCancel: Boolean,
    /** Every notification in the group, newest first. */
    val items: List<Item>,
) {
    /** One notification inside an app's group. */
    data class Item(
        val key: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val intent: PendingIntent?,
        val autoCancel: Boolean,
    ) {
        fun open(context: Context, fallback: () -> Unit) = send(context, intent, listOf(key), autoCancel, fallback)
    }

    companion object {
        /**
         * Media controls, including One UI's own "MediaOngoingActivity" from
         * System UI, which isn't flagged as ongoing.
         */
        private fun isMedia(sbn: StatusBarNotification): Boolean {
            val n = sbn.notification
            return n.category == Notification.CATEGORY_TRANSPORT ||
                n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true ||
                (sbn.packageName == "com.android.systemui" &&
                    n.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains("Media") == true)
        }

        /** Newest first, one entry per app; group summaries only count when they're all there is. */
        fun from(active: List<StatusBarNotification>): List<CoverNotification> = active
            // Music players and other ongoing notifications aren't news.
            .filter { it.isClearable && !it.isOngoing && it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE == 0 }
            .filter { !isMedia(it) }
            .groupBy { it.packageName }
            .mapNotNull { (pkg, list) ->
                val shown = list.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.ifEmpty { list }
                    .sortedByDescending { it.postTime }
                val items = shown.map { it.toItem() }
                val newest = items.firstOrNull() ?: return@mapNotNull null
                CoverNotification(
                    pkg = pkg,
                    keys = list.map { it.key },
                    title = newest.title,
                    text = newest.text,
                    postTime = newest.postTime,
                    count = shown.size,
                    color = shown.first().notification.color,
                    intent = newest.intent,
                    autoCancel = newest.autoCancel,
                    items = items,
                )
            }
            .sortedByDescending { it.postTime }

        private fun StatusBarNotification.toItem(): Item {
            val extras = notification.extras
            return Item(
                key = key,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                postTime = postTime,
                intent = notification.contentIntent,
                autoCancel = notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
            )
        }

        /** Opens what a notification points at, as tapping it in the shade would. */
        private fun send(context: Context, intent: PendingIntent?, keys: List<String>, autoCancel: Boolean, fallback: () -> Unit) {
            val pending = intent ?: return fallback()
            try {
                val mode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                } else {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode)
                pending.send(context, 0, null, null, null, null, options.toBundle())
                if (autoCancel) NotificationWatcher.dismiss(keys)
            } catch (e: PendingIntent.CanceledException) {
                fallback()
            }
        }
    }

    /** Opens the newest notification. */
    fun open(context: Context, fallback: () -> Unit) = send(context, intent, keys, autoCancel, fallback)
}

data class CoverEvent(
    val id: Long,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val title: String,
    val location: String,
    val color: Int,
) {
    /** The calendar day it falls on. All-day events are stored at UTC midnight, so read them in UTC. */
    val day: java.time.LocalDate
        get() = java.time.Instant.ofEpochMilli(begin)
            .atZone(if (allDay) java.time.ZoneOffset.UTC else java.time.ZoneId.systemDefault()).toLocalDate()

    /** Last day it covers (an all-day event's end is the next UTC midnight). */
    val lastDay: java.time.LocalDate
        get() = if (allDay) {
            java.time.Instant.ofEpochMilli(end - 1).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        } else {
            java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }
}

/** Today's and tomorrow's events, read from the phone's calendars. */
object CoverCalendar {
    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun load(context: Context, now: Long = System.currentTimeMillis()): List<CoverEvent> {
        if (!hasPermission(context)) return emptyList()
        val start = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 2) }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start.timeInMillis)
            ContentUris.appendId(it, end.timeInMillis)
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DISPLAY_COLOR,
        )
        return try {
            context.contentResolver.query(uri, columns, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val event = CoverEvent(
                            id = c.getLong(0), begin = c.getLong(1), end = c.getLong(2), allDay = c.getInt(3) == 1,
                            title = c.getString(4).orEmpty(), location = c.getString(5).orEmpty(), color = c.getInt(6),
                        )
                        // Only today and tomorrow; the query window is in local time, which
                        // can catch all-day events of the day after in time zones west of UTC.
                        val today = java.time.LocalDate.now()
                        val overlaps = event.day <= today.plusDays(1) && event.lastDay >= today
                        if (overlaps && (event.allDay || event.end > now)) add(event)
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w("FoldDesk", "Calendar query failed", e)
            emptyList()
        }
    }

    fun openEvent(context: Context, event: CoverEvent) = startSafely(
        context,
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end),
    )

    fun openCalendar(context: Context) =
        startSafely(context, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))

    private fun startSafely(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("FoldDesk", "No activity for $intent", e)
        }
    }
}
