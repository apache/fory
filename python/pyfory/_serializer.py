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

import datetime
import logging
import platform
import time
import array
from abc import ABC

from pyfory._fory import NOT_NULL_INT64_FLAG
from pyfory.resolver import NOT_NULL_VALUE_FLAG, NULL_FLAG
from pyfory.serialization import (
    BFloat16Array,
    BoolArray,
    Float16Array,
    Float32Array,
    Float64Array,
    Int16Array,
    Int32Array,
    Int64Array,
    Int8Array,
    UInt16Array,
    UInt32Array,
    UInt64Array,
    UInt8Array,
    _bfloat16_from_bits,
    _bfloat16_to_bits,
    _float16_from_bits,
    _float16_to_bits,
)
from pyfory.types import is_primitive_type
from pyfory.utils import is_little_endian

try:
    import numpy as np
except ImportError:
    np = None

logger = logging.getLogger(__name__)


class Serializer(ABC):
    """
    Pure-Python serializer base used only by the Python serializer shim.

    Keep Cython-mode serializer ownership in `pyfory.serialization` rather than
    branching in this module. The mode switch belongs at the import boundary.
    """

    __slots__ = "type_resolver", "type_", "need_to_write_ref"

    def __init__(self, type_resolver, type_: type):
        self.type_resolver = type_resolver
        self.type_: type = type_
        self.need_to_write_ref = type_resolver.track_ref and not is_primitive_type(type_)

    def write(self, write_context, value):
        raise NotImplementedError

    def read(self, read_context):
        raise NotImplementedError

    @classmethod
    def support_subclass(cls) -> bool:
        return False


class BooleanSerializer(Serializer):
    def write(self, write_context, value):
        write_context.write_bool(value)

    def read(self, read_context):
        return read_context.read_bool()


class ByteSerializer(Serializer):
    def write(self, write_context, value):
        write_context.write_int8(value)

    def read(self, read_context):
        return read_context.read_int8()


class Int16Serializer(Serializer):
    def write(self, write_context, value):
        write_context.write_int16(value)

    def read(self, read_context):
        return read_context.read_int16()


class Int32Serializer(Serializer):
    """Serializer for INT32/VARINT32 type - uses variable-length encoding for xlang compatibility."""

    def write(self, write_context, value):
        write_context.write_varint32(value)

    def read(self, read_context):
        return read_context.read_varint32()


class FixedInt32Serializer(Serializer):
    """Serializer for fixed-width 32-bit signed integer (INT32 type_id=4)."""

    def write(self, write_context, value):
        write_context.write_int32(value)

    def read(self, read_context):
        return read_context.read_int32()


class Int64Serializer(Serializer):
    """Serializer for INT64/VARINT64 type - uses variable-length encoding for xlang compatibility."""

    def write(self, write_context, value):
        write_context.write_varint64(value)

    def read(self, read_context):
        return read_context.read_varint64()


class FixedInt64Serializer(Serializer):
    """Serializer for fixed-width 64-bit signed integer (INT64 type_id=6)."""

    def write(self, write_context, value):
        write_context.write_int64(value)

    def read(self, read_context):
        return read_context.read_int64()


class Varint32Serializer(Serializer):
    """Serializer for VARINT32 type - variable-length encoded signed 32-bit integer."""

    def write(self, write_context, value):
        write_context.write_varint32(value)

    def read(self, read_context):
        return read_context.read_varint32()


class Varint64Serializer(Serializer):
    """Serializer for VARINT64 type - variable-length encoded signed 64-bit integer."""

    def write(self, write_context, value):
        write_context.write_varint64(value)

    def read(self, read_context):
        return read_context.read_varint64()


class TaggedInt64Serializer(Serializer):
    """Serializer for TAGGED_INT64 type - tagged encoding for signed 64-bit integer."""

    def write(self, write_context, value):
        write_context.write_tagged_int64(value)

    def read(self, read_context):
        return read_context.read_tagged_int64()


class Uint8Serializer(Serializer):
    """Serializer for UINT8 type - unsigned 8-bit integer."""

    def write(self, write_context, value):
        write_context.write_uint8(value)

    def read(self, read_context):
        return read_context.read_uint8()


class Uint16Serializer(Serializer):
    """Serializer for UINT16 type - unsigned 16-bit integer."""

    def write(self, write_context, value):
        write_context.write_uint16(value)

    def read(self, read_context):
        return read_context.read_uint16()


class Uint32Serializer(Serializer):
    """Serializer for UINT32 type - fixed-size unsigned 32-bit integer."""

    def write(self, write_context, value):
        write_context.write_uint32(value)

    def read(self, read_context):
        return read_context.read_uint32()


class VarUint32Serializer(Serializer):
    """Serializer for VAR_UINT32 type - variable-length encoded unsigned 32-bit integer."""

    def write(self, write_context, value):
        write_context.write_var_uint32(value)

    def read(self, read_context):
        return read_context.read_var_uint32()


