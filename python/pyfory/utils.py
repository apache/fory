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

import sys


is_little_endian = sys.byteorder == "little"


def get_bit(buffer, base_offset, index):
    from pyfory.serialization import get_bit as _get_bit

    return _get_bit(buffer, base_offset, index)


def set_bit(buffer, base_offset, index):
    from pyfory.serialization import set_bit as _set_bit

    return _set_bit(buffer, base_offset, index)


def clear_bit(buffer, base_offset, index):
    from pyfory.serialization import clear_bit as _clear_bit

    return _clear_bit(buffer, base_offset, index)


def set_bit_to(buffer, base_offset, index, bit_is_set):
    from pyfory.serialization import set_bit_to as _set_bit_to

    return _set_bit_to(buffer, base_offset, index, bit_is_set)


__all__ = [
    "get_bit",
    "set_bit",
    "clear_bit",
    "set_bit_to",
    "is_little_endian",
]
