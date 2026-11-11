#!/usr/bin/env python3
"""Explicit one-time release key creation. Builds never invoke this tool."""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import tempfile


def legacy_debug_key(root, directory, explicit=None):
    candidates = [explicit] if explicit is not None else [
        directory / "debug.keystore",
        root / ".signing/debug.keystore",
        root / ".singning/debug.keystore",
    ]
    found = {}
    for path in candidates:
        if not path.exists() and not path.is_symlink():
            if explicit is not None:
                raise ValueError("Указанный старый ключ не найден")
            continue
        if path.is_symlink() or not path.is_file():
            raise ValueError("Старый ключ должен быть обычным файлом без символической ссылки")
        content = path.read_bytes()
        if not content:
            raise ValueError("Файл старого ключа пуст")
        found.setdefault(hashlib.sha256(content).digest(), path)
    if len(found) > 1:
        raise ValueError("Найдены разные старые debug-ключи. Укажите нужный через --legacy-debug-key")
    return next(iter(found.values()), None)


def main():
    os.umask(0o077)
    root = Path(__file__).resolve().parents[1]
    build_env = Path(os.environ.get("BOT4VPS_BUILD_ENV", str(root / ".build-env")))
    parser = argparse.ArgumentParser(description="Создать отдельный ключ release; существующие файлы не заменяются")
    parser.add_argument("--create-release", action="store_true", required=True)
    parser.add_argument("--directory", type=Path, required=True, help="Каталог ключа вне исходников")
    parser.add_argument("--keytool", type=Path, default=build_env / "jdk/bin/keytool")
    parser.add_argument("--legacy-debug-key", type=Path, help="Прежний debug.keystore для сохранения без изменения оригинала")
    args = parser.parse_args()
    directory = args.directory.resolve()
    if directory.is_relative_to(root):
        parser.error("Храните ключ вне каталога проекта")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    key = directory / "release.keystore"
    properties = directory / "release.properties"
    if key.exists() or properties.exists():
        parser.error("Release-ключ или параметры уже существуют; замена запрещена")
    # Preserve the old certificate/key without moving or modifying the original.
    try:
        old = legacy_debug_key(root, directory, args.legacy_debug_key)
    except ValueError as error:
        parser.error(str(error))
    archived = directory / "archive/legacy-debug.keystore"
    if old is not None:
        archived.parent.mkdir(mode=0o700, exist_ok=True)
        if archived.exists():
            if archived.read_bytes() != old.read_bytes():
                parser.error("Архив старого ключа отличается от оригинала")
        else:
            with archived.open("xb") as output:
                output.write(old.read_bytes())
            archived.chmod(0o600)
    password = secrets.token_hex(32)
    environment = os.environ.copy()
    environment["BOT4VPS_NEW_RELEASE_PASSWORD"] = password
    # Stage first; a failed keytool invocation cannot leave a usable-looking key.
    with tempfile.TemporaryDirectory(dir=directory) as temporary:
        staged = Path(temporary) / "release.keystore"
        subprocess.run([str(args.keytool), "-genkeypair", "-keystore", str(staged),
                        "-storepass:env", "BOT4VPS_NEW_RELEASE_PASSWORD",
                        "-keypass:env", "BOT4VPS_NEW_RELEASE_PASSWORD", "-alias", "bot4vps-release",
                        "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                        "-storetype", "PKCS12", "-dname", "CN=Bot4VPS Mobile Release,O=Bot4VPS"],
                       env=environment, check=True)
        with key.open("xb") as output:
            output.write(staged.read_bytes())
        key.chmod(0o600)
        with properties.open("x") as output:
            output.write(f"storeFile={key.as_posix()}\nstorePassword={password}\n"
                         f"keyAlias=bot4vps-release\nkeyPassword={password}\n")
        properties.chmod(0o600)
    print("Release-ключ создан. Сохраните закрытую резервную копию каталога подписи.")


if __name__ == "__main__":
    main()
