import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("reload_region", Path(__file__).parents[1] / "reload-region-catalog.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReloadScriptTest(unittest.TestCase):
    def target(self, instance="one"):
        return dict(application="wabd", instanceId=instance, url=f"http://{instance}:8080/api/wabd", sha256="a" * 64)

    def run_targets(self, targets, responses):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "targets.json"
            path.write_text(json.dumps(targets))
            with patch.dict(os.environ, REGION_CATALOG_RELOAD_TOKEN="secret"), patch.object(module, "request", side_effect=responses) as request:
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    result = module.main([str(path)])
                return result, request.call_count

    def response(self, instance="one", sha="a" * 64):
        return dict(application="wabd", instanceId=instance, active=dict(sha256=sha), next=None, result="UNCHANGED")

    def test_all_instances_verified(self):
        self.assertEqual((0, 4), self.run_targets([self.target(), self.target("two")],
                         [self.response(), self.response(), self.response("two"), self.response("two")]))

    def test_partial_failure_is_failure_and_remaining_instances_are_checked(self):
        self.assertEqual((1, 3), self.run_targets([self.target(), self.target("two")],
                         [OSError("timeout"), self.response("two"), self.response("two")]))

    def test_duplicate_targets_rejected_before_mutation(self):
        self.assertEqual((1, 0), self.run_targets([self.target(), self.target()], []))

    def test_wrong_identity_or_hash_is_failure(self):
        for response in [self.response("wrong"), self.response(sha="b" * 64)]:
            self.assertEqual((1, 1), self.run_targets([self.target()], [response]))

    def test_status_verification_required_after_successful_post(self):
        self.assertEqual((1, 2), self.run_targets([self.target()], [self.response(), self.response(sha="b" * 64)]))

    def test_redirects_are_not_followed(self):
        self.assertIsNone(module.NoRedirect().redirect_request(None, None, 302, "", {}, "http://other"))


if __name__ == "__main__":
    unittest.main()
