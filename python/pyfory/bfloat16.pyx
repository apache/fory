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

# distutils: language = c++
# cython: embedsignature = True
# cython: language_level = 3

from libc.stdint cimport uint16_t, uint32_t
from libc.string cimport memcpy

cdef inline uint16_t float32_to_bfloat16_bits(float value) nogil:
    cdef uint32_t f32_bits
    memcpy(&f32_bits, &value, 4)
    cdef uint16_t bf16_bits = <uint16_t>(f32_bits >> 16)
    cdef uint16_t truncated = <uint16_t>(f32_bits & 0xFFFF)
    if truncated > 0x8000:
        bf16_bits += 1
        if (bf16_bits & 0x7F80) == 0x7F80:
            bf16_bits = (bf16_bits & 0x8000) | 0x7F80
    elif truncated == 0x8000 and (bf16_bits & 1):
        bf16_bits += 1
        if (bf16_bits & 0x7F80) == 0x7F80:
            bf16_bits = (bf16_bits & 0x8000) | 0x7F80
    return bf16_bits

cdef inline float bfloat16_bits_to_float32(uint16_t bits) nogil:
    cdef uint32_t f32_bits = <uint32_t>bits << 16
    cdef float result
    memcpy(&result, &f32_bits, 4)
    return result


cdef class bfloat16:
    cdef uint16_t _bits
    
    def __init__(self, value):
        if isinstance(value, bfloat16):
            self._bits = (<bfloat16>value)._bits
        else:
            self._bits = float32_to_bfloat16_bits(<float>float(value))
    
    @staticmethod
    cpdef bfloat16 from_bits(uint16_t bits):
        cdef bfloat16 bf16 = bfloat16.__new__(bfloat16)
        bf16._bits = bits
        return bf16
    
    cpdef uint16_t to_bits(self):
        return self._bits
    
    def to_float32(self):
        return bfloat16_bits_to_float32(self._bits)
    
    def __float__(self):
        return float(self.to_float32())
    
    def __repr__(self):
        return f"bfloat16({self.to_float32()})"
    
    def __str__(self):
        return str(self.to_float32())
    
    def __eq__(self, other):
        if isinstance(other, bfloat16):
            if self.is_nan() or (<bfloat16>other).is_nan():
                return False
            if self.is_zero() and (<bfloat16>other).is_zero():
                return True
            return self._bits == (<bfloat16>other)._bits
        return False
    
    def __hash__(self):
        return hash(self._bits)
    
    def is_nan(self):
        cdef uint16_t exp = (self._bits >> 7) & 0xFF
        cdef uint16_t mant = self._bits & 0x7F
        return exp == 0xFF and mant != 0
    
    def is_inf(self):
        cdef uint16_t exp = (self._bits >> 7) & 0xFF
        cdef uint16_t mant = self._bits & 0x7F
        return exp == 0xFF and mant == 0
    
    def is_zero(self):
        return (self._bits & 0x7FFF) == 0
    
    def is_finite(self):
        cdef uint16_t exp = (self._bits >> 7) & 0xFF
        return exp != 0xFF
    
    def is_normal(self):
        cdef uint16_t exp = (self._bits >> 7) & 0xFF
        return exp != 0 and exp != 0xFF
    
    def is_subnormal(self):
        cdef uint16_t exp = (self._bits >> 7) & 0xFF
        cdef uint16_t mant = self._bits & 0x7F
        return exp == 0 and mant != 0
    
    def signbit(self):
        return (self._bits & 0x8000) != 0


# Backward-compatible alias for existing user code.
BFloat16 = bfloat16
