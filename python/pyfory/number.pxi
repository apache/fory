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

import array as _py_array
import operator as _operator
from collections.abc import MutableSequence as _MutableSequence
from libc.string cimport memcpy
from pyfory.utils import is_little_endian


cdef object _check_bool_value(object value):
    if type(value) is not bool:
        raise TypeError(f"array<bool> element must be bool, got {type(value)!r}")
    return value


cdef uint8_t _bool_to_byte(object value) except *:
    _check_bool_value(value)
    return <uint8_t>1 if value is True else <uint8_t>0


cdef object _check_int_value(object value, object min_value, object max_value, str name):
    if type(value) is bool or not isinstance(value, int):
        raise TypeError(f"{name} element must be int, got {type(value)!r}")
    if value < min_value or value > max_value:
        raise OverflowError(f"{name} element {value!r} out of range [{min_value}, {max_value}]")
    return value


cdef double _check_float_value(object value, str name) except *:
    if type(value) is bool or not isinstance(value, (int, float)):
        raise TypeError(f"{name} element must be float-compatible, got {type(value)!r}")
    return <double>value


cdef class _ForyArray:
    def __init__(self, values=None):
        if values is not None:
            self.extend(values)

    @classmethod
    def from_values(cls, values):
        return cls(values)

    cpdef Py_ssize_t _size(self):
        raise NotImplementedError

    cpdef object _get(self, Py_ssize_t index):
        raise NotImplementedError

    cpdef _set(self, Py_ssize_t index, object value):
        raise NotImplementedError

    cpdef _append(self, object value):
        raise NotImplementedError

    cpdef _insert(self, Py_ssize_t index, object value):
        raise NotImplementedError

    cpdef _delete(self, Py_ssize_t index):
        raise NotImplementedError

    cpdef _clear(self):
        raise NotImplementedError

    cdef Py_ssize_t _normalize_index(self, object index) except -1:
        cdef Py_ssize_t n = self._size()
        cdef Py_ssize_t i = index
        if i < 0:
            i += n
        if i < 0 or i >= n:
            raise IndexError("array index out of range")
        return i

    cdef Py_ssize_t _normalize_insert_index(self, object index) except -1:
        cdef Py_ssize_t n = self._size()
        cdef Py_ssize_t i = index
        if i < 0:
            i += n
            if i < 0:
                i = 0
        elif i > n:
            i = n
        return i

    def __len__(self):
        return self._size()

    def __bool__(self):
        return self._size() != 0

    def __iter__(self):
        cdef Py_ssize_t i
        cdef Py_ssize_t n = self._size()
        for i in range(n):
            yield self._get(i)

    def __getitem__(self, index):
        if isinstance(index, slice):
            return type(self)(self._get(i) for i in range(*index.indices(self._size())))
        return self._get(self._normalize_index(index))

    def __setitem__(self, index, value):
        cdef list items
        if isinstance(index, slice):
            items = list(self)
            items[index] = value
            self._clear()
            self.extend(items)
            return
        self._set(self._normalize_index(index), value)

    def __delitem__(self, index):
        cdef list items
        if isinstance(index, slice):
            items = list(self)
            del items[index]
            self._clear()
            self.extend(items)
            return
        self._delete(self._normalize_index(index))

    def __repr__(self):
        return f"{type(self).__name__}({list(self)!r})"

    def __eq__(self, other):
        try:
            return list(self) == list(other)
        except TypeError:
            return False

    def __ne__(self, other):
        return not self == other

    def __lt__(self, other):
        return list(self) < list(other)

    def __le__(self, other):
        return list(self) <= list(other)

    def __gt__(self, other):
        return list(self) > list(other)

    def __ge__(self, other):
        return list(self) >= list(other)

    def __reversed__(self):
        cdef Py_ssize_t i
        for i in range(self._size() - 1, -1, -1):
            yield self._get(i)

    def __contains__(self, value):
        cdef Py_ssize_t i
        for i in range(self._size()):
            if self._get(i) == value:
                return True
        return False

    def __add__(self, other):
        return type(self)(list(self) + list(other))

    def __iadd__(self, other):
        self.extend(other)
        return self

    def __mul__(self, count):
        return type(self)(list(self) * _operator.index(count))

    def __rmul__(self, count):
        return self * count

    def __imul__(self, count):
        cdef list items = list(self) * _operator.index(count)
        self._clear()
        self.extend(items)
        return self

    def __reduce__(self):
        return (type(self), (list(self),))

    def __reduce_ex__(self, protocol):
        return (type(self), (list(self),))

    def append(self, value):
        self._append(value)

    def extend(self, values):
        for value in values:
            self._append(value)

    def insert(self, index, value):
        self._insert(self._normalize_insert_index(index), value)

    def pop(self, index=-1):
        cdef Py_ssize_t i = self._normalize_index(index)
        cdef object value = self._get(i)
        self._delete(i)
        return value

    def clear(self):
        self._clear()

    def copy(self):
        return type(self)(self)

    def tolist(self):
        return list(self)

    def count(self, value):
        cdef Py_ssize_t i
        cdef Py_ssize_t total = 0
        for i in range(self._size()):
            if self._get(i) == value:
                total += 1
        return total

    def index(self, value, start=0, stop=None):
        cdef Py_ssize_t i
        cdef Py_ssize_t n = self._size()
        cdef Py_ssize_t begin = start
        cdef Py_ssize_t end = n if stop is None else stop
        if begin < 0:
            begin += n
            if begin < 0:
                begin = 0
        if end < 0:
            end += n
        if end > n:
            end = n
        for i in range(begin, end):
            if self._get(i) == value:
                return i
        raise ValueError(f"{value!r} is not in array")

    def remove(self, value):
        self._delete(self.index(value))

    def reverse(self):
        cdef list items = list(self)
        items.reverse()
        self._clear()
        self.extend(items)

    def sort(self, *, key=None, reverse=False):
        cdef list items = list(self)
        items.sort(key=key, reverse=reverse)
        self._clear()
        self.extend(items)


