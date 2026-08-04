package dev.nodera.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps this node on the network when the screen is off.
 *
 * ## Why this exists
 *
 * The worker runs inside this app's process (see [NoderaWorker]). That was a deliberate choice —
 * an Android app may not spawn a VM — but it left the node's lifetime equal to the *app's*
 * lifetime, and Android ends an app's lifetime whenever it likes. Measured in the field: a phone
 * holding committee seats and replicating a world was killed roughly 25 minutes into a session
 * with the screen idle. `pidof` was empty, the control port refused connections, and every peer it
 * was serving simply lost it. Nothing in the app noticed, because there was nothing left to notice.
 *
 * A peer that disappears whenever its owner locks the phone is not a peer anybody can plan around:
 * the swarm counts it towards a world's durability, hands it committee seats, and then finds it
 * gone. This is limitation **M-2**, whose exit test is "the node survives the screen being off for
 * an hour with the app backgrounded".
 *
 * ## What it does and does not promise
 *
 * A `dataSync` foreground service tells Android this process is doing work on the user's behalf,
 * which is the only supported way to keep it alive while nothing is on screen. It is not a
 * guarantee — the system may still reclaim the process under real memory pressure — so
 * [onStartCommand] returns [START_STICKY] and the service is restarted when that happens.
 *
 * The notification is not decoration either: a foreground service must show one, and a user who
 * cannot tell what a permanent notification is for turns it off. It says what the node is doing.
 */
class NoderaWorkerService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.node_running)))
        // The worker's own start is idempotent, so a restart by the system is harmless: it either
        // begins the worker or finds it already running.
        runCatching { NoderaWorker.start(applicationContext) }
            .onFailure { Log.e(TAG, "worker: could not start from the service", it) }
        return START_STICKY
    }

    /** Nothing binds to this: it exists to hold the process up, not to be talked to. */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.node_channel_name),
            // LOW: present and readable, never a sound or a heads-up. A node quietly doing its job
            // must not interrupt anybody to say so.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.node_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "NoderaMC"
        private const val CHANNEL_ID = "nodera-node"
        private const val NOTIFICATION_ID = 1

        /**
         * Start the node, in the only way that outlives the screen going off.
         *
         * Replaces a direct [NoderaWorker.start] call: same worker, different owner. Safe to call
         * more than once — Android delivers a second [onStartCommand] and the worker ignores it.
         */
        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, NoderaWorkerService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                // A phone that refuses the service must still run the node for as long as it can,
                // rather than not running it at all — the old behaviour, as a floor.
                Log.e(TAG, "worker: foreground service refused; running in-process instead", it)
                NoderaWorker.start(context.applicationContext)
            }
        }
    }
}
