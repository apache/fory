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


def write_sample_fbs(path: Path) -> None:
    path.write_text(
        """
namespace demo.foo;

table User {
  name: string;
}

root_type User;
"""
    )


def test_cli_swift_namespace_style_works_for_flatbuffers(tmp_path: Path):
    fbs_path = tmp_path / "style.fbs"
    write_sample_fbs(fbs_path)

    swift_out = tmp_path / "swift_out"
    ok = compile_file(
        fbs_path,
        {"swift": swift_out},
        swift_namespace_style="flatten",
    )
    assert ok is True

    generated_files = list(swift_out.rglob("*.swift"))
    assert len(generated_files) == 1
    content = generated_files[0].read_text()

    assert "public enum Demo {" not in content
    assert "public struct Demo_Foo_User" in content
    assert "public enum Demo_Foo_ForyRegistration" in content


def test_cli_go_nested_type_style_is_accepted_for_flatbuffers(tmp_path: Path):
    fbs_path = tmp_path / "style.fbs"
    write_sample_fbs(fbs_path)

    go_out = tmp_path / "go_out"
    ok = compile_file(
        fbs_path,
        {"go": go_out},
        go_nested_type_style="camelcase",
    )
    assert ok is True

    go_file = go_out / "demo_foo.go"
    assert go_file.exists()
    content = go_file.read_text()
    assert "package foo" in content
    assert "type User struct" in content
