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

from pyfory import Fory


@pytest.mark.parametrize("xlang", [False, True])
def test_root_header_rejects_reserved_bits(xlang):
    fory = Fory(xlang=xlang, compatible=xlang)
    data = bytearray(fory.serialize(1))
    data[0] |= 0x04

    with pytest.raises(ValueError, match="Unsupported root header bitmap"):
        fory.deserialize(bytes(data))


@pytest.mark.parametrize("xlang", [False, True])
def test_root_header_rejects_xlang_mismatch(xlang):
    fory = Fory(xlang=xlang, compatible=xlang)
    data = bytearray(fory.serialize(1))
    data[0] ^= 0x01

    with pytest.raises(ValueError, match="xlang bit"):
        fory.deserialize(bytes(data))


@pytest.mark.parametrize("xlang", [False, True])
def test_root_header_oob_flag_requires_buffers(xlang):
    fory = Fory(xlang=xlang, compatible=xlang)
    data = bytearray(fory.serialize(1))
    data[0] |= 0x02

    with pytest.raises(ValueError, match="Out-of-band buffers are required"):
        fory.deserialize(bytes(data))


@pytest.mark.parametrize("xlang", [False, True])
def test_root_header_rejects_buffers_without_oob_flag(xlang):
    fory = Fory(xlang=xlang, compatible=xlang)
    data = fory.serialize(1)

    with pytest.raises(ValueError, match="Out-of-band buffers were provided"):
        fory.deserialize(data, buffers=[])
