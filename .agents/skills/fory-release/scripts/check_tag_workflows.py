# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess

RUN_FIELDS = "databaseId,workflowName,status,conclusion,headSha,headBranch,url"
RC_TAG_PATTERN = re.compile(r"v\d+\.\d+\.\d+-rc\d+")
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")


def _parse_args():
    parser = argparse.ArgumentParser(
        description="Validate GitHub Actions runs triggered by a Fory RC tag"
    )
    parser.add_argument("--repo", default="apache/fory", help="GitHub repository")
    parser.add_argument("--tag", required=True, help="immutable RC tag")
    parser.add_argument("--commit", required=True, help="full release commit SHA")
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument(
        "--watch",
        action="store_true",
        help="wait for every tag-triggered run and require success",
    )
    action.add_argument(
        "--allow-incomplete",
        action="store_true",
        help="record a read-only snapshot without waiting for incomplete runs",
    )
    return parser.parse_args()


def _run_gh(arguments):
    result = subprocess.run(
        ["gh", *arguments],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise RuntimeError(f"gh {' '.join(arguments)} failed: {detail}")
    return result.stdout


def _watch_run(repo, database_id):
    result = subprocess.run(
        [
            "gh",
            "run",
            "watch",
            str(database_id),
            "--repo",
            repo,
            "--exit-status",
        ],
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"GitHub workflow run {database_id} did not succeed")


def _list_runs(repo, tag):
    output = _run_gh(
        [
            "run",
            "list",
            "--repo",
            repo,
            "--branch",
            tag,
            "--event",
            "push",
            "--limit",
            "100",
            "--json",
            RUN_FIELDS,
        ]
    )
    try:
        runs = json.loads(output)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"gh returned invalid workflow JSON: {exc}") from None
    if not isinstance(runs, list) or not runs:
        raise RuntimeError(f"No tag-triggered workflows found for {tag}")
    return runs


def _validate_runs(runs, tag, commit):
    wrong_revision = [
        run
        for run in runs
        if run.get("headSha") != commit or run.get("headBranch") != tag
    ]
    if wrong_revision:
        raise RuntimeError(f"Unexpected workflow revisions: {wrong_revision}")
    for run in sorted(
        runs, key=lambda item: (str(item.get("workflowName")), item.get("databaseId"))
    ):
        print(
            run.get("databaseId"),
            run.get("workflowName"),
            run.get("status"),
            run.get("conclusion") or "-",
            run.get("url"),
        )
    failed = [
        run
        for run in runs
        if run.get("status") == "completed" and run.get("conclusion") != "success"
    ]
    if failed:
        raise RuntimeError(f"Failed tag-triggered workflows: {failed}")


def _incomplete_runs(runs):
    return [run for run in runs if run.get("status") != "completed"]


def _watch_runs(repo, tag, commit):
    while True:
        runs = _list_runs(repo, tag)
        _validate_runs(runs, tag, commit)
        incomplete = _incomplete_runs(runs)
        if not incomplete:
            return
        for run in incomplete:
            database_id = run.get("databaseId")
            if database_id is None:
                raise RuntimeError(f"Workflow run has no databaseId: {run}")
            _watch_run(repo, database_id)


def main():
    args = _parse_args()
    if not shutil.which("gh"):
        raise SystemExit("gh is required")
    if not RC_TAG_PATTERN.fullmatch(args.tag):
        raise SystemExit("--tag must be an RC tag such as v1.7.0-rc3")
    if not COMMIT_PATTERN.fullmatch(args.commit):
        raise SystemExit("--commit must be a full 40-character lowercase SHA")
    try:
        if args.watch:
            _watch_runs(args.repo, args.tag, args.commit)
            return
        runs = _list_runs(args.repo, args.tag)
        _validate_runs(runs, args.tag, args.commit)
        incomplete = _incomplete_runs(runs)
        if incomplete:
            print(f"Monitoring waived: {len(incomplete)} workflow run(s) incomplete")
    except RuntimeError as exc:
        raise SystemExit(str(exc)) from None


if __name__ == "__main__":
    main()
