"""Catálogo de UUIDs Bluetooth (espejo de BluetoothUuidCatalog.kt)."""

BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

KNOWN = {
    0x1101: "SPP (Serial Port)",
    0x1108: "HSP (Headset)",
    0x1112: "HSP AG",
    0x110A: "A2DP Source",
    0x110B: "A2DP Sink",
    0x110C: "AVRCP Target",
    0x110D: "A2DP (Advanced Audio)",
    0x110E: "AVRCP Controller",
    0x110F: "AVRCP Controller (alt)",
    0x111E: "HFP Hands-Free",
    0x111F: "HFP Audio Gateway",
    0x1124: "HID",
    0x1200: "PnP Information (Device ID)",
    0x1800: "GATT Generic Access",
    0x1801: "GATT Generic Attribute",
    0x180A: "GATT Device Information",
    0x180F: "GATT Battery Service",
    0x1812: "GATT HID over GATT",
    0xFE59: "Nordic DFU (OTA)",
    0xFFF0: "Vendor-specific 0xFFF0",
    0xFFE0: "Vendor-specific 0xFFE0 (UART BLE común)",
    0xAE00: "Vendor-specific 0xAE00",
}

AUDIO_CRITICAL = {0x111E, 0x1108, 0x110B, 0x110C, 0x110E}


def short_id(uuid: str):
    u = uuid.lower().strip()
    if len(u) == 4:
        return int(u, 16)
    if len(u) == 36 and u.endswith(BASE_SUFFIX) and u.startswith("0000"):
        return int(u[4:8], 16)
    return None


def describe(uuid: str) -> dict:
    s = short_id(uuid)
    name = KNOWN.get(s) if s is not None else None
    return {
        "uuid": uuid.lower(),
        "short": f"0x{s:04X}" if s is not None else None,
        "name": name or ("UUID de 128 bits no estándar (posible servicio propietario)" if s is None else "Desconocido"),
        "audio_critical": s in AUDIO_CRITICAL if s is not None else False,
    }
