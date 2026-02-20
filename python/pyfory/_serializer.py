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
from abc import ABC

from pyfory._fory import NOT_NULL_INT64_FLAG
from pyfory.resolver import NOT_NULL_VALUE_FLAG, NULL_FLAG
from pyfory.types import is_primitive_type

try:
    import numpy as np
except ImportError:
    np = None

logger = logging.getLogger(__name__)


class Serializer(ABC):
    __slots__ = "fory", "type_", "need_to_write_ref"

    def __init__(self, fory, type_: type):
        self.fory = fory
        self.type_: type = type_
        self.need_to_write_ref = fory.track_ref and not is_primitive_type(type_)

    def write(self, buffer, value):
        raise NotImplementedError

    def read(self, buffer):
        raise NotImplementedError

    @classmethod
    def support_subclass(cls) -> bool:
        return False


class BooleanSerializer(Serializer):
    def write(self, buffer, value):
        buffer.write_bool(value)

    def read(self, buffer):
        return buffer.read_bool()


class ByteSerializer(Serializer):
    def write(self, buffer, value):
        buffer.write_int8(value)

    def read(self, buffer):
        return buffer.read_int8()


class Int16Serializer(Serializer):
    def write(self, buffer, value):
        buffer.write_int16(value)

    def read(self, buffer):
        return buffer.read_int16()


class Int32Serializer(Serializer):
    """Serializer for INT32/VARINT32 type - uses variable-length encoding for xlang compatibility."""

    def write(self, buffer, value):
        buffer.write_varint32(value)

    def read(self, buffer):
        return buffer.read_varint32()


class FixedInt32Serializer(Serializer):
    """Serializer for fixed-width 32-bit signed integer (INT32 type_id=4)."""

    def write(self, buffer, value):
        buffer.write_int32(value)

    def read(self, buffer):
        return buffer.read_int32()


class Int64Serializer(Serializer):
    """Serializer for INT64/VARINT64 type - uses variable-length encoding for xlang compatibility."""

    def write(self, buffer, value):
        buffer.write_varint64(value)

    def read(self, buffer):
        return buffer.read_varint64()


class FixedInt64Serializer(Serializer):
    """Serializer for fixed-width 64-bit signed integer (INT64 type_id=6)."""

    def write(self, buffer, value):
        buffer.write_int64(value)

    def read(self, buffer):
        return buffer.read_int64()


class Varint32Serializer(Serializer):
    """Serializer for VARINT32 type - variable-length encoded signed 32-bit integer."""

    def write(self, buffer, value):
        buffer.write_varint32(value)

    def read(self, buffer):
        return buffer.read_varint32()


class Varint64Serializer(Serializer):
    """Serializer for VARINT64 type - variable-length encoded signed 64-bit integer."""

    def write(self, buffer, value):
        buffer.write_varint64(value)

    def read(self, buffer):
        return buffer.read_varint64()


class TaggedInt64Serializer(Serializer):
    """Serializer for TAGGED_INT64 type - tagged encoding for signed 64-bit integer."""

    def write(self, buffer, value):
        buffer.write_tagged_int64(value)

    def read(self, buffer):
        return buffer.read_tagged_int64()


class Uint8Serializer(Serializer):
    """Serializer for UINT8 type - unsigned 8-bit integer."""

    def write(self, buffer, value):
        buffer.write_uint8(value)

    def read(self, buffer):
        return buffer.read_uint8()


class Uint16Serializer(Serializer):
    """Serializer for UINT16 type - unsigned 16-bit integer."""

    def write(self, buffer, value):
        buffer.write_uint16(value)

    def read(self, buffer):
        return buffer.read_uint16()


class Uint32Serializer(Serializer):
    """Serializer for UINT32 type - fixed-size unsigned 32-bit integer."""

    def write(self, buffer, value):
        buffer.write_uint32(value)

    def read(self, buffer):
        return buffer.read_uint32()


class VarUint32Serializer(Serializer):
    """Serializer for VAR_UINT32 type - variable-length encoded unsigned 32-bit integer."""

    def write(self, buffer, value):
        buffer.write_var_uint32(value)

    def read(self, buffer):
        return buffer.read_var_uint32()


