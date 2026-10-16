---
title: Inspecting Serialized Data
sidebar_position: 15
---

Use the Python inspection command to view one compatible xlang value as YAML
without registering the sender's application classes:

```bash
pip install "pyfory[inspect]"
python -m pyfory.inspect value.bin
```

For example, generate a file using a registered dataclass:

```python
from dataclasses import dataclass
from pathlib import Path

from pyfory import Fory, Int32


@dataclass
class Item:
    value: Int32


writer = Fory(xlang=True, compatible=True)
writer.register_type(Item, name="example.Item")
Path("value.bin").write_bytes(writer.serialize(Item(17)))
```

The output identifies the remote struct, its field type IDs, nullability,
reference flags, and decoded values. Fields encoded with numeric tags retain
their tag IDs; the original field name is not available in that encoding.
Types registered by numeric ID retain that ID instead of inventing an
application class name.

## References and Containers

Pass `--ref` when the writer enables reference tracking:

```bash
python -m pyfory.inspect value.bin --ref
```

Container and struct nodes receive display IDs. A repeated object is represented
as `ref: ID`, including circular references. These IDs describe the decoded
graph, not the original binary reference numbers. Maps use a list of key/value
entries so keys such as the integer `1` and the string `"1"` remain distinct.
Set iteration order is not guaranteed.

## Limits

```bash
python -m pyfory.inspect value.bin --depth 4 --max-nodes 200 --max-bytes 1048576
```

- `--depth`: display depth, from 0 to 32, default 8. At zero only root scalars or
  the root container/struct summary are displayed.
- `--max-nodes`: positive number of displayed values, default 1,000.
- `--max-bytes`: positive input byte limit, default 16 MiB.
- Strings and binary values longer than 256 characters/bytes show a prefix,
  their full length, and a truncation marker.
- YAML output is limited to 4 Mi characters. Exceeding it fails before writing
  any YAML; reduce the depth or node limit.

Depth and node limits truncate the display, not decoding. The command first
decodes the complete root with Fory's default deserialization resource limits
and strict class policy. It does not import or instantiate the sender's
application classes. Decoded objects and the YAML representation still consume
memory; the input byte limit is not a process memory limit.

Success returns exit code 0 and writes YAML to stdout. Invalid command arguments
return 2. Read, decode, dependency, or display errors return 1 with a diagnostic
on stderr.

## Supported Scope

This is a schema-and-value inspection view, not a binary-layout dump or editable
serialization format. It does not preserve byte offsets, wire encoding choices,
or dynamic scalar wire widths. Field metadata reports the immediate field type
ID and flags, not a complete recursive schema. YAML cannot be converted back to
the original bytes by this command.

Input must be a single in-band xlang root using compatible metadata and the
matching reference-tracking setting. Native formats, out-of-band buffers,
trailing bytes, and unregistered same-schema application structs are unsupported.
Unknown compatible structs, built-in scalar values, lists, tuples, sets, maps,
and Fory dense arrays are displayed. Decimal, date, datetime, and
timedelta values use an explicit type with a textual value. Other decoded
carriers fail with an unsupported-type diagnostic. Custom extension serializers
and unregistered application enums/unions are not supported by this command.
