"""Package only source allowlists; never include credentials, caches or local SDK paths."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(__file__).resolve().parent
out = root / "dist"
out.mkdir(exist_ok=True)
apk = out / "XBatchDownload-0.7.0-debug.apk"
shutil.copyfile(root / "app/build/outputs/apk/debug/app-debug.apk", apk)
shutil.copyfile(root / "README.md", out / "使用说明-0.7.0.md")
(out / "SHA256-0.7.0.txt").write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + "  " + apk.name + "\n", encoding="utf-8")
with zipfile.ZipFile(out / "XBatchDownload-0.7.0-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for name in ["README.md", ".gitignore", "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts", "package_release.py"]:
        archive.write(root / name, name)
    for folder in [root / "app/src", root / "gradle/wrapper"]:
        for path in folder.rglob("*"):
            if path.is_file():
                archive.write(path, path.relative_to(root))
print("Packaged standalone public-page APK, source, instructions and checksum into dist/")
