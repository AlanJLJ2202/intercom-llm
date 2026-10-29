package mx.alan.intercom.core.lab

/**
 * Traduce UUIDs de servicio Bluetooth (SDP clásico y GATT) a perfiles
 * conocidos. Los UUID no reconocidos se marcan como "desconocido/propietario":
 * son candidatos para investigación, NO capacidades confirmadas.
 */
object BluetoothUuidCatalog {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    private val known = mapOf(
        0x1101 to "SPP (Serial Port)",
        0x1108 to "HSP (Headset)",
        0x1112 to "HSP AG (Headset Audio Gateway)",
        0x110A to "A2DP Source",
        0x110B to "A2DP Sink",
        0x110C to "AVRCP Target",
        0x110D to "A2DP (Advanced Audio)",
        0x110E to "AVRCP Controller",
        0x110F to "AVRCP Controller (alt)",
        0x111E to "HFP Hands-Free",
        0x111F to "HFP Audio Gateway",
        0x1124 to "HID",
        0x112F to "PBAP PSE",
        0x1200 to "PnP Information (Device ID)",
        0x1203 to "Generic Audio",
        0x1800 to "GATT Generic Access",
        0x1801 to "GATT Generic Attribute",
        0x180A to "GATT Device Information",
        0x180F to "GATT Battery Service",
        0x1812 to "GATT HID over GATT",
        0x184E to "LE Audio ASCS",
        0x184F to "LE Audio BASS",
        0x1850 to "LE Audio PACS",
        0xFE59 to "Nordic DFU (OTA)",
        0xFEF5 to "Dialog Semiconductor (servicio registrado)",
        0xFFF0 to "Vendor-specific 0xFFF0 (común en SoCs chinos)",
        0xFFE0 to "Vendor-specific 0xFFE0 (UART BLE común)",
        0xAE00 to "Vendor-specific 0xAE00 (común en SoCs de audio chinos)",
    )

    /** Perfiles que importan para el objetivo (voz bidireccional y control). */
    val AUDIO_CRITICAL = setOf(0x111E, 0x1108, 0x110B, 0x110C, 0x110E)

    fun shortId(uuid: String): Int? {
        val u = uuid.lowercase().trim()
        if (u.length == 4) return u.toIntOrNull(16)
        if (u.length == 36 && u.endsWith(BASE_SUFFIX) && u.startsWith("0000")) return u.substring(4, 8).toIntOrNull(16)
        return null
    }

    fun describe(uuid: String): UuidInfo {
        val short = shortId(uuid)
        val name = short?.let { known[it] }
        return UuidInfo(
            uuid = uuid.lowercase(),
            shortId = short?.let { "0x%04X".format(it) },
            name = name ?: if (short == null) "UUID de 128 bits no estándar (posible servicio propietario)" else "Desconocido",
            standard = name != null && (short ?: 0) !in 0xFF00..0xFFFF && (short ?: 0) != 0xAE00,
            audioCritical = short != null && short in AUDIO_CRITICAL,
        )
    }
}

data class UuidInfo(val uuid: String, val shortId: String?, val name: String, val standard: Boolean, val audioCritical: Boolean)
