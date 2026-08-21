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

import pytest

import pyfory
from pyfory.collection import (
    COLL_IS_DECL_ELEMENT_TYPE,
    COLL_IS_SAME_TYPE,
    KEY_DECL_TYPE,
    KEY_HAS_NULL,
    VALUE_DECL_TYPE,
    VALUE_HAS_NULL,
    ListSerializer,
    MapSerializer,
    SetSerializer,
    TupleSerializer,
)


pytestmark = pytest.mark.skipif(
    not pyfory.ENABLE_FORY_CYTHON_SERIALIZATION,
    reason="requires the Cython collection readers",
)


def _read_buffer(fory, serializer, write):
    buffer = pyfory.Buffer.allocate(32)
    write(buffer)
    buffer.set_reader_index(0)
    fory.read_context.prepare(buffer)
    try:
        return serializer.read(fory.read_context)
    finally:
        fory.reset_read()


@pytest.mark.parametrize("serializer_type", [ListSerializer, TupleSerializer, SetSerializer])
def test_missing_declared_element(serializer_type):
    fory = pyfory.Fory(xlang=True, ref=False, compatible=False, strict=False)
    serializer = serializer_type(fory.type_resolver, serializer_type)

    def write(buffer):
        buffer.write_var_uint32(1)
        buffer.write_int8(COLL_IS_SAME_TYPE | COLL_IS_DECL_ELEMENT_TYPE)

    with pytest.raises(ValueError, match="Missing serializer for declared collection"):
        _read_buffer(fory, serializer, write)


@pytest.mark.parametrize(
    "chunk_header",
    [
        KEY_DECL_TYPE | VALUE_DECL_TYPE,
        KEY_HAS_NULL | VALUE_DECL_TYPE,
        VALUE_HAS_NULL | KEY_DECL_TYPE,
    ],
)
def test_missing_declared_map(chunk_header):
    fory = pyfory.Fory(xlang=True, ref=False, compatible=False, strict=False)
    serializer = MapSerializer(fory.type_resolver, dict)

    def write(buffer):
        buffer.write_var_uint32(1)
        buffer.write_uint8(chunk_header)
        if (chunk_header & (KEY_HAS_NULL | VALUE_HAS_NULL)) == 0:
            buffer.write_uint8(1)

    with pytest.raises(ValueError, match="Missing serializer for declared map"):
        _read_buffer(fory, serializer, write)


class ListCapture:
    def __init__(self, items):
        self.items = items

    def __reduce__(self):
        return list, (self.items,)


def copy_then_fail(items):
    list(items)
    raise ValueError("expected failure")


class FailingListCapture:
    def __init__(self, items):
        self.items = items

    def __reduce__(self):
        return copy_then_fail, (self.items,)


def clear_parent_list(parent):
    parent.clear()
    return "cleared"


class ClearingListCapture:
    def __init__(self, parent):
        self.parent = parent

    def __reduce__(self):
        return clear_parent_list, (self.parent,)


def test_published_list_has_valid_slots():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False, compatible=False)
    outer = []
    outer.append(ListCapture(outer))

    restored = fory.deserialize(fory.serialize(outer))

    assert restored == [[]]


def test_published_list_failure_cleanup():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False, compatible=False)
    outer = []
    outer.append(FailingListCapture(outer))
    data = fory.serialize(outer)

    with pytest.raises(ValueError, match="expected failure"):
        fory.deserialize(data)
    assert fory.deserialize(fory.serialize([1, 2])) == [1, 2]


def test_reentrant_list_clear():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False, compatible=False)
    outer = []
    outer.append(ClearingListCapture(outer))
    data = fory.serialize(outer)

    with pytest.raises(ValueError, match="Published list was modified"):
        fory.deserialize(data)
    assert fory.deserialize(fory.serialize([1, 2])) == [1, 2]
