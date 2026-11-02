package com.downtube.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Runs the queue one item at a time in the foreground so downloads survive the
 * app going to the background.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var engineChecked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { cancelItem(it) }
            ACTION_CANCEL_ALL -> cancelAll()
        }

        ensureForeground()
        startWorker()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ queue

    private fun startWorker() {
        if (worker?.isActive == true) return

        worker = scope.launch {
            while (isActive) {
                val item = DownloadStore.takeNext() ?: break
                updateNotification(item)
                runItem(item)
            }
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun runItem(item: DownloadItem) {
        val workDir = File(cacheDir, "work/${item.id}").apply { mkdirs() }
        try {
            refreshEngine(item)
            val before = workDir.listFiles().orEmpty().map { it.name }.toSet()

            Engine.run(applicationContext, item, workDir) { progress, eta ->
                DownloadStore.update(item.id) {
                    it.progress = progress.toInt().coerceIn(0, 100)
                    it.etaSeconds = eta
                    if (progress > 0f && it.progress > 0) it.message = null
                }
                updateNotification(item)
            }

            val produced = workDir.listFiles()
                ?.filter { it.isFile && it.name !in before }
                .orEmpty()
                .ifEmpty { workDir.listFiles()?.filter { it.isFile }.orEmpty() }

            if (produced.isEmpty()) throw IllegalStateException("nothing was downloaded")

            var lastUri: Uri? = null
            var lastName: String? = null
            for (file in produced) {
                lastUri = MediaSaver.publish(applicationContext, file)
                lastName = file.name
                file.delete()
            }
            workDir.deleteRecursively()

            val count = produced.size
            DownloadStore.update(item.id) {
                it.status = DownloadStatus.DONE
                it.progress = 100
                it.etaSeconds = 0
                it.uri = lastUri
                it.outputName = if (count > 1) "$count files" else lastName
                it.message = if (count > 1) "Saved $count files to Downloads/downtube" else "Saved to Downloads/downtube"
            }
            Feedback.success(applicationContext)
            item.uri?.let { notifyResult(item, getString(R.string.download_finished), it) }
        } catch (e: YoutubeDL.CanceledException) {
            markCancelled(item.id)
        } catch (e: InterruptedException) {
            markCancelled(item.id)
        } catch (t: Throwable) {
            val message = describeFailure(t)
            DownloadStore.update(item.id) {
                it.status = DownloadStatus.FAILED
                it.progress = 0
                it.message = message
            }
            Feedback.error(applicationContext)
            notifyResult(item, getString(R.string.download_failed), null)
        }
    }

    /**
     * The yt-dlp binary bundled in the APK ages out; refresh it before the first
     * download of a session so extraction keeps working.
     */
    private fun refreshEngine(item: DownloadItem) {
        if (engineChecked) return
        engineChecked = true
        val stale = System.currentTimeMillis() - Prefs.lastEngineUpdate(this) > 3L * 24 * 60 * 60 * 1000
        if (!stale) {
            Engine.init(applicationContext)
            return
        }

        DownloadStore.update(item.id) { it.message = getString(R.string.updating_engine) }
        updateNotification(item)
        runCatching { Engine.ensureFresh(applicationContext) }
        DownloadStore.update(item.id) { if (it.progress <= 0) it.message = null }
    }

    /** Friendly headline for the list, plus the raw engine line for diagnosis. */
    private fun describeFailure(t: Throwable): String {
        val friendly = FriendlyError.from(t)
        val raw = (t.message ?: "")
            .replace("\n", " ")
            .replace("ERROR: ", "")
            .trim()
        if (raw.isEmpty() || raw.equals(friendly, ignoreCase = true)) return friendly
        if (friendly.contains(raw.take(40), ignoreCase = true)) return friendly
        return "$friendly\n${raw.take(180)}"
    }

    private fun markCancelled(id: String) {
        DownloadStore.update(id) {
            it.status = DownloadStatus.CANCELLED
            it.progress = 0
            it.etaSeconds = 0
            it.message = "Cancelled"
        }
    }

    /** A separate, dismissible notification for items that finished. */
    private fun notifyResult(item: DownloadItem, titleText: String, uri: Uri?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val intent = if (uri != null) {
            Intent(this, PlayerActivity::class.java).setData(uri)
                .putExtra(PlayerActivity.EXTRA_TITLE, item.outputName ?: item.title)
        } else {
            Intent(this, MainActivity::class.java)
        }
        val pending = PendingIntent.getActivity(
            this,
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(titleText)
            .setContentText(item.title)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(item.id.hashCode(), notification)
    }

    private fun cancelItem(id: String) {
        val item = DownloadStore.find(id) ?: return
        if (item.status == DownloadStatus.QUEUED) {
            markCancelled(id)
            return
        }
        scope.launch { Engine.cancel(id) }
    }

    private fun cancelAll() {
        DownloadStore.all().forEach { item ->
            when (item.status) {
                DownloadStatus.QUEUED -> markCancelled(item.id)
                DownloadStatus.RUNNING -> scope.launch { Engine.cancel(item.id) }
                else -> Unit
            }
        }
    }

    // ----------------------------------------------------------- notification

    private fun ensureForeground() {
        createChannel()
        val notification = buildNotification(DownloadStore.running())
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateNotification(item: DownloadItem?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(item))
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows the active download and lets you cancel it"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(item: DownloadItem?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle(item?.title ?: "DownTube")

        if (item == null) {
            builder.setContentText("Preparing download engine…")
        } else {
            val eta = if (item.etaSeconds > 0) " - ETA ${item.etaSeconds / 60}:${(item.etaSeconds % 60).toString().padStart(2, '0')}" else ""
            builder.setContentText("Downloading ${item.progress}%$eta")
            builder.setProgress(100, item.progress, item.progress <= 0)

            val pending = DownloadStore.pendingCount()
            if (pending > 0) builder.setSubText("$pending more in queue")

            val cancel = PendingIntent.getService(
                this,
                item.id.hashCode(),
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, item.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Cancel", cancel)
        }

        return builder.build()
    }

    companion object {
        const val ACTION_START = "com.downtube.app.START"
        const val ACTION_CANCEL = "com.downtube.app.CANCEL"
        const val ACTION_CANCEL_ALL = "com.downtube.app.CANCEL_ALL"
        const val EXTRA_ID = "id"

        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(ACTION_START),
            )
        }

        fun cancel(context: Context, id: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            )
        }

        fun newId(): String = UUID.randomUUID().toString()
    }
}
