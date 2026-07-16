package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Préférence de sortie propre au moteur audio local. Elle n'utilise pas MediaRouter : sélectionner
 * une enceinte Bluetooth ici ne ferme donc pas la session Chromecast utilisée comme écran karaoké.
 */
object AudioOutputDevicePreferences {
    private const val PREFS = "blaze_audio_output_device"
    private const val KEY_AUTOMATIC = "automatic"
    private const val KEY_TYPE = "type"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    fun availableDevices(context: Context): List<AudioDeviceInfo> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        val allowedTypes = buildSet {
            add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_USB_DEVICE)
            add(AudioDeviceInfo.TYPE_USB_ACCESSORY)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_DOCK)
            add(AudioDeviceInfo.TYPE_HDMI)
            add(AudioDeviceInfo.TYPE_HDMI_ARC)
            add(AudioDeviceInfo.TYPE_LINE_ANALOG)
            add(AudioDeviceInfo.TYPE_LINE_DIGITAL)
            add(AudioDeviceInfo.TYPE_AUX_LINE)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
                add(AudioDeviceInfo.TYPE_HDMI_EARC)
            }
            if (Build.VERSION.SDK_INT >= 35) {
                add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
            }
        }
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .asSequence()
            .filter { it.isSink && it.type in allowedTypes }
            .distinctBy { stableKey(it) }
            .sortedWith(
                compareBy<AudioDeviceInfo> { devicePriority(it.type) }
                    .thenBy { displayName(it).lowercase() }
            )
            .toList()
    }

    fun save(context: Context, device: AudioDeviceInfo?) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (device == null) {
                    putBoolean(KEY_AUTOMATIC, true)
                    remove(KEY_TYPE)
                    remove(KEY_ADDRESS)
                    remove(KEY_NAME)
                } else {
                    putBoolean(KEY_AUTOMATIC, false)
                    putInt(KEY_TYPE, device.type)
                    putString(KEY_ADDRESS, device.address.orEmpty())
                    putString(KEY_NAME, device.productName?.toString().orEmpty())
                }
            }
            .apply()
    }

    fun isAutomatic(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOMATIC, true)

    fun preferredDevice(context: Context): AudioDeviceInfo? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTOMATIC, true)) return null
        val type = prefs.getInt(KEY_TYPE, AudioDeviceInfo.TYPE_UNKNOWN)
        val address = prefs.getString(KEY_ADDRESS, null).orEmpty()
        val name = prefs.getString(KEY_NAME, null).orEmpty()
        val devices = availableDevices(context)
        if (address.isNotBlank()) {
            devices.firstOrNull { it.type == type && it.address == address }?.let { return it }
        }
        if (name.isNotBlank()) {
            devices.firstOrNull {
                it.type == type && it.productName?.toString().orEmpty().equals(name, ignoreCase = true)
            }?.let { return it }
        }
        return devices.firstOrNull { it.type == type }
    }

    fun isSelected(context: Context, device: AudioDeviceInfo): Boolean {
        val selected = preferredDevice(context) ?: return false
        return stableKey(selected) == stableKey(device)
    }

    fun displayName(device: AudioDeviceInfo): String =
        device.productName?.toString()?.trim().orEmpty().ifBlank {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones"
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI"
                else -> "Audio output"
            }
        }

    private fun stableKey(device: AudioDeviceInfo): String =
        "${device.type}:${device.address}:${device.productName}"

    private fun devicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
        AudioDeviceInfo.TYPE_HEARING_AID -> 3
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET -> 4
        else -> 5
    }
}
