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

from __future__ import annotations

from pyfory.serialization import Config
from pyfory.lib import mmh3
from pyfory.meta.metastring import Encoding
from pyfory.resolver import (
    MapRefReader,
    MapRefWriter,
    NoRefReader,
    NoRefWriter,
    NOT_NULL_VALUE_FLAG,
    NULL_FLAG,
)
from pyfory.types import TypeId

SMALL_STRING_THRESHOLD = 16
INT64_TYPE_ID = TypeId.VARINT64
FLOAT64_TYPE_ID = TypeId.FLOAT64
BOOL_TYPE_ID = TypeId.BOOL
STRING_TYPE_ID = TypeId.STRING


def _mix64(x: int) -> int:
    x &= 0xFFFFFFFFFFFFFFFF
    x ^= x >> 33
    x = (x * 0xFF51AFD7ED558CCD) & 0xFFFFFFFFFFFFFFFF
    x ^= x >> 33
    x = (x * 0xC4CEB9FE1A85EC53) & 0xFFFFFFFFFFFFFFFF
    x ^= x >> 33
    return x


def hash_small_metastring(v1: int, v2: int, length: int, encoding: int) -> int:
    k = 0x9E3779B97F4A7C15
    x = (v1 & 0xFFFFFFFFFFFFFFFF) ^ ((v2 & 0xFFFFFFFFFFFFFFFF) * k & 0xFFFFFFFFFFFFFFFF)
    x ^= (length & 0xFFFFFFFFFFFFFFFF) << 56
    h = _mix64(x)
    return (h & 0xFFFFFFFFFFFFFF00) | (encoding & 0xFF)


def hash_meta_string_data(data: bytes, encoding: int) -> int:
    length = len(data)
    if length <= SMALL_STRING_THRESHOLD:
        padded = data + b"\x00" * (16 - length)
        v1 = int.from_bytes(padded[:8], "little", signed=False)
        v2 = int.from_bytes(padded[8:16], "little", signed=False)
        return hash_small_metastring(v1, v2, length, encoding)
    hashcode = mmh3.hash_buffer(data, seed=47)[0]
    return (hashcode >> 8 << 8) | (encoding & 0xFF)


class EncodedMetaString:
    __slots__ = ("data", "length", "encoding", "hashcode")

    def __init__(self, data: bytes, hashcode: int):
        self.data = data
        self.length = len(data)
        if hashcode >= (1 << 63):
            hashcode -= 1 << 64
        elif hashcode < -(1 << 63):
            hashcode += 1 << 64
        self.hashcode = hashcode
        self.encoding = hashcode & 0xFF

    def decode(self, decoder):
        return decoder.decode(self.data, Encoding(self.encoding))

    def __hash__(self):
        return hash((self.hashcode, self.data))

    def __eq__(self, other):
        return type(other) is EncodedMetaString and self.hashcode == other.hashcode and self.data == other.data

    def __repr__(self):
        return f"EncodedMetaString(data={self.data}, hashcode={self.hashcode})"


EMPTY_ENCODED_META_STRING = EncodedMetaString(b"", 0)


class MetaStringWriter:
    __slots__ = ("_written_meta_strings",)

    def __init__(self):
        self._written_meta_strings = {}

    def write_encoded_meta_string(self, buffer, encoded_meta_string: EncodedMetaString):
        entry = self._written_meta_strings.get(id(encoded_meta_string))
        length = encoded_meta_string.length
        if entry is None:
            dynamic_id = len(self._written_meta_strings)
            self._written_meta_strings[id(encoded_meta_string)] = (dynamic_id, encoded_meta_string)
            buffer.write_var_uint32(length << 1)
            if length <= SMALL_STRING_THRESHOLD:
                if length != 0:
                    buffer.write_int8(encoded_meta_string.encoding)
            else:
                buffer.write_int64(encoded_meta_string.hashcode)
            buffer.write_bytes(encoded_meta_string.data)
            return
        buffer.write_var_uint32(((entry[0] + 1) << 1) | 1)

    def reset(self):
        self._written_meta_strings.clear()


