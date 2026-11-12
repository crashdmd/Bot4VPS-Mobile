#!/usr/bin/env python3
"""Сверка документации, конфигурации и подписанного APK без сборки и сети."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit


OFFICIAL_CERTIFICATE = "0e7201279b7e2d47cf12b40feb31423c4eb23000ef270833fa8c758e7c56dbd0"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def capture(pattern, text, label):
    match = re.search(pattern, text, re.MULTILINE)
    require(match is not None, f"Не найдено поле: {label}")
    return match.group(1)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(command):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=30)
    require(result.returncode == 0,
            f"Проверка {Path(command[0]).name} завершилась ошибкой:\n{result.stdout.strip()}")
    return result.stdout


def check_documentation(project, version, code):
    readme = (project / "README.md").read_text(encoding="utf-8")
    match = re.search(r"^\- \*\*Текущая версия:\*\* \[([^\]]+)\].*?`versionCode=(\d+)`",
                      readme, re.MULTILINE)
    require(match is not None, "README.md: укажите текущую версию и `versionCode=…`")
    require((match[1], int(match[2])) == (version, code),
            f"README.md: текущий выпуск должен быть {version}, versionCode={code}")
    development = (project / "docs/development.md").read_text(encoding="utf-8")
    releases = re.findall(r"Текущий выпуск ([\d.]+) имеет `versionCode=(\d+)`", development)
    require(releases == [(version, str(code))],
            f"docs/development.md: текущий выпуск должен быть {version}, versionCode={code}")
    workflows = (project / "docs/workflows.md").read_text(encoding="utf-8")
    documented_version = capture(r"^# Сценарии и функциональность Bot4VPS Mobile ([\d.]+)$",
                                 workflows, "версия сценариев")
    require(documented_version == version, "docs/workflows.md: версия сценариев не совпадает с APK")
    documents = sorted(project.glob("*.md")) + sorted((project / "docs").rglob("*.md"))
    for document in documents:
        content = document.read_text(encoding="utf-8")
        for target in re.findall(r"!?\[[^\]\n]*\]\(([^)\s]+)\)", content):
            link = urlsplit(target.strip("<>"))
            if link.scheme or link.netloc or not link.path:
                continue
            require((document.parent / unquote(link.path)).exists(),
                    f"{document.relative_to(project)}: отсутствует цель ссылки {target}")
    return len(documents)


def check_release(project, build_tools, java, certificate):
    require(re.fullmatch(r"[a-f0-9]{64}", certificate) is not None,
            "Отпечаток сертификата должен содержать 64 шестнадцатеричных символа")
    gradle = (project / "app/build.gradle.kts").read_text(encoding="utf-8")
    version = capture(r'^\s*versionName\s*=\s*"([^"]+)"', gradle, "versionName")
    code = int(capture(r"^\s*versionCode\s*=\s*(\d+)\s*$", gradle, "versionCode"))
    package = capture(r'^\s*applicationId\s*=\s*"([^"]+)"', gradle, "applicationId")
    require(re.fullmatch(r"\d+\.\d+\.\d+", version) is not None and code > 0,
            "Некорректная версия в Gradle")
    metadata = json.loads((project / "apk/release.json").read_text(encoding="utf-8"))
    require(isinstance(metadata, dict), "release.json должен содержать JSON-объект")
    require(metadata.get("version") == version, "release.json: version не совпадает с Gradle")
    require(type(metadata.get("versionCode")) is int and metadata["versionCode"] == code,
            "release.json: versionCode не совпадает с Gradle")
    filename = f"Bot4VPS-Mobile-{version}-release.apk"
    require(metadata.get("filename") == filename, "release.json: filename не соответствует версии")
    apk = project / "apk" / filename
    require(apk.is_file() and not apk.is_symlink(), f"Не найден обычный файл APK: {filename}")
    require(type(metadata.get("size")) is int and 0 < metadata["size"] <= 49_000_000
            and apk.stat().st_size == metadata["size"], "release.json: размер APK не совпадает")
    require(metadata.get("sha256") == sha256(apk), "release.json: SHA-256 APK не совпадает")
    changelog = (project / "CHANGELOG.md").read_text(encoding="utf-8").split("\n## ", 1)[0].rstrip() + "\n"
    require(changelog.startswith(f"# Bot4VPS Mobile {version}\n"),
            "CHANGELOG.md: заголовок текущего выпуска не соответствует версии")
    require(metadata.get("changelog") == changelog,
            "release.json: changelog не совпадает с текущим разделом CHANGELOG.md")
    documents = check_documentation(project, version, code)
    badging = run([str(build_tools / "aapt"), "dump", "badging", str(apk)])
    apk_package = capture(r"^package: name='([^']+)'", badging, "пакет APK")
    apk_version = capture(r"^package: .*versionName='([^']+)'", badging, "версия APK")
    apk_code = int(capture(r"^package: .*versionCode='(\d+)'", badging, "versionCode APK"))
    require((apk_package, apk_version, apk_code) == (package, version, code),
            f"Манифест APK не совпадает с Gradle: {apk_package}, {apk_version}, versionCode={apk_code}")
    require("application-debuggable" not in badging, "Публикуемый APK содержит debug-флаг")
    certs = run([java, "-Xmx128m", "-jar", str(build_tools / "lib/apksigner.jar"),
                 "verify", "--print-certs", str(apk)])
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)$",
                              certs, re.MULTILINE)
    require(fingerprints == [certificate], "Сертификат подписи APK не совпадает с ожидаемым")
    return {"version": version, "versionCode": code, "size": metadata["size"],
            "sha256": metadata["sha256"], "certificate_sha256": certificate,
            "markdown_documents_checked": documents}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1],
                        help="Корень проекта (по умолчанию каталог над tools)")
    parser.add_argument("--build-tools", required=True, type=Path,
                        help="Каталог Android SDK Build Tools, содержащий aapt и lib/apksigner.jar")
    java_home = os.environ.get("JAVA_HOME")
    parser.add_argument("--java", default=str(Path(java_home) / "bin/java") if java_home else "java",
                        help="Путь к Java (по умолчанию JAVA_HOME/bin/java или java из PATH)")
    parser.add_argument("--certificate-sha256", default=OFFICIAL_CERTIFICATE,
                        help="Ожидаемый отпечаток сертификата (по умолчанию официальный release-ключ)")
    args = parser.parse_args()
    try:
        result = check_release(args.project.resolve(), args.build_tools.resolve(),
                               args.java, args.certificate_sha256.lower())
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"Ошибка проверки выпуска: {error}", file=sys.stderr)
        return 1
    print("Выпуск согласован: " + json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
