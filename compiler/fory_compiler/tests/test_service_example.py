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

from pathlib import Path

from fory_compiler.cli import compile_file


def test_service_example_compiles_for_java_and_python(tmp_path: Path):
    example_path = Path(__file__).resolve().parents[2] / "examples" / "service.fdl"

    java_out = tmp_path / "java"
    python_out = tmp_path / "python"
    ok = compile_file(
        example_path,
        {
            "java": java_out,
            "python": python_out,
        },
    )

    assert ok is True
    assert (java_out / "demo" / "greeter" / "HelloRequest.java").exists()
    assert (java_out / "demo" / "greeter" / "HelloReply.java").exists()
    assert (python_out / "demo_greeter.py").exists()
