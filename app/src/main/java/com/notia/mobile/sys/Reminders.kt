package com.notia.mobile.sys

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.Store
import java.util.Calendar

object Reminders {
    const val CH_TASKS = "task_alarm"
    const val CH_READ = "reading"
    const val ACT_TASK = "com.notia.mobile.TASK"
    const val ACT_READ = "com.notia.mobile.READING"
    const val ACT_DONE = "com.notia.mobile.TASK_DONE"
    const val ACT_SNOOZE = "com.notia.mobile.TASK_SNOOZE"
    const val CH_NOTE = "daily_note"
    const val CH_IDLE = "come_back"
    const val ACT_MORNING = "com.notia.mobile.MORNING"
    const val ACT_IDLE = "com.notia.mobile.IDLE"
    /** Extra on the notification tap intent: open a blank note straight away. */
    const val EXTRA_NEW_NOTE = "com.notia.mobile.NEW_NOTE"
    private const val READ_HOUR = 19
    private const val IDLE_HOUR = 18
    private const val DAY = 86_400_000L
    /** Days away after which Notia nudges the user (then stops, so it never nags forever). */
    private val IDLE_STEPS = intArrayOf(3, 7, 14, 30)

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TASKS, if (Store.lang == "fr") "Alarmes de tâches" else "Task alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            // rings like an alarm so a due task is hard to miss
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM) ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_READ, if (Store.lang == "fr") "Rappels de lecture" else "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_NOTE, if (Store.lang == "fr") "Rappel d’écriture du matin" else "Morning writing reminder", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_IDLE, if (Store.lang == "fr") "On se retrouve ?" else "Come-back reminders", NotificationManager.IMPORTANCE_LOW))
    }

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun enabled(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun openExactSettings(a: Activity) {
        try { if (Build.VERSION.SDK_INT >= 31) a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.packageName))) } catch (e: Exception) {}
    }

    fun openNotificationSettings(a: Activity) {
        try { a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)) } catch (e: Exception) {}
    }

    private fun pi(ctx: Context, action: String, key: String, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("notia://$action/$key")).apply(extras)
        return PendingIntent.getBroadcast(ctx, key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Returns true when the alarm will fire at the exact time. */
    fun schedule(ctx: Context, id: String, due: Long): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, ACT_TASK, id) { putExtra("id", id) }
        return if (canExact(ctx)) { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, p); true }
        else { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, p); false }
    }

    fun cancel(ctx: Context, id: String) { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, ACT_TASK, id) { putExtra("id", id) }) }

    fun topBook() = Store.books.filter { it.pct in 0.02..0.97 }.maxByOrNull { it.time }

    /** Daily 19:00 nudge for the most-read unfinished book. */
    fun scheduleReading(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, ACT_READ, "daily")
        if (!Store.remBooks || topBook() == null) { am.cancel(p); return }
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, READ_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis() + 60_000) c.add(Calendar.DAY_OF_YEAR, 1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, p)
    }

    private fun nextAt(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis() + 60_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Every morning: "got an idea? write it down". */
    fun scheduleMorning(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, ACT_MORNING, "daily")
        if (!Store.remMorning) { am.cancel(p); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt(Store.morningHour, Store.morningMin), p)
    }

    /** Whole days since the app was last used, rounded so an alarm that fires a little early still counts. */
    private fun daysAway(): Int = if (Store.lastOpen <= 0L) 0 else Math.round((System.currentTimeMillis() - Store.lastOpen) / DAY.toDouble()).toInt()

    /** Arms the next "we miss you" nudge (after 3, 7, 14 and 30 days away). Opening the app resets the countdown. */
    fun scheduleIdle(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, ACT_IDLE, "idle")
        val next = IDLE_STEPS.firstOrNull { it > daysAway() }
        if (!Store.remInactive || Store.lastOpen <= 0L || next == null) { am.cancel(p); return }
        val c = Calendar.getInstance().apply { timeInMillis = Store.lastOpen; add(Calendar.DAY_OF_YEAR, next); set(Calendar.HOUR_OF_DAY, IDLE_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(c.timeInMillis, System.currentTimeMillis() + 60_000), p)
    }

    fun scheduleAll(ctx: Context) { scheduleReading(ctx); scheduleMorning(ctx); scheduleIdle(ctx) }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for (t in Store.tasks) if (!t.done && t.due != null && t.due!! > now) { t.armed = true; schedule(ctx, t.id, t.due!!) } else if (t.armed) t.armed = false
        scheduleAll(ctx)
    }

    /** Asks for the notification permission on Android 13+, then calls back with whether it is usable. */
    fun ask(a: MainActivityLike, cb: (Boolean) -> Unit) {
        if (hasPermission(a.activity)) { cb(enabled(a.activity)); return }
        a.requestNotifPermission { cb(it && enabled(a.activity)) }
    }

    private fun action(ctx: Context, act: String, taskId: String, label: String, icon: Int): Notification.Action {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(act).setData(Uri.parse("notia://$act/$taskId")).putExtra("id", taskId)
        val p = PendingIntent.getBroadcast(ctx, (act + taskId).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, icon), label, p).build()
    }

    /** Task alarm: rings, vibrates and offers Done and Snooze 10 min. */
    fun showTask(ctx: Context, taskId: String, title: String) {
        val fr = Store.lang == "fr"
        show(ctx, CH_TASKS, taskId.hashCode(), if (fr) "Tâche à terminer" else "Time to finish your task", title, false) { b ->
            b.setCategory(Notification.CATEGORY_REMINDER)
            b.addAction(action(ctx, ACT_DONE, taskId, if (fr) "Terminé" else "Done", R.drawable.ic_check))
            b.addAction(action(ctx, ACT_SNOOZE, taskId, if (fr) "Reporter 10 min" else "Snooze 10 min", R.drawable.ic_clock))
        }
    }

    fun show(ctx: Context, channel: String, id: Int, title: String, text: String, newNote: Boolean = false, extra: (Notification.Builder) -> Unit = {}) {
        val tap = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(EXTRA_NEW_NOTE, newNote)
        // own request code per notification, otherwise FLAG_UPDATE_CURRENT would overwrite the extras of the others
        val open = PendingIntent.getActivity(ctx, id, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else @Suppress("DEPRECATION") Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
        extra(b)
        if (hasPermission(ctx)) ctx.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }
}

