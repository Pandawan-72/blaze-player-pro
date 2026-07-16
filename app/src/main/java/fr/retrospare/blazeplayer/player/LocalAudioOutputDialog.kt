package fr.retrospare.blazeplayer.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import fr.retrospare.blazeplayer.R

/** Sélectionne le périphérique préféré de l'ExoPlayer local sans modifier la route Cast visuelle. */
object LocalAudioOutputDialog {
    fun show(activity: Activity) {
        val devices = AudioOutputDevicePreferences.availableDevices(activity)
        if (devices.isEmpty()) {
            android.widget.Toast.makeText(
                activity,
                activity.getString(R.string.audio_output_picker_unavailable),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val labels = buildList {
            add(activity.getString(R.string.audio_output_automatic))
            addAll(devices.map(AudioOutputDevicePreferences::displayName))
        }.toTypedArray()
        val selected = if (AudioOutputDevicePreferences.isAutomatic(activity)) {
            0
        } else {
            devices.indexOfFirst { AudioOutputDevicePreferences.isSelected(activity, it) }
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: 0
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.audio_local_output_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val deviceId = if (which == 0) -1 else devices.getOrNull(which - 1)?.id ?: -1
                applySelection(activity, deviceId)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun applySelection(activity: Activity, deviceId: Int) {
        val token = SessionToken(activity, ComponentName(activity, BlazePlayerService::class.java))
        val controllerFuture = MediaController.Builder(activity, token).buildAsync()
        controllerFuture.addListener(controllerListener@{
            val controller = runCatching { controllerFuture.get() }.getOrElse {
                MediaController.releaseFuture(controllerFuture)
                showFailure(activity)
                return@controllerListener
            }
            val args = Bundle().apply {
                putInt(BlazePlayerService.EXTRA_PREFERRED_AUDIO_DEVICE_ID, deviceId)
            }
            val commandFuture = controller.sendCustomCommand(
                SessionCommand(BlazePlayerService.COMMAND_SET_PREFERRED_AUDIO_DEVICE, Bundle.EMPTY),
                args
            )
            commandFuture.addListener(commandListener@{
                val success = runCatching { commandFuture.get().resultCode == SessionResult.RESULT_SUCCESS }
                    .getOrDefault(false)
                MediaController.releaseFuture(controllerFuture)
                if (activity.isFinishing || activity.isDestroyed) return@commandListener
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(
                        if (success) R.string.audio_output_changed else R.string.audio_output_change_failed
                    ),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }, ContextCompat.getMainExecutor(activity))
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun showFailure(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        android.widget.Toast.makeText(
            activity,
            activity.getString(R.string.audio_output_change_failed),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}
