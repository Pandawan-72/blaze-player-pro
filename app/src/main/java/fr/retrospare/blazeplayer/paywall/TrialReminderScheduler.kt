package fr.retrospare.blazeplayer.paywall

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.MainActivity
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.repository.SubscriptionAccessState
import fr.retrospare.blazeplayer.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Programme le rappel à J-2 et un contrôle à l'expiration de l'essai. */
object TrialReminderScheduler {
    const val ACTION_TRIAL_REMINDER = "fr.retrospare.blazeplayer.action.TRIAL_REMINDER"
    const val ACTION_TRIAL_EXPIRED = "fr.retrospare.blazeplayer.action.TRIAL_EXPIRED"
    const val EXTRA_OPEN_PAYWALL = "openPaywall"

    private const val REQUEST_REMINDER = 15013
    private const val REQUEST_EXPIRY = 15015

    fun sync(context: Context, state: SubscriptionAccessState) {
        if (state.trialStartMillis <= 0L || state.isProPurchased || state.isProPlusPurchased) {
            cancel(context)
            return
        }

        val wallClockNow = System.currentTimeMillis()
        val effectiveNow = maxOf(wallClockNow, state.evaluatedAtMillis)
        fun alarmTimeFor(targetMillis: Long): Long =
            wallClockNow + (targetMillis - effectiveNow).coerceAtLeast(5_000L)

        val reminderAt = state.trialEndMillis - UserRepository.TRIAL_REMINDER_BEFORE_END_MILLIS
        if (!state.trialReminderSent && state.isTrialActive) {
            scheduleAlarm(
                context,
                ACTION_TRIAL_REMINDER,
                alarmTimeFor(reminderAt),
                REQUEST_REMINDER
            )
        } else {
            cancelAlarm(context, ACTION_TRIAL_REMINDER, REQUEST_REMINDER)
        }

        if (state.isTrialActive) {
            scheduleAlarm(context, ACTION_TRIAL_EXPIRED, alarmTimeFor(state.trialEndMillis), REQUEST_EXPIRY)
        } else {
            cancelAlarm(context, ACTION_TRIAL_EXPIRED, REQUEST_EXPIRY)
        }
    }

    fun cancel(context: Context) {
        cancelAlarm(context, ACTION_TRIAL_REMINDER, REQUEST_REMINDER)
        cancelAlarm(context, ACTION_TRIAL_EXPIRED, REQUEST_EXPIRY)
    }

    private fun scheduleAlarm(context: Context, action: String, atMillis: Long, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = receiverPendingIntent(context, action, requestCode)
        // Aucun accès spécial « alarmes exactes » n'est demandé : cette alarme reste compatible
        // avec les règles Play Store et peut être légèrement regroupée par Android en mode veille.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
    }

    private fun cancelAlarm(context: Context, action: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(receiverPendingIntent(context, action, requestCode))
    }

    private fun receiverPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TrialReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

@AndroidEntryPoint
class TrialReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var userRepository: UserRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        val state = userRepository.ensureTrialStarted()
                        TrialReminderScheduler.sync(context, state)
                    }
                    TrialReminderScheduler.ACTION_TRIAL_REMINDER -> handleReminder(context)
                    TrialReminderScheduler.ACTION_TRIAL_EXPIRED -> handleExpiry(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleReminder(context: Context) {
        val state = userRepository.currentAccessState()
        if (!state.isTrialActive || state.isProPlusPurchased || state.trialReminderSent) {
            TrialReminderScheduler.sync(context, state)
            return
        }
        val remaining = state.trialEndMillis - state.evaluatedAtMillis
        if (remaining > UserRepository.TRIAL_REMINDER_BEFORE_END_MILLIS + 15L * 60L * 1000L) {
            TrialReminderScheduler.sync(context, state)
            return
        }
        // Une seule tentative est faite. Si l'utilisateur a refusé les notifications Android,
        // relancer une alarme toutes les cinq secondes serait inutile et énergivore.
        postReminderNotification(context)
        userRepository.markTrialReminderSent()
        TrialReminderScheduler.sync(context, userRepository.currentAccessState())
    }

    private suspend fun handleExpiry(context: Context) {
        val state = userRepository.currentAccessState()
        if (!state.hasProPlusAccess) {
            // Le lecteur audio premium ne doit pas continuer en arrière-plan une fois l'essai fini.
            context.stopService(Intent(context, fr.retrospare.blazeplayer.player.BlazePlayerService::class.java))
        }
        TrialReminderScheduler.sync(context, state)
    }

    private fun postReminderNotification(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val channelId = "pro_plus_trial"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    context.getString(R.string.trial_notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val openPaywall = PendingIntent.getActivity(
            context,
            15048,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(TrialReminderScheduler.EXTRA_OPEN_PAYWALL, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(context.getString(R.string.trial_notification_title))
            .setContentText(context.getString(R.string.trial_notification_message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.trial_notification_message)))
            .setContentIntent(openPaywall)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(15048, notification)
        return true
    }
}