class Uint64Serializer(Serializer):
    """Serializer for UINT64 type - fixed-size unsigned 64-bit integer."""

    def write(self, write_context, value):
        write_context.write_uint64(value)

    def read(self, read_context):
        return read_context.read_uint64()


class VarUint64Serializer(Serializer):
    """Serializer for VAR_UINT64 type - variable-length encoded unsigned 64-bit integer."""

    def write(self, write_context, value):
        write_context.write_var_uint64(value)

    def read(self, read_context):
        return read_context.read_var_uint64()


class TaggedUint64Serializer(Serializer):
    """Serializer for TAGGED_UINT64 type - tagged encoding for unsigned 64-bit integer."""

    def write(self, write_context, value):
        write_context.write_tagged_uint64(value)

    def read(self, read_context):
        return read_context.read_tagged_uint64()


class Float32Serializer(Serializer):
    def write(self, write_context, value):
        write_context.write_float32(value)

    def read(self, read_context):
        return read_context.read_float32()


class Float64Serializer(Serializer):
    def write(self, write_context, value):
        write_context.write_float64(value)

    def read(self, read_context):
        return read_context.read_float64()


def _coerce_float16_bits(value):
    return _float16_to_bits(value)


def _coerce_bfloat16_bits(value):
    return _bfloat16_to_bits(value)


class Float16Serializer(Serializer):
    def write(self, write_context, value):
        write_context.write_uint16(_coerce_float16_bits(value))

    def read(self, read_context):
        return _float16_from_bits(read_context.read_uint16())


class _DenseArraySerializer(Serializer):
    wrapper_type = None
    typecode = None
    reduced_precision = False

    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)
        self.need_to_write_ref = False

    def write(self, write_context, value):
        if type(value) is not self.wrapper_type:
            raise TypeError(f"{self.wrapper_type.__name__} serializer requires {self.wrapper_type.__name__}, got {type(value)!r}")
        safe = value
        buffer = safe.to_buffer()
        itemsize = 1 if isinstance(buffer, (bytes, bytearray)) else buffer.itemsize
        write_context.write_var_uint32(len(buffer) * itemsize)
        if itemsize == 1 or is_little_endian:
            write_context.write_buffer(buffer)
        else:
            swapped = array.array(buffer.typecode, buffer)
            swapped.byteswap()
            write_context.write_buffer(swapped)

    def read(self, read_context):
        payload_size = read_context.read_var_uint32()
        data = read_context.read_bytes(payload_size)
        if self.wrapper_type is BoolArray:
            return BoolArray(bool(value) for value in data)
        if self.reduced_precision:
            if payload_size & 1:
                raise ValueError(f"{self.wrapper_type.__name__} payload size mismatch")
            raw = array.array("H")
            raw.frombytes(data)
            if not is_little_endian:
                raw.byteswap()
            return self.wrapper_type.from_buffer(raw.tobytes())
        raw = array.array(self.typecode)
        raw.frombytes(data)
        if not is_little_endian and raw.itemsize > 1:
            raw.byteswap()
        return self.wrapper_type(raw)


class BoolArraySerializer(_DenseArraySerializer):
    wrapper_type = BoolArray
    typecode = "B"


class Int8ArraySerializer(_DenseArraySerializer):
    wrapper_type = Int8Array
    typecode = "b"


class Int16ArraySerializer(_DenseArraySerializer):
    wrapper_type = Int16Array
    typecode = "h"


class Int32ArraySerializer(_DenseArraySerializer):
    wrapper_type = Int32Array
    typecode = "i"


class Int64ArraySerializer(_DenseArraySerializer):
    wrapper_type = Int64Array
    typecode = "q"


class UInt8ArraySerializer(_DenseArraySerializer):
    wrapper_type = UInt8Array
    typecode = "B"


class UInt16ArraySerializer(_DenseArraySerializer):
    wrapper_type = UInt16Array
    typecode = "H"


class UInt32ArraySerializer(_DenseArraySerializer):
    wrapper_type = UInt32Array
    typecode = "I"


class UInt64ArraySerializer(_DenseArraySerializer):
    wrapper_type = UInt64Array
    typecode = "Q"


class Float16ArraySerializer(_DenseArraySerializer):
    wrapper_type = Float16Array
    typecode = "H"
    reduced_precision = True


class BFloat16Serializer(Serializer):
    def write(self, write_context, value):
        write_context.write_uint16(_coerce_bfloat16_bits(value))

    def read(self, read_context):
        return _bfloat16_from_bits(read_context.read_uint16())


class BFloat16ArraySerializer(_DenseArraySerializer):
    wrapper_type = BFloat16Array
    typecode = "H"
    reduced_precision = True


class Float32ArraySerializer(_DenseArraySerializer):
    wrapper_type = Float32Array
    typecode = "f"


class Float64ArraySerializer(_DenseArraySerializer):
    wrapper_type = Float64Array
    typecode = "d"


class StringSerializer(Serializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)
        self.need_to_write_ref = False

    def write(self, write_context, value: str):
        write_context.write_string(value)

    def read(self, read_context):
        return read_context.read_string()


