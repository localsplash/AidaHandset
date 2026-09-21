package ai.localsplash.aida.handset

import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceIdentifier {
    fun getLocalIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return ips
    }

    fun getClaimedMac(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback) continue
                val mac = intf.hardwareAddress ?: continue
                if (mac.isNotEmpty()) {
                    val hex = mac.joinToString("") { "%02x".format(it) }
                    if (hex != "020000000000") return hex
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun getDeviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