class Uint64Serializer(Serializer):
    """Serializer for UINT64 type - fixed-size unsigned 64-bit integer."""

    def write(self, buffer, value):
        buffer.write_uint64(value)

    def read(self, buffer):
        return buffer.read_uint64()


class VarUint64Serializer(Serializer):
    """Serializer for VAR_UINT64 type - variable-length encoded unsigned 64-bit integer."""

    def write(self, buffer, value):
        buffer.write_var_uint64(value)

    def read(self, buffer):
        return buffer.read_var_uint64()


class TaggedUint64Serializer(Serializer):
    """Serializer for TAGGED_UINT64 type - tagged encoding for unsigned 64-bit integer."""

    def write(self, buffer, value):
        buffer.write_tagged_uint64(value)

    def read(self, buffer):
        return buffer.read_tagged_uint64()


class Float32Serializer(Serializer):
    def write(self, buffer, value):
        buffer.write_float(value)

    def read(self, buffer):
        return buffer.read_float()


class Float64Serializer(Serializer):
    def write(self, buffer, value):
        buffer.write_double(value)

    def read(self, buffer):
        return buffer.read_double()



class BFloat16Serializer(XlangCompatibleSerializer):
    def write(self, buffer, value):
        from pyfory.bfloat16 import BFloat16

        if isinstance(value, BFloat16):
            buffer.write_bfloat16(value.to_bits())
        else:
            buffer.write_bfloat16(BFloat16(value).to_bits())

    def read(self, buffer):
        from pyfory.bfloat16 import BFloat16

        return BFloat16.from_bits(buffer.read_bfloat16())



class StringSerializer(Serializer):
  
    def __init__(self, fory, type_):
        super().__init__(fory, type_)
        self.need_to_write_ref = False

    def write(self, buffer, value: str):
        buffer.write_string(value)

    def read(self, buffer):
        return buffer.read_string()


_base_date = datetime.date(1970, 1, 1)


class DateSerializer(Serializer):
    def write(self, buffer, value: datetime.date):
        if not isinstance(value, datetime.date):
            raise TypeError("{} should be {} instead of {}".format(value, datetime.date, type(value)))
        days = (value - _base_date).days
        buffer.write_int32(days)

    def read(self, buffer):
        days = buffer.read_int32()
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

    def write(self, buffer, value: datetime.datetime):
        if not isinstance(value, datetime.datetime):
            raise TypeError("{} should be {} instead of {}".format(value, datetime, type(value)))
        seconds, nanos = self._get_timestamp(value)
        buffer.write_int64(seconds)
        buffer.write_uint32(nanos)

    def read(self, buffer):
        seconds = buffer.read_int64()
        nanos = buffer.read_uint32()
        ts = seconds + nanos / 1_000_000_000
        # TODO support timezone
        return datetime.datetime.fromtimestamp(ts)


class EnumSerializer(Serializer):
    def __init__(self, fory, type_):
        super().__init__(fory, type_)
        self.need_to_write_ref = False
        self._members = tuple(type_)
        self._ordinal_by_member = {member: idx for idx, member in enumerate(self._members)}

    @classmethod
    def support_subclass(cls) -> bool:
        return True

    def write(self, buffer, value):
        buffer.write_var_uint32(self._ordinal_by_member[value])

    def read(self, buffer):
        ordinal = buffer.read_var_uint32()
        return self._members[ordinal]


class SliceSerializer(Serializer):
    def write(self, buffer, value: slice):
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
                self.fory.write_no_ref(buffer, start)
        if type(stop) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(stop)
        else:
            if stop is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fory.write_no_ref(buffer, stop)
        if type(step) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(step)
        else:
            if step is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fory.write_no_ref(buffer, step)

    def read(self, buffer):
        if buffer.read_int8() == NULL_FLAG:
            start = None
        else:
            start = self.fory.read_no_ref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            stop = None
        else:
            stop = self.fory.read_no_ref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            step = None
        else:
            step = self.fory.read_no_ref(buffer)
        return slice(start, stop, step)
