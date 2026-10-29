#!/usr/bin/env bash
set -euo pipefail

# 每次构建解析最新正式 Release，从该次响应选定版本、下载地址和摘要。
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
BUNDLE="$TEMP_DIR/cloudphone.zip"
CURL_OPTIONS=(--fail --location --retry 3 --connect-timeout 20 --max-time 300)
API_HEADERS=(-H "Accept: application/vnd.github+json")
if [ -n "${GITHUB_TOKEN:-}" ]; then
    API_HEADERS+=(-H "Authorization: Bearer $GITHUB_TOKEN")
fi
curl "${CURL_OPTIONS[@]}" "${API_HEADERS[@]}" \
    "https://api.github.com/repos/hqw700/ScrcpyOverWebRTC/releases/latest" \
    -o "$TEMP_DIR/release.json"
python3 - "$TEMP_DIR/release.json" > "$TEMP_DIR/asset.txt" <<'PYTHON'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    release = json.load(source)
version = release.get("tag_name", "")
if release.get("draft") or release.get("prerelease") or not re.fullmatch(r"[A-Za-z0-9._-]+", version):
    sys.exit("Latest release has an invalid tag or is not a stable release")
name = f"cloudphone-{version}.zip"
assets = [asset for asset in release.get("assets", []) if asset.get("name") == name]
if len(assets) != 1:
    sys.exit(f"Latest release {version} must contain exactly one {name}")
asset = assets[0]
url = asset.get("browser_download_url", "")
expected_url = f"https://github.com/hqw700/ScrcpyOverWebRTC/releases/download/{version}/{name}"
if url != expected_url:
    sys.exit("Unexpected release asset download URL")
digest = asset.get("digest") or ""
if not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", digest):
    sys.exit(f"Release asset {name} is missing a valid GitHub SHA-256 digest")
print(version)
print(url)
print(digest.split(":", 1)[1])
PYTHON
{
    read -r AGENT_VERSION
    read -r BUNDLE_URL
    read -r AGENT_SHA256
} < "$TEMP_DIR/asset.txt"
echo "Downloading agent bundle from latest release: $AGENT_VERSION"
curl "${CURL_OPTIONS[@]}" "$BUNDLE_URL" -o "$BUNDLE"
printf '%s  %s\n' "$AGENT_SHA256" "$BUNDLE" | shasum -a 256 --check
unzip -q "$BUNDLE" 'agentd/*' -d "$TEMP_DIR/extracted"
ASSETS=(cloudphone-agent-arm64 cloudphone-agent-armeabi-v7a cloudphone-agent-amd64 libsys_core.so)
for name in "${ASSETS[@]}"; do
    test -s "$TEMP_DIR/extracted/agentd/$name" || { echo "Missing required agent asset: $name" >&2; exit 1; }
done
mkdir -p "$ROOT_DIR/app/src/main/assets"
for name in "${ASSETS[@]}"; do
    cp "$TEMP_DIR/extracted/agentd/$name" "$ROOT_DIR/app/src/main/assets/$name"
done
echo "Verified and installed agent assets from $AGENT_VERSION"
