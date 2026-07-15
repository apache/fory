#!/usr/bin/env python3
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

import tempfile
import unittest
from pathlib import Path

import verify_release


class VerifyMappingTest(unittest.TestCase):
    def test_rejects_renamed_forbidden_classes(self) -> None:
        forbidden = (
            verify_release.FORBIDDEN_MAPPING_CLASSES
            + tuple(
                class_name + "$Nested"
                for class_name in verify_release.FORBIDDEN_MAPPING_CLASSES
            )
            + tuple(
                prefix + "Compiler"
                for prefix in verify_release.FORBIDDEN_MAPPING_PREFIXES
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            mapping = Path(directory) / "mapping.txt"
            for class_name in forbidden:
                with self.subTest(class_name=class_name):
                    mapping.write_text(f"{class_name} -> a:\n", encoding="utf-8")
                    with self.assertRaisesRegex(
                        AssertionError, "Forbidden Android class survived R8"
                    ):
                        verify_release.verify_mapping(mapping, ())


if __name__ == "__main__":
    unittest.main()
