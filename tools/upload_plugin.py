#!/usr/bin/env python3
"""
Upload and install a Kiosk Satellite plugin ZIP via the Remote API.

Usage:
    python3 tools/upload_plugin.py --host 192.168.1.50 --password secret
    python3 tools/upload_plugin.py --host 192.168.1.50:2324 -p secret --enable
    python3 tools/upload_plugin.py dist/kiosk-satellite-frigate-1.0.2.zip --host 192.168.1.50 -p secret
"""

import argparse
import base64
import json
import os
import ssl
import sys
import urllib.error
import urllib.request
from pathlib import Path

MAX_ZIP_BYTES = 4 * 1024 * 1024  # 4 MB limit enforced by Kiosk Satellite


def parse_args():
    parser = argparse.ArgumentParser(
        description="Upload and install a plugin ZIP on Kiosk Satellite via Remote API."
    )
    parser.add_argument(
        "zip_path",
        nargs="?",
        default=None,
        help="Path to plugin ZIP file (defaults to latest in dist/)",
    )
    parser.add_argument(
        "--host",
        "-H",
        required=True,
        help="Device hostname or IP (e.g. 192.168.1.50 or http://192.168.1.50:2324)",
    )
    parser.add_argument(
        "--port",
        "-P",
        type=int,
        default=None,
        help="Server port (default: 2324 or parsed from --host)",
    )
    parser.add_argument(
        "--password",
        "-p",
        default="",
        help="Remote admin password (if configured on Kiosk Satellite)",
    )
    parser.add_argument(
        "--token",
        "-t",
        default="",
        help="Existing Bearer token (skips /api/login)",
    )
    parser.add_argument(
        "--https",
        action="store_true",
        help="Use HTTPS instead of HTTP (auto-detected if URL has https://)",
    )
    parser.add_argument(
        "--insecure",
        "-k",
        action="store_true",
        default=True,
        help="Allow self-signed HTTPS certificates (default: True)",
    )
    parser.add_argument(
        "--enable",
        "-e",
        action="store_true",
        help="Automatically enable the plugin if it is disabled after install",
    )
    parser.add_argument(
        "--master-enable",
        action="store_true",
        help="Automatically enable the global Plugins master switch if disabled",
    )
    return parser.parse_args()


def find_zip(provided_path):
    if provided_path:
        p = Path(provided_path)
        if not p.is_file():
            print(f"Error: File not found: {provided_path}", file=sys.stderr)
            sys.exit(1)
        return p

    # Look in dist/
    dist_dir = Path("dist")
    if not dist_dir.is_dir():
        dist_dir = Path(__file__).resolve().parent.parent / "dist"

    if dist_dir.is_dir():
        zips = sorted(dist_dir.glob("*.zip"), key=os.path.getmtime, reverse=True)
        if zips:
            return zips[0]

    print("Error: No zip file specified and none found in dist/", file=sys.stderr)
    sys.exit(1)


def build_base_url(host, port, use_https):
    raw = host.strip()
    if raw.startswith("http://"):
        scheme = "http"
        rest = raw[7:]
    elif raw.startswith("https://"):
        scheme = "https"
        rest = raw[8:]
    else:
        scheme = "https" if use_https else "http"
        rest = raw

    rest = rest.rstrip("/")
    if ":" in rest:
        base_host, parsed_port = rest.rsplit(":", 1)
        try:
            port = int(parsed_port)
            rest = base_host
        except ValueError:
            pass

    final_port = port if port is not None else 2324
    return f"{scheme}://{rest}:{final_port}"


def make_request(url, data=None, token=None, insecure=True):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=headers)

    context = None
    if url.startswith("https://"):
        context = ssl.create_default_context()
        if insecure:
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE

    try:
        with urllib.request.urlopen(req, context=context, timeout=90) as resp:
            content = resp.read().decode("utf-8")
            return resp.status, json.loads(content) if content else {}
    except urllib.error.HTTPError as err:
        error_body = err.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(error_body)
        except Exception:
            parsed = {"error": error_body}
        return err.code, parsed
    except urllib.error.URLError as err:
        print(f"Connection failed: {err.reason}", file=sys.stderr)
        sys.exit(1)


