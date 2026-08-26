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
import urllib.error
import urllib.parse
import urllib.request

PONY_MAIL_SEARCH_URL = "https://lists.apache.org/api/stats.lua"
FORY_LIST_DOMAIN = "fory.apache.org"
RELEASE_VERSION_PATTERN = re.compile(r"\d+\.\d+\.\d+")


def _parse_args():
    parser = argparse.ArgumentParser(
        description="Find the root Fory release discussion thread"
    )
    parser.add_argument(
        "release_version",
        help="final release version without a v prefix or RC suffix",
    )
    parser.add_argument(
        "--years",
        type=int,
        default=1,
        help="number of years of mailing-list history to search",
    )
    return parser.parse_args()


def _search_messages(release_version, years):
    query = urllib.parse.urlencode(
        {
            "d": f"lte={years}y",
            "list": "dev",
            "domain": FORY_LIST_DOMAIN,
            "q": release_version,
            "header_subject": "DISCUSS",
        }
    )
    request = urllib.request.Request(
        f"{PONY_MAIL_SEARCH_URL}?{query}",
        headers={
            "Accept": "application/json",
            "User-Agent": "apache-fory-release-helper/1",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
    except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"Apache mailing-list search failed: {exc}") from None
    if not isinstance(payload, dict):
        raise RuntimeError("Apache mailing-list search returned invalid JSON")
    messages = payload.get("emails")
    if not isinstance(messages, list):
        raise RuntimeError("Apache mailing-list search returned no email list")
    return messages


def _discussion_url(release_version, messages):
    version = re.compile(rf"(?<![\d.]){re.escape(release_version)}(?![\d.])")
    matches = []
    for message in messages:
        subject = str(message.get("subject", ""))
        if message.get("in-reply-to"):
            continue
        if "[DISCUSS]" not in subject.upper() or not version.search(subject):
            continue
        thread_id = message.get("id")
        if thread_id:
            matches.append(str(thread_id))
    if len(matches) != 1:
        raise RuntimeError(
            f"Expected one root [DISCUSS] thread for {release_version}, "
            f"found {len(matches)}"
        )
    return f"https://lists.apache.org/thread/{matches[0]}"


def main():
    args = _parse_args()
    if not RELEASE_VERSION_PATTERN.fullmatch(args.release_version):
        raise SystemExit("release_version must be a final version such as 1.7.0")
    if args.years < 1:
        raise SystemExit("--years must be positive")
    try:
        messages = _search_messages(args.release_version, args.years)
        print(_discussion_url(args.release_version, messages))
    except RuntimeError as exc:
        raise SystemExit(str(exc)) from None


if __name__ == "__main__":
    main()
