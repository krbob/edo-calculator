import importlib.util
import json
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("publish", Path(__file__).with_name("publish-edo-catalog.py"))
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)
SHA = "a" * 40


def pr():
    return dict(state="OPEN", baseRefName="main", headRefName=publish.BRANCH,
                headRefOid=SHA, isCrossRepository=False, files=[{"path": publish.CATALOG}])


class PublishTest(unittest.TestCase):
    def test_only_expected_catalog_pr_can_be_merged(self):
        original = pr()
        publish.validate_pr(original, SHA)
        for changes in [
            {"state": "CLOSED"}, {"headRefOid": "b" * 40}, {"baseRefName": "other"},
            {"headRefName": "other"}, {"isCrossRepository": True}, {"files": []},
            {"files": [{"path": publish.CATALOG}, {"path": ".github/workflows/ci.yml"}]},
        ]:
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                publish.validate_pr({**original, **changes}, SHA)

    def test_failed_ci_stops_merge_and_publication(self):
        with patch.object(publish, "gh", return_value=json.dumps(pr())) as gh, \
                patch.object(publish, "run_ci", side_effect=RuntimeError("CI failed")):
            with self.assertRaisesRegex(RuntimeError, "CI failed"):
                publish.publish(1, SHA)
        self.assertTrue(all(call.args[:2] == ("pr", "view") for call in gh.call_args_list))

    def test_changed_pr_during_ci_is_not_merged(self):
        changed = {**pr(), "headRefOid": "b" * 40}
        with patch.object(publish, "gh", side_effect=[json.dumps(pr()), json.dumps(changed)]) as gh, \
                patch.object(publish, "run_ci"):
            with self.assertRaises(RuntimeError):
                publish.publish(1, SHA)
        self.assertTrue(all(call.args[:2] == ("pr", "view") for call in gh.call_args_list))

    def test_main_publication_uses_actual_merge_commit_after_successful_ci(self):
        merged_sha = "c" * 40
        responses = [json.dumps(pr()), json.dumps(pr()), "", json.dumps({
            "state": "MERGED", "mergeCommit": {"oid": merged_sha},
        })]
        with patch.object(publish, "gh", side_effect=responses) as gh, \
                patch.object(publish, "run_ci") as ci:
            publish.publish(12, SHA)
        self.assertEqual([call.args for call in ci.call_args_list],
                         [(publish.BRANCH, SHA), ("main", merged_sha)])
        self.assertIn(("pr", "merge", "12", "--squash", "--match-head-commit", SHA),
                      [call.args for call in gh.call_args_list])

    def test_wait_does_not_accept_old_success_or_another_commit(self):
        old = {"databaseId": 1, "headSha": SHA}
        other = {"databaseId": 2, "headSha": "b" * 40}
        new = {"databaseId": 3, "headSha": SHA}
        with patch.object(publish, "runs", side_effect=[[old], [old, other], [old, other, new]]), \
                patch.object(publish, "gh"), patch.object(publish.time, "sleep"), \
                patch.object(publish.subprocess, "run") as watch:
            publish.run_ci(publish.BRANCH, SHA)
        self.assertEqual(watch.call_args.args[0][3], "3")
        self.assertTrue(watch.call_args.kwargs["check"])

    def test_no_matching_run_fails_instead_of_hanging_or_merging(self):
        with patch.object(publish, "runs", return_value=[]), patch.object(publish, "gh"), \
                patch.object(publish.time, "sleep"), patch.object(publish.subprocess, "run") as watch:
            with self.assertRaisesRegex(RuntimeError, "No dispatched CI run"):
                publish.run_ci(publish.BRANCH, SHA)
        watch.assert_not_called()

    def test_failed_dispatched_workflow_is_propagated(self):
        with patch.object(publish, "runs", side_effect=[[], [{"databaseId": 3, "headSha": SHA}]]), \
                patch.object(publish, "gh"), patch.object(publish.subprocess, "run",
                    side_effect=subprocess.CalledProcessError(1, "gh")):
            with self.assertRaises(subprocess.CalledProcessError):
                publish.run_ci(publish.BRANCH, SHA)


if __name__ == "__main__":
    unittest.main()
