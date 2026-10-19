"""python-for-android hook that registers the native Auto Clicker services."""
from pathlib import Path
import xml.etree.ElementTree as ET

MARKER = "com.george.autoclickerpro.AutoClickerTileService"
SERVICES = """
<service
    android:name="com.george.autoclickerpro.AutoClickerTileService"
    android:label="Auto Clicker"
    android:icon="@android:drawable/ic_media_play"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
    <meta-data android:name="android.service.quicksettings.ACTIVE_TILE" android:value="true" />
</service>
<service
    android:name="com.george.autoclickerpro.AutoClickerAccessibilityService"
    android:label="Auto Clicker Pro"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/autoclicker_accessibility_service" />
</service>
<service
    android:name="com.george.autoclickerpro.FloatingOverlayService"
    android:label="Auto Clicker floating controls"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="User-started floating auto-click controls" />
</service>
"""

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
                "Could not identify app manifest. Candidates: "
                + ", ".join(str(p.relative_to(dist_dir)) for p in candidates[:10])
            )

    manifest = manifest_path.read_text(encoding="utf-8")
    if MARKER in manifest:
        print("Auto Clicker services already present in AndroidManifest.xml")
        return

    closing = "</application>"
    if closing not in manifest:
        raise RuntimeError("No application element found in " + str(manifest_path))

    manifest = manifest.replace(closing, SERVICES + "\n" + closing, 1)
    ET.fromstring(manifest)
    manifest_path.write_text(manifest, encoding="utf-8")
    print("Registered Auto Clicker services in " + str(manifest_path))
