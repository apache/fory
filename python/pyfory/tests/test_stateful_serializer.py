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
from pyfory import Fory, Language
from pyfory.serializer import StatefulSerializer


class BasicStatefulObject:
    """Basic object with __getstate__ and __setstate__"""

    def __init__(self, value, secret=None):
        self.value = value
        self.secret = secret or "default_secret"
        self.computed = self.value * 2

    def __getstate__(self):
        # Only serialize value, not secret or computed
        return {"value": self.value}

    def __setstate__(self, state):
        self.value = state["value"]
        self.secret = "restored_secret"
        self.computed = self.value * 2

    def __eq__(self, other):
        return isinstance(other, BasicStatefulObject) and self.value == other.value and self.computed == other.computed
        # Note: secret is expected to be different after deserialization


class ImmutableWithArgsEx:
    """Object with __getnewargs_ex__ and state methods"""

    def __init__(self, x, y, name="default"):
        self._x = x
        self._y = y
        self._name = name

    def __getnewargs_ex__(self):
        return (self._x, self._y), {"name": self._name}

    def __getstate__(self):
        return {"extra_data": "some_extra"}

    def __setstate__(self, state):
        self._extra = state.get("extra_data", "none")

    def __eq__(self, other):
        if not isinstance(other, ImmutableWithArgsEx):
            return False
        return (
            self._x == other._x
            and self._y == other._y
            and self._name == other._name
            and getattr(self, "_extra", None) == getattr(other, "_extra", None)
        )


class ImmutableWithArgs:
    """Object with __getnewargs__ (old style) and state methods"""

    def __init__(self, a, b):
        self._a = a
        self._b = b

    def __getnewargs__(self):
        return self._a, self._b

    def __getstate__(self):
        return {"metadata": "old_style"}

    def __setstate__(self, state):
        self._metadata = state.get("metadata", "none")

    def __eq__(self, other):
        if not isinstance(other, ImmutableWithArgs):
            return False
        return self._a == other._a and self._b == other._b and getattr(self, "_metadata", None) == getattr(other, "_metadata", None)


class StatefulOnlyObject:
    """Object with only __getstate__ and __setstate__, no constructor args"""

    def __init__(self, data):
        self.data = data
        self.processed = f"processed_{data}"

    def __getstate__(self):
        return {"data": self.data}

    def __setstate__(self, state):
        self.data = state["data"]
        self.processed = f"restored_{self.data}"

    def __eq__(self, other):
        if not isinstance(other, StatefulOnlyObject):
            return False
        return self.data == other.data and self.processed == other.processed


class ComplexStateObject:
    """Object with a complex state including nested objects"""

    def __init__(self, name, items=None):
        self.name = name
        self.items = items or []
        self.count = len(self.items)

    def __getstate__(self):
        return {"name": self.name, "items": self.items, "extra_info": {"serialized_at": "test_time"}}

    def __setstate__(self, state):
        self.name = state["name"]
        self.items = state["items"]
        self.count = len(self.items)
        self.extra_info = state.get("extra_info", {})

    def __eq__(self, other):
        if not isinstance(other, ComplexStateObject):
            return False
        return (
            self.name == other.name
            and self.items == other.items
            and self.count == other.count
            and getattr(self, "extra_info", {}) == getattr(other, "extra_info", {})
        )


def test_basic_stateful_object():
    """Test basic object with __getstate__ and __setstate__"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = BasicStatefulObject(42, "original_secret")
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    # Check that StatefulSerializer is being used
    serializer = fory.type_resolver.get_serializer(BasicStatefulObject)
    assert isinstance(serializer, StatefulSerializer)

    # Check deserialization correctness
    assert deserialized.value == 42
    assert deserialized.secret == "restored_secret"  # Changed by __setstate__
    assert deserialized.computed == 84
    assert obj == deserialized


def test_immutable_with_getnewargs_ex():
    """Test object with __getnewargs_ex__"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = ImmutableWithArgsEx(10, 20, "test")
    # Simulate the state that would be set by __setstate__ for comparison
    obj._extra = "some_extra"

    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    # Check that StatefulSerializer is being used
    serializer = fory.type_resolver.get_serializer(ImmutableWithArgsEx)
    assert isinstance(serializer, StatefulSerializer)

    # Check that constructor arguments were used correctly
    assert deserialized._x == 10
    assert deserialized._y == 20
    assert deserialized._name == "test"
    assert deserialized._extra == "some_extra"  # Set by __setstate__
    assert obj == deserialized