/** Small seam so Reminders can ask for the permission without depending on the full activity. */
interface MainActivityLike {
    val activity: Activity
    fun requestNotifPermission(cb: (Boolean) -> Unit)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.ensureLoaded(ctx)
        val fr = Store.lang == "fr"
        when (intent.action) {
            Reminders.ACT_TASK -> {
                val t = Store.tasks.firstOrNull { it.id == intent.getStringExtra("id") } ?: return
                if (t.done) return
                t.armed = false
                Reminders.showTask(ctx, t.id, t.title)
                Store.flush(); Store.changed()
            }
            Reminders.ACT_DONE, Reminders.ACT_SNOOZE -> {
                val t = Store.tasks.firstOrNull { it.id == intent.getStringExtra("id") } ?: return
                ctx.getSystemService(NotificationManager::class.java).cancel(t.id.hashCode())
                if (intent.action == Reminders.ACT_DONE) { t.done = true; t.armed = false }
                else { val at = System.currentTimeMillis() + 10 * 60_000L; t.due = at; t.armed = true; t.done = false; Reminders.schedule(ctx, t.id, at) }
                Store.flush(); Store.changed()
            }
            Reminders.ACT_READ -> {
                val b = Reminders.topBook()
                // stay quiet when the person already read today
                if (Store.remBooks && b != null && !sameDay(b.last, System.currentTimeMillis())) {
                    val pc = Math.round(b.pct * 100)
                    val msgs = if (fr) listOf("Terminer « ${b.title} » ce soir ? Vous en avez lu $pc %.", "« ${b.title} » vous attend : $pc % lus. Quelques pages ce soir ?", "Un petit moment de lecture ? Reprenez « ${b.title} » là où vous vous êtes arrêté.")
                               else listOf("Finish \"${b.title}\" tonight? You are $pc% through.", "\"${b.title}\" is waiting: $pc% read. A few pages tonight?", "Time for a reading break? Pick \"${b.title}\" back up where you left off.")
                    Reminders.show(ctx, Reminders.CH_READ, 7001, if (fr) "Finissez votre livre" else "Finish your book", msgs[dayOfYear() % msgs.size])
                }
                Reminders.scheduleReading(ctx)
            }
            Reminders.ACT_MORNING -> {
                // no nudge when a note was already written today
                val now = System.currentTimeMillis()
                if (Store.remMorning && Store.notes.none { sameDay(it.created, now) }) {
                    val msgs = if (fr) listOf("Une idée, une pensée, une liste ? Notez-la avant qu’elle ne s’envole.", "Bonjour ! Qu’avez-vous en tête ce matin ? Écrivez-le en 10 secondes.", "Commencez la journée en écrivant trois choses importantes.", "Une nouvelle journée, une nouvelle page. Que voulez-vous retenir aujourd’hui ?", "Et si vous notiez l’idée qui vous trotte dans la tête ?")
                               else listOf("Got an idea, a thought, a to-do? Write it down before it escapes.", "Good morning! What's on your mind? Capture it in 10 seconds.", "Start the day by writing down your three most important things.", "A fresh day, a fresh page. What do you want to remember today?", "Why not jot down that idea floating around in your head?")
                    Reminders.show(ctx, Reminders.CH_NOTE, 7002, if (fr) "Bonjour ☀️" else "Good morning ☀️", msgs[dayOfYear() % msgs.size], true)
                }
                Reminders.scheduleMorning(ctx)
            }
            Reminders.ACT_IDLE -> {
                val away = if (Store.lastOpen <= 0L) 0 else ((System.currentTimeMillis() - Store.lastOpen) / 86_400_000L).toInt()
                if (Store.remInactive && away >= 2) {
                    val open = Store.tasks.count { !it.done }
                    val b = Reminders.topBook()
                    val text = when {
                        open > 0 -> if (fr) "Vous avez $open tâche(s) en attente. Revenez les terminer." else "You have $open unfinished task(s) waiting for you."
                        b != null -> if (fr) "Reprenez « ${b.title} » : ${Math.round(b.pct * 100)} % lus." else "Pick \"${b.title}\" back up: you are ${Math.round(b.pct * 100)}% through."
                        else -> if (fr) "Vos notes vous attendent. Une idée à noter ?" else "Your notes miss you. Got an idea to capture?"
                    }
                    Reminders.show(ctx, Reminders.CH_IDLE, 7003, if (fr) "Notia vous attend" else "Notia misses you", text, false)
                }
                Reminders.scheduleIdle(ctx)
            }
        }
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    if (a <= 0L) return false
    val x = Calendar.getInstance().apply { timeInMillis = a }; val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun dayOfYear() = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

/** Alarms are cleared on reboot and on app update; this restores them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Store.ensureLoaded(ctx)
        Reminders.channels(ctx)
        Reminders.rescheduleAll(ctx)
        Store.flush()
    }
}
