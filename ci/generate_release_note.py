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

from collections import defaultdict


def generate_release_notes(content_text):
    """
    Generate release notes from the github auto generated release note.
    """
    sections = defaultdict(list)
    new_contributors = []

    current_section = None
    full_changelog_link = None

    for line in content_text.strip().split("\n"):
        if line.startswith("##"):
            continue  # Skip section headers

        # Check for the Full Changelog line
        if "Full Changelog" in line:
            full_changelog_link = line
            continue

        # Remove the trailing ')' character, if present, from the line
        line = line.rstrip(")")

        if (
            line.startswith("* feat")
            or line.startswith("* perf")
            or line.startswith("* refactor")
        ):
            current_section = "Features"
        elif line.startswith("* fix"):
            current_section = "Bug Fix"
        elif (
            line.startswith("* chore")
            or line.startswith("* docs")
            or line.startswith("* style")
        ):
            current_section = "Other Improvements"
        elif line.startswith("* @"):
            current_section = "New Contributors"

        if current_section == "New Contributors":
            new_contributors.append(line)
        elif current_section:
            sections[current_section].append(line)

    markdown_text = "## Highlights\n* xxx\n* xxx\n\n"

    if "Features" in sections:
        markdown_text += "## Features\n" + "\n".join(sections["Features"]) + "\n\n"

    if "Bug Fix" in sections:
        markdown_text += "## Bug Fix\n" + "\n".join(sections["Bug Fix"]) + "\n\n"

    if "Other Improvements" in sections:
        markdown_text += (
            "## Other Improvements\n"
            + "\n".join(sections["Other Improvements"])
            + "\n\n"
        )

    if new_contributors:
        markdown_text += "## New Contributors\n" + "\n".join(new_contributors) + "\n\n"

    if full_changelog_link:
        markdown_text += full_changelog_link

    print(markdown_text)


# Example input
input_text = """
## What's Changed
* feat(java): xxx by @xxx in https://github.com/apache/fory/pull/xxx
* fix(doc): xxx by @xxx in https://github.com/apache/fory/pull/xxx
* perf(python): xxx by @xxx in https://github.com/apache/fory/pull/xxx
* chore: xxx by @xxx in https://github.com/apache/fory/pull/xxx
* feat(python): xxx by @xxx in https://github.com/apache/fory/pull/xxx

## New Contributors
* @XXX made their first contribution in https://github.com/apache/fory/pull/XXX

**Full Changelog**: https://github.com/apache/fory/compare/v0.11.0...v0.11.1-rc1
"""

# Generate release notes from given input
generate_release_notes(input_text)
