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

@cython.final
cdef class BooleanSerializer(Serializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_bool(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_bool()


@cython.final
cdef class ByteSerializer(Serializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_int8(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_int8()


@cython.final
cdef class Int16Serializer(Serializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_int16(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_int16()


@cython.final
cdef class Int32Serializer(Serializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_varint32(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_varint32()


@cython.final
cdef class Int64Serializer(Serializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_varint64(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_varint64()


@cython.final
cdef class FixedInt32Serializer(Serializer):
    """Serializer for fixed-width 32-bit signed integer (INT32 type_id=4)."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_int32(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_int32()


@cython.final
cdef class FixedInt64Serializer(Serializer):
    """Serializer for fixed-width 64-bit signed integer (INT64 type_id=6)."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_int64(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_int64()


@cython.final
cdef class Varint32Serializer(Serializer):
    """Serializer for VARINT32 type - variable-length encoded signed 32-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_varint32(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_varint32()


@cython.final
cdef class Varint64Serializer(Serializer):
    """Serializer for VARINT64 type - variable-length encoded signed 64-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_varint64(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_varint64()


@cython.final
cdef class TaggedInt64Serializer(Serializer):
    """Serializer for TAGGED_INT64 type - tagged encoding for signed 64-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_tagged_int64(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_tagged_int64()


@cython.final
cdef class Uint8Serializer(Serializer):
    """Serializer for UINT8 type - unsigned 8-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_uint8(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_uint8()


@cython.final
cdef class Uint16Serializer(Serializer):
    """Serializer for UINT16 type - unsigned 16-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_uint16(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_uint16()


@cython.final
cdef class Uint32Serializer(Serializer):
    """Serializer for UINT32 type - fixed-size unsigned 32-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_uint32(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_uint32()


@cython.final
cdef class VarUint32Serializer(Serializer):
    """Serializer for VAR_UINT32 type - variable-length encoded unsigned 32-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_var_uint32(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_var_uint32()


@cython.final
cdef class Uint64Serializer(Serializer):
    """Serializer for UINT64 type - fixed-size unsigned 64-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_uint64(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_uint64()


@cython.final
cdef class VarUint64Serializer(Serializer):
    """Serializer for VAR_UINT64 type - variable-length encoded unsigned 64-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_var_uint64(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_var_uint64()


@cython.final
cdef class TaggedUint64Serializer(Serializer):
    """Serializer for TAGGED_UINT64 type - tagged encoding for unsigned 64-bit integer."""
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_tagged_uint64(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_tagged_uint64()


@cython.final
cdef class Float32Serializer(Serializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_float(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_float()


@cython.final
cdef class Float64Serializer(Serializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_double(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_double()


@cython.final
cdef class StringSerializer(Serializer):
    def __init__(self, fory, type_, track_ref=False):
        super().__init__(fory, type_)
        self.need_to_write_ref = track_ref

    cpdef inline write(self, Buffer buffer, value):
        buffer.write_string(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_string(self.fory.max_string_bytes_length)


cdef _base_date = datetime.date(1970, 1, 1)
cdef int _base_date_ordinal = _base_date.toordinal()  # Precompute for faster date deserialization


@cython.final
cdef class DateSerializer(Serializer):
    cpdef inline write(self, Buffer buffer, value):
        if type(value) is not datetime.date:
            raise TypeError(
                "{} should be {} instead of {}".format(
                    value, datetime.date, type(value)
                )
            )
        days = (value - _base_date).days
        buffer.write_int32(days)

    cpdef inline read(self, Buffer buffer):
        days = buffer.read_int32()
        return datetime.date.fromordinal(_base_date_ordinal + days)


@cython.final
cdef class TimestampSerializer(Serializer):
    cdef bint win_platform

    def __init__(self, fory, type_: Union[type, TypeVar]):
        super().__init__(fory, type_)
        self.win_platform = platform.system() == "Windows"

    cdef inline _get_timestamp(self, value):
        seconds_offset = 0
        if self.win_platform and value.tzinfo is None:
            is_dst = time.daylight and time.localtime().tm_isdst > 0
            seconds_offset = time.altzone if is_dst else time.timezone
            value = value.replace(tzinfo=datetime.timezone.utc)
        cdef long long micros = <long long>((value.timestamp() + seconds_offset) * 1000000)
        cdef long long seconds
        cdef long long micros_rem
        if micros >= 0:
            seconds = micros // 1000000
            micros_rem = micros % 1000000
        else:
            seconds = -((-micros) // 1000000)
            micros_rem = micros - seconds * 1000000
        if micros_rem < 0:
            seconds -= 1
            micros_rem += 1000000
        return seconds, <unsigned int>(micros_rem * 1000)

    cpdef inline write(self, Buffer buffer, value):
        if type(value) is not datetime.datetime:
            raise TypeError(
                "{} should be {} instead of {}".format(value, datetime, type(value))
            )
        cdef long long seconds
        cdef unsigned int nanos
        seconds, nanos = self._get_timestamp(value)
        buffer.write_int64(seconds)
        buffer.write_uint32(nanos)

    cpdef inline read(self, Buffer buffer):
        cdef long long seconds = buffer.read_int64()
        cdef unsigned int nanos = buffer.read_uint32()
        ts = seconds + (<double>nanos) / 1000000000.0
        # TODO support timezone
        return datetime.datetime.fromtimestamp(ts)