class MetaStringReader:
    __slots__ = (
        "shared_registry",
        "_dynamic_id_to_encoded_meta_strings",
        "_hash_to_encoded_meta_strings",
        "_small_encoded_meta_strings",
    )

    def __init__(self, shared_registry):
        self.shared_registry = shared_registry
        self._dynamic_id_to_encoded_meta_strings = []
        self._hash_to_encoded_meta_strings = {}
        self._small_encoded_meta_strings = {}

    def read_encoded_meta_string(self, buffer):
        header = buffer.read_var_uint32()
        length = header >> 1
        if (header & 0b1) != 0:
            if length <= 0:
                raise ValueError("Invalid dynamic metastring id 0")
            if length > len(self._dynamic_id_to_encoded_meta_strings):
                raise ValueError(f"Invalid dynamic metastring id {length}")
            return self._dynamic_id_to_encoded_meta_strings[length - 1]
        if length <= SMALL_STRING_THRESHOLD:
            encoded_meta_string = self._read_small_meta_string(buffer, length)
        else:
            encoded_meta_string = self._read_big_meta_string(buffer, length)
        self._dynamic_id_to_encoded_meta_strings.append(encoded_meta_string)
        return encoded_meta_string

    def _read_small_meta_string(self, buffer, length: int):
        if length == 0:
            return EMPTY_ENCODED_META_STRING
        encoding = buffer.read_int8()
        if length <= 8:
            v1 = buffer.read_bytes_as_int64(length)
            v2 = 0
        else:
            v1 = buffer.read_int64()
            v2 = buffer.read_bytes_as_int64(length - 8)
        key = (v1, v2, encoding, length)
        encoded_meta_string = self._small_encoded_meta_strings.get(key)
        if encoded_meta_string is not None:
            return encoded_meta_string
        hashcode = hash_small_metastring(v1, v2, length, encoding)
        reader_index = buffer.get_reader_index()
        data = buffer.get_bytes(reader_index - length, length)
        encoded_meta_string = self.shared_registry.get_or_create_encoded_meta_string(data, hashcode)
        self._small_encoded_meta_strings[key] = encoded_meta_string
        return encoded_meta_string

    def _read_big_meta_string(self, buffer, length: int):
        hashcode = buffer.read_int64()
        reader_index = buffer.get_reader_index()
        buffer.check_bound(reader_index, length)
        data = buffer.get_bytes(reader_index, length)
        buffer.set_reader_index(reader_index + length)
        key = (hashcode, data)
        encoded_meta_string = self._hash_to_encoded_meta_strings.get(key)
        if encoded_meta_string is None:
            encoded_meta_string = self.shared_registry.get_or_create_encoded_meta_string(data, hashcode)
            self._hash_to_encoded_meta_strings[key] = encoded_meta_string
        return encoded_meta_string

    def reset(self):
        self._dynamic_id_to_encoded_meta_strings.clear()


class MetaShareWriteContext:
    __slots__ = ("class_map",)

    def __init__(self):
        self.class_map = {}

    def reset(self):
        self.class_map.clear()


class MetaShareReadContext:
    __slots__ = ("read_type_infos",)

    def __init__(self):
        self.read_type_infos = []

    def reset(self):
        self.read_type_infos.clear()


