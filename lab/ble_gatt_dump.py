#!/usr/bin/env python3
"""
EXP-02 (BLE) desde PC: escanea anuncios BLE y, opcionalmente, enumera GATT
de un candidato leyendo SOLO características con propiedad 'read'.

Nunca escribe, nunca se suscribe a notificaciones, nunca inicia DFU/OTA.

Requisitos: pip install bleak
Uso:
  python3 lab/ble_gatt_dump.py --scan 15
  python3 lab/ble_gatt_dump.py --address AA:BB:CC:DD:EE:FF --out experiments/evidence/gatt.json
"""
import argparse
import asyncio
import datetime as dt
import json
import sys

from bt_uuids import describe

try:
    from bleak import BleakClient, BleakScanner
except ImportError:  # pragma: no cover
    sys.exit("Falta bleak: pip install bleak")


async def scan(seconds: float, match: str):
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    rows = []
    for addr, (dev, adv) in found.items():
        name = dev.name or adv.local_name
        rows.append({
            "name": name,
            "address": addr,
            "rssi": adv.rssi,
            "service_uuids": [describe(u) for u in adv.service_uuids],
            "manufacturer_ids": [f"0x{k:04X}" for k in adv.manufacturer_data.keys()],
            "candidate": bool(name and any(m in name.lower() for m in match.split("|"))),
        })
    return sorted(rows, key=lambda r: (not r["candidate"], -(r["rssi"] or -999)))


async def dump(address: str):
    out = {"address": address, "services": []}
    async with BleakClient(address, timeout=20) as client:
        for svc in client.services:
            s = {"service": describe(svc.uuid), "characteristics": []}
            for ch in svc.characteristics:
                c = {"uuid": ch.uuid, "properties": ch.properties}
                if "read" in ch.properties:
                    try:
                        v = await client.read_gatt_char(ch)
                        c["value_hex"] = v.hex()
                        c["value_ascii"] = "".join(chr(b) if 32 <= b < 127 else "." for b in v)
                    except Exception as e:  # lectura puede requerir cifrado
                        c["read_error"] = str(e)
                s["characteristics"].append(c)
            out["services"].append(s)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scan", type=float, default=0, help="segundos de escaneo")
    ap.add_argument("--match", default="ejeas|v6|intercom")
    ap.add_argument("--address", help="dirección BLE a enumerar (solo lectura)")
    ap.add_argument("--out")
    a = ap.parse_args()
    report = {"captured_at": dt.datetime.now().astimezone().isoformat()}
    if a.scan:
        report["scan"] = asyncio.run(scan(a.scan, a.match))
    if a.address:
        report["gatt"] = asyncio.run(dump(a.address))
    if not a.scan and not a.address:
        ap.error("usa --scan N y/o --address MAC")
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if a.out:
        open(a.out, "w", encoding="utf-8").write(text)
    print(text)


if __name__ == "__main__":
    main()
