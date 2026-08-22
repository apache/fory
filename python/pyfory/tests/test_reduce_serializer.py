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

import os
import types

import pyfory.serializer as serializer_module
import pytest

from pyfory import DeserializationPolicy, Fory
from pyfory.error import TypeUnregisteredError
from pyfory.serializer import ReduceSerializer


_reduce_factory_calls = []
_class_callable_calls = []
_function_factory_calls = []


def _unregistered_reduce_factory(value):
    _reduce_factory_calls.append(value)
    return NestedGlobalReduce("result", value)


class NestedGlobalReduce:
    """Encode a global callable through a nested value of this registered type."""

    def __init__(self, mode, value):
        self.mode = mode
        self.value = value

    def __reduce__(self):
        if self.mode == "global":
            return f"{__name__}._unregistered_reduce_factory"
        if self.mode == "outer":
            return NestedGlobalReduce("global", None), (self.value,)
        return self.__class__, (self.mode, self.value)

    def __eq__(self, other):
        return isinstance(other, NestedGlobalReduce) and (self.mode, self.value) == (
            other.mode,
            other.value,
        )


class UnregisteredClassCallable:
    def __init__(self):
        _class_callable_calls.append(True)


class ClassCallableReduce:
    def __reduce__(self):
        return UnregisteredClassCallable, ()


def _function_reduce_factory(value):
    _function_factory_calls.append(value)
    return FunctionCallableReduce(value)


class FunctionCallableReduce:
    def __init__(self, value):
        self.value = value

    def __reduce__(self):
        return _function_reduce_factory, (self.value,)

    def __eq__(self, other):
        return isinstance(other, FunctionCallableReduce) and self.value == other.value


class NativeCallableReduce:
    def __reduce__(self):
        return os.system, ()


class BoundReduceFactory:
    calls = 0

    def build(self):
        type(self).calls += 1
        return BoundMethodReduce()


_bound_reduce_factory = BoundReduceFactory()


class BoundMethodReduce:
    def __reduce__(self):
        return _bound_reduce_factory.build, ()


class CallableFactory:
    calls = 0

    def __call__(self):
        type(self).calls += 1
        return CallableInstanceReduce()


_callable_factory = CallableFactory()


class CallableInstanceReduce:
    def __reduce__(self):
        return _callable_factory, ()


class BasicReduceObject:
    """Object that implements __reduce__ returning (callable, args)"""

    def __init__(self, value, multiplier=1):
        self.value = value
        self.multiplier = multiplier

    def __reduce__(self):
        return self.__class__, (self.value, self.multiplier)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.value == other.value and self.multiplier == other.multiplier


class ReduceWithStateObject:
    """Object that implements __reduce__ returning (callable, args, state)"""

    def __init__(self, name, data=None):
        self.name = name
        self.data = data or {}
        self.secret = "hidden"

    def __reduce__(self):
        # Return (callable, args, state)
        return self.__class__, (self.name,), {"data": self.data, "secret": self.secret}

    def __setstate__(self, state):
        self.data = state["data"]
        self.secret = state["secret"]

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.name == other.name and self.data == other.data and self.secret == other.secret


class ReduceExObject:
    """Object that implements __reduce_ex__"""

    def __init__(self, x, y):
        self.x = x
        self.y = y
        self.computed = x * y

    def __reduce_ex__(self, protocol):
        return self.__class__, (self.x, self.y)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.x == other.x and self.y == other.y and self.computed == other.computed


class ReduceWithListItems:
    """Object that implements __reduce__ with list items"""

    def __init__(self, initial_items=None):
        self.items = list(initial_items or [])
        self.metadata = "test"

    def __reduce__(self):
        # Return (callable, args, state, listitems)
        return self.__class__, (), {"metadata": self.metadata}, iter(self.items)

    def __setstate__(self, state):
        self.metadata = state["metadata"]

    def extend(self, items):
        self.items.extend(items)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.items == other.items and self.metadata == other.metadata


