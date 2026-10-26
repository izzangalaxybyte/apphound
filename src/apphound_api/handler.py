"""apphound API: lists the apps in the store and their latest versions.

Routes (Lambda Function URL, all require `Authorization: Bearer <token>`):
  GET /apps          -> {"apps": [App, ...]}
  GET /apps/{pkg}    -> App

App is the manifest entry plus short-lived presigned `apkUrl` / `iconUrl`. The manifest lives at
s3://$BUCKET/manifest.json and is written only by scripts/publish.py.
"""

import hmac
import json
import os

import boto3
from botocore.config import Config

BUCKET = os.environ.get("BUCKET", "")
TOKEN_PARAM = os.environ.get("TOKEN_PARAM", "/apphound/api-token")
URL_TTL_SECONDS = int(os.environ.get("URL_TTL_SECONDS", "900"))
MANIFEST_KEY = "manifest.json"

# Fields copied from the manifest into responses; `key` / `iconKey` stay server-side.
PUBLIC_FIELDS = (
    "packageName", "name", "versionCode", "versionName", "minSdk",
    "size", "sha256", "signerSha256", "changelog", "publishedAt",
)

_s3 = None
_token = None


def s3():
    global _s3
    if _s3 is None:
        # Presign against the regional endpoint: the global one rejects non-us-east-1 signatures
        # for new buckets ("the region 'eu-central-1' is wrong").
        region = os.environ.get("AWS_REGION", "eu-central-1")
        _s3 = boto3.client(
            "s3", region_name=region, endpoint_url=f"https://s3.{region}.amazonaws.com",
            config=Config(signature_version="s3v4", s3={"addressing_style": "virtual"}),
        )
    return _s3


def api_token():
    global _token
    if _token is None:
        ssm = boto3.client("ssm")
        _token = ssm.get_parameter(Name=TOKEN_PARAM, WithDecryption=True)["Parameter"]["Value"]
    return _token


def load_manifest():
    try:
        body = s3().get_object(Bucket=BUCKET, Key=MANIFEST_KEY)["Body"].read()
    except s3().exceptions.NoSuchKey:
        return {"apps": {}}
    return json.loads(body)


def presign(key):
    return s3().generate_presigned_url(
        "get_object", Params={"Bucket": BUCKET, "Key": key}, ExpiresIn=URL_TTL_SECONDS
    )


def to_public(entry):
    app = {k: entry[k] for k in PUBLIC_FIELDS if k in entry}
    app["apkUrl"] = presign(entry["key"])
    app["iconUrl"] = presign(entry["iconKey"]) if entry.get("iconKey") else None
    return app


def response(status, body):
    return {
        "statusCode": status,
        "headers": {"content-type": "application/json", "cache-control": "no-store"},
        "body": json.dumps(body),
    }


def authorized(event):
    header = (event.get("headers") or {}).get("authorization", "")
    scheme, _, token = header.partition(" ")
    return scheme.lower() == "bearer" and hmac.compare_digest(token.encode(), api_token().encode())


def handler(event, context):
    method = event.get("requestContext", {}).get("http", {}).get("method", "GET")
    path = event.get("rawPath", "/").rstrip("/") or "/"

    if not authorized(event):
        return response(401, {"error": "unauthorized"})
    if method != "GET":
        return response(405, {"error": "method not allowed"})

    if path == "/apps":
        apps = load_manifest()["apps"].values()
        return response(200, {"apps": [to_public(a) for a in sorted(apps, key=lambda a: a["name"].lower())]})

    if path.startswith("/apps/"):
        entry = load_manifest()["apps"].get(path.removeprefix("/apps/"))
        if entry is None:
            return response(404, {"error": "no such app"})
        return response(200, to_public(entry))

    return response(404, {"error": "not found"})
