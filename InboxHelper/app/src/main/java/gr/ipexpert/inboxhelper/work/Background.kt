package gr.ipexpert.inboxhelper.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import gr.ipexpert.inboxhelper.MainActivity
import gr.ipexpert.inboxhelper.R
import gr.ipexpert.inboxhelper.ai.AiException
import gr.ipexpert.inboxhelper.ai.AiService
import gr.ipexpert.inboxhelper.data.AnalysisState
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.engine.Clock
import gr.ipexpert.inboxhelper.engine.DeadlineState
import gr.ipexpert.inboxhelper.engine.IntelEngine
import gr.ipexpert.inboxhelper.engine.SlaState
import gr.ipexpert.inboxhelper.engine.Stats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Background AI pipeline: new/changed conversations are queued, analysed with bounded concurrency,
 * and retried with exponential backoff. The message is always kept even if AI fails.
 * Cost control: only conversations whose content changed since the last analysis are sent.
 */
object Analyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private const val MAX_ATTEMPTS = 5
    lateinit var appContext: Context

    fun enqueue(id: String, force: Boolean = false) {
        if (!inFlight.add(id)) return
        scope.launch {
            try { gate.withPermit { run(id, force) } } finally { inFlight.remove(id) }
        }
    }

    private suspend fun run(id: String, force: Boolean) {
        val c = Repo.get(id) ?: return
        if (!force && c.analysis != null && !c.stale) {
            if (c.analysisState != AnalysisState.DONE) Repo.update(id) { it.copy(analysisState = AnalysisState.DONE) }
            return
        }
        if (!Settings.hasKey) {
            Repo.update(id) { it.copy(analysisState = AnalysisState.FAILED, analysisError = "Add your Claude API key in Settings") }
            return
        }
        Repo.update(id) { it.copy(analysisState = AnalysisState.RUNNING, analysisError = "") }
        val hash = c.contentHash
        try {
            val a = AiService.analyze(c)
            Repo.update(id) { cur ->
                cur.copy(
                    analysis = a, analyzedHash = hash, attempts = 0, nextRetryAt = 0,
                    analysisState = if (cur.contentHash == hash) AnalysisState.DONE else AnalysisState.PENDING,
                )
            }
            Repo.log(id, "ai", "analyzed", "${a.aiPriority}, imp ${a.importance}, urg ${a.urgency}, status ${a.status}, conf ${a.confidence}% (${a.model})")
            Notifier.afterAnalysis(appContext, id)
            // Content changed while we were analysing: run again once this job has released its slot.
            if (Repo.get(id)?.analysisState == AnalysisState.PENDING) later(id, 1_000)
        } catch (e: Exception) {
            val retryable = (e as? AiException)?.retryable ?: true
            val attempts = c.attempts + 1
            val delay = if (retryable && attempts < MAX_ATTEMPTS) 30_000L * (1L shl (attempts - 1)) else 0L
            Repo.update(id) {
                it.copy(
                    analysisState = AnalysisState.FAILED, analysisError = e.message ?: "Analysis failed",
                    attempts = attempts, nextRetryAt = if (delay > 0) System.currentTimeMillis() + delay else 0L,
                )
            }
            Repo.log(id, "ai", "analysis_failed", "attempt $attempts: ${e.message}")
            if (delay > 0) later(id, delay)
        }
    }

    private fun later(id: String, ms: Long) {
        scope.launch { kotlinx.coroutines.delay(ms); enqueue(id) }
    }

    /** Called periodically and at startup: picks up pending work and due retries. */
    fun sweep() {
        if (!Settings.autoAnalyze || !Settings.hasKey) return
        val now = System.currentTimeMillis()
        Repo.conversations.value.forEach { c ->
            val due = when (c.analysisState) {
                AnalysisState.PENDING -> true
                AnalysisState.FAILED -> c.nextRetryAt in 1..now
                else -> c.analysis == null || c.stale
            }
            if (due && !c.archived) enqueue(c.id)
        }
    }
}

/** Sends replies through the reply button the original app put on its notification. */
object Replier {
    fun canReply(id: String): Boolean = Repo.replyActions.containsKey(id)

    fun send(ctx: Context, id: String, text: String): Boolean {
        val action = Repo.replyActions[id] ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(ctx, 0, intent)
            Repo.addMyReply(id, text, Repo.get(id)?.appName ?: "app")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun open(ctx: Context, id: String): Boolean {
        try {
            val pi = Repo.openIntents[id]
            if (pi != null) {
                val opts = if (Build.VERSION.SDK_INT >= 34)
                    ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    ).toBundle()
                else null
                pi.send(ctx, 0, null, null, null, null, opts)
                return true
            }
            val pkg = Repo.get(id)?.pkg ?: return false
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: Exception) {
            return false
        }
    }
}

