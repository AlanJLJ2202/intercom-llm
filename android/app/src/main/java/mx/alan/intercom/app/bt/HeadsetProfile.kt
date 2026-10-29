package mx.alan.intercom.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Acceso a los proxies de perfil HFP (BluetoothHeadset) y A2DP.
 *
 * BluetoothHeadset permite, sin APIs ocultas:
 *  - saber qué dispositivo está conectado por HFP;
 *  - startVoiceRecognition(): abre SCO enviando +BVRA:1 al casco, el
 *    mecanismo estándar HFP para "sesión de asistente" (alternativa a
 *    setCommunicationDevice, EXP-04/05);
 *  - isAudioConnected(): estado del enlace SCO.
 */
@SuppressLint("MissingPermission")
class HeadsetProfile(private val ctx: Context) {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    @Volatile var headset: BluetoothHeadset? = null; private set
    @Volatile var a2dp: BluetoothA2dp? = null; private set

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.HEADSET -> headset = proxy as BluetoothHeadset
                BluetoothProfile.A2DP -> a2dp = proxy as BluetoothA2dp
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> headset = null
                BluetoothProfile.A2DP -> a2dp = null
            }
        }
    }

    /** Obtiene los proxies y espera hasta [timeoutMs] a que conecten. */
    fun connect(timeoutMs: Long = 2000): Boolean {
        val a = adapter ?: return false
        if (headset != null && a2dp != null) return true
        val latch = CountDownLatch(2)
        val wrapped = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                listener.onServiceConnected(profile, proxy); latch.countDown()
            }
            override fun onServiceDisconnected(profile: Int) = listener.onServiceDisconnected(profile)
        }
        a.getProfileProxy(ctx, wrapped, BluetoothProfile.HEADSET)
        a.getProfileProxy(ctx, wrapped, BluetoothProfile.A2DP)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return headset != null
    }

    fun hfpDevices(): List<BluetoothDevice> = headset?.connectedDevices ?: emptyList()
    fun a2dpDevices(): List<BluetoothDevice> = a2dp?.connectedDevices ?: emptyList()

    fun startVoiceRecognition(device: BluetoothDevice): Boolean = headset?.startVoiceRecognition(device) ?: false
    fun stopVoiceRecognition(device: BluetoothDevice): Boolean = headset?.stopVoiceRecognition(device) ?: false
    fun isScoConnected(device: BluetoothDevice): Boolean = headset?.isAudioConnected(device) ?: false

    fun close() {
        headset?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headset = null; a2dp = null
    }
}
