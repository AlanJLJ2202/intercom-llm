import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import bt_inventory  # noqa: E402
import usb_probe  # noqa: E402
from bt_uuids import describe  # noqa: E402

FIX = os.path.join(os.path.dirname(__file__), "fixtures")


class ParserTests(unittest.TestCase):
    def test_info(self):
        info = bt_inventory.parse_info(open(os.path.join(FIX, "bluetoothctl_info_example.txt")).read())
        names = [u["name"] for u in info["uuids"]]
        self.assertIn("HFP Hands-Free", names)
        self.assertIn("A2DP Sink", names)
        self.assertTrue(any(u["short"] is None for u in info["uuids"]))
        self.assertEqual(info["device_id"]["vendor"], "0x05D6")
        self.assertEqual(info["class"], "0x00240404")

    def test_sdp(self):
        sdp = bt_inventory.parse_sdp(open(os.path.join(FIX, "sdptool_browse_example.txt")).read())
        hf = sdp[0]
        self.assertEqual(hf["rfcomm_channel"], 1)
        self.assertEqual(hf["profiles"], ["0x0107"])
        self.assertEqual(hf["classes"][0]["id"], "0x111e")

    def test_devices_and_mask(self):
        out = "Device 11:22:33:44:55:66 EJEAS V6 PRO\nDevice AA:BB:CC:DD:EE:FF Phone\n"
        self.assertEqual(bt_inventory.parse_devices(out)[0], ("11:22:33:44:55:66", "EJEAS V6 PRO"))
        self.assertEqual(bt_inventory.mask("11:22:33:44:55:66"), "11:22:33:XX:XX:XX")

    def test_uuid(self):
        self.assertTrue(describe("0000111e-0000-1000-8000-00805f9b34fb")["audio_critical"])

    def test_usb_snapshot_on_fake_sysfs(self):
        root = tempfile.mkdtemp()
        d = os.path.join(root, "1-2")
        os.makedirs(os.path.join(d, "1-2:1.0"))
        for k, v in {"idVendor": "10d6", "idProduct": "1100", "product": "Example", "speed": "12"}.items():
            open(os.path.join(d, k), "w").write(v + "\n")
        open(os.path.join(d, "1-2:1.0", "bInterfaceClass"), "w").write("03\n")
        snap = usb_probe.snapshot(root)
        self.assertEqual(snap["1-2"]["vendor_hint"], "Actions Semiconductor")
        self.assertEqual(snap["1-2"]["interfaces"], ["03"])


if __name__ == "__main__":
    unittest.main()