class ReduceWithDictItems:
    """Object that implements __reduce__ with dict items"""

    def __init__(self, initial_dict=None):
        self.data = dict(initial_dict or {})
        self.name = "dict_obj"

    def __reduce__(self):
        # Return (callable, args, state, listitems, dictitems)
        return self.__class__, (), {"name": self.name}, None, iter(self.data.items())

    def __setstate__(self, state):
        self.name = state["name"]

    def __setitem__(self, key, value):
        self.data[key] = value

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.data == other.data and self.name == other.name


class BothReduceAndStateful:
    """Object that has both __reduce__ and __getstate__/__setstate__
    Should use ReduceSerializer due to a higher precedence"""

    def __init__(self, value):
        self.value = value
        self.reduce_used = False
        self.state_used = False

    def __reduce__(self):
        self.reduce_used = True
        return self.__class__, (self.value,)

    def __getstate__(self):
        self.state_used = True
        return {"value": self.value, "state_used": True}

    def __setstate__(self, state):
        self.value = state["value"]
        self.state_used = state.get("state_used", False)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.value == other.value


def _nested_reduce_data():
    writer = Fory(xlang=False, ref=True, strict=True, compatible=False)
    writer.register_type(NestedGlobalReduce)
    return writer.serialize(NestedGlobalReduce("outer", "allowed"))


def test_strict_reduce_global(monkeypatch):
    _reduce_factory_calls.clear()
    reader = Fory(xlang=False, ref=True, strict=True, compatible=False)
    reader.register_type(NestedGlobalReduce)
    data = _nested_reduce_data()

    def unexpected_resolution(*args, **kwargs):
        raise AssertionError("strict default policy resolved an unregistered global")

    monkeypatch.setattr(
        serializer_module,
        "_resolve_validated_module_attr",
        unexpected_resolution,
    )

    with pytest.raises(TypeUnregisteredError, match="Reduce global value"):
        reader.deserialize(data)

    assert _reduce_factory_calls == []


def test_policy_reduce_global():
    class AllowFactoryPolicy(DeserializationPolicy):
        def __init__(self):
            self.functions = []

        def validate_function(self, func, is_local, **kwargs):
            if func is not _unregistered_reduce_factory:
                raise ValueError(f"Unexpected reduce function {func}")
            self.functions.append((func, is_local))

    _reduce_factory_calls.clear()
    policy = AllowFactoryPolicy()
    reader = Fory(
        xlang=False,
        ref=True,
        strict=True,
        compatible=False,
        policy=policy,
    )
    reader.register_type(NestedGlobalReduce)

    assert reader.deserialize(_nested_reduce_data()) == NestedGlobalReduce(
        "result",
        "allowed",
    )
    assert policy.functions == [(_unregistered_reduce_factory, False)]
    assert _reduce_factory_calls == ["allowed"]


def test_nonstrict_reduce_global():
    _reduce_factory_calls.clear()
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    assert fory.deserialize(fory.serialize(NestedGlobalReduce("outer", "allowed"))) == NestedGlobalReduce("result", "allowed")
    assert _reduce_factory_calls == ["allowed"]


def test_strict_reduce_roundtrip():
    fory = Fory(xlang=False, ref=True, strict=True, compatible=False)
    fory.register_type(type)
    fory.register_type(BasicReduceObject)
    obj = BasicReduceObject(42, 3)

    assert fory.deserialize(fory.serialize(obj)) == obj


def test_strict_reduce_class(monkeypatch):
    writer = Fory(xlang=False, ref=True, strict=True, compatible=False)
    writer.register_type(type)
    writer.register_type(ClassCallableReduce)
    data = writer.serialize(ClassCallableReduce())

    reader = Fory(xlang=False, ref=True, strict=True, compatible=False)
    reader.register_type(type)
    reader.register_type(ClassCallableReduce)

    def unexpected_resolution(*args, **kwargs):
        raise AssertionError("strict default policy resolved an unregistered class")

    monkeypatch.setattr(
        serializer_module,
        "_resolve_validated_module_qualname",
        unexpected_resolution,
    )
    _class_callable_calls.clear()
    with pytest.raises(TypeUnregisteredError, match="is not registered"):
        reader.deserialize(data)
    assert _class_callable_calls == []


