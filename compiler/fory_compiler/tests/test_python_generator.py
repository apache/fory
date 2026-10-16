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

"""Tests for Python code generation."""

import dataclasses
import sys
import types
from pathlib import Path
from textwrap import dedent

import pytest

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.python import PythonGenerator
from fory_compiler.ir.ast import Schema


def parse_fdl(source: str) -> Schema:
    return Parser(Lexer(source).tokenize()).parse()


def generate_python(source: str) -> str:
    schema = parse_fdl(source)
    generator = PythonGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    files = generator.generate()
    assert len(files) == 1
    return files[0].content


def exec_generated_module(content: str) -> dict:
    """Execute generated Python against a minimal pyfory stub.

    The stub keeps dataclass semantics real so class-body evaluation errors
    (such as a field shadowing a helper name) surface exactly as they would
    with the real pyfory package.
    """

    def stub_field(id=None, *, nullable=False, ref=False, **kwargs):
        return dataclasses.field(metadata={"id": id}, **kwargs)

    def stub_dataclass(cls=None, **kwargs):
        if cls is None:
            return dataclasses.dataclass
        return dataclasses.dataclass(cls)

    stub = types.ModuleType("pyfory")
    stub.field = stub_field
    stub.dataclass = stub_dataclass
    stub.Fory = object
    stub.ThreadSafeFory = object
    previous = sys.modules.get("pyfory")
    sys.modules["pyfory"] = stub
    module = types.ModuleType("generated_module")
    sys.modules["generated_module"] = module
    try:
        exec(compile(content, "generated_module.py", "exec"), module.__dict__)
        return module.__dict__
    finally:
        del sys.modules["generated_module"]
        if previous is None:
            del sys.modules["pyfory"]
        else:
            sys.modules["pyfory"] = previous


def test_field_names_shadowing_default_helpers_are_escaped():
    """Fields named after helpers used by later defaults must not break the class body."""
    source = dedent(
        """
        package example;

        message Tricky [id=100] {
            string pyfory = 1;
            string field = 2;
            list<string> tags = 3;
            map<string, string> attrs = 4;
        }
        """
    )
    output = generate_python(source)

    # The escaped attribute names keep the wire names: pyfory strips the
    # trailing underscore when computing the snake_case field name.
    assert "pyfory_: str = pyfory.field(id=1" in output
    assert "field_: str = pyfory.field(id=2" in output

    namespace = exec_generated_module(output)
    instance = namespace["Tricky"]()
    assert instance.pyfory_ == ""
    assert instance.field_ == ""
    assert instance.tags == []
    assert instance.attrs == {}


@pytest.mark.parametrize("name", ["field", "pyfory", "decimal", "List", "dict"])
@pytest.mark.parametrize("reverse", [False, True])
@pytest.mark.parametrize("field_type", ["string", "any"])
def test_helper_name_suffixes(name, reverse, field_type):
    fields = [f"{field_type} {name}{'_' * i} = {i + 1};" for i in range(3)]
    if reverse:
        fields.reverse()
    output = generate_python("message Tricky [id=100] {" + "\n".join(fields) + "}")
    namespace = exec_generated_module(output)
    cls = namespace["Tricky"]
    names = [name.lower() + "_" * (i + 1) for i in range(3)]

    assert {f.name: f.metadata["id"] for f in dataclasses.fields(cls)} == {
        field_name: i + 1 for i, field_name in enumerate(names)
    }
    values = {field_name: f"value-{i}" for i, field_name in enumerate(names)}
    instance = cls(**values)
    assert dataclasses.asdict(instance) == values
    for field_name, value in values.items():
        rendered = repr(value) if field_type == "string" else "any(...)"
        assert f"{field_name}={rendered}" in repr(instance)


@pytest.mark.parametrize("name", ["class", "Class", "from", "async", "lambda"])
@pytest.mark.parametrize("bare", [False, True])
@pytest.mark.parametrize("field_type", ["string", "any"])
def test_keyword_field_names(name, bare, field_type):
    first = name if bare else name + "_"
    output = generate_python(
        f"message Tricky [id=100] {{ {field_type} {first} = 1; "
        f"{field_type} {name}__ = 2; }}"
    )
    cls = exec_generated_module(output)["Tricky"]
    names = [name.lower() + "_", name.lower() + "__"]
    assert {f.name: f.metadata["id"] for f in dataclasses.fields(cls)} == {
        names[0]: 1,
        names[1]: 2,
    }
    values = {names[0]: "first", names[1]: "second"}
    instance = cls(**values)
    assert dataclasses.asdict(instance) == values
    for field_name, value in values.items():
        rendered = repr(value) if field_type == "string" else "any(...)"
        assert f"{field_name}={rendered}" in repr(instance)


@pytest.mark.parametrize(
    "names, generated_name",
    [
        (("class", "class_"), "class_"),
        (("Class", "class_"), "class_"),
        (("from", "from_"), "from_"),
        (("async", "async_"), "async_"),
        (("lambda", "lambda_"), "lambda_"),
        (("userName", "user_name"), "user_name"),
    ],
)
@pytest.mark.parametrize("reverse", [False, True])
@pytest.mark.parametrize("nested", [False, True])
def test_field_name_collision(names, generated_name, reverse, nested):
    fields = [f"string {name} = {i + 1};" for i, name in enumerate(names)]
    if reverse:
        fields.reverse()
    source = "message Tricky [id=100] {" + "\n".join(fields) + "}"
    if nested:
        source = "message Outer [id=101] {" + source + "}"
    with pytest.raises(ValueError) as exc:
        generate_python(source)
    message = str(exc.value)
    assert "Python field name collision" in message
    assert ("Outer.Tricky" if nested else "Tricky") in message
    assert all(repr(name) in message for name in names)
    assert f"both generate {generated_name!r}" in message
    assert "rename" in message
