import io
import json

import pytest
from botocore.exceptions import ClientError

import publish


class FakeS3:
    """In-memory manifest.json with S3's conditional-write semantics (If-Match / If-None-Match)."""

    class exceptions:
        class NoSuchKey(Exception):
            pass

    def __init__(self, manifest=None):
        self.body = json.dumps(manifest).encode() if manifest is not None else None
        self.version = 0
        self.before_put = None  # hook: simulate another publisher writing first

    def etag(self):
        return f'"v{self.version}"'

    def get_object(self, Bucket, Key):
        if self.body is None:
            raise self.exceptions.NoSuchKey()
        return {"Body": io.BytesIO(self.body), "ETag": self.etag()}

    def put_object(self, Bucket, Key, Body, ContentType, IfMatch=None, IfNoneMatch=None):
        if self.before_put:
            hook, self.before_put = self.before_put, None
            hook(self)
        if (IfMatch and IfMatch != self.etag()) or (IfNoneMatch and self.body is not None):
            raise ClientError({"Error": {"Code": "PreconditionFailed"}}, "PutObject")
        self.body, self.version = Body, self.version + 1

    def apps(self):
        return json.loads(self.body)["apps"]


def entry(pkg, code):
    return lambda current: {"packageName": pkg, "versionCode": code}


def no_check(current):
    pass


def test_concurrent_publish_of_another_app_is_kept():
    s3 = FakeS3({"apps": {}})

    def tv_publishes_first(s3):
        apps = s3.apps() if s3.body else {}
        apps["com.example.tv"] = {"packageName": "com.example.tv", "versionCode": 5}
        s3.body, s3.version = json.dumps({"apps": apps}).encode(), s3.version + 1

    s3.before_put = tv_publishes_first
    publish.commit_entry(s3, "b", "com.example.phone", entry("com.example.phone", 7), no_check, sleep=lambda s: None)

    assert s3.apps() == {
        "com.example.tv": {"packageName": "com.example.tv", "versionCode": 5},
        "com.example.phone": {"packageName": "com.example.phone", "versionCode": 7},
    }


def test_first_ever_publish_races_too():
    s3 = FakeS3(None)
    s3.before_put = lambda s3: setattr(s3, "body", json.dumps({"apps": {"a": {"versionCode": 1}}}).encode())
    publish.commit_entry(s3, "b", "b", entry("b", 1), no_check, sleep=lambda s: None)
    assert set(s3.apps()) == {"a", "b"}


def test_checks_rerun_against_the_newer_manifest():
    s3 = FakeS3({"apps": {}})

    def same_app_published_higher(s3):
        s3.body = json.dumps({"apps": {"p": {"versionCode": 9}}}).encode()
        s3.version += 1

    def check(current):
        if current and current["versionCode"] >= 7:
            raise SystemExit("not higher")

    s3.before_put = same_app_published_higher
    with pytest.raises(SystemExit):
        publish.commit_entry(s3, "b", "p", entry("p", 7), check, sleep=lambda s: None)
    assert s3.apps() == {"p": {"versionCode": 9}}


def test_gives_up_after_repeated_conflicts():
    s3 = FakeS3({"apps": {}})
    real_put = s3.put_object

    def always_conflict(**kw):
        s3.version += 1
        return real_put(**kw)

    s3.put_object = always_conflict
    with pytest.raises(SystemExit, match="gave up"):
        publish.commit_entry(s3, "b", "p", entry("p", 1), no_check, sleep=lambda s: None)


def test_other_errors_are_not_retried():
    s3 = FakeS3({"apps": {}})

    def denied(**kw):
        raise ClientError({"Error": {"Code": "AccessDenied"}}, "PutObject")

    s3.put_object = denied
    with pytest.raises(ClientError):
        publish.commit_entry(s3, "b", "p", entry("p", 1), no_check, sleep=lambda s: None)
