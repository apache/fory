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

import array
import builtins
import datetime
import decimal
import subprocess
import sys
from dataclasses import dataclass
from typing import List

import pytest
import yaml

from pyfory import Fory, Int32, field
from pyfory.inspect import main


@dataclass
class Item:
    value: Int32


@dataclass
class Tagged:
    value: Int32 = field(id=7, default=0)  # noqa: RUF009 - Fory dataclass field metadata.


@dataclass
class Bundle:
    items: List[Item]


def inspect_bytes(tmp_path, capsys, data, *options):
    path = tmp_path / "value.bin"
    path.write_bytes(data)
    status = main([str(path), *options])
    output = capsys.readouterr()
    assert status == 0, output.err
    assert output.err == ""
    return yaml.safe_load(output.out)["root"]


def test_unknown_struct(tmp_path, capsys):
    writer = Fory(ref=True)
    writer.register_type(Item, name="inspect.Item")
    writer.register_type(Bundle, name="inspect.Bundle")
    shared = Item(17)
    root = inspect_bytes(tmp_path, capsys, writer.serialize(Bundle([shared, shared])), "--ref")
    assert root["schema"]["name"] == "Bundle"
    assert root["schema"]["namespace"] == "inspect"
    items = root["fields"][0]["value"]["items"]
    assert items[0]["fields"][0]["value"] == 17
    assert items[1] == {"ref": items[0]["id"]}


def test_numeric_type_and_tag(tmp_path, capsys):
    writer = Fory()
    writer.register_type(Tagged, type_id=123)
    root = inspect_bytes(tmp_path, capsys, writer.serialize(Tagged(42)))
    assert root["schema"]["user_type_id"] == 123
    assert "name" not in root["schema"]
    assert root["fields"][0]["tag_id"] == 7
    assert root["fields"][0]["value"] == 42


def test_cycles_and_maps(tmp_path, capsys):
    value = {1: [], "1": "distinct key"}
    value[1].append(value)
    root = inspect_bytes(tmp_path, capsys, Fory(ref=True).serialize(value), "--ref")
    entries = root["entries"]
    assert entries[0]["key"] == 1
    assert entries[1]["key"] == "1"
    assert entries[0]["value"]["items"] == [{"ref": root["id"]}]


@pytest.mark.parametrize("options,reason", [(["--depth", "0"], "depth limit"), (["--max-nodes", "1"], "node limit")])
def test_display_limits(tmp_path, capsys, options, reason):
    root = inspect_bytes(tmp_path, capsys, Fory().serialize([1, 2, 3]), *options)
    assert root["truncated"] == reason


@pytest.mark.parametrize("value", ["x" * 300, b"x" * 300])
def test_scalar_limit(tmp_path, capsys, value):
    root = inspect_bytes(tmp_path, capsys, Fory().serialize(value))
    assert root["prefix"] == value[:256]
    assert root["length"] == 300
    assert root["truncated"] == "scalar limit"


@pytest.mark.parametrize("value", [None, True, 17, 1.25, "text", b"bytes", datetime.date(2026, 1, 2), decimal.Decimal("1.23")])
def test_scalars(tmp_path, capsys, value):
    root = inspect_bytes(tmp_path, capsys, Fory().serialize(value))
    if isinstance(value, (datetime.date, decimal.Decimal)):
        assert root["value"] == str(value)
    else:
        assert root == value


def test_array(tmp_path, capsys):
    root = inspect_bytes(tmp_path, capsys, Fory().serialize(array.array("i", [3, 4])))
    assert root["items"] == [3, 4]
    assert root["type"] == "Int32Array"


@pytest.mark.parametrize("case", ["empty", "native", "oob", "truncated", "trailing", "oversize"])
def test_invalid_input(tmp_path, capsys, case):
    data = Fory().serialize("test")
    options = []
    if case == "empty":
        data = b""
    elif case == "native":
        data = Fory(xlang=False).serialize(1)
    elif case == "oob":
        data = bytes([data[0] | 2]) + data[1:]
    elif case == "truncated":
        data = data[:-1]
    elif case == "trailing":
        data += b"trailing"
    else:
        options = ["--max-bytes", "1"]
    path = tmp_path / "invalid.bin"
    path.write_bytes(data)
    assert main([str(path), *options]) == 1
    output = capsys.readouterr()
    assert output.out == ""
    assert "Cannot inspect data:" in output.err


def test_missing_yaml(monkeypatch, capsys):
    original = builtins.__import__

    def without_yaml(name, *args, **kwargs):
        if name == "yaml":
            raise ImportError("not installed")
        return original(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", without_yaml)
    assert main(["unused.bin"]) == 1
    assert "pyfory[inspect]" in capsys.readouterr().err


def test_module_entrypoint(tmp_path):
    path = tmp_path / "value.bin"
    path.write_bytes(Fory().serialize(17))
    result = subprocess.run([sys.executable, "-m", "pyfory.inspect", str(path)], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    assert yaml.safe_load(result.stdout)["root"] == 17