def test_strict_reduce_function(monkeypatch):
    writer = Fory(xlang=False, ref=True, strict=True, compatible=False)
    writer.register_type(types.FunctionType)
    writer.register_type(FunctionCallableReduce)
    data = writer.serialize(FunctionCallableReduce("blocked"))

    reader = Fory(xlang=False, ref=True, strict=True, compatible=False)
    reader.register_type(types.FunctionType)
    reader.register_type(FunctionCallableReduce)

    def unexpected_resolution(*args, **kwargs):
        raise AssertionError("strict default policy resolved an unregistered function")

    monkeypatch.setattr(
        serializer_module,
        "_resolve_validated_module_qualname",
        unexpected_resolution,
    )
    _function_factory_calls.clear()
    with pytest.raises(TypeUnregisteredError, match="carrier"):
        reader.deserialize(data)
    assert _function_factory_calls == []


def test_policy_reduce_function():
    class AllowFunctionPolicy(DeserializationPolicy):
        def __init__(self):
            self.functions = []

        def validate_function(self, func, is_local, **kwargs):
            self.functions.append((func, is_local))

    writer = Fory(xlang=False, ref=True, strict=True, compatible=False)
    writer.register_type(types.FunctionType)
    writer.register_type(FunctionCallableReduce)
    data = writer.serialize(FunctionCallableReduce("allowed"))

    policy = AllowFunctionPolicy()
    reader = Fory(
        xlang=False,
        ref=True,
        strict=True,
        policy=policy,
        compatible=False,
    )
    reader.register_type(types.FunctionType)
    reader.register_type(FunctionCallableReduce)
    _function_factory_calls.clear()

    assert reader.deserialize(data) == FunctionCallableReduce("allowed")
    assert policy.functions == [(_function_reduce_factory, False)]
    assert _function_factory_calls == ["allowed"]


def test_nonstrict_reduce_function():
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)
    _function_factory_calls.clear()

    value = FunctionCallableReduce("allowed")
    assert fory.deserialize(fory.serialize(value)) == value
    assert _function_factory_calls == ["allowed"]


def test_strict_reduce_native(monkeypatch):
    writer = Fory(xlang=False, ref=True, strict=True, compatible=False)
    writer.register_type(type(os.system))
    writer.register_type(NativeCallableReduce)
    data = writer.serialize(NativeCallableReduce())

    reader = Fory(xlang=False, ref=True, strict=True, compatible=False)
    reader.register_type(type(os.system))
    reader.register_type(NativeCallableReduce)

    def unexpected_resolution(*args, **kwargs):
        raise AssertionError("strict default policy resolved a native function")

    monkeypatch.setattr(
        serializer_module,
        "_resolve_validated_module_attr",
        unexpected_resolution,
    )
    with pytest.raises(TypeUnregisteredError, match="callable"):
        reader.deserialize(data)


def test_strict_reduce_bound_method():
    writer = Fory(xlang=False, ref=True, strict=True, compatible=False)
    writer.register_type(types.MethodType)
    writer.register_type(BoundReduceFactory)
    writer.register_type(BoundMethodReduce)
    data = writer.serialize(BoundMethodReduce())

    reader = Fory(xlang=False, ref=True, strict=True, compatible=False)
    reader.register_type(types.MethodType)
    reader.register_type(BoundReduceFactory)
    reader.register_type(BoundMethodReduce)
    BoundReduceFactory.calls = 0

    with pytest.raises(TypeUnregisteredError, match="carrier"):
        reader.deserialize(data)
    assert BoundReduceFactory.calls == 0