def execute_command(base_url, command_name, params, token, insecure=True):
    url = f"{base_url}/api/commands/{command_name}"
    status, res = make_request(url, data=params, token=token, insecure=insecure)
    if status == 200 and res.get("ok", True):
        return res.get("data")
    error_msg = res.get("error", f"HTTP {status}")
    raise RuntimeError(f"Command '{command_name}' failed: {error_msg}")


def main():
    args = parse_args()
    zip_path = find_zip(args.zip_path)
    file_size = zip_path.stat().st_size

    if file_size > MAX_ZIP_BYTES:
        print(
            f"Error: Plugin ZIP is {file_size / (1024 * 1024):.2f} MB. Kiosk Satellite maximum limit is 4 MB.",
            file=sys.stderr,
        )
        sys.exit(1)

    base_url = build_base_url(args.host, args.port, args.https)
    print(f"📦 Plugin ZIP: {zip_path.name} ({file_size / 1024:.1f} KB)")
    print(f"🌐 Target:     {base_url}")

    token = args.token
    # 1. Login if password provided and no token supplied
    if not token and args.password:
        print("🔑 Logging in to remote admin...")
        status, login_res = make_request(
            f"{base_url}/api/login",
            data={"password": args.password},
            insecure=args.insecure,
        )
        if status != 200 or "token" not in login_res:
            err = login_res.get("error", f"HTTP {status}")
            print(f"Authentication failed: {err}", file=sys.stderr)
            sys.exit(1)
        token = login_res["token"]
        print("   Authentication successful.")

    # 2. Read ZIP and base64 encode
    print("⏳ Encoding ZIP...")
    with open(zip_path, "rb") as f:
        zip_base64 = base64.b64encode(f.read()).decode("ascii")

    # 3. Upload & install
    print("🚀 Uploading and installing plugin...")
    try:
        plugins_list = execute_command(
            base_url,
            "installPlugin",
            {"data": zip_base64, "trusted": True},
            token=token,
            insecure=args.insecure,
        )
    except Exception as e:
        print(f"❌ Installation failed: {e}", file=sys.stderr)
        sys.exit(1)

    print("✅ Installation completed successfully!")

    # 4. Check status & optionally enable
    try:
        state = execute_command(
            base_url, "getPluginState", {}, token=token, insecure=args.insecure
        )
        global_enabled = state.get("enabled", False)
        plugins = state.get("plugins", [])

        # Find our plugin in the installed list
        installed_plugin = None
        for p in plugins:
            # Match by zip file name or match frigate ID
            if p.get("id") == "kiosk-satellite-frigate" or "frigate" in p.get("id", "").lower():
                installed_plugin = p
                break

        if not global_enabled:
            print("⚠️  Warning: Master 'Plugins' feature is currently DISABLED on device.")
            if args.master_enable:
                print("   Enabling global Plugins feature...")
                execute_command(
                    base_url,
                    "setPluginsEnabled",
                    {"enabled": True},
                    token=token,
                    insecure=args.insecure,
                )
                print("   Global Plugins enabled.")

        if installed_plugin:
            p_id = installed_plugin.get("id")
            p_name = installed_plugin.get("name")
            p_ver = installed_plugin.get("version")
            p_enabled = installed_plugin.get("enabled", False)
            p_running = installed_plugin.get("running", False)

            print(f"\n📋 Status for '{p_name}' ({p_id}):")
            print(f"   • Version: {p_ver}")
            print(f"   • Enabled: {p_enabled}")
            print(f"   • Running: {p_running}")

            if not p_enabled and args.enable:
                print(f"   Enabling plugin '{p_id}'...")
                execute_command(
                    base_url,
                    "enablePlugin",
                    {"id": p_id},
                    token=token,
                    insecure=args.insecure,
                )
                print(f"   Plugin '{p_id}' is now enabled!")
        else:
            print(f"\n📋 Installed plugins count: {len(plugins)}")

    except Exception as e:
        print(f"Note: Could not retrieve final plugin state ({e})")


if __name__ == "__main__":
    main()
