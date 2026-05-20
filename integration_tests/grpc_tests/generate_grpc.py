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

import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TEST_DIR = Path(__file__).resolve().parent

SCHEMAS = [
    TEST_DIR / "idl" / "grpc_fdl.fdl",
    TEST_DIR / "idl" / "grpc_pb.proto",
    TEST_DIR / "idl" / "grpc_fbs.fbs",
]

OUTPUTS = {
    "java": TEST_DIR / "java/src/main/java/generated",
    "python": TEST_DIR / "python/grpc_tests/generated",
}


def main() -> int:
    env = os.environ.copy()
    compiler_path = str(REPO_ROOT / "compiler")
    env["PYTHONPATH"] = compiler_path + os.pathsep + env.get("PYTHONPATH", "")

    for root in OUTPUTS.values():
        root.mkdir(parents=True, exist_ok=True)
        subprocess.check_call(
            [
                sys.executable,
                "-m",
                "fory_compiler",
                "--scan-generated",
                "--delete",
                "--root",
                str(root),
            ],
            env=env,
        )

    for schema in SCHEMAS:
        subprocess.check_call(
            [
                sys.executable,
                "-m",
                "fory_compiler",
                "compile",
                str(schema),
                f"--java_out={OUTPUTS['java']}",
                f"--python_out={OUTPUTS['python']}",
                "--grpc",
            ],
            env=env,
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
