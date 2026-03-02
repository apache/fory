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

import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
IDL_DIR = Path(__file__).resolve().parent
SCHEMAS = [
    IDL_DIR / "idl" / "addressbook.fdl",
    IDL_DIR / "idl" / "collection.fdl",
    IDL_DIR / "idl" / "optional_types.fdl",
    IDL_DIR / "idl" / "tree.fdl",
    IDL_DIR / "idl" / "graph.fdl",
    IDL_DIR / "idl" / "root.idl",
    IDL_DIR / "idl" / "evolving1.idl",
    IDL_DIR / "idl" / "evolving2.idl",
    IDL_DIR / "idl" / "any_example.fdl",
    IDL_DIR / "idl" / "any_example.proto",
    IDL_DIR / "idl" / "monster.fbs",
    IDL_DIR / "idl" / "complex_fbs.fbs",
    IDL_DIR / "idl" / "auto_id.fdl",
]

LANG_OUTPUTS = {
    "java": REPO_ROOT / "integration_tests/idl_tests/java/src/main/java/generated",
    "python": REPO_ROOT / "integration_tests/idl_tests/python/idl_tests/generated",
    "cpp": REPO_ROOT / "integration_tests/idl_tests/cpp/generated",
    "go": REPO_ROOT / "integration_tests/idl_tests/go/generated",
    "rust": REPO_ROOT / "integration_tests/idl_tests/rust/src/generated",
    "csharp": REPO_ROOT / "integration_tests/idl_tests/csharp/IdlTests/Generated",
    "javascript": REPO_ROOT / "integration_tests/idl_tests/javascript/generated",
    "swift": REPO_ROOT
    / "integration_tests/idl_tests/swift/idl_package/Sources/IdlGenerated/generated",
}

GO_OUTPUT_OVERRIDES = {
    "addressbook.fdl": IDL_DIR / "go" / "addressbook" / "generated",
    "collection.fdl": IDL_DIR / "go" / "collection" / "generated",
    "monster.fbs": IDL_DIR / "go" / "monster" / "generated",
    "complex_fbs.fbs": IDL_DIR / "go" / "complex_fbs" / "generated",
    "optional_types.fdl": IDL_DIR / "go" / "optional_types" / "generated",
    "tree.fdl": IDL_DIR / "go" / "tree" / "generated",
    "graph.fdl": IDL_DIR / "go" / "graph" / "generated",
    "root.idl": IDL_DIR / "go" / "root" / "generated",
    "evolving1.idl": IDL_DIR / "go" / "evolving1" / "generated",
    "evolving2.idl": IDL_DIR / "go" / "evolving2" / "generated",
    "any_example.fdl": IDL_DIR / "go" / "any_example" / "generated",
    "any_example.proto": IDL_DIR / "go" / "any_example_pb" / "generated",
    "complex_pb.proto": IDL_DIR / "go" / "complex_pb" / "generated",
    "auto_id.fdl": IDL_DIR / "go" / "auto_id" / "generated",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate IDL test code")
    parser.add_argument(
        "--lang",
        default="all",
        help="Comma-separated list of languages to generate (default: all)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    langs_arg = args.lang.strip()
    if langs_arg == "all":
        langs = sorted(LANG_OUTPUTS.keys())
    else:
        langs = [lang.strip() for lang in langs_arg.split(",") if lang.strip()]

    unknown = [lang for lang in langs if lang not in LANG_OUTPUTS]
    if unknown:
        print(f"Unknown languages: {', '.join(unknown)}", file=sys.stderr)
        return 2

    env = os.environ.copy()
    compiler_path = str(REPO_ROOT / "compiler")
    env["PYTHONPATH"] = compiler_path + os.pathsep + env.get("PYTHONPATH", "")

    generated_roots = set()
    for lang in langs:
        out_dir = LANG_OUTPUTS[lang]
        if lang == "go":
            for schema in SCHEMAS:
                generated_roots.add(GO_OUTPUT_OVERRIDES.get(schema.name, out_dir))
        else:
            generated_roots.add(out_dir)

    for root in sorted(generated_roots):
        Path(root).mkdir(parents=True, exist_ok=True)
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
        cmd = [
            sys.executable,
            "-m",
            "fory_compiler",
            "compile",
            str(schema),
        ]

        for lang in langs:
            out_dir = LANG_OUTPUTS[lang]
            if lang == "go":
                out_dir = GO_OUTPUT_OVERRIDES.get(schema.name, out_dir)
            out_dir.mkdir(parents=True, exist_ok=True)
            cmd.append(f"--{lang}_out={out_dir}")

        subprocess.check_call(cmd, env=env)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
