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

"""Command-line schema and value inspection for compatible xlang data."""

import argparse
import array
import datetime
import decimal
import io
import sys

from pyfory import Buffer, Fory, UnknownStruct
from pyfory._fory import NO_USER_TYPE_ID
from pyfory.serialization import (
    BFloat16Array,
    BoolArray,
    Float16Array,
    Float32Array,
    Float64Array,
    Int8Array,
    Int16Array,
    Int32Array,
    Int64Array,
    UInt8Array,
    UInt16Array,
    UInt32Array,
    UInt64Array,
)

_ARRAYS = (
    BoolArray,
    Int8Array,
    Int16Array,
    Int32Array,
    Int64Array,
    UInt8Array,
    UInt16Array,
    UInt32Array,
    UInt64Array,
    Float16Array,
    BFloat16Array,
    Float32Array,
    Float64Array,
)


class _Display:
    def __init__(self, depth, nodes):
        self.depth = depth
        self.remaining = nodes
        self.seen = {}

    def value(self, value, depth=0):
        if self.remaining == 0:
            return {"truncated": "node limit"}
        self.remaining -= 1
        kind = type(value)
        if value is None or kind in (bool, int, float):
            return value
        if kind in (str, bytes):
            if len(value) > 256:
                return {"type": kind.__name__, "length": len(value), "prefix": value[:256], "truncated": "scalar limit"}
            return value
        if kind in (datetime.date, datetime.datetime, datetime.timedelta, decimal.Decimal):
            return {"type": kind.__name__, "value": str(value)}
        if kind not in (UnknownStruct, list, tuple, dict, set, array.array) + _ARRAYS:
            raise ValueError(f"Unsupported display type: {kind.__name__}")
        identity = id(value)
        if identity in self.seen:
            return {"ref": self.seen[identity]}
        node = {"id": len(self.seen), "type": kind.__name__}
        self.seen[identity] = node["id"]
        node["size"] = len(value)
        if kind is UnknownStruct:
            typedef = value.type_def
            node["schema"] = {"type_id": typedef.type_id}
            if typedef.user_type_id != NO_USER_TYPE_ID:
                node["schema"]["user_type_id"] = typedef.user_type_id
            else:
                node["schema"].update(namespace=typedef.namespace, name=typedef.typename)
        elif kind is array.array:
            node["typecode"] = value.typecode
        if depth >= self.depth:
            node["truncated"] = "depth limit"
            return node
        children = []
        if kind is UnknownStruct:
            source = value.type_def.fields
        elif kind is dict:
            source = value.items()
        else:
            source = value
        for child in source:
            if self.remaining == 0:
                node["truncated"] = "node limit"
                break
            if kind is UnknownStruct:
                entry = {"name": child.name, "type_id": child.field_type.type_id}
                if child.tag_id >= 0:
                    entry["tag_id"] = child.tag_id
                entry["nullable"] = child.field_type.is_nullable
                entry["tracking_ref"] = child.field_type.is_tracking_ref
                entry["value"] = self.value(value[child.name], depth + 1)
                children.append(entry)
            elif kind is dict:
                key, item = child
                children.append({"key": self.value(key, depth + 1), "value": self.value(item, depth + 1)})
            else:
                children.append(self.value(child, depth + 1))
        node["fields" if kind is UnknownStruct else "entries" if kind is dict else "items"] = children
        return node


class _YamlOutput(io.StringIO):
    def write(self, text):
        if self.tell() + len(text) > 4 * 1024 * 1024:
            raise ValueError("YAML output exceeds 4 Mi characters; reduce --max-nodes or --depth")
        return super().write(text)


def _positive(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return number


def main(argv=None):
    """Inspect one in-band compatible xlang root; return a process exit code.

    Outputs YAML to stdout and diagnostics to stderr. Display IDs describe the
    decoded object graph, not wire reference IDs. No application types are loaded.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", help="binary file containing one compatible xlang root")
    parser.add_argument("--ref", action="store_true", help="match a writer configured with reference tracking")
    parser.add_argument("--depth", type=int, choices=range(33), default=8, metavar="0..32", help="display depth (default: 8)")
    parser.add_argument("--max-nodes", type=_positive, default=1000, help="displayed values (default: 1000)")
    parser.add_argument("--max-bytes", type=_positive, default=16 * 1024 * 1024, help="input size limit (default: 16 MiB)")
    args = parser.parse_args(argv)
    try:
        import yaml
    except ImportError:
        print('Install the inspection extra: pip install "pyfory[inspect]"', file=sys.stderr)
        return 1
    try:
        with open(args.file, "rb") as stream:
            data = stream.read(args.max_bytes + 1)
        if len(data) > args.max_bytes:
            raise ValueError("Input exceeds --max-bytes")
        if not data:
            raise ValueError("Empty input")
        if not data[0] & 1:
            raise ValueError("Only xlang data is supported")
        if data[0] & 2:
            raise ValueError("Out-of-band data is not supported")
        buffer = Buffer(data)
        value = Fory(xlang=True, compatible=True, strict=True, ref=args.ref).deserialize(buffer)
        if buffer.get_reader_index() != len(data):
            raise ValueError("Trailing data after the root value")
        document = {"format": "fory-xlang-value-inspection", "root": _Display(args.depth, args.max_nodes).value(value)}
        output = _YamlOutput()
        yaml.safe_dump(document, stream=output, sort_keys=False, allow_unicode=False)
        sys.stdout.write(output.getvalue())
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI boundary for runtime-specific decoder failures.
        print(f"Cannot inspect data: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