cdef class BoolArray(_ForyArray):
    cdef vector[uint8_t] _values

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return bool(<int>self._values[index])

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = _bool_to_byte(value)

    cpdef _append(self, object value):
        self._values.push_back(_bool_to_byte(value))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, _bool_to_byte(value))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        return bytearray(self)


cdef class Int8Array(_ForyArray):
    cdef vector[int8_t] _values
    typecode = "b"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <int>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = <int8_t>_check_int_value(value, -128, 127, "Int8Array")

    cpdef _append(self, object value):
        self._values.push_back(<int8_t>_check_int_value(value, -128, 127, "Int8Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, <int8_t>_check_int_value(value, -128, 127, "Int8Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        cdef object raw = _py_array.array("b")
        cdef Py_ssize_t i
        for i in range(self._size()):
            raw.append(self._values[i])
        return raw


cdef class Int16Array(_ForyArray):
    cdef vector[int16_t] _values
    typecode = "h"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <int>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = <int16_t>_check_int_value(value, -32768, 32767, "Int16Array")

    cpdef _append(self, object value):
        self._values.push_back(<int16_t>_check_int_value(value, -32768, 32767, "Int16Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, <int16_t>_check_int_value(value, -32768, 32767, "Int16Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        cdef object raw = _py_array.array("h")
        cdef Py_ssize_t i
        for i in range(self._size()):
            raw.append(self._values[i])
        return raw


cdef class Int32Array(_ForyArray):
    cdef vector[int32_t] _values
    typecode = "i"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <int>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = <int32_t>_check_int_value(value, -2147483648, 2147483647, "Int32Array")

    cpdef _append(self, object value):
        self._values.push_back(<int32_t>_check_int_value(value, -2147483648, 2147483647, "Int32Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, <int32_t>_check_int_value(value, -2147483648, 2147483647, "Int32Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        cdef object raw = _py_array.array("i")
        cdef Py_ssize_t i
        for i in range(self._size()):
            raw.append(self._values[i])
        return raw


cdef class Int64Array(_ForyArray):
    cdef vector[int64_t] _values
    typecode = "q"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <int64_t>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = <int64_t>_check_int_value(value, -9223372036854775808, 9223372036854775807, "Int64Array")

    cpdef _append(self, object value):
        self._values.push_back(<int64_t>_check_int_value(value, -9223372036854775808, 9223372036854775807, "Int64Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, <int64_t>_check_int_value(value, -9223372036854775808, 9223372036854775807, "Int64Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        cdef object raw = _py_array.array("q")
        cdef Py_ssize_t i
        for i in range(self._size()):
            raw.append(self._values[i])
        return raw


cdef class UInt8Array(_ForyArray):
    cdef vector[uint8_t] _values
    typecode = "B"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <int>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = <uint8_t>_check_int_value(value, 0, 255, "UInt8Array")

    cpdef _append(self, object value):
        self._values.push_back(<uint8_t>_check_int_value(value, 0, 255, "UInt8Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, <uint8_t>_check_int_value(value, 0, 255, "UInt8Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        return bytearray(self)


cdef class UInt16Array(_ForyArray):
    cdef vector[uint16_t] _values
    typecode = "H"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <int>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = <uint16_t>_check_int_value(value, 0, 65535, "UInt16Array")

    cpdef _append(self, object value):
        self._values.push_back(<uint16_t>_check_int_value(value, 0, 65535, "UInt16Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, <uint16_t>_check_int_value(value, 0, 65535, "UInt16Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        cdef object raw = _py_array.array("H")
        cdef Py_ssize_t i
        for i in range(self._size()):
            raw.append(self._values[i])
        return raw


cdef class UInt32Array(_ForyArray):
    cdef vector[uint32_t] _values
    typecode = "I"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <uint32_t>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = <uint32_t>_check_int_value(value, 0, 4294967295, "UInt32Array")

    cpdef _append(self, object value):
        self._values.push_back(<uint32_t>_check_int_value(value, 0, 4294967295, "UInt32Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, <uint32_t>_check_int_value(value, 0, 4294967295, "UInt32Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        cdef object raw = _py_array.array("I")
        cdef Py_ssize_t i
        for i in range(self._size()):
            raw.append(self._values[i])
        return raw


cdef class UInt64Array(_ForyArray):
    cdef vector[uint64_t] _values
    typecode = "Q"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <uint64_t>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = <uint64_t>_check_int_value(value, 0, 18446744073709551615, "UInt64Array")

    cpdef _append(self, object value):
        self._values.push_back(<uint64_t>_check_int_value(value, 0, 18446744073709551615, "UInt64Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, <uint64_t>_check_int_value(value, 0, 18446744073709551615, "UInt64Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        cdef object raw = _py_array.array("Q")
        cdef Py_ssize_t i
        for i in range(self._size()):
            raw.append(self._values[i])
        return raw


cdef class Float16Array(_ForyArray):
    cdef vector[uint16_t] _values

    @classmethod
    def from_buffer(cls, buffer):
        return _float16_array_from_buffer(buffer)

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return _float16_bits_to_float(self._values[index])

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = _coerce_float16_bits(value)

    cpdef _append(self, object value):
        self._values.push_back(_coerce_float16_bits(value))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, _coerce_float16_bits(value))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        return _float16_array_to_buffer(self)


cdef class BFloat16Array(_ForyArray):
    cdef vector[uint16_t] _values

    @classmethod
    def from_buffer(cls, buffer):
        return _bfloat16_array_from_buffer(buffer)

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return _bfloat16_bits_to_float(self._values[index])

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = _coerce_bfloat16_bits(value)

    cpdef _append(self, object value):
        self._values.push_back(_coerce_bfloat16_bits(value))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, _coerce_bfloat16_bits(value))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        return _bfloat16_array_to_buffer(self)


cdef class Float32Array(_ForyArray):
    cdef vector[float] _values
    typecode = "f"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <float>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = <float>_check_float_value(value, "Float32Array")

    cpdef _append(self, object value):
        self._values.push_back(<float>_check_float_value(value, "Float32Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, <float>_check_float_value(value, "Float32Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        cdef object raw = _py_array.array("f")
        cdef Py_ssize_t i
        for i in range(self._size()):
            raw.append(self._values[i])
        return raw


cdef class Float64Array(_ForyArray):
    cdef vector[double] _values
    typecode = "d"

    cpdef Py_ssize_t _size(self):
        return <Py_ssize_t>self._values.size()

    cpdef object _get(self, Py_ssize_t index):
        return <double>self._values[index]

    cpdef _set(self, Py_ssize_t index, object value):
        self._values[index] = _check_float_value(value, "Float64Array")

    cpdef _append(self, object value):
        self._values.push_back(_check_float_value(value, "Float64Array"))

    cpdef _insert(self, Py_ssize_t index, object value):
        self._values.insert(self._values.begin() + index, _check_float_value(value, "Float64Array"))

    cpdef _delete(self, Py_ssize_t index):
        self._values.erase(self._values.begin() + index)

    cpdef _clear(self):
        self._values.clear()

    def to_buffer(self):
        cdef object raw = _py_array.array("d")
        cdef Py_ssize_t i
        for i in range(self._size()):
            raw.append(self._values[i])
        return raw


for _array_type in (
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
):
    _MutableSequence.register(_array_type)


cdef inline uint16_t _float16_float_to_bits(float value):
    cdef uint32_t bits32
    cdef uint32_t sign
    cdef uint32_t exp
    cdef uint32_t mant
    cdef int32_t new_exp
    cdef uint32_t out_exp
    cdef uint32_t out_mant
    cdef uint32_t full_mant
    cdef int32_t net_shift
    cdef uint32_t round_bit
    cdef uint32_t sticky
    memcpy(&bits32, &value, sizeof(float))
    sign = (bits32 >> 16) & 0x8000
    exp = (bits32 >> 23) & 0xFF
    mant = bits32 & 0x7FFFFF

    if exp == 0xFF:
        out_exp = 0x1F
        if mant != 0:
            out_mant = 0x200 | ((mant >> 13) & 0x1FF)
            if out_mant == 0x200:
                out_mant = 0x201
        else:
            out_mant = 0
    elif exp == 0:
        out_exp = 0
        out_mant = 0
    else:
        new_exp = <int32_t>exp - 127 + 15
        if new_exp >= 31:
            out_exp = 0x1F
            out_mant = 0
        elif new_exp <= 0:
            full_mant = mant | 0x800000
            net_shift = 13 + 1 - new_exp
            if net_shift >= 24:
                out_exp = 0
                out_mant = 0
            else:
                out_exp = 0
                round_bit = (full_mant >> (net_shift - 1)) & 1
                sticky = full_mant & ((1 << (net_shift - 1)) - 1)
                out_mant = full_mant >> net_shift
                if round_bit == 1 and (sticky != 0 or (out_mant & 1) == 1):
                    out_mant += 1
        else:
            out_exp = <uint32_t>new_exp
            out_mant = mant >> 13
            round_bit = (mant >> 12) & 1
            sticky = mant & 0xFFF
            if round_bit == 1 and (sticky != 0 or (out_mant & 1) == 1):
                out_mant += 1
                if out_mant > 0x3FF:
                    out_mant = 0
                    out_exp += 1
                    if out_exp >= 31:
                        out_exp = 0x1F

    return <uint16_t>(sign | (out_exp << 10) | out_mant)


cdef inline float _float16_bits_to_float(uint16_t bits):
    cdef uint32_t sign = (<uint32_t>((bits >> 15) & 0x1)) << 31
    cdef uint32_t exp = (bits >> 10) & 0x1F
    cdef uint32_t mant = bits & 0x3FF
    cdef uint32_t out_bits = sign
    cdef int32_t shift = 0
    cdef float value

    if exp == 0x1F:
        out_bits |= 0xFF << 23
        if mant != 0:
            out_bits |= mant << 13
    elif exp == 0:
        if mant != 0:
            while (mant & 0x400) == 0:
                mant <<= 1
                shift += 1
            mant &= 0x3FF
            out_bits |= <uint32_t>(1 - 15 - shift + 127) << 23
            out_bits |= mant << 13
    else:
        out_bits |= <uint32_t>(exp - 15 + 127) << 23
        out_bits |= mant << 13

    memcpy(&value, &out_bits, sizeof(float))
    return value


cdef inline uint16_t _bfloat16_float_to_bits(float value):
    cdef uint32_t bits32
    cdef uint32_t lsb
    memcpy(&bits32, &value, sizeof(float))
    if (bits32 & 0x7F800000) == 0x7F800000 and (bits32 & 0x007FFFFF) != 0:
        return <uint16_t>0x7FC0
    lsb = (bits32 >> 16) & 1
    return <uint16_t>(((bits32 + 0x7FFF + lsb) >> 16) & 0xFFFF)


cdef inline float _bfloat16_bits_to_float(uint16_t bits):
    cdef uint32_t bits32 = (<uint32_t>bits) << 16
    cdef float value
    memcpy(&value, &bits32, sizeof(float))
    return value


cdef inline uint16_t _coerce_float16_bits(value):
    return _float16_float_to_bits(<float>value)


cdef inline uint16_t _coerce_bfloat16_bits(value):
    return _bfloat16_float_to_bits(<float>value)


cpdef uint16_t _float16_to_bits(object value):
    return _coerce_float16_bits(value)


cpdef float _float16_from_bits(object bits):
    return _float16_bits_to_float(<uint16_t>bits)


cpdef uint16_t _bfloat16_to_bits(object value):
    return _coerce_bfloat16_bits(value)


cpdef float _bfloat16_from_bits(object bits):
    return _bfloat16_bits_to_float(<uint16_t>bits)


cpdef object _float16_array_to_buffer(object values):
    cdef Float16Array array_values
    cdef object raw = _py_array.array("H")
    cdef object value
    cdef Py_ssize_t i
    if isinstance(values, Float16Array):
        array_values = <Float16Array>values
        for i in range(array_values._size()):
            raw.append(array_values._values[i])
        return raw
    for value in values:
        raw.append(_coerce_float16_bits(value))
    return raw


cpdef object _float16_array_from_buffer(object buffer):
    cdef bytes raw_bytes = bytes(buffer)
    cdef object raw = _py_array.array("H")
    cdef object bits
    cdef Float16Array values = Float16Array()
    if len(raw_bytes) & 1:
        raise ValueError("float16 bits payload size mismatch")
    raw.frombytes(raw_bytes)
    for bits in raw:
        values._values.push_back(<uint16_t>bits)
    return values


cpdef object _bfloat16_array_to_buffer(object values):
    cdef BFloat16Array array_values
    cdef object raw = _py_array.array("H")
    cdef object value
    cdef Py_ssize_t i
    if isinstance(values, BFloat16Array):
        array_values = <BFloat16Array>values
        for i in range(array_values._size()):
            raw.append(array_values._values[i])
        return raw
    for value in values:
        raw.append(_coerce_bfloat16_bits(value))
    return raw


cpdef object _bfloat16_array_from_buffer(object buffer):
    cdef bytes raw_bytes = bytes(buffer)
    cdef object raw = _py_array.array("H")
    cdef object bits
    cdef BFloat16Array values = BFloat16Array()
    if len(raw_bytes) & 1:
        raise ValueError("bfloat16 bits payload size mismatch")
    raw.frombytes(raw_bytes)
    for bits in raw:
        values._values.push_back(<uint16_t>bits)
    return values


@cython.final
cdef class Float16Serializer(Serializer):
    cpdef inline write(self, WriteContext write_context, value):
        write_context.write_uint16(_coerce_float16_bits(value))

    cpdef inline read(self, ReadContext read_context):
        return _float16_bits_to_float(read_context.read_uint16())


cdef inline uint32_t _array_payload_count(uint32_t payload_size, uint32_t item_size, str name) except *:
    if payload_size % item_size != 0:
        raise ValueError(f"{name} payload size mismatch")
    return payload_size // item_size


cdef inline void _write_uint8_vector(WriteContext write_context, vector[uint8_t]& values) except *:
    cdef uint32_t nbytes = <uint32_t>values.size()
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes > 0:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)


cdef inline void _read_uint8_vector(ReadContext read_context, vector[uint8_t]& values, str name) except *:
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    values.resize(payload_size)
    if payload_size > 0:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)


cdef inline void _write_int8_vector(WriteContext write_context, vector[int8_t]& values) except *:
    cdef uint32_t nbytes = <uint32_t>values.size()
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes > 0:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)


cdef inline void _read_int8_vector(ReadContext read_context, vector[int8_t]& values, str name) except *:
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    values.resize(payload_size)
    if payload_size > 0:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)


cdef inline void _write_int16_vector(WriteContext write_context, vector[int16_t]& values) except *:
    cdef uint32_t i
    cdef uint32_t count = <uint32_t>values.size()
    cdef uint32_t nbytes = count * sizeof(int16_t)
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes == 0:
        return
    if is_little_endian:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)
    else:
        for i in range(count):
            write_context.c_buffer.write_int16(values[i])


cdef inline void _read_int16_vector(ReadContext read_context, vector[int16_t]& values, str name) except *:
    cdef uint32_t i
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    cdef uint32_t count = _array_payload_count(payload_size, sizeof(int16_t), name)
    values.resize(count)
    if payload_size == 0:
        return
    if is_little_endian:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)
    else:
        for i in range(count):
            values[i] = read_context.c_buffer.read_int16(read_context.buffer._error)


cdef inline void _write_uint16_vector(WriteContext write_context, vector[uint16_t]& values) except *:
    cdef uint32_t i
    cdef uint32_t count = <uint32_t>values.size()
    cdef uint32_t nbytes = count * sizeof(uint16_t)
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes == 0:
        return
    if is_little_endian:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)
    else:
        for i in range(count):
            write_context.c_buffer.write_uint16(values[i])


cdef inline void _read_uint16_vector(ReadContext read_context, vector[uint16_t]& values, str name) except *:
    cdef uint32_t i
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    cdef uint32_t count = _array_payload_count(payload_size, sizeof(uint16_t), name)
    values.resize(count)
    if payload_size == 0:
        return
    if is_little_endian:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)
    else:
        for i in range(count):
            values[i] = read_context.c_buffer.read_uint16(read_context.buffer._error)


cdef inline void _write_int32_vector(WriteContext write_context, vector[int32_t]& values) except *:
    cdef uint32_t i
    cdef uint32_t count = <uint32_t>values.size()
    cdef uint32_t nbytes = count * sizeof(int32_t)
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes == 0:
        return
    if is_little_endian:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)
    else:
        for i in range(count):
            write_context.c_buffer.write_int32(values[i])


cdef inline void _read_int32_vector(ReadContext read_context, vector[int32_t]& values, str name) except *:
    cdef uint32_t i
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    cdef uint32_t count = _array_payload_count(payload_size, sizeof(int32_t), name)
    values.resize(count)
    if payload_size == 0:
        return
    if is_little_endian:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)
    else:
        for i in range(count):
            values[i] = read_context.c_buffer.read_int32(read_context.buffer._error)


cdef inline void _write_uint32_vector(WriteContext write_context, vector[uint32_t]& values) except *:
    cdef uint32_t i
    cdef uint32_t count = <uint32_t>values.size()
    cdef uint32_t nbytes = count * sizeof(uint32_t)
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes == 0:
        return
    if is_little_endian:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)
    else:
        for i in range(count):
            write_context.c_buffer.write_uint32(values[i])


cdef inline void _read_uint32_vector(ReadContext read_context, vector[uint32_t]& values, str name) except *:
    cdef uint32_t i
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    cdef uint32_t count = _array_payload_count(payload_size, sizeof(uint32_t), name)
    values.resize(count)
    if payload_size == 0:
        return
    if is_little_endian:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)
    else:
        for i in range(count):
            values[i] = read_context.c_buffer.read_uint32(read_context.buffer._error)


cdef inline void _write_int64_vector(WriteContext write_context, vector[int64_t]& values) except *:
    cdef uint32_t i
    cdef uint32_t count = <uint32_t>values.size()
    cdef uint32_t nbytes = count * sizeof(int64_t)
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes == 0:
        return
    if is_little_endian:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)
    else:
        for i in range(count):
            write_context.c_buffer.write_int64(values[i])


cdef inline void _read_int64_vector(ReadContext read_context, vector[int64_t]& values, str name) except *:
    cdef uint32_t i
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    cdef uint32_t count = _array_payload_count(payload_size, sizeof(int64_t), name)
    values.resize(count)
    if payload_size == 0:
        return
    if is_little_endian:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)
    else:
        for i in range(count):
            values[i] = read_context.c_buffer.read_int64(read_context.buffer._error)


cdef inline void _write_uint64_vector(WriteContext write_context, vector[uint64_t]& values) except *:
    cdef uint32_t i
    cdef uint32_t count = <uint32_t>values.size()
    cdef uint32_t nbytes = count * sizeof(uint64_t)
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes == 0:
        return
    if is_little_endian:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)
    else:
        for i in range(count):
            write_context.c_buffer.write_int64(<int64_t>values[i])


cdef inline void _read_uint64_vector(ReadContext read_context, vector[uint64_t]& values, str name) except *:
    cdef uint32_t i
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    cdef uint32_t count = _array_payload_count(payload_size, sizeof(uint64_t), name)
    values.resize(count)
    if payload_size == 0:
        return
    if is_little_endian:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)
    else:
        for i in range(count):
            values[i] = read_context.c_buffer.read_uint64(read_context.buffer._error)


