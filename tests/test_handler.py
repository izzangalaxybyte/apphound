import io
import json

import pytest

from apphound_api import handler as h

MANIFEST = {
    "apps": {
        "com.example.notes": {
            "packageName": "com.example.notes", "name": "Notes", "versionCode": 3, "versionName": "1.2",
            "key": "apps/com.example.notes/3.apk", "iconKey": "apps/com.example.notes/icon-3.png",
        },
        "com.example.alarm": {
            "packageName": "com.example.alarm", "name": "alarm", "versionCode": 7, "versionName": "2.0",
            "key": "apps/com.example.alarm/7.apk", "iconKey": None,
        },
    }
}


class FakeS3:
    class exceptions:
        class NoSuchKey(Exception):
            pass

    def __init__(self, manifest):
        self.manifest = manifest

    def get_object(self, Bucket, Key):
        if self.manifest is None:
            raise self.exceptions.NoSuchKey()
        return {"Body": io.BytesIO(json.dumps(self.manifest).encode())}

    def generate_presigned_url(self, op, Params, ExpiresIn):
        return f"https://signed/{Params['Key']}"


@pytest.fixture
def s3(monkeypatch):
    fake = FakeS3(MANIFEST)
    monkeypatch.setattr(h, "_s3", fake)
    monkeypatch.setattr(h, "_token", "secret")
    return fake


def call(path, token="secret", method="GET"):
    headers = {"authorization": f"Bearer {token}"} if token else {}
    res = h.handler({"rawPath": path, "headers": headers, "requestContext": {"http": {"method": method}}}, None)
    return res["statusCode"], json.loads(res["body"])


def test_rejects_missing_or_wrong_token(s3):
    assert call("/apps", token=None)[0] == 401
    assert call("/apps", token="nope")[0] == 401


def test_lists_apps_sorted_with_signed_urls_and_no_s3_keys(s3):
    status, body = call("/apps")
    assert status == 200
    assert [a["name"] for a in body["apps"]] == ["alarm", "Notes"]
    notes = body["apps"][1]
    assert notes["apkUrl"] == "https://signed/apps/com.example.notes/3.apk"
    assert notes["iconUrl"] == "https://signed/apps/com.example.notes/icon-3.png"
    assert body["apps"][0]["iconUrl"] is None
    assert "key" not in notes and "iconKey" not in notes


def test_single_app_and_unknown_app(s3):
    status, body = call("/apps/com.example.alarm/")
    assert status == 200 and body["versionCode"] == 7
    assert call("/apps/com.example.missing")[0] == 404


def test_empty_store_before_first_publish(s3):
    s3.manifest = None
    assert call("/apps") == (200, {"apps": []})


def test_only_get(s3):
    assert call("/apps", method="POST")[0] == 405
