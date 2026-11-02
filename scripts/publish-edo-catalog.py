#!/usr/bin/env python3
"""Verify a catalog-only PR, merge it through branch protection, then publish via CI."""

import argparse
import json
import re
import subprocess
import time

BRANCH = "automation/edo-catalog"
CATALOG = "src/main/resources/web/series.json"


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def runs(branch):
    return json.loads(gh("run", "list", "--workflow", "ci.yml", "--branch", branch,
                         "--event", "workflow_dispatch", "--limit", "30", "--json", "databaseId,headSha"))


def run_ci(branch, sha):
    previous = {run["databaseId"] for run in runs(branch)}
    # GITHUB_TOKEN pushes/merges do not start push CI. Dispatch it explicitly so both
    # the protected-branch check and the image publication actually run.
    gh("workflow", "run", "ci.yml", "--ref", branch)
    for _ in range(30):
        for run in runs(branch):
            if run["databaseId"] not in previous and run["headSha"] == sha:
                print(f"Waiting for CI run {run['databaseId']} on {branch} ({sha}).", flush=True)
                subprocess.run(["gh", "run", "watch", str(run["databaseId"]),
                                "--exit-status", "--interval", "10"], check=True, timeout=900)
                return
        time.sleep(2)
    raise RuntimeError(f"No dispatched CI run found for {branch} at {sha}; no unverified merge performed.")


def validate_pr(pr, sha):
    if (pr["state"] != "OPEN" or pr["baseRefName"] != "main" or pr["headRefName"] != BRANCH
            or pr["headRefOid"] != sha or pr["isCrossRepository"]
            or [file["path"] for file in pr["files"]] != [CATALOG]):
        raise RuntimeError("Refusing to merge: expected the unchanged catalog-only PR from the automation branch.")


def publish(number, sha):
    fields = "state,baseRefName,headRefName,headRefOid,isCrossRepository,files"
    validate_pr(json.loads(gh("pr", "view", str(number), "--json", fields)), sha)
    run_ci(BRANCH, sha)
    validate_pr(json.loads(gh("pr", "view", str(number), "--json", fields)), sha)
    gh("pr", "merge", str(number), "--squash", "--match-head-commit", sha)
    merged = json.loads(gh("pr", "view", str(number), "--json", "state,mergeCommit"))
    if merged["state"] != "MERGED":
        raise RuntimeError("Catalog PR was not merged; image publication was not requested.")
    run_ci("main", merged["mergeCommit"]["oid"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pr", type=int, required=True)
    parser.add_argument("--sha", required=True)
    args = parser.parse_args()
    if args.pr <= 0 or not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        parser.error("Expected a positive PR number and a full commit SHA")
    publish(args.pr, args.sha)


if __name__ == "__main__":
    main()