cdef inline void _write_float_vector(WriteContext write_context, vector[float]& values) except *:
    cdef uint32_t i
    cdef uint32_t count = <uint32_t>values.size()
    cdef uint32_t nbytes = count * sizeof(float)
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes == 0:
        return
    if is_little_endian:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)
    else:
        for i in range(count):
            write_context.c_buffer.write_float(values[i])


cdef inline void _read_float_vector(ReadContext read_context, vector[float]& values, str name) except *:
    cdef uint32_t i
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    cdef uint32_t count = _array_payload_count(payload_size, sizeof(float), name)
    values.resize(count)
    if payload_size == 0:
        return
    if is_little_endian:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)
    else:
        for i in range(count):
            values[i] = read_context.c_buffer.read_float(read_context.buffer._error)


cdef inline void _write_double_vector(WriteContext write_context, vector[double]& values) except *:
    cdef uint32_t i
    cdef uint32_t count = <uint32_t>values.size()
    cdef uint32_t nbytes = count * sizeof(double)
    write_context.c_buffer.write_var_uint32(nbytes)
    if nbytes == 0:
        return
    if is_little_endian:
        write_context.c_buffer.write_bytes(<const void*>&values[0], nbytes)
    else:
        for i in range(count):
            write_context.c_buffer.write_double(values[i])