class WriteContext:
    __slots__ = (
        "type_resolver",
        "xlang",
        "track_ref",
        "strict",
        "compatible",
        "field_nullable",
        "policy",
        "max_collection_size",
        "max_binary_size",
        "ref_writer",
        "meta_string_writer",
        "meta_share_context",
        "buffer",
        "buffer_callback",
        "unsupported_callback",
        "context_objects",
        "depth",
    )

    def __init__(self, config: Config, type_resolver):
        self.type_resolver = type_resolver
        self.xlang = config.xlang
        self.track_ref = config.track_ref
        self.strict = config.strict
        self.compatible = config.compatible
        self.field_nullable = config.field_nullable
        self.policy = config.policy
        self.max_collection_size = config.max_collection_size
        self.max_binary_size = config.max_binary_size
        self.ref_writer = MapRefWriter() if self.track_ref else NoRefWriter()
        self.meta_string_writer = MetaStringWriter()
        self.meta_share_context = MetaShareWriteContext() if config.scoped_meta_share_enabled else None
        self.buffer = None
        self.buffer_callback = None
        self.unsupported_callback = None
        self.context_objects = {}
        self.depth = 0

    def __getattr__(self, name):
        buffer = object.__getattribute__(self, "buffer")
        if buffer is None:
            raise AttributeError(name)
        return getattr(buffer, name)

    def prepare(self, buffer, buffer_callback=None, unsupported_callback=None):
        self.buffer = buffer
        self.buffer_callback = buffer_callback
        self.unsupported_callback = unsupported_callback
        self.depth = 0

    def reset(self):
        self.ref_writer.reset()
        self.meta_string_writer.reset()
        if self.meta_share_context is not None:
            self.meta_share_context.reset()
        if self.context_objects:
            self.context_objects.clear()
        self.buffer = None
        self.buffer_callback = None
        self.unsupported_callback = None
        self.depth = 0

    def add_context_object(self, key, obj):
        self.context_objects[id(key)] = obj

    def has_context_object(self, key) -> bool:
        return id(key) in self.context_objects

    def get_context_object(self, key, default=None):
        return self.context_objects.get(id(key), default)

    def increase_depth(self, diff=1):
        self.depth += diff

    def decrease_depth(self, diff=1):
        self.depth -= diff

    def write_ref_or_null(self, obj):
        return self.ref_writer.write_ref_or_null(self.buffer, obj)

    def write_ref_value_flag(self, obj):
        return self.ref_writer.write_ref_value_flag(self.buffer, obj)

    def write_null_flag(self, obj):
        return self.ref_writer.write_null_flag(self.buffer, obj)

    def write_ref(self, obj, typeinfo=None, serializer=None):
        if serializer is None and typeinfo is not None:
            serializer = typeinfo.serializer
        if serializer is None or serializer.need_to_write_ref:
            if self.ref_writer.write_ref_or_null(self.buffer, obj):
                return
            self.write_non_ref(obj, serializer=serializer, typeinfo=typeinfo)
            return
        if obj is None:
            self.buffer.write_int8(NULL_FLAG)
            return
        self.buffer.write_int8(NOT_NULL_VALUE_FLAG)
        self.write_non_ref(obj, serializer=serializer, typeinfo=typeinfo)

    def write_non_ref(self, obj, serializer=None, typeinfo=None):
        if serializer is not None:
            self.increase_depth()
            serializer.write(self, obj)
            self.decrease_depth()
            return
        cls = type(obj)
        if cls is str:
            self.buffer.write_uint8(STRING_TYPE_ID)
            self.buffer.write_string(obj)
            return
        if cls is int:
            self.buffer.write_uint8(INT64_TYPE_ID)
            self.buffer.write_varint64(obj)
            return
        if cls is bool:
            self.buffer.write_uint8(BOOL_TYPE_ID)
            self.buffer.write_bool(obj)
            return
        if cls is float:
            self.buffer.write_uint8(FLOAT64_TYPE_ID)
            self.buffer.write_double(obj)
            return
        if typeinfo is None:
            typeinfo = self.type_resolver.get_type_info(cls)
        self.type_resolver.write_type_info(self, typeinfo)
        self.increase_depth()
        typeinfo.serializer.write(self, obj)
        self.decrease_depth()

    def write_no_ref(self, obj, serializer=None, typeinfo=None):
        self.write_non_ref(obj, serializer=serializer, typeinfo=typeinfo)

    def write_buffer_object(self, buffer_object):
        if self.buffer_callback is None:
            size = buffer_object.total_bytes()
            self.buffer.write_var_uint32(size)
            writer_index = self.buffer.get_writer_index()
            self.buffer.ensure(writer_index + size)
            buf = self.buffer.slice(writer_index, size)
            buffer_object.write_to(buf)
            self.buffer.set_writer_index(writer_index + size)
            return
        if self.buffer_callback(buffer_object):
            self.buffer.write_bool(True)
            size = buffer_object.total_bytes()
            self.buffer.write_var_uint32(size)
            writer_index = self.buffer.get_writer_index()
            self.buffer.ensure(writer_index + size)
            buf = self.buffer.slice(writer_index, size)
            buffer_object.write_to(buf)
            self.buffer.set_writer_index(writer_index + size)
            return
        self.buffer.write_bool(False)

    def handle_unsupported_write(self, obj):
        if self.unsupported_callback is None or self.unsupported_callback(obj):
            raise NotImplementedError(f"{type(obj)} is not supported for write")

    def enter_flush_barrier(self):
        output_stream = None if self.buffer is None else self.buffer.get_output_stream()
        if output_stream is not None:
            output_stream.enter_flush_barrier()

    def exit_flush_barrier(self):
        output_stream = None if self.buffer is None else self.buffer.get_output_stream()
        if output_stream is not None:
            output_stream.exit_flush_barrier()

    def try_flush(self):
        if self.buffer is None or self.buffer.get_writer_index() <= 4096:
            return
        output_stream = self.buffer.get_output_stream()
        if output_stream is not None:
            output_stream.try_flush()

    def force_flush(self):
        if self.buffer is None:
            return
        output_stream = self.buffer.get_output_stream()
        if output_stream is not None:
            output_stream.force_flush()

    def write_bool(self, value):
        self.buffer.write_bool(value)

    def write_int8(self, value):
        self.buffer.write_int8(value)

    def write_uint8(self, value):
        self.buffer.write_uint8(value)

    def write_int16(self, value):
        self.buffer.write_int16(value)

    def write_uint16(self, value):
        self.buffer.write_uint16(value)

    def write_int32(self, value):
        self.buffer.write_int32(value)

    def write_uint32(self, value):
        self.buffer.write_uint32(value)

    def write_int64(self, value):
        self.buffer.write_int64(value)

    def write_uint64(self, value):
        self.buffer.write_uint64(value)

    def write_varint32(self, value):
        self.buffer.write_varint32(value)

    def write_var_uint32(self, value):
        self.buffer.write_var_uint32(value)

    def write_varint64(self, value):
        self.buffer.write_varint64(value)

    def write_var_uint64(self, value):
        self.buffer.write_var_uint64(value)

    def write_tagged_int64(self, value):
        self.buffer.write_tagged_int64(value)

    def write_tagged_uint64(self, value):
        self.buffer.write_tagged_uint64(value)

    def write_float(self, value):
        self.buffer.write_float(value)

    def write_double(self, value):
        self.buffer.write_double(value)

    def write_string(self, value):
        self.buffer.write_string(value)

    def write_bytes(self, value):
        self.buffer.write_bytes(value)


