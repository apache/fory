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

"""
Tests for field shadowing in inheritance.
"""

from dataclasses import dataclass
from pyfory import Fory
from pyfory.meta.typedef_encoder import encode_typedef


@dataclass
class Parent:
    name: str
    value: int


@dataclass
class ChildWithShadow(Parent):
    name: str  # Shadows Parent.name
    extra: float


def test_shadowed_fields_serialization():
    """Serialization with shadowed and inherited fields."""
    fory = Fory(xlang=True)
    fory.register(Parent, namespace="test", typename="Parent")
    fory.register(ChildWithShadow, namespace="test", typename="ChildWithShadow")

    # Verify TypeDef has exactly 3 fields (no duplicate 'name')
    typedef = encode_typedef(fory.type_resolver, ChildWithShadow)
    assert len(typedef.fields) == 3

    obj = ChildWithShadow(name="shadowed", value=10, extra=3.14)
    data = fory.serialize(obj)
    result = fory.deserialize(data)

    assert result.name == "shadowed"
    assert result.value == 10  # inherited field
    assert result.extra == 3.14
