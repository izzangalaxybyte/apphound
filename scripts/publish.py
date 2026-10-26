"""Publish an APK to the apphound store.

    uv run scripts/publish.py path/to/app-release.apk --changelog "Fixed sync"

Reads package name, version and label from the APK (aapt), checks the signing certificate
(apksigner), uploads the APK (and its icon when it's a PNG) to S3, then updates manifest.json,
which is the moment the new version becomes visible to the store app.

Refuses to publish when the versionCode isn't higher than the published one (use --force to
re-publish), or when the signing certificate changed: Android won't install an update signed
with a different key, so a store entry like that would be stuck.
"""

import argparse
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

import boto3
from botocore.exceptions import ClientError

ROOT = Path(__file__).resolve().parent.parent
MANIFEST_KEY = "manifest.json"
# Several apps published at once (e.g. phone + TV builds) race on manifest.json; the loser retries.
MAX_MANIFEST_ATTEMPTS = 8


def load_env():
    env_file = ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            key, sep, value = line.partition("=")
            if sep and not line.lstrip().startswith("#"):
                os.environ.setdefault(key.strip(), value.strip())


def build_tool(name):
    """Path to `name` from the newest Android SDK build-tools."""
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    tools = sorted((Path(sdk) / "build-tools").glob("*/" + name))
    if tools:
        return str(tools[-1])
    found = shutil.which(name)
    if found:
        return found
    sys.exit(f"{name} not found: install Android SDK build-tools or set ANDROID_HOME")


def java_env():
    """apksigner needs a JDK; fall back to the one bundled with Android Studio.

    (macOS always has a /usr/bin/java stub, even without a JDK, so `which java` proves nothing.)
    """
    env = dict(os.environ)
    bundled = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    if "JAVA_HOME" not in env and Path(bundled).exists():
        env["JAVA_HOME"] = bundled
    return env


def read_badging(apk):
    out = subprocess.run([build_tool("aapt"), "dump", "badging", str(apk)],
                         check=True, capture_output=True, text=True).stdout
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    if not pkg:
        sys.exit("could not read package info from the APK")
    label = re.search(r"application-label:'([^']*)'", out)
    min_sdk = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", out)
    # Highest-density icon; only PNGs are usable (adaptive icons are XML).
    icons = re.findall(r"application-icon-(\d+):'([^']+)'", out)
    icon = max(icons, key=lambda i: int(i[0]))[1] if icons else None
    return {
        "packageName": pkg[1],
        "versionCode": int(pkg[2]),
        "versionName": pkg[3],
        "name": label[1] if label else pkg[1],
        "minSdk": int(min_sdk[1]) if min_sdk else 1,
        "iconPath": icon if icon and icon.endswith(".png") else None,
    }


def signer_sha256(apk):
    out = subprocess.run([build_tool("apksigner"), "verify", "--print-certs", str(apk)],
                         check=True, capture_output=True, text=True, env=java_env()).stdout
    digest = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", out)
    if not digest:
        sys.exit("APK is not signed (apksigner found no signer)")
    return digest[1]


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_manifest(s3, bucket):
    """The current manifest and its ETag (None when nothing has been published yet)."""
    try:
        obj = s3.get_object(Bucket=bucket, Key=MANIFEST_KEY)
        return json.loads(obj["Body"].read()), obj["ETag"]
    except s3.exceptions.NoSuchKey:
        return {"apps": {}}, None


def is_write_conflict(e):
    """S3's answer to a conditional write that lost a race with another writer."""
    return e.response.get("Error", {}).get("Code") in ("PreconditionFailed", "ConditionalRequestConflict")


