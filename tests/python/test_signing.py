"""Release key provisioning is explicit, private and cannot replace a key."""
import hashlib
import importlib.util
import os
import shutil
from pathlib import Path
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[2]
KEYTOOL = Path(os.environ.get("BOT4VPS_TEST_KEYTOOL", shutil.which("keytool") or "/nonexistent/keytool"))


def signing_module():
    spec = importlib.util.spec_from_file_location("prepare_signing", ROOT / "tools/prepare_signing.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize("legacy_directory", [".signing", ".singning"])
def test_legacy_key_is_found_in_old_source_layout(tmp_path, legacy_directory):
    root = tmp_path / "project"
    old = root / legacy_directory / "debug.keystore"
    old.parent.mkdir(parents=True)
    original = os.urandom(128)
    old.write_bytes(original)
    assert signing_module().legacy_debug_key(root, tmp_path / "new-signing") == old
    assert old.read_bytes() == original


def test_different_legacy_keys_require_explicit_selection(tmp_path):
    directory = tmp_path / "new-signing"
    directory.mkdir()
    first = directory / "debug.keystore"
    first.write_bytes(b"first private key")
    second = tmp_path / "project/.signing/debug.keystore"
    second.parent.mkdir(parents=True)
    second.write_bytes(b"second private key")
    module = signing_module()
    with pytest.raises(ValueError, match="разные старые"):
        module.legacy_debug_key(tmp_path / "project", directory)
    assert module.legacy_debug_key(tmp_path / "project", directory, second) == second
    assert first.read_bytes() == b"first private key" and second.read_bytes() == b"second private key"


def test_legacy_key_symlink_and_missing_explicit_path_are_rejected(tmp_path):
    module = signing_module()
    missing = tmp_path / "missing.key"
    with pytest.raises(ValueError, match="не найден"):
        module.legacy_debug_key(tmp_path, tmp_path / "signing", missing)
    link = tmp_path / "debug.keystore"
    link.symlink_to(missing)
    with pytest.raises(ValueError, match="символической ссылки"):
        module.legacy_debug_key(tmp_path, tmp_path / "signing", link)


@pytest.mark.skipif(not KEYTOOL.is_file(), reason="Local Android build JDK is required")
def test_release_key_is_distinct_private_and_not_replaced(tmp_path):
    directory = tmp_path / "signing"
    directory.mkdir()
    original = os.urandom(256)
    (directory / "debug.keystore").write_bytes(original)
    command = ["python3", str(ROOT / "tools/prepare_signing.py"), "--create-release",
               "--directory", str(directory), "--keytool", str(KEYTOOL)]
    subprocess.run(command, check=True, capture_output=True)
    assert (directory / "debug.keystore").read_bytes() == original
    assert (directory / "archive/legacy-debug.keystore").read_bytes() == original
    key = directory / "release.keystore"
    properties = directory / "release.properties"
    before = hashlib.sha256(key.read_bytes()).hexdigest()
    values = dict(line.split("=", 1) for line in properties.read_text().splitlines())
    assert Path(values["storeFile"]) == key
    assert values["keyAlias"] == "bot4vps-release"
    assert len(values["storePassword"]) >= 64 and values["storePassword"] != "android"
    assert directory.stat().st_mode & 0o777 == 0o700
    assert key.stat().st_mode & 0o777 == properties.stat().st_mode & 0o777 == 0o600
    environment = os.environ.copy()
    environment["BOT4VPS_TEST_STORE_PASSWORD"] = values["storePassword"]
    certificate = subprocess.check_output([str(KEYTOOL), "-list", "-v", "-keystore", str(key),
        "-storepass:env", "BOT4VPS_TEST_STORE_PASSWORD"], env=environment).decode()
    assert "CN=Bot4VPS Mobile Release" in certificate and "CN=Android Debug" not in certificate
    assert subprocess.run(command, capture_output=True).returncode != 0
    assert hashlib.sha256(key.read_bytes()).hexdigest() == before
    assert (directory / "debug.keystore").read_bytes() == original


def test_provisioning_without_explicit_create_does_not_create_key(tmp_path):
    command = ["python3", str(ROOT / "tools/prepare_signing.py"), "--directory", str(tmp_path)]
    assert subprocess.run(command, capture_output=True).returncode != 0
    assert not (tmp_path / "release.keystore").exists()


def test_release_key_cannot_be_created_under_application_sources():
    directory = ROOT / "app/forbidden-signing"
    command = ["python3", str(ROOT / "tools/prepare_signing.py"),
               "--create-release", "--directory", str(directory)]
    assert subprocess.run(command, capture_output=True).returncode != 0
    assert not directory.exists()


@pytest.mark.skipif(not KEYTOOL.is_file(), reason="Local Android build JDK is required")
def test_key_provisioning_uses_the_configured_build_environment(tmp_path):
    environment = os.environ.copy()
    environment["BOT4VPS_BUILD_ENV"] = str(KEYTOOL.parent.parent.parent)
    command = ["python3", str(ROOT / "tools/prepare_signing.py"),
               "--create-release", "--directory", str(tmp_path / "signing")]
    subprocess.run(command, env=environment, check=True, capture_output=True)
    assert (tmp_path / "signing/release.keystore").is_file()