cdef inline void _read_double_vector(ReadContext read_context, vector[double]& values, str name) except *:
    cdef uint32_t i
    cdef uint32_t payload_size = read_context.c_buffer.read_var_uint32(read_context.buffer._error)
    cdef uint32_t count = _array_payload_count(payload_size, sizeof(double), name)
    values.resize(count)
    if payload_size == 0:
        return
    if is_little_endian:
        read_context.c_buffer.read_bytes(<void*>&values[0], payload_size, read_context.buffer._error)
    else:
        for i in range(count):
            values[i] = read_context.c_buffer.read_double(read_context.buffer._error)


cdef class _DenseArraySerializer(Serializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)
        self.need_to_write_ref = False


@cython.final
cdef class BoolArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef BoolArray safe
        if type(value) is not BoolArray:
            raise TypeError(f"BoolArray serializer requires BoolArray, got {type(value)!r}")
        safe = <BoolArray>value
        _write_uint8_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef BoolArray values = BoolArray()
        _read_uint8_vector(read_context, values._values, "BoolArray")
        return values


@cython.final
cdef class Int8ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef Int8Array safe
        if type(value) is not Int8Array:
            raise TypeError(f"Int8Array serializer requires Int8Array, got {type(value)!r}")
        safe = <Int8Array>value
        _write_int8_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef Int8Array values = Int8Array()
        _read_int8_vector(read_context, values._values, "Int8Array")
        return values


