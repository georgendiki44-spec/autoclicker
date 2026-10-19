"""python-for-android hook.

p4a has no setting for adding child elements to <application>, so the
<service> declarations in services.xml are inserted into the generated
AndroidManifest.xml after p4a renders it and before Gradle builds the APK.
"""
import xml.etree.ElementTree as ET
from pathlib import Path

MARKER = "com.george.autoclickerpro.AutoClickerTileService"


def after_apk_build(toolchain):
    dist_dir = Path(toolchain._dist.dist_dir)
    manifest_path = dist_dir / "src" / "main" / "AndroidManifest.xml"
    services_path = Path(__file__).resolve().parent / "services.xml"

    manifest = manifest_path.read_text(encoding="utf-8")
    services = services_path.read_text(encoding="utf-8").strip()

    if MARKER in manifest:
        print("Auto Clicker services already present in AndroidManifest.xml")
        return
    closing = "</application>"
    if closing not in manifest:
        raise RuntimeError("Could not find %s in %s" % (closing, manifest_path))
    manifest = manifest.replace(closing, services + "\n" + closing, 1)
    ET.fromstring(manifest)  # fail fast on malformed XML
    manifest_path.write_text(manifest, encoding="utf-8")
    print("Auto Clicker services inserted into AndroidManifest.xml")
