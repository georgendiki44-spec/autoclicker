"""python-for-android hook for registering Auto Clicker Android services."""
from pathlib import Path
import xml.etree.ElementTree as ET

def before_apk_build(toolchain):
    dist_dir = Path(toolchain._dist.dist_dir)
    manifest_path = dist_dir / "src" / "main" / "AndroidManifest.xml"
    services_path = Path(__file__).resolve().parent / "services.xml"
    manifest = manifest_path.read_text(encoding="utf-8")
    services = services_path.read_text(encoding="utf-8").strip()
    marker = "com.george.autoclickerpro.AutoClickerTileService"
    if marker in manifest:
        return
    closing = "</application>"
    if closing not in manifest:
        raise RuntimeError("Application element missing from generated manifest")
    manifest = manifest.replace(closing, services + "\n" + closing, 1)
    ET.fromstring(manifest)
    manifest_path.write_text(manifest, encoding="utf-8")
