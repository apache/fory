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
from pyfory.type import is_primitive_type

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
        self.need_to_write_ref = fory.ref_tracking and not is_primitive_type(type_)

    def write(self, buffer, value):
        raise NotImplementedError

    def read(self, buffer):
        raise NotImplementedError

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError

    @classmethod
    def support_subclass(cls) -> bool:
        return False


class XlangCompatibleSerializer(Serializer):
    def __init__(self, fory, type_):
        super().__init__(fory, type_)

    def xwrite(self, buffer, value):
        self.write(buffer, value)

    def xread(self, buffer):
        return self.read(buffer)


class BooleanSerializer(XlangCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_bool(value)

    def read(self, buffer):
        return buffer.read_bool()


class ByteSerializer(XlangCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_int8(value)

    def read(self, buffer):
        return buffer.read_int8()


class Int16Serializer(XlangCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_int16(value)

    def read(self, buffer):
        return buffer.read_int16()


class Int32Serializer(XlangCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_varint32(value)

    def read(self, buffer):
        return buffer.read_varint32()


class Int64Serializer(Serializer):
    def xwrite(self, buffer, value):
        buffer.write_varint64(value)

    def xread(self, buffer):
        return buffer.read_varint64()

    def write(self, buffer, value):
        buffer.write_varint64(value)

    def read(self, buffer):
        return buffer.read_varint64()


class Float32Serializer(XlangCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_float(value)

    def read(self, buffer):
        return buffer.read_float()


class Float64Serializer(XlangCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_double(value)

    def read(self, buffer):
        return buffer.read_double()


class StringSerializer(XlangCompatibleSerializer):
    def __init__(self, fory, type_):
        super().__init__(fory, type_)
        self.need_to_write_ref = False

    def write(self, buffer, value: str):
        buffer.write_string(value)

    def read(self, buffer):
        return buffer.read_string()


_base_date = datetime.date(1970, 1, 1)


class DateSerializer(XlangCompatibleSerializer):
    def write(self, buffer, value: datetime.date):
        if not isinstance(value, datetime.date):
            raise TypeError("{} should be {} instead of {}".format(value, datetime.date, type(value)))
        days = (value - _base_date).days
        buffer.write_int32(days)

    def read(self, buffer):
        days = buffer.read_int32()
        return _base_date + datetime.timedelta(days=days)


class TimestampSerializer(XlangCompatibleSerializer):
    __win_platform = platform.system() == "Windows"

    def _get_timestamp(self, value: datetime.datetime):
        seconds_offset = 0
        if TimestampSerializer.__win_platform and value.tzinfo is None:
            is_dst = time.daylight and time.localtime().tm_isdst > 0
            seconds_offset = time.altzone if is_dst else time.timezone
            value = value.replace(tzinfo=datetime.timezone.utc)
        return int((value.timestamp() + seconds_offset) * 1000000)

    def write(self, buffer, value: datetime.datetime):
        if not isinstance(value, datetime.datetime):
            raise TypeError("{} should be {} instead of {}".format(value, datetime, type(value)))
        # TimestampType represent micro seconds
        buffer.write_int64(self._get_timestamp(value))

    def read(self, buffer):
        ts = buffer.read_int64() / 1000000
        # TODO support timezone
        return datetime.datetime.fromtimestamp(ts)


class EnumSerializer(Serializer):
    def __init__(self, fory, type_):
        super().__init__(fory, type_)
        self.need_to_write_ref = False

    @classmethod
    def support_subclass(cls) -> bool:
        return True

    def write(self, buffer, value):
        buffer.write_string(value.name)

    def read(self, buffer):
        name = buffer.read_string()
        return getattr(self.type_, name)

    def xwrite(self, buffer, value):
        buffer.write_varuint32(value.value)

    def xread(self, buffer):
        ordinal = buffer.read_varuint32()
        return self.type_(ordinal)


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

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError


class UnionSerializer(Serializer):
    """
    Serializer for typing.Union types.

    Serializes a Union by storing:
    1. The index of the active alternative (as varuint32)
    2. The value of the active alternative

    This allows the deserializer to determine which alternative to use
    and forward to the appropriate serializer.
    """

    __slots__ = ("alternative_types", "alternative_serializers", "type_resolver")

    def __init__(self, fory, type_, alternative_types):
        super().__init__(fory, type_)
        self.alternative_types = alternative_types
        self.type_resolver = fory.type_resolver
        self.alternative_serializers = []
        for alt_type in alternative_types:
            serializer = fory.type_resolver.get_serializer(alt_type)
            self.alternative_serializers.append((alt_type, serializer))

    def write(self, buffer, value):
        # Find which alternative type matches the value
        active_index = None
        active_serializer = None

        for i, (alt_type, serializer) in enumerate(self.alternative_serializers):
            if isinstance(value, alt_type):
                active_index = i
                active_serializer = serializer
                break

        if active_index is None:
            raise TypeError(f"Value {value} of type {type(value)} doesn't match any alternative in Union{self.alternative_types}")

        # Write the active variant index
        buffer.write_varuint32(active_index)

        # Write the alternative's value (no type info in Python mode)
        active_serializer.write(buffer, value)

    def read(self, buffer):
        # Read the stored variant index
        stored_index = buffer.read_varuint32()

        # Validate index is within bounds
        if stored_index >= len(self.alternative_serializers):
            raise ValueError(f"Union index out of bounds: {stored_index} (max: {len(self.alternative_serializers) - 1})")

        # Dispatch to the appropriate alternative's serializer
        _, serializer = self.alternative_serializers[stored_index]
        return serializer.read(buffer)

    def xwrite(self, buffer, value):
        # Find which alternative type matches the value
        active_index = None
        active_serializer = None
        active_type = None

        for i, (alt_type, serializer) in enumerate(self.alternative_serializers):
            if isinstance(value, alt_type):
                active_index = i
                active_serializer = serializer
                active_type = alt_type
                break

        if active_index is None:
            raise TypeError(f"Value {value} of type {type(value)} doesn't match any alternative in Union{self.alternative_types}")

        # Write the active variant index
        buffer.write_varuint32(active_index)

        # In xlang mode, write type info for the alternative
        # Get the typeinfo for the alternative type and write it
        typeinfo = self.type_resolver.get_typeinfo(active_type)
        self.type_resolver.write_typeinfo(buffer, typeinfo)

        # Write the alternative's value data
        active_serializer.xwrite(buffer, value)

    def xread(self, buffer):
        # Read the stored variant index
        stored_index = buffer.read_varuint32()

        # Validate index is within bounds
        if stored_index >= len(self.alternative_serializers):
            raise ValueError(f"Union index out of bounds: {stored_index} (max: {len(self.alternative_serializers) - 1})")

        # In xlang mode, read type info for the alternative
        typeinfo = self.type_resolver.read_typeinfo(buffer)

        # Dispatch to the appropriate alternative's serializer
        # Use typeinfo's serializer which may be more specific than what we registered
        return typeinfo.serializer.xread(buffer)