def test_immutable_with_getnewargs():
    """Test object with __getnewargs__ (old style)"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = ImmutableWithArgs(100, 200)
    # Simulate the state that would be set by __setstate__ for comparison
    obj._metadata = "old_style"

    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    # Check that StatefulSerializer is being used
    serializer = fory.type_resolver.get_serializer(ImmutableWithArgs)
    assert isinstance(serializer, StatefulSerializer)

    # Check that constructor arguments were used correctly
    assert deserialized._a == 100
    assert deserialized._b == 200
    assert deserialized._metadata == "old_style"  # Set by __setstate__
    assert obj == deserialized


def test_stateful_only_object():
    """Test object with only state methods, no constructor args"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = StatefulOnlyObject("test_data")
    # Simulate the state that would be set by __setstate__ for comparison
    obj.processed = "restored_test_data"

    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    # Check that StatefulSerializer is being used
    serializer = fory.type_resolver.get_serializer(StatefulOnlyObject)
    assert isinstance(serializer, StatefulSerializer)

    # Check deserialization correctness
    assert deserialized.data == "test_data"
    assert deserialized.processed == "restored_test_data"  # Changed by __setstate__
    assert obj == deserialized


def test_complex_state_object():
    """Test object with a complex nested state"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = ComplexStateObject("test", [1, 2, 3, {"nested": "value"}])
    # Simulate the state that would be set by __setstate__ for comparison
    obj.extra_info = {"serialized_at": "test_time"}

    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    # Check that StatefulSerializer is being used
    serializer = fory.type_resolver.get_serializer(ComplexStateObject)
    assert isinstance(serializer, StatefulSerializer)

    # Check deserialization correctness
    assert deserialized.name == "test"
    assert deserialized.items == [1, 2, 3, {"nested": "value"}]
    assert deserialized.count == 4
    assert deserialized.extra_info == {"serialized_at": "test_time"}
    assert obj == deserialized


def test_reference_tracking():
    """Test that reference tracking works with StatefulSerializer"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = BasicStatefulObject(42)
    # Create a list with the same object referenced twice
    container = [obj, obj, {"ref": obj}]

    serialized = fory.serialize(container)
    deserialized = fory.deserialize(serialized)

    # Check that references are preserved
    assert deserialized[0] is deserialized[1]
    assert deserialized[0] is deserialized[2]["ref"]
    assert deserialized[0].value == 42


def test_nested_stateful_objects():
    """Test serialization of nested stateful objects"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    inner = BasicStatefulObject(10)
    outer = ComplexStateObject("outer", [inner, BasicStatefulObject(20)])

    serialized = fory.serialize(outer)
    deserialized = fory.deserialize(serialized)

    # Check that nested objects are correctly deserialized
    assert deserialized.name == "outer"
    assert len(deserialized.items) == 2
    assert isinstance(deserialized.items[0], BasicStatefulObject)
    assert isinstance(deserialized.items[1], BasicStatefulObject)
    assert deserialized.items[0].value == 10
    assert deserialized.items[1].value == 20


def test_cross_language_compatibility():
    """Test that StatefulSerializer works with type registration"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=True)

    # Register the type explicitly
    fory.register_type(BasicStatefulObject)

    obj = BasicStatefulObject(42)
    serialized = fory.serialize(obj)

    # Deserialize with a new Fory instance that also has the type registered
    fory_new = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=True)
    fory_new.register_type(BasicStatefulObject)
    deserialized = fory_new.deserialize(serialized)

    assert deserialized.value == 42
    assert obj == deserialized


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