def commit_entry(s3, bucket, pkg, build_entry, check, sleep=time.sleep):
    """Writes build_entry(current) into the manifest under pkg without losing concurrent publishes.

    Each attempt reads the latest manifest, re-runs check(current) against it, and writes only if
    the manifest is still the version it read (If-Match). When another publish got in first, S3
    refuses the write and we retry on top of their change.
    """
    for attempt in range(MAX_MANIFEST_ATTEMPTS):
        manifest, etag = read_manifest(s3, bucket)
        current = manifest["apps"].get(pkg)
        check(current)
        manifest["apps"][pkg] = build_entry(current)
        put = {"Bucket": bucket, "Key": MANIFEST_KEY, "ContentType": "application/json",
               "Body": json.dumps(manifest, indent=2).encode()}
        put.update({"IfMatch": etag} if etag else {"IfNoneMatch": "*"})
        try:
            s3.put_object(**put)
            return manifest["apps"][pkg]
        except ClientError as e:
            if not is_write_conflict(e):
                raise
            print(f"manifest changed by another publish; retrying ({attempt + 1}/{MAX_MANIFEST_ATTEMPTS})")
            sleep(random.uniform(0.2, 1.0) * (attempt + 1))
    sys.exit(f"{pkg}: gave up after {MAX_MANIFEST_ATTEMPTS} conflicting manifest writes; "
             "the APK is uploaded, re-run to publish it")


def main():
    load_env()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--changelog", default="")
    parser.add_argument("--name", help="display name (default: the APK's label)")
    parser.add_argument("--icon", type=Path, help="PNG icon to use instead of the APK's")
    parser.add_argument("--force", action="store_true", help="allow re-publishing the same or a lower versionCode")
    args = parser.parse_args()

    bucket = os.environ.get("APPHOUND_BUCKET") or sys.exit("APPHOUND_BUCKET not set: run scripts/deploy.sh first")
    info = read_badging(args.apk)
    signer = signer_sha256(args.apk)
    pkg, code = info["packageName"], info["versionCode"]

    session = boto3.Session(profile_name=os.environ.get("AWS_PROFILE", "apphound"),
                            region_name=os.environ.get("AWS_REGION", "eu-central-1"))
    s3 = session.client("s3")

    def check(current):
        if current:
            if current.get("signerSha256") and current["signerSha256"] != signer:
                sys.exit(f"{pkg}: signing certificate differs from the published one; devices could not update")
            if code <= current["versionCode"] and not args.force:
                sys.exit(f"{pkg}: versionCode {code} is not higher than published {current['versionCode']} (use --force)")

    # Check before uploading anything; commit_entry checks again against the manifest it writes.
    check(read_manifest(s3, bucket)[0]["apps"].get(pkg))

    key = f"apps/{pkg}/{code}.apk"
    print(f"uploading {pkg} {info['versionName']} ({code}) -> s3://{bucket}/{key}")
    s3.upload_file(str(args.apk), bucket, key,
                   ExtraArgs={"ContentType": "application/vnd.android.package-archive"})

    icon_bytes = args.icon.read_bytes() if args.icon else None
    if icon_bytes is None and info["iconPath"]:
        with zipfile.ZipFile(args.apk) as z:
            icon_bytes = z.read(info["iconPath"])
    new_icon_key = None
    if icon_bytes:
        new_icon_key = f"apps/{pkg}/icon-{code}.png"
        s3.put_object(Bucket=bucket, Key=new_icon_key, Body=icon_bytes, ContentType="image/png")

    size, sha256 = args.apk.stat().st_size, sha256_file(args.apk)

    def build_entry(current):
        current = current or {}
        return {
            "packageName": pkg,
            "name": args.name or current.get("name") or info["name"],
            "versionCode": code,
            "versionName": info["versionName"],
            "minSdk": info["minSdk"],
            "size": size,
            "sha256": sha256,
            "signerSha256": signer,
            "changelog": args.changelog,
            "publishedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "key": key,
            "iconKey": new_icon_key or current.get("iconKey"),
        }

    entry = commit_entry(s3, bucket, pkg, build_entry, check)
    print(f"published {entry['name']} {info['versionName']}")


if __name__ == "__main__":
    main()