_base_date = datetime.date(1970, 1, 1)


class DateSerializer(Serializer):
    def write(self, write_context, value: datetime.date):
        if not isinstance(value, datetime.date):
            raise TypeError("{} should be {} instead of {}".format(value, datetime.date, type(value)))
        days = (value - _base_date).days
        if self.type_resolver.xlang:
            write_context.write_varint64(days)
        else:
            write_context.write_int32(days)

    def read(self, read_context):
        if self.type_resolver.xlang:
            days = read_context.read_varint64()
        else:
            days = read_context.read_int32()
        return _base_date + datetime.timedelta(days=days)


class TimestampSerializer(Serializer):
    __win_platform = platform.system() == "Windows"

    def _get_timestamp(self, value: datetime.datetime):
        seconds_offset = 0
        if TimestampSerializer.__win_platform and value.tzinfo is None:
            is_dst = time.daylight and time.localtime().tm_isdst > 0
            seconds_offset = time.altzone if is_dst else time.timezone
            value = value.replace(tzinfo=datetime.timezone.utc)
        micros = int((value.timestamp() + seconds_offset) * 1_000_000)
        seconds, micros_rem = divmod(micros, 1_000_000)
        nanos = micros_rem * 1000
        return seconds, nanos

    def write(self, write_context, value: datetime.datetime):
        if not isinstance(value, datetime.datetime):
            raise TypeError("{} should be {} instead of {}".format(value, datetime, type(value)))
        seconds, nanos = self._get_timestamp(value)
        write_context.write_int64(seconds)
        write_context.write_uint32(nanos)

    def read(self, read_context):
        seconds = read_context.read_int64()
        nanos = read_context.read_uint32()
        ts = seconds + nanos / 1_000_000_000
        return datetime.datetime.fromtimestamp(ts, tz=datetime.timezone.utc)


class DurationSerializer(Serializer):
    def write(self, write_context, value: datetime.timedelta):
        if not isinstance(value, datetime.timedelta):
            raise TypeError("{} should be {} instead of {}".format(value, datetime.timedelta, type(value)))
        total_micros = value.days * 86_400_000_000 + value.seconds * 1_000_000 + value.microseconds
        seconds, micros = divmod(total_micros, 1_000_000)
        write_context.write_varint64(seconds)
        write_context.write_int32(micros * 1000)

    def read(self, read_context):
        seconds = read_context.read_varint64()
        nanos = read_context.read_int32()
        if nanos < 0 or nanos > 999_999_999:
            raise ValueError(f"Duration nanoseconds {nanos} out of valid range [0, 999999999]")
        return datetime.timedelta(seconds=seconds, microseconds=nanos // 1000)


class EnumSerializer(Serializer):
    """Pure-Python enum serializer used when Cython serialization is disabled."""

    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)
        self.need_to_write_ref = False
        self._members = tuple(type_)
        self._wire_value_by_member = {member: idx for idx, member in enumerate(self._members)}
        self._member_by_wire_value = {idx: member for idx, member in enumerate(self._members)}
        if type_resolver.xlang:
            explicit_wire_values = {}
            use_explicit_ids = True
            for member in self._members:
                raw_value = member.value
                if isinstance(raw_value, bool) or not isinstance(raw_value, int) or raw_value < 0:
                    use_explicit_ids = False
                    break
                wire_value = int(raw_value)
                if wire_value in explicit_wire_values:
                    use_explicit_ids = False
                    break
                explicit_wire_values[wire_value] = member
            if use_explicit_ids:
                self._wire_value_by_member = {member: int(member.value) for member in self._members}
                self._member_by_wire_value = explicit_wire_values

    @classmethod
    def support_subclass(cls) -> bool:
        return True

    def write(self, write_context, value):
        write_context.write_var_uint32(self._wire_value_by_member[value])

    def read(self, read_context):
        wire_value = read_context.read_var_uint32()
        try:
            return self._member_by_wire_value[wire_value]
        except KeyError as exc:
            raise ValueError(f"Unknown enum value {wire_value} for {self.type_.__qualname__}") from exc


class SliceSerializer(Serializer):
    """Pure-Python slice serializer used when Cython serialization is disabled."""

    def write(self, write_context, value: slice):
        buffer = write_context.buffer
        start, stop, step = value.start, value.stop, value.step
        if type(start) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(start)
        else:
            if start is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                write_context.write_no_ref(start)
        if type(stop) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(stop)
        else:
            if stop is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                write_context.write_no_ref(stop)
        if type(step) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(step)
        else:
            if step is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                write_context.write_no_ref(step)

    def read(self, read_context):
        buffer = read_context.buffer
        if buffer.read_int8() == NULL_FLAG:
            start = None
        else:
            start = read_context.read_no_ref()
        if buffer.read_int8() == NULL_FLAG:
            stop = None
        else:
            stop = read_context.read_no_ref()
        if buffer.read_int8() == NULL_FLAG:
            step = None
        else:
            step = read_context.read_no_ref()
        return slice(start, stop, step)