/** The app's own alerts (critical items, SLA, deadlines, escalations, reminders, briefing). */
object Notifier {
    const val CH_ALERTS = "alerts"
    const val CH_SLA = "sla"
    const val CH_REMINDERS = "reminders"
    const val CH_BRIEFING = "briefing"
    const val EXTRA_CONV = "conv_id"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Critical & escalations", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SLA, "SLA & deadlines", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Follow-up reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_BRIEFING, "Daily briefing", NotificationManager.IMPORTANCE_LOW))
    }

    private fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, channel: String, key: String, title: String, text: String, convId: String = "") {
        if (Settings.alertSent(key)) return
        Settings.markAlert(key)
        if (!allowed(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (convId.isNotBlank()) intent.putExtra(EXTRA_CONV, convId)
        val pi = PendingIntent.getActivity(ctx, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(key.hashCode(), n) } catch (_: SecurityException) { }
    }

    fun afterAnalysis(ctx: Context, id: String) {
        val c = Repo.get(id) ?: return
        val i = IntelEngine.compute(c, System.currentTimeMillis(), Repo.corrections.value)
        val a = c.analysis ?: return
        if (!i.waitingOnMe && !a.escalation) return
        val hash = c.analyzedHash
        if (Settings.notifyCritical && i.priority == Level.CRITICAL)
            post(ctx, CH_ALERTS, "crit:$id:$hash", "Critical · ${c.contact}", a.summary + "\nNext: " + a.nextAction, id)
        if (Settings.notifyEscalation && a.escalation)
            post(ctx, CH_ALERTS, "esc:$id:$hash", "Potential escalation · ${c.contact}", a.escalationReason.ifBlank { a.summary }, id)
    }

    /** Periodic checks: SLA approaching/breached, deadlines, due reminders, morning briefing. */
    fun periodic(ctx: Context) {
        val now = System.currentTimeMillis()
        val all = IntelEngine.computeAll(Repo.conversations.value, now, Repo.corrections.value)
        if (Settings.notifySla) all.forEach { i ->
            val since = i.unansweredSince
            when (i.slaState) {
                SlaState.AT_RISK -> post(ctx, CH_SLA, "slarisk:${i.conv.id}:$since", "SLA at risk · ${i.conv.contact}",
                    "${Level.label(i.priority)} — ${Clock.duration(i.slaRemainingMs)} left. ${i.conv.analysis?.nextAction ?: ""}", i.conv.id)
                SlaState.BREACHED -> post(ctx, CH_SLA, "slabreach:${i.conv.id}:$since", "SLA breached · ${i.conv.contact}",
                    "Unanswered for ${Clock.duration(i.waitedMs)}. ${i.conv.analysis?.summary ?: ""}", i.conv.id)
                else -> {}
            }
        }
        if (Settings.notifyDeadline) all.filter { it.deadline != null && it.status !in setOf("completed", "no_action", "answered") }.forEach { i ->
            val d = i.deadline ?: return@forEach
            val mins = Duration.between(LocalDateTime.now(Clock.zone), d).toMinutes()
            if (mins in 0..180) post(ctx, CH_SLA, "deadline:${i.conv.id}:$d", "Deadline soon · ${i.conv.contact}",
                "${i.conv.analysis?.deadlineText ?: ""} (${Clock.duration(mins * 60_000)} left). ${i.conv.analysis?.nextAction ?: ""}", i.conv.id)
            if (i.deadlineState == DeadlineState.OVERDUE && i.waitingOnMe) post(ctx, CH_SLA, "overdue:${i.conv.id}:$d", "Deadline passed · ${i.conv.contact}",
                i.conv.analysis?.summary ?: "", i.conv.id)
        }
        Repo.tasks.value.filter { !it.done && it.dueAt in 1..now }.forEach { t ->
            post(ctx, CH_REMINDERS, "task:${t.id}", "Follow up", t.text, t.convId)
        }
        val today = Clock.today().toString()
        if (Settings.morningBriefing && Settings.lastBriefingDay != today && LocalDateTime.now(Clock.zone).hour >= Settings.briefingHour) {
            Settings.lastBriefingDay = today
            post(ctx, CH_BRIEFING, "brief:$today", "Your briefing", Stats.briefing(all, Repo.tasks.value, false, now))
        }
    }
}

/** Every 15 minutes (Android's minimum): retries AI work, retention cleanup, SLA/deadline/reminder alerts. */
class MonitorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        Repo.applyRetention(now)
        if (gr.ipexpert.inboxhelper.m365.M365Config.connected) {
            // Keep the knowledge index inside the chosen history window, then pull changes from Microsoft 365.
            gr.ipexpert.inboxhelper.index.Index.deleteOlderThan(now - (gr.ipexpert.inboxhelper.m365.M365Config.historyMonths + 1) * 30L * 86_400_000L)
            try { gr.ipexpert.inboxhelper.m365.SyncEngine.syncAll("periodic") } catch (_: Exception) { }
        }
        Analyzer.sweep()
        Notifier.periodic(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("monitor", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Exact-ish reminder for a follow-up task. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("task") ?: return Result.success()
        val t = Repo.tasks.value.firstOrNull { it.id == id } ?: return Result.success()
        if (!t.done) Notifier.post(applicationContext, Notifier.CH_REMINDERS, "task:${t.id}", "Follow up", t.text, t.convId)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context, taskId: String, dueAt: Long) {
            val delay = (dueAt - System.currentTimeMillis()).coerceAtLeast(0)
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("task" to taskId))
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
