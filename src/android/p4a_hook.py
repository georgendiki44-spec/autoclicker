from pathlib import Path
import xml.etree.ElementTree as ET


def after_apk_build(toolchain):
    """Insert the app's native Android services before Gradle merges the manifest."""
    dist_dir = Path(toolchain._dist.dist_dir)
    manifest_path = dist_dir / "src" / "main" / "AndroidManifest.xml"
    services_path = Path(__file__).resolve().parent / "extra_manifest_application_arguments.xml"

    manifest = manifest_path.read_text(encoding="utf-8")
    services = services_path.read_text(encoding="utf-8").strip()

    if "com.george.autoclickerpro.AutoClickerTileService" not in manifest:
        closing_tag = "</application>"
        if closing_tag not in manifest:
            raise RuntimeError(f"Could not find {closing_tag} in {manifest_path}")
        manifest = manifest.replace(closing_tag, services + "\n" + closing_tag, 1)
        ET.fromstring(manifest)
        manifest_path.write_text(manifest, encoding="utf-8")
        print("Auto Clicker native services inserted into AndroidManifest.xml")
    else:
        print("Auto Clicker native services already present in AndroidManifest.xml")
