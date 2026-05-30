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

"""dart analyze smoke test for emitted Dart gRPC files.

First run on a clean machine performs `dart pub get` which downloads grpc's
transitive deps (http2, protobuf, crypto) -- about 30 seconds of network I/O.
Subsequent runs hit the local pub cache (~/.pub-cache) and complete in
under 5 seconds.

Skipped when:
  - `dart` is not on PATH, or
  - the env var FORY_SKIP_DART_ANALYZE is set.

The test does not write an `analysis_options.yaml` into tmp_path, so Dart
uses its built-in default lint set. The generated file's
`// ignore_for_file:` directive covers the lints the emitter knowingly
triggers (`non_constant_identifier_names` for `_$sayHello` / `sayHello_Pre`).
"""

import os
import shutil
import subprocess
import textwrap
from pathlib import Path

import pytest

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.dart import DartGenerator

REPO_ROOT = Path(__file__).resolve().parents[3]
assert (REPO_ROOT / "dart" / "packages" / "fory" / "pubspec.yaml").exists(), (
    f"Repo root resolution wrong: {REPO_ROOT}"
)

_GREETER_FDL = textwrap.dedent(
    """
    package demo.greeter;

    message HelloRequest {
        string name = 1;
    }

    message HelloReply {
        string reply = 1;
    }

    service Greeter {
        rpc SayHello (HelloRequest) returns (HelloReply);
    }
    """
)


@pytest.mark.skipif(
    shutil.which("dart") is None or bool(os.environ.get("FORY_SKIP_DART_ANALYZE")),
    reason="dart not on PATH or FORY_SKIP_DART_ANALYZE set",
)
def test_dart_analyze_accepts_generated_grpc_file(tmp_path: Path) -> None:
    schema = Parser(Lexer(_GREETER_FDL).tokenize()).parse()
    options_grpc = GeneratorOptions(output_dir=tmp_path, grpc=True)
    generator = DartGenerator(schema, options_grpc)

    for file in generator.generate():
        out = tmp_path / file.path
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(file.content)
    for file in generator.generate_services():
        out = tmp_path / file.path
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(file.content)

    fory_path = REPO_ROOT / "dart" / "packages" / "fory"
    pubspec = textwrap.dedent(
        f"""
        name: fory_grpc_smoke
        environment:
          sdk: ^3.7.0
        dependencies:
          grpc: ^4.0.0
          fory:
            path: {fory_path}
        """
    ).strip() + "\n"
    (tmp_path / "pubspec.yaml").write_text(pubspec)

    subprocess.run(
        ["dart", "pub", "get"],
        cwd=tmp_path,
        check=True,
    )

    # Analyze only the emitted grpc file. The companion messages file
    # references a build_runner-generated `.fory.dart` part that this
    # compiler does not produce (it is emitted by `package:fory`'s
    # source_gen at user-build time), so analyzing the whole tree would
    # surface unrelated errors. The grpc file's own correctness is what
    # this smoke test exists to gate.
    grpc_file = tmp_path / "demo" / "greeter" / "demo_greeter_grpc.dart"
    assert grpc_file.exists(), f"grpc file not emitted: {grpc_file}"
    result = subprocess.run(
        ["dart", "analyze", "--fatal-warnings", str(grpc_file)],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, (
        f"dart analyze failed:\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
