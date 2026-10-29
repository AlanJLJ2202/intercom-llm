#!/usr/bin/env python3
"""
EXP-01: identificar el chip por USB (no invasivo).

Conecta el intercomunicador por USB-C a un PC Linux. Si el puerto expone datos
(no solo carga), el descriptor USB (VID:PID, fabricante, producto, clase)
suele revelar el fabricante del SoC o del puente de actualización.

Uso:
  python3 lab/usb_probe.py --before   # con el casco DESCONECTADO
  python3 lab/usb_probe.py --after --out experiments/evidence/usb.json   # ya conectado

Solo lee /sys/bus/usb/devices. No abre el dispositivo ni envía comandos.
"""
import argparse
import json
import os
import sys
import tempfile

SYS = "/sys/bus/usb/devices"
SNAP = os.path.join(tempfile.gettempdir(), "usb_probe_before.json")

# VIDs públicos relevantes para SoCs de audio Bluetooth (referencia, no prueba).
VENDOR_HINTS = {
    "0a12": "Cambridge Silicon Radio / Qualcomm (CSR)",
    "05c6": "Qualcomm",
    "10d6": "Actions Semiconductor",
    "1915": "Nordic Semiconductor",
    "0bda": "Realtek",
    "4c4a": "posible Jieli (JL) — verificar",
    "1a86": "QinHeng (CH340/CH55x, puente serie)",
    "0483": "STMicroelectronics",
    "10c4": "Silicon Labs (CP210x)",
}


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().strip()
    except OSError:
        return None


def snapshot(root=SYS):
    devs = {}
    if not os.path.isdir(root):
        return devs
    for d in os.listdir(root):
        p = os.path.join(root, d)
        vid = read(os.path.join(p, "idVendor"))
        if not vid:
            continue
        devs[d] = {
            "path": d,
            "vid": vid,
            "pid": read(os.path.join(p, "idProduct")),
            "manufacturer": read(os.path.join(p, "manufacturer")),
            "product": read(os.path.join(p, "product")),
            "serial_present": read(os.path.join(p, "serial")) is not None,
            "bcdDevice": read(os.path.join(p, "bcdDevice")),
            "class": read(os.path.join(p, "bDeviceClass")),
            "speed": read(os.path.join(p, "speed")),
            "interfaces": sorted(
                {read(os.path.join(p, i, "bInterfaceClass")) for i in os.listdir(p) if os.path.exists(os.path.join(p, i, "bInterfaceClass"))}
                - {None}
            ),
            "vendor_hint": VENDOR_HINTS.get(vid.lower()),
        }
    return devs


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--before", action="store_true")
    g.add_argument("--after", action="store_true")
    ap.add_argument("--out")
    a = ap.parse_args()
    if a.before:
        json.dump(snapshot(), open(SNAP, "w"))
        print(f"Instantánea guardada ({SNAP}). Conecta el casco y ejecuta --after.")
        return
    before = json.load(open(SNAP)) if os.path.exists(SNAP) else {}
    now = snapshot()
    new = [v for k, v in now.items() if k not in before]
    result = {"new_devices": new, "note": "Sin dispositivos nuevos: el puerto USB-C podría ser solo de carga." if not new else None}
    text = json.dumps(result, indent=2, ensure_ascii=False)
    if a.out:
        open(a.out, "w").write(text)
    print(text)
    if not before:
        print("Aviso: no había instantánea --before; se listan diferencias contra vacío.", file=sys.stderr)


if __name__ == "__main__":
    main()
