#!/usr/bin/env python3
"""
EXP-01/02 desde un PC Linux (BlueZ): inventario NO invasivo del intercomunicador.

Requisitos: bluetoothctl (bluez). Opcional: sdptool (bluez-deprecated) para el
volcado SDP completo con versiones de perfil (p. ej. HFP 1.7 = mSBC).

Uso:
  1. Empareja el casco con el PC (bluetoothctl → scan on → pair/trust/connect),
     o al menos tenlo visible.
  2. python3 lab/bt_inventory.py --match ejeas --out experiments/evidence/inventario.json

Solo lee información: no escribe en el dispositivo ni cambia su configuración.
La salida enmascara la MAC (conserva el OUI) salvo con --no-mask.
"""
import argparse
import datetime as dt
import json
import re
import shutil
import subprocess
import sys

from bt_uuids import describe


def run(cmd, timeout=20):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        return f"__error__ {e}"


def parse_devices(out: str):
    """`bluetoothctl devices` → [(mac, nombre)]"""
    return [(m.group(1), m.group(2).strip()) for m in re.finditer(r"^Device ([0-9A-F:]{17}) (.*)$", out, re.M)]


def parse_info(out: str) -> dict:
    """`bluetoothctl info <mac>` → dict con nombre, clase, UUIDs, modalias, estado."""
    info = {"uuids": []}
    for line in out.splitlines():
        line = line.strip()
        m = re.match(r"UUID: (.+?)\s+\(([0-9a-fA-F-]{36})\)", line)
        if m:
            d = describe(m.group(2))
            d["bluez_name"] = m.group(1).strip()
            info["uuids"].append(d)
            continue
        m = re.match(r"(Name|Alias|Class|Icon|Paired|Bonded|Trusted|Connected|Modalias|LegacyPairing|RSSI|TxPower|Battery Percentage): (.+)", line)
        if m:
            info[m.group(1).lower().replace(" ", "_")] = m.group(2).strip()
    if "modalias" in info:
        # Formato bluetooth:vXXXXpYYYYdZZZZ → fabricante/producto/versión (registro Device ID)
        m = re.match(r"bluetooth:v([0-9A-F]{4})p([0-9A-F]{4})d([0-9A-F]{4})", info["modalias"], re.I)
        if m:
            info["device_id"] = {"vendor": "0x" + m.group(1), "product": "0x" + m.group(2), "version": "0x" + m.group(3)}
    return info


def parse_sdp(out: str):
    """`sdptool browse <mac>` → servicios con canal RFCOMM y versión de perfil."""
    services, cur = [], None
    for line in out.splitlines():
        if line.startswith("Service Name:"):
            cur = {"name": line.split(":", 1)[1].strip(), "classes": [], "profiles": []}
            services.append(cur)
        elif cur is not None:
            m = re.search(r'"(.+?)" \((0x[0-9a-fA-F]+)\)', line)
            if m and line.startswith("  ") and cur.get("_section") == "class":
                cur["classes"].append({"name": m.group(1), "id": m.group(2)})
            if line.startswith("Service Class ID List"):
                cur["_section"] = "class"
            elif line.startswith("Profile Descriptor List"):
                cur["_section"] = "profile"
            elif line.startswith("Protocol Descriptor List"):
                cur["_section"] = "proto"
            m2 = re.search(r"Channel: (\d+)", line)
            if m2:
                cur["rfcomm_channel"] = int(m2.group(1))
            m3 = re.search(r"Version: (0x[0-9a-fA-F]+)", line)
            if m3 and cur.get("_section") == "profile":
                cur["profiles"].append(m3.group(1))
    for s in services:
        s.pop("_section", None)
    return services


def mask(mac: str) -> str:
    p = mac.split(":")
    return ":".join(p[:3] + ["XX"] * 3) if len(p) == 6 else mac


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--match", default="ejeas|v6|intercom", help="regex (insensible a mayúsculas) para el nombre")
    ap.add_argument("--out", help="archivo JSON de evidencia")
    ap.add_argument("--no-mask", action="store_true")
    args = ap.parse_args()

    if not shutil.which("bluetoothctl"):
        sys.exit("bluetoothctl no encontrado: instala bluez")

    devices = parse_devices(run(["bluetoothctl", "devices"]))
    pat = re.compile(args.match, re.I)
    report = {
        "captured_at": dt.datetime.now().astimezone().isoformat(),
        "host": run(["uname", "-a"]).strip(),
        "bluez": run(["bluetoothctl", "--version"]).strip(),
        "candidates": [],
        "others": [n for _, n in devices if not pat.search(n)],
    }
    for mac, name in devices:
        if not pat.search(name):
            continue
        entry = {"name": name, "address": mac if args.no_mask else mask(mac), "oui": mac[:8]}
        entry["info"] = parse_info(run(["bluetoothctl", "info", mac]))
        if shutil.which("sdptool"):
            entry["sdp"] = parse_sdp(run(["sdptool", "browse", mac], timeout=30))
        else:
            entry["sdp"] = "sdptool no disponible (paquete bluez-deprecated)"
        report["candidates"].append(entry)

    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"Evidencia guardada en {args.out}")
    print(text)
    if not report["candidates"]:
        print("\nNingún dispositivo coincide. ¿Está emparejado o visible? Prueba: bluetoothctl scan on", file=sys.stderr)


if __name__ == "__main__":
    main()
