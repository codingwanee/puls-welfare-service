#!/usr/bin/env python3
"""Reload each explicitly listed instance and verify its identity and catalog hash."""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_targets(path):
    targets = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(targets, list) or not targets:
        raise ValueError("targets must be a non-empty JSON array")
    identities, urls = set(), set()
    for target in targets:
        if not isinstance(target, dict):
            raise ValueError("each target must be an object")
        app, instance = target.get("application"), target.get("instanceId")
        url = target.get("url", "").rstrip("/")
        parsed = urllib.parse.urlsplit(url)
        if (app not in ("wabd", "wsbd") or not isinstance(instance, str) or not instance.strip()
                or parsed.scheme not in ("http", "https") or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment
                or parsed.path != "/api/" + app):
            raise ValueError("invalid target identity or URL (expected http(s)://instance:port/api/wabd or /api/wsbd)")
        identity = (app, instance)
        if identity in identities or url in urls:
            raise ValueError("duplicate target instance or URL")
        identities.add(identity)
        urls.add(url)
        for field in ("sha256", "nextSha256"):
            value = target.get(field)
            if (field == "sha256" or value is not None) and (not isinstance(value, str) or not re.fullmatch("[0-9a-f]{64}", value)):
                raise ValueError("expected lowercase SHA-256 for " + field)
        if app == "wsbd" and target.get("nextSha256") is not None:
            raise ValueError("wsbd has no next catalog")
        token_env = target.get("tokenEnv", "REGION_CATALOG_RELOAD_TOKEN")
        if not isinstance(token_env, str) or not os.environ.get(token_env, "").strip():
            raise ValueError("missing token environment variable")
        target["url"] = url
    return targets


def request(target, method, operation, timeout):
    token = os.environ[target.get("tokenEnv", "REGION_CATALOG_RELOAD_TOKEN")]
    req = urllib.request.Request(target["url"] + "/internal/regions/" + operation,
                                 method=method, headers={"Authorization": "Bearer " + token})
    with urllib.request.build_opener(NoRedirect).open(req, timeout=timeout) as response:
        return json.loads(response.read(1024 * 1024))


def verify(target, response):
    if not isinstance(response, dict):
        raise ValueError("invalid response")
    if (response.get("application"), response.get("instanceId")) != (target["application"], target["instanceId"]):
        raise ValueError("instance identity mismatch")
    if response.get("active", {}).get("sha256") != target["sha256"]:
        raise ValueError("active hash mismatch")
    if (response.get("next") or {}).get("sha256") != target.get("nextSha256"):
        raise ValueError("next hash mismatch")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("targets", help="JSON array of application, instanceId, url, sha256, optional nextSha256/tokenEnv")
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args(argv)
    if args.timeout <= 0:
        parser.error("timeout must be positive")
    try:
        targets = load_targets(args.targets)
    except (ValueError, OSError, TypeError):
        print("Invalid targets or missing token; check the target file and environment.", file=sys.stderr)
        return 1
    failures = 0
    for target in targets:
        try:
            result = request(target, "POST", "reload", args.timeout)
            verify(target, result)
            if result.get("result") not in ("RELOADED", "UNCHANGED"):
                raise ValueError("unexpected reload result")
            verify(target, request(target, "GET", "status", args.timeout))
            print(f'{target["application"]}/{target["instanceId"]}: {result["result"]} {target["sha256"]}')
        except urllib.error.HTTPError as exc:
            failures += 1
            print(f'{target["application"]}/{target["instanceId"]}: FAILED HTTP {exc.code}', file=sys.stderr)
        except (OSError, ValueError, TypeError, AttributeError):
            failures += 1
            # Do not echo untrusted server bodies, URLs, headers or tokens.
            print(f'{target["application"]}/{target["instanceId"]}: FAILED request or identity/hash verification', file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