@cython.final
cdef class Int16ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef Int16Array safe
        if type(value) is not Int16Array:
            raise TypeError(f"Int16Array serializer requires Int16Array, got {type(value)!r}")
        safe = <Int16Array>value
        _write_int16_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef Int16Array values = Int16Array()
        _read_int16_vector(read_context, values._values, "Int16Array")
        return values


@cython.final
cdef class Int32ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef Int32Array safe
        if type(value) is not Int32Array:
            raise TypeError(f"Int32Array serializer requires Int32Array, got {type(value)!r}")
        safe = <Int32Array>value
        _write_int32_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef Int32Array values = Int32Array()
        _read_int32_vector(read_context, values._values, "Int32Array")
        return values


@cython.final
cdef class Int64ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef Int64Array safe
        if type(value) is not Int64Array:
            raise TypeError(f"Int64Array serializer requires Int64Array, got {type(value)!r}")
        safe = <Int64Array>value
        _write_int64_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef Int64Array values = Int64Array()
        _read_int64_vector(read_context, values._values, "Int64Array")
        return values


@cython.final
cdef class UInt8ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef UInt8Array safe
        if type(value) is not UInt8Array:
            raise TypeError(f"UInt8Array serializer requires UInt8Array, got {type(value)!r}")
        safe = <UInt8Array>value
        _write_uint8_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef UInt8Array values = UInt8Array()
        _read_uint8_vector(read_context, values._values, "UInt8Array")
        return values


