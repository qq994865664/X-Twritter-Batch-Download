"""Sign and package the already-built release APK without exposing signing passwords."""
from pathlib import Path
import hashlib
import os
import re
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parent
out = root / "dist"
out.mkdir(exist_ok=True)
signing = root / ".release-signing"
required = [signing / "legacy-debug.keystore", signing / "release.keystore",
            signing / "release-password.txt", signing / "signing-lineage.bin"]
if not all(path.is_file() for path in required):
    raise SystemExit("Missing release signing files; restore the protected .release-signing directory.")

sdk_path = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
if not sdk_path:
    local = root / "local.properties"
    match = re.search(r"(?m)^sdk\.dir\s*=\s*(.+?)\s*$", local.read_text(encoding="utf-8")) if local.is_file() else None
    if not match:
        raise SystemExit("Set ANDROID_SDK_ROOT or sdk.dir in local.properties.")
    sdk_path = match.group(1).replace("\\:", ":").replace("\\\\", "\\")
build_tools = Path(sdk_path) / "build-tools" / "36.0.0"
suffix = ".exe" if os.name == "nt" else ""
zipalign = build_tools / ("zipalign" + suffix)
apksigner = build_tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
unsigned = root / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"
if not unsigned.is_file():
    raise SystemExit("Build the release APK first: gradlew :app:assembleRelease")
version_match = re.search(r'versionName\s*=\s*"([^"]+)"', (root / "app/build.gradle.kts").read_text(encoding="utf-8"))
if not version_match or not re.fullmatch(r"[A-Za-z0-9._-]+", version_match.group(1)):
    raise SystemExit("Could not determine a safe version name.")
version = version_match.group(1)
release_apk = out / f"XBatchDownload-{version}-release.apk"
env = os.environ.copy()
env["XBATCH_RELEASE_PASSWORD"] = (signing / "release-password.txt").read_text(encoding="ascii").strip()

temporary = []
try:
    for label in ("aligned", "signed"):
        with tempfile.NamedTemporaryFile(prefix=f".release-{label}-", suffix=".apk", dir=out, delete=False) as f:
            temporary.append(Path(f.name))
    aligned, signed = temporary
    subprocess.run([str(zipalign), "-P", "16", "-f", "4", str(unsigned), str(aligned)], check=True, env=env)
    subprocess.run([str(apksigner), "sign", "--ks", str(signing / "legacy-debug.keystore"),
                    "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
                    "--next-signer", "--ks", str(signing / "release.keystore"), "--ks-key-alias", "xbatch-release",
                    "--ks-pass", "env:XBATCH_RELEASE_PASSWORD", "--key-pass", "env:XBATCH_RELEASE_PASSWORD",
                    "--lineage", str(signing / "signing-lineage.bin"), "--rotation-min-sdk-version", "28",
                    "--v4-signing-enabled", "false", "--out", str(signed), str(aligned)], check=True, env=env)
    subprocess.run([str(apksigner), "verify", "--verbose", str(signed)], check=True, env=env)
    subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(signed)], check=True, env=env)
    os.replace(signed, release_apk)
finally:
    for path in temporary:
        if path.exists():
            path.unlink()

(out / f"SHA256-{version}-release.txt").write_text(
    hashlib.sha256(release_apk.read_bytes()).hexdigest() + "  " + release_apk.name + "\n", encoding="utf-8")
(out / f"发布说明-{version}.md").write_text((root / "README.md").read_text(encoding="utf-8"), encoding="utf-8")
with zipfile.ZipFile(out / f"XBatchDownload-{version}-release-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for name in ["README.md", ".gitignore", "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
                 "gradlew", "gradlew.bat", "app/build.gradle.kts", "package_release.py", "package_signed_release.py"]:
        archive.write(root / name, name)
    for folder in [root / "app/src", root / "gradle/wrapper"]:
        for path in folder.rglob("*"):
            if path.is_file():
                archive.write(path, path.relative_to(root))
print("Signed release APK, instructions, checksum and source package created in dist/.")
