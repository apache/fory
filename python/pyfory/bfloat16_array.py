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

import array

from pyfory.bfloat16 import bfloat16


class BFloat16Array:
    def __init__(self, values=None):
        if values is None:
            self._data = array.array("H")
        else:
            self._data = array.array("H", [bfloat16(v).to_bits() if not isinstance(v, bfloat16) else v.to_bits() for v in values])

    def __len__(self):
        return len(self._data)

    def __getitem__(self, index):
        return bfloat16.from_bits(self._data[index])

    def __setitem__(self, index, value):
        if isinstance(value, bfloat16):
            self._data[index] = value.to_bits()
        else:
            self._data[index] = bfloat16(value).to_bits()

    def __iter__(self):
        for bits in self._data:
            yield bfloat16.from_bits(bits)

    def __repr__(self):
        return f"BFloat16Array([{', '.join(str(bf16) for bf16 in self)}])"

    def __eq__(self, other):
        if not isinstance(other, BFloat16Array):
            return False
        return self._data == other._data

    def append(self, value):
        if isinstance(value, bfloat16):
            self._data.append(value.to_bits())
        else:
            self._data.append(bfloat16(value).to_bits())

    def extend(self, values):
        for value in values:
            self.append(value)

    @property
    def itemsize(self):
        return 2

    def tobytes(self):
        return self._data.tobytes()

    @classmethod
    def frombytes(cls, data):
        arr = cls()
        arr._data = array.array("H")
        arr._data.frombytes(data)
        return arr