def test_strict_callable_instance():
    writer = Fory(xlang=False, ref=True, strict=True, compatible=False)
    writer.register_type(CallableFactory)
    writer.register_type(CallableInstanceReduce)
    data = writer.serialize(CallableInstanceReduce())

    reader = Fory(xlang=False, ref=True, strict=True, compatible=False)
    reader.register_type(CallableFactory)
    reader.register_type(CallableInstanceReduce)
    CallableFactory.calls = 0

    with pytest.raises(TypeUnregisteredError, match="reduce type"):
        reader.deserialize(data)
    assert CallableFactory.calls == 0


def test_basic_reduce_object():
    """Test basic __reduce__ functionality"""
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    obj = BasicReduceObject(42, 3)

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(BasicReduceObject)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.value == 42
    assert deserialized.multiplier == 3


def test_reduce_with_state_object():
    """Test __reduce__ with state"""
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    obj = ReduceWithStateObject("test", {"key": "value"})

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(ReduceWithStateObject)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.name == "test"
    assert deserialized.data == {"key": "value"}
    assert deserialized.secret == "hidden"


def test_reduce_ex_object():
    """Test __reduce_ex__ functionality"""
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    obj = ReduceExObject(5, 7)

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(ReduceExObject)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.x == 5
    assert deserialized.y == 7
    assert deserialized.computed == 35


def test_reduce_with_list_items():
    """Test __reduce__ with list items"""
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    obj = ReduceWithListItems([1, 2, 3, 4])

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(ReduceWithListItems)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.items == [1, 2, 3, 4]
    assert deserialized.metadata == "test"


def test_reduce_with_dict_items():
    """Test __reduce__ with dict items"""
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    obj = ReduceWithDictItems({"a": 1, "b": 2})

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(ReduceWithDictItems)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.data == {"a": 1, "b": 2}
    assert deserialized.name == "dict_obj"


def test_reduce_precedence_over_stateful():
    """Test that ReduceSerializer has higher precedence than StatefulSerializer"""
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    obj = BothReduceAndStateful(100)

    # Verify ReduceSerializer is used, not StatefulSerializer
    serializer = fory.type_resolver.get_serializer(BothReduceAndStateful)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.value == 100
    # The reduce method should have been used during serialization
    # (though we can't directly test this since it's called on the original object)


def test_reference_tracking():
    """Test that reference tracking works with ReduceSerializer"""
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    obj1 = BasicReduceObject(42)
    obj2 = BasicReduceObject(42)
    container = [obj1, obj1, obj2]  # obj1 appears twice

    serialized = fory.serialize(container)
    deserialized = fory.deserialize(serialized)

    assert len(deserialized) == 3
    assert deserialized[0] == obj1
    assert deserialized[1] == obj1
    assert deserialized[2] == obj2
    # Check that the first two references point to the same object
    assert deserialized[0] is deserialized[1]
    assert deserialized[0] is not deserialized[2]


def test_nested_reduce_objects():
    """Test nested objects with __reduce__"""
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    inner = BasicReduceObject(10, 2)
    outer = ReduceWithStateObject("outer", {"inner": inner})

    serialized = fory.serialize(outer)
    deserialized = fory.deserialize(serialized)

    assert deserialized == outer
    assert deserialized.name == "outer"
    assert deserialized.data["inner"] == inner
    assert deserialized.data["inner"].value == 10
    assert deserialized.data["inner"].multiplier == 2


def test_cross_language_compatibility():
    """Test cross-language compatibility"""
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    obj = BasicReduceObject(123, 4)

    # Serialize with Python
    serialized = fory.serialize(obj)

    # Should be able to deserialize (basic test)
    deserialized = fory.deserialize(serialized)
    assert deserialized == obj

    # The serialized data should use Fory's native format, not pickle
    # This is verified by the fact that we're using write_ref/read_ref
    # in the ReduceSerializer implementation
