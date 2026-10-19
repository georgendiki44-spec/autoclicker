"""python-for-android hook to register Auto Clicker services.

The p4a distribution's manifest location differs across versions. Locate the
actual SDL app manifest rather than assuming a src/main layout.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

MARKER = "com.george.autoclickerpro.AutoClickerTileService"

def before_apk_build(toolchain):
    dist_dir = Path(toolchain._dist.dist_dir)
    preferred = [
        dist_dir / "src" / "main" / "AndroidManifest.xml",
        dist_dir / "jni" / "SDL" / "android-project" / "app" / "src" / "main" / "AndroidManifest.xml",
    ]
    manifest_path = next((p for p in preferred if p.is_file()), None)
    if manifest_path is None:
        candidates = [
            p for p in dist_dir.rglob("AndroidManifest.xml")
            if "SDL2_image" not in str(p) and "libavif" not in str(p)
            and "build" not in p.parts and "intermediates" not in p.parts
        ]
        if len(candidates) == 1:
            manifest_path = candidates[0]
        else:
            raise RuntimeError(
                "Could not identify generated app manifest. Candidates: "
                + ", ".join(str(p.relative_to(dist_dir)) for p in candidates[:10])
            )

    services_path = Path(__file__).resolve().parent / "services.xml"
    manifest = manifest_path.read_text(encoding="utf-8")
    services = services_path.read_text(encoding="utf-8").strip()

    if MARKER in manifest:
        print("Auto Clicker services already present in AndroidManifest.xml")
        return

    closing = "</application>"
    if closing not in manifest:
        raise RuntimeError("No application element found in " + str(manifest_path))

    manifest = manifest.replace(closing, services + "\\n" + closing, 1)
    ET.fromstring(manifest)
    manifest_path.write_text(manifest, encoding="utf-8")
    print("Registered Auto Clicker services in " + str(manifest_path))
