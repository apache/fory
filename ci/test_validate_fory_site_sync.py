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

import pathlib
import tempfile
import unittest
from unittest import mock

if __package__:
    from . import validate_fory_site_sync
else:
    import validate_fory_site_sync


class ForySiteSyncTest(unittest.TestCase):
    def test_rejects_forbidden_doc_roots(self):
        for source, dest in (
            ("docs/security/", "docs/security/"),
            ("docs/development/", "docs/security/"),
        ):
            content = f"""apache/fory-site@main:
  - source: {source}
    dest: {dest}
"""
            with (
                self.subTest(source=source, dest=dest),
                tempfile.TemporaryDirectory() as directory,
            ):
                sync_file = pathlib.Path(directory) / "sync.yml"
                sync_file.write_text(content, encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "must not be synced"):
                    validate_fory_site_sync.parse_sync_mappings(sync_file)

    def test_preserves_site_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            fory_root = root / "fory"
            site_root = root / "fory-site"
            (fory_root / ".github").mkdir(parents=True)
            (fory_root / "docs").mkdir()
            (site_root / "docs").mkdir(parents=True)
            (site_root / "versioned_docs" / "version-9.9.9").mkdir(parents=True)
            (site_root / "versioned_sidebars").mkdir()

            (fory_root / ".github" / "sync.yml").write_text(
                """apache/fory-site@main:
  - source: docs/index.md
    dest: docs/index.md
""",
                encoding="utf-8",
            )
            (fory_root / "docs" / "index.md").write_text("current\n", encoding="utf-8")
            (site_root / "docs" / "index.md").write_text("old\n", encoding="utf-8")
            versioned_doc = site_root / "versioned_docs" / "version-9.9.9" / "index.md"
            versioned_doc.write_text("released\n", encoding="utf-8")
            versioned_sidebar = (
                site_root / "versioned_sidebars" / "version-9.9.9-sidebars.json"
            )
            versioned_sidebar.write_text("{}\n", encoding="utf-8")
            versions_json = site_root / "versions.json"
            versions_json.write_text('["9.9.9"]\n', encoding="utf-8")
            config = site_root / "docusaurus.config.ts"
            config.write_text("lastVersion: '9.9.9'\n", encoding="utf-8")

            with (
                mock.patch.object(
                    validate_fory_site_sync,
                    "parse_args",
                    return_value=mock.Mock(
                        fory_root=fory_root, fory_site_root=site_root
                    ),
                ),
                mock.patch.object(validate_fory_site_sync, "run_site_commands") as run,
            ):
                self.assertEqual(validate_fory_site_sync.main(), 0)

            run.assert_called_once_with(site_root)
            self.assertEqual((site_root / "docs" / "index.md").read_text(), "current\n")
            self.assertEqual(versioned_doc.read_text(), "released\n")
            self.assertEqual(versioned_sidebar.read_text(), "{}\n")
            self.assertEqual(versions_json.read_text(), '["9.9.9"]\n')
            self.assertEqual(config.read_text(), "lastVersion: '9.9.9'\n")

    def test_uses_single_locale_build(self):
        site_root = pathlib.Path("fory-site")
        with mock.patch.object(validate_fory_site_sync.subprocess, "run") as run:
            validate_fory_site_sync.run_site_commands(site_root)

        self.assertEqual(
            run.call_args_list,
            [
                mock.call(
                    ("npm", "run", "lint", "--if-present"),
                    cwd=site_root,
                    check=True,
                ),
                mock.call(
                    ("npm", "run", "build", "--", "--locale", "en-US"),
                    cwd=site_root,
                    check=True,
                ),
            ],
        )


if __name__ == "__main__":
    unittest.main()
