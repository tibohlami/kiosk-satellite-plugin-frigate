#!/usr/bin/env python3
"""Build a plugin ZIP with the published SDK and the Android DEX compiler."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

from android_sdk import android_platform
from plugin_manifest import build_manifest
from plugin_assets import asset_files, MAX_PACKAGE_BYTES

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('plugin', nargs='?', default=str(ROOT), help='Path to the plugin repository')
parser.add_argument('--version', help='Package version override, normally supplied by the release tag')
parser.add_argument('--android-platform', help='Installed platform version, for example 35 or 37.0')
args = parser.parse_args()
plugin = Path(args.plugin).resolve()
try:
    manifest_bytes = build_manifest(plugin / 'kiosk-satellite-plugin.json', args.version)
except ValueError as error:
    parser.error(str(error))
manifest = json.loads(manifest_bytes)
try:
    assets = asset_files(plugin)
except ValueError as error:
    parser.error(str(error))
def find_sdk_root():
    for cand in [
        os.environ.get('ANDROID_HOME'),
        os.environ.get('ANDROID_SDK_ROOT'),
        str(Path.home() / 'Library' / 'Android' / 'sdk'),
        str(Path.home() / 'android-sdk'),
        str(Path.home() / 'Android' / 'Sdk')
    ]:
        if cand and Path(cand).is_dir():
            return Path(cand)
    return Path(Path.home() / 'android-sdk')

sdk_root = find_sdk_root()
java_root = os.environ.get('JAVA_HOME')
def java_tool(name):
    return str(Path(java_root) / 'bin' / name) if java_root else name
# Windows ships d8 as d8.bat.
build_tools = sorted([*(sdk_root / 'build-tools').glob('*/d8'), *(sdk_root / 'build-tools').glob('*/d8.bat')], key=lambda p: tuple(int(v) for v in p.parent.name.split('.') if v.isdigit()))
if not build_tools:
    raise SystemExit('Set ANDROID_HOME to an Android SDK with build-tools installed.')
try:
    platform = android_platform(sdk_root, args.android_platform)
except ValueError as error:
    raise SystemExit(str(error)) from error
out = plugin / 'dist'
out.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='kiosk-plugin-') as temp:
    temp = Path(temp)
    sdk_classes, classes, dex = [temp / name for name in ('sdk', 'classes', 'dex')]
    for folder in (sdk_classes, classes, dex):
        folder.mkdir()
    # The overlay classes use Android views, so the SDK compiles against the platform too.
    subprocess.run([java_tool('javac'), '--release', '8', '-cp', str(platform), '-d', str(sdk_classes), *map(str, sorted((ROOT / 'sdk/src').rglob('*.java')))], check=True)
    sdk_jar = out / 'kiosk-plugin-sdk-1.jar'
    subprocess.run([java_tool('jar'), 'cf', str(sdk_jar), '-C', str(sdk_classes), '.'], check=True)
    extra_jars = sorted((plugin / 'libs' / 'media3').glob('*.jar')) if (plugin / 'libs' / 'media3').exists() else []
    asm_jars = sorted((plugin / 'libs' / 'asm').glob('*.jar')) if (plugin / 'libs' / 'asm').exists() else []
    compile_cp = [str(sdk_jar), str(platform), *map(str, extra_jars)]
    subprocess.run([java_tool('javac'), '--release', '8', '-cp', os.pathsep.join(compile_cp), '-d', str(classes), *map(str, sorted((plugin / 'src').rglob('*.java')))], check=True)
    def resolve_r8_jar(sdk):
        candidates = [
            sdk / 'cmdline-tools' / 'latest' / 'lib' / 'r8.jar',
            *sorted((sdk / 'cmdline-tools').glob('*/lib/r8.jar'), reverse=True),
            *sorted((sdk / 'build-tools').glob('*/lib/d8.jar'), reverse=True),
            *sorted((sdk / 'build-tools').glob('*/lib/r8.jar'), reverse=True),
        ]
        for cand in candidates:
            if cand.is_file():
                return cand
        return sdk / 'cmdline-tools' / 'latest' / 'lib' / 'r8.jar'

    r8_jar = resolve_r8_jar(sdk_root)
    pg_rules = plugin / 'tools' / 'proguard-rules.pro'
    shader_src = plugin / 'tools' / 'ShadeDependencies.java'
    if extra_jars and asm_jars and shader_src.exists() and r8_jar.exists() and pg_rules.exists():
        shader_cls = temp / 'shader'
        shaded_cls = temp / 'shaded'
        shader_cls.mkdir()
        shaded_cls.mkdir()
        subprocess.run([java_tool('javac'), '--release', '8', '-cp', os.pathsep.join(map(str, asm_jars)), '-d', str(shader_cls), str(shader_src)], check=True)
        subprocess.run([
            java_tool('java'), '-cp', os.pathsep.join([str(shader_cls), *map(str, asm_jars)]),
            'ShadeDependencies', str(classes), str(shaded_cls), *map(str, extra_jars)
        ], check=True)
        r8_cmd = [
            java_tool('java'), '-cp', str(r8_jar), 'com.android.tools.r8.R8',
            '--release', '--no-minification',
            '--min-api', str(manifest['minAndroidSdk']),
            '--lib', str(platform),
            '--lib', str(sdk_jar),
            '--pg-conf', str(pg_rules),
            '--output', str(dex),
            *map(str, sorted(shaded_cls.rglob('*.class'))),
        ]
        subprocess.run(r8_cmd, check=True)
    elif extra_jars and r8_jar.exists() and pg_rules.exists():
        r8_cmd = [
            java_tool('java'), '-cp', str(r8_jar), 'com.android.tools.r8.R8',
            '--release', '--no-minification',
            '--min-api', str(manifest['minAndroidSdk']),
            '--lib', str(platform),
            '--lib', str(sdk_jar),
            '--pg-conf', str(pg_rules),
            '--output', str(dex),
            *map(str, sorted(classes.rglob('*.class'))),
            *map(str, extra_jars),
        ]
        subprocess.run(r8_cmd, check=True)
    else:
        subprocess.run([str(build_tools[-1]), '--min-api', str(manifest['minAndroidSdk']), '--lib', str(platform), '--classpath', str(sdk_jar), '--output', str(dex), *map(str, sorted(classes.rglob('*.class')))], check=True)
    jar = temp / 'plugin.jar'
    def add(archive, name, data):
        info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        archive.writestr(info, data)
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(dex.glob('*.dex')):
            add(archive, file.name, file.read_bytes())
    package = out / f"{manifest['id']}-{manifest['version']}.zip"
    expanded = len(manifest_bytes) + jar.stat().st_size + (plugin / 'LICENSE').stat().st_size + sum(file.stat().st_size for _, file in assets)
    if expanded > MAX_PACKAGE_BYTES:
        raise SystemExit('Expanded plugin exceeds 4 MB')
    with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
        add(archive, 'kiosk-satellite-plugin.json', manifest_bytes)
        add(archive, 'plugin.jar', jar.read_bytes())
        add(archive, 'LICENSE', (plugin / 'LICENSE').read_bytes())
        for name, file in assets:
            add(archive, name, file.read_bytes())
    if package.stat().st_size > MAX_PACKAGE_BYTES:
        package.unlink()
        raise SystemExit('Plugin ZIP must be at most 4 MB')
    digest = hashlib.sha256(package.read_bytes()).hexdigest()
    package.with_suffix('.zip.sha256').write_text(f'{digest}  {package.name}\n')
    (out / 'kiosk-satellite-plugin.json').write_bytes(manifest_bytes)
    print(f'Package: {package}\nSHA-256: {digest}\nSDK: {sdk_jar}')
