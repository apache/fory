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

from pyfory import Fory, Language


# Test class with __getstate__ and __setstate__
class StatefulObject:
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
        return isinstance(other, StatefulObject) and self.value == other.value and self.computed == other.computed
        # Note: secret is expected to be different after deserialization

    def __repr__(self):
        return f"StatefulObject(value={self.value}, secret={self.secret}, computed={self.computed})"


# Test class with getnewargs_ex
class ImmutableWithArgs:
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
        return (
            isinstance(other, ImmutableWithArgs)
            and self._x == other._x
            and self._y == other._y
            and self._name == other._name
            and getattr(self, "_extra", None) == getattr(other, "_extra", None)
        )

    def __repr__(self):
        return f"ImmutableWithArgs(x={self._x}, y={self._y}, name={self._name}, extra={getattr(self, '_extra', None)})"


# Test class with getnewargs (older style)
class ImmutableOldStyle:
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
        return (
            isinstance(other, ImmutableOldStyle)
            and self._a == other._a
            and self._b == other._b
            and getattr(self, "_metadata", None) == getattr(other, "_metadata", None)
        )

    def __repr__(self):
        return f"ImmutableOldStyle(a={self._a}, b={self._b}, metadata={getattr(self, '_metadata', None)})"


def test_current_behavior():
    print("Testing current behavior with stateful objects...")

    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    # Test basic stateful object
    obj1 = StatefulObject(42, "original_secret")
    print(f"Original: {obj1}")

    serialized = fory.serialize(obj1)
    deserialized = fory.deserialize(serialized)
    print(f"Deserialized: {deserialized}")
    print(f"Equal: {obj1 == deserialized}")
    print()

    # Test with getnewargs_ex
    obj2 = ImmutableWithArgs(10, 20, "test")
    print(f"Original: {obj2}")

    serialized2 = fory.serialize(obj2)
    deserialized2 = fory.deserialize(serialized2)
    print(f"Deserialized attributes: {dir(deserialized2)}")
    print(f"Deserialized vars: {vars(deserialized2)}")
    try:
        print(f"Deserialized: {deserialized2}")
        print(f"Equal: {obj2 == deserialized2}")
    except Exception as e:
        print(f"Error in repr/comparison: {e}")
    print()

    # Test with getnewargs (old style)
    obj3 = ImmutableOldStyle(100, 200)
    print(f"Original: {obj3}")

    serialized3 = fory.serialize(obj3)
    deserialized3 = fory.deserialize(serialized3)
    print(f"Deserialized: {deserialized3}")
    print(f"Equal: {obj3 == deserialized3}")
    print()

    # Check what serializer is being used
    serializer1 = fory.type_resolver.get_serializer(StatefulObject)
    serializer2 = fory.type_resolver.get_serializer(ImmutableWithArgs)
    serializer3 = fory.type_resolver.get_serializer(ImmutableOldStyle)

    print(f"StatefulObject serializer: {type(serializer1)}")
    print(f"ImmutableWithArgs serializer: {type(serializer2)}")
    print(f"ImmutableOldStyle serializer: {type(serializer3)}")


if __name__ == "__main__":
    test_current_behavior()