@cython.final
cdef class UInt16ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef UInt16Array safe
        if type(value) is not UInt16Array:
            raise TypeError(f"UInt16Array serializer requires UInt16Array, got {type(value)!r}")
        safe = <UInt16Array>value
        _write_uint16_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef UInt16Array values = UInt16Array()
        _read_uint16_vector(read_context, values._values, "UInt16Array")
        return values


@cython.final
cdef class UInt32ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef UInt32Array safe
        if type(value) is not UInt32Array:
            raise TypeError(f"UInt32Array serializer requires UInt32Array, got {type(value)!r}")
        safe = <UInt32Array>value
        _write_uint32_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef UInt32Array values = UInt32Array()
        _read_uint32_vector(read_context, values._values, "UInt32Array")
        return values


@cython.final
cdef class UInt64ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef UInt64Array safe
        if type(value) is not UInt64Array:
            raise TypeError(f"UInt64Array serializer requires UInt64Array, got {type(value)!r}")
        safe = <UInt64Array>value
        _write_uint64_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef UInt64Array values = UInt64Array()
        _read_uint64_vector(read_context, values._values, "UInt64Array")
        return values


@cython.final
cdef class Float16ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef Float16Array safe
        if type(value) is not Float16Array:
            raise TypeError(f"Float16Array serializer requires Float16Array, got {type(value)!r}")
        safe = <Float16Array>value
        _write_uint16_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef Float16Array values = Float16Array()
        _read_uint16_vector(read_context, values._values, "Float16Array")
        return values


