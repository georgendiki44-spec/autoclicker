"""python-for-android hook for registering Auto Clicker Android services."""
from pathlib import Path
import xml.etree.ElementTree as ET


def before_apk_build(toolchain):
    # The Gradle project built by python-for-android uses this manifest.
    # The older jni/SDL/android-project path is a template and is not packaged.
    dist_dir = Path(toolchain._dist.dist_dir)
    manifest_path = dist_dir / "src" / "main" / "AndroidManifest.xml"
    services_path = Path(__file__).resolve().parent / "services.xml"

    if not manifest_path.is_file():
        raise RuntimeError(f"Generated Android manifest not found: {manifest_path}")
    if not services_path.is_file():
        raise RuntimeError(f"Service declarations not found: {services_path}")

    manifest = manifest_path.read_text(encoding="utf-8")
    services = services_path.read_text(encoding="utf-8").strip()

    # Make the hook idempotent, but check the accessibility service specifically:
    # the tile service may exist without the accessibility service.
    marker = "com.george.autoclickerpro.AutoClickerAccessibilityService"
    if marker not in manifest:
        closing = "</application>"
        if closing not in manifest:
            raise RuntimeError("Application element missing from generated manifest")
        manifest = manifest.replace(closing, services + "\n" + closing, 1)

    # Fail the build if the merged manifest still doesn't register the service.
    root = ET.fromstring(manifest)
    android_ns = "{http://schemas.android.com/apk/res/android}"
    service_found = False
    for node in root.findall(".//service"):
        if node.get(android_ns + "name") == marker:
            permission = node.get(android_ns + "permission")
            actions = [a.get(android_ns + "name") for a in node.findall("./intent-filter/action")]
            metadata = [m for m in node.findall("./meta-data")
                        if m.get(android_ns + "name") == "android.accessibilityservice"]
            if (permission == "android.permission.BIND_ACCESSIBILITY_SERVICE"
                    and "android.accessibilityservice.AccessibilityService" in actions
                    and metadata):
                service_found = True
                break
    if not service_found:
        raise RuntimeError("Accessibility service declaration is missing or incomplete")

    manifest_path.write_text(manifest, encoding="utf-8")