class ReadContext:
    __slots__ = (
        "type_resolver",
        "xlang",
        "track_ref",
        "strict",
        "compatible",
        "field_nullable",
        "policy",
        "max_collection_size",
        "max_binary_size",
        "max_depth",
        "ref_reader",
        "meta_string_reader",
        "meta_share_context",
        "buffer",
        "buffers",
        "unsupported_objects",
        "peer_out_of_band_enabled",
        "context_objects",
        "depth",
    )

    def __init__(self, config: Config, type_resolver):
        self.type_resolver = type_resolver
        self.xlang = config.xlang
        self.track_ref = config.track_ref
        self.strict = config.strict
        self.compatible = config.compatible
        self.field_nullable = config.field_nullable
        self.policy = config.policy
        self.max_collection_size = config.max_collection_size
        self.max_binary_size = config.max_binary_size
        self.max_depth = config.max_depth
        self.ref_reader = MapRefReader() if self.track_ref else NoRefReader()
        self.meta_string_reader = MetaStringReader(type_resolver.shared_registry)
        self.meta_share_context = MetaShareReadContext() if config.scoped_meta_share_enabled else None
        self.buffer = None
        self.buffers = None
        self.unsupported_objects = None
        self.peer_out_of_band_enabled = False
        self.context_objects = {}
        self.depth = 0

    def __getattr__(self, name):
        buffer = object.__getattribute__(self, "buffer")
        if buffer is None:
            raise AttributeError(name)
        return getattr(buffer, name)

    def prepare(
        self,
        buffer,
        buffers=None,
        unsupported_objects=None,
        peer_out_of_band_enabled=False,
    ):
        self.buffer = buffer
        self.buffers = iter(buffers) if buffers is not None else None
        self.unsupported_objects = iter(unsupported_objects) if unsupported_objects is not None else None
        self.peer_out_of_band_enabled = peer_out_of_band_enabled
        self.depth = 0

    def reset(self):
        self.ref_reader.reset()
        self.meta_string_reader.reset()
        if self.meta_share_context is not None:
            self.meta_share_context.reset()
        if self.context_objects:
            self.context_objects.clear()
        self.buffer = None
        self.buffers = None
        self.unsupported_objects = None
        self.peer_out_of_band_enabled = False
        self.depth = 0

    def add_context_object(self, key, obj):
        self.context_objects[id(key)] = obj

    def has_context_object(self, key) -> bool:
        return id(key) in self.context_objects

    def get_context_object(self, key, default=None):
        return self.context_objects.get(id(key), default)

    def increase_depth(self, diff=1):
        # Depth accounting is paired on the successful path only.
        # If a nested read raises, the top-level deserialize/reset path clears
        # `depth`, so nested readers must not add local try/finally wrappers
        # around increase/decrease pairs.
        self.depth += diff
        if self.depth > self.max_depth:
            raise Exception(
                f"Read depth exceed max depth: {self.depth}, the deserialization data may be malicious. If it's not malicious, "
                "please increase max read depth by Fory(..., max_depth=...)"
            )

    def decrease_depth(self, diff=1):
        # Only call this after the matching nested read completed successfully.
        self.depth -= diff

    def read_ref_or_null(self):
        return self.ref_reader.read_ref_or_null(self.buffer)

    def preserve_ref_id(self, ref_id=None):
        return self.ref_reader.preserve_ref_id(ref_id)

    def try_preserve_ref_id(self):
        return self.ref_reader.try_preserve_ref_id(self.buffer)

    def last_preserved_ref_id(self):
        return self.ref_reader.last_preserved_ref_id()

    def has_preserved_ref_id(self):
        return self.ref_reader.has_preserved_ref_id()

    def reference(self, obj):
        self.ref_reader.reference(obj)

    def get_read_ref(self, ref_id=None):
        return self.ref_reader.get_read_ref(ref_id)

    def set_read_ref(self, ref_id, obj):
        self.ref_reader.set_read_ref(ref_id, obj)

    def read_ref(self, serializer=None):
        if serializer is None or serializer.need_to_write_ref:
            ref_id = self.ref_reader.try_preserve_ref_id(self.buffer)
            if ref_id >= NOT_NULL_VALUE_FLAG:
                obj = self._read_non_ref_internal(serializer)
                self.ref_reader.set_read_ref(ref_id, obj)
                return obj
            return self.ref_reader.get_read_ref()
        head_flag = self.buffer.read_int8()
        if head_flag == NULL_FLAG:
            return None
        return self.read_non_ref(serializer=serializer)

    def read_non_ref(self, serializer=None):
        if self.track_ref and (serializer is None or serializer.need_to_write_ref):
            self.ref_reader.preserve_ref_id(-1)
        return self._read_non_ref_internal(serializer)

    def read_no_ref(self, serializer=None):
        return self.read_non_ref(serializer=serializer)

    def read_nullable(self, serializer=None):
        if self.buffer.read_int8() == NULL_FLAG:
            return None
        return self.read_non_ref(serializer=serializer)

    def _read_non_ref_internal(self, serializer=None):
        if serializer is None:
            typeinfo = self.type_resolver.read_type_info(self)
            type_id = typeinfo.type_id
            if type_id == STRING_TYPE_ID:
                return self.buffer.read_string()
            if type_id == INT64_TYPE_ID:
                return self.buffer.read_varint64()
            if type_id == BOOL_TYPE_ID:
                return self.buffer.read_bool()
            if type_id == FLOAT64_TYPE_ID:
                return self.buffer.read_double()
            serializer = typeinfo.serializer
        self.increase_depth()
        obj = serializer.read(self)
        self.decrease_depth()
        return obj

    def read_buffer_object(self):
        if not self.peer_out_of_band_enabled:
            size = self.buffer.read_var_uint32()
            if size > self.max_binary_size:
                raise ValueError(f"Binary size {size} exceeds the configured limit of {self.max_binary_size}")
            if self.buffer.has_input_stream():
                return self.buffer.read_bytes(size)
            reader_index = self.buffer.get_reader_index()
            buf = self.buffer.slice(reader_index, size)
            self.buffer.set_reader_index(reader_index + size)
            return buf
        in_band = self.buffer.read_bool()
        if not in_band:
            assert self.buffers is not None
            return next(self.buffers)
        size = self.buffer.read_var_uint32()
        if size > self.max_binary_size:
            raise ValueError(f"Binary size {size} exceeds the configured limit of {self.max_binary_size}")
        if self.buffer.has_input_stream():
            return self.buffer.read_bytes(size)
        reader_index = self.buffer.get_reader_index()
        buf = self.buffer.slice(reader_index, size)
        self.buffer.set_reader_index(reader_index + size)
        return buf

    def handle_unsupported_read(self):
        assert self.unsupported_objects is not None
        return next(self.unsupported_objects)

    def read_bool(self):
        return self.buffer.read_bool()

    def read_int8(self):
        return self.buffer.read_int8()

    def read_uint8(self):
        return self.buffer.read_uint8()

    def read_int16(self):
        return self.buffer.read_int16()

    def read_uint16(self):
        return self.buffer.read_uint16()

    def read_int32(self):
        return self.buffer.read_int32()

    def read_uint32(self):
        return self.buffer.read_uint32()

    def read_int64(self):
        return self.buffer.read_int64()

    def read_uint64(self):
        return self.buffer.read_uint64()

    def read_varint32(self):
        return self.buffer.read_varint32()

    def read_var_uint32(self):
        return self.buffer.read_var_uint32()

    def read_varint64(self):
        return self.buffer.read_varint64()

    def read_var_uint64(self):
        return self.buffer.read_var_uint64()

    def read_tagged_int64(self):
        return self.buffer.read_tagged_int64()

    def read_tagged_uint64(self):
        return self.buffer.read_tagged_uint64()

    def read_float(self):
        return self.buffer.read_float()

    def read_double(self):
        return self.buffer.read_double()

    def read_string(self):
        return self.buffer.read_string()