@cython.final
cdef class BFloat16Serializer(Serializer):
    cpdef inline write(self, WriteContext write_context, value):
        write_context.write_uint16(_coerce_bfloat16_bits(value))

    cpdef inline read(self, ReadContext read_context):
        return _bfloat16_bits_to_float(read_context.read_uint16())


@cython.final
cdef class BFloat16ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef BFloat16Array safe
        if type(value) is not BFloat16Array:
            raise TypeError(f"BFloat16Array serializer requires BFloat16Array, got {type(value)!r}")
        safe = <BFloat16Array>value
        _write_uint16_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef BFloat16Array values = BFloat16Array()
        _read_uint16_vector(read_context, values._values, "BFloat16Array")
        return values


@cython.final
cdef class Float32ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef Float32Array safe
        if type(value) is not Float32Array:
            raise TypeError(f"Float32Array serializer requires Float32Array, got {type(value)!r}")
        safe = <Float32Array>value
        _write_float_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef Float32Array values = Float32Array()
        _read_float_vector(read_context, values._values, "Float32Array")
        return values


@cython.final
cdef class Float64ArraySerializer(_DenseArraySerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)

    cpdef write(self, WriteContext write_context, value):
        cdef Float64Array safe
        if type(value) is not Float64Array:
            raise TypeError(f"Float64Array serializer requires Float64Array, got {type(value)!r}")
        safe = <Float64Array>value
        _write_double_vector(write_context, safe._values)

    cpdef read(self, ReadContext read_context):
        cdef Float64Array values = Float64Array()
        _read_double_vector(read_context, values._values, "Float64Array")
        return values
