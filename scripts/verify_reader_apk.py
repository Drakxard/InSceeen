"""Check the built APK, including the exact offline package and native PDF engine."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def verify(apk: Path, package: Path) -> dict:
    expected = json.loads((package / "current.json").read_text(encoding="utf-8"))
    with zipfile.ZipFile(apk) as archive:
        manifest = json.loads(archive.read("assets/native-web/current.json"))
        assert manifest == expected, "APK contains a different Cursado revision"
        for resource in manifest["files"]:
            data = archive.read(f'assets/native-web/releases/{manifest["revision"]}/{resource["path"]}')
            assert len(data) == resource["size"], resource["path"]
            assert hashlib.sha256(data).hexdigest() == resource["hash"], resource["path"]
        names = archive.namelist()
        assert any(name.startswith("lib/") and "pdfium" in name.lower() and name.endswith(".so") for name in names), "Native PDFium library is missing"
        dex = b"".join(archive.read(name) for name in names if name.startswith("classes") and name.endswith(".dex"))
        for descriptor in (b"Lcom/inscreen/mic/WorkspacePdfActivity;", b"Lcom/github/barteksc/pdfviewer/PDFView;"):
            assert descriptor in dex, f"Missing reader class: {descriptor!r}"
        assert "assets/pdfjs/web/viewer.html" not in names, "Legacy PDF.js viewer must not ship in the APK"
    metadata = json.loads((apk.parent / "output-metadata.json").read_text(encoding="utf-8"))
    output = next(item for item in metadata["elements"] if item["outputFile"] == apk.name)
    return {"apk": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "versionName": output["versionName"], "versionCode": output["versionCode"],
            "cursadoRevision": manifest["revision"], "verifiedFiles": len(manifest["files"]),
            "cursadoSource": Path(".github/cursado-reader-ref").read_text().strip()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--package", type=Path, default=Path("modules/native-web"))
    parser.add_argument("--report", type=Path, default=Path("mobile_android/app/build/reports/reader-apk.json"))
    args = parser.parse_args()
    report = verify(args.apk, args.package)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
