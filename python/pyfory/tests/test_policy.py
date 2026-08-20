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
import types

import pytest
from pyfory import Fory, DeserializationPolicy
from pyfory.policy import DEFAULT_POLICY
from pyfory.serializer import (
    FunctionSerializer,
    MethodSerializer,
    NativeFuncMethodSerializer,
    ReduceSerializer,
    TypeSerializer,
)
from pyfory.type_util import load_class


def policy_global_function():
    return "safe"


def policy_replacement_function():
    return "replacement"


class PolicyMethodHolder:
    def run(self):
        return "safe"


policy_method_holder = PolicyMethodHolder()
policy_global_bound_method = policy_method_holder.run


class PolicyGlobalClass:
    pass


class PolicyHookMeta(type):
    attribute_reads = []

    def __getattribute__(cls, name):
        if name in {"__module__", "__qualname__"}:
            PolicyHookMeta.attribute_reads.append(name)
        return super().__getattribute__(name)


class PolicyHookClass(metaclass=PolicyHookMeta):
    pass


class PolicyNestedMeta(type):
    nested_reads = 0

    def __getattribute__(cls, name):
        if name == "Nested":
            PolicyNestedMeta.nested_reads += 1
        return super().__getattribute__(name)


class PolicyTypeOwner(metaclass=PolicyNestedMeta):
    class Nested:
        pass


PolicyNestedClass = type.__getattribute__(PolicyTypeOwner, "__dict__")["Nested"]


class PolicyDescriptor:
    called = False

    def __get__(self, instance, owner):
        type(self).called = True
        return PolicyHookClass


class PolicyDescriptorOwner:
    target = PolicyDescriptor()


class PolicyDataMeta(type):
    attribute_reads = []

    @property
    def __dict__(cls):
        PolicyDataMeta.attribute_reads.append("__dict__")
        return type.__dict__["__dict__"].__get__(cls, type(cls))

    @property
    def __mro__(cls):
        PolicyDataMeta.attribute_reads.append("__mro__")
        return type.__dict__["__mro__"].__get__(cls, type(cls))


class PolicyDataOwner(metaclass=PolicyDataMeta):
    class Nested:
        pass


PolicyDataNested = type.__dict__["__dict__"].__get__(PolicyDataOwner, PolicyDataMeta)["Nested"]


class PolicyCallableObject:
    class_reads = 0

    @property
    def __class__(self):
        type(self).class_reads += 1
        return type(self)

    def __call__(self):
        return None


policy_callable_object = PolicyCallableObject()


class PolicyClassMethodOwner:
    @classmethod
    def direct(cls):
        return cls


class PolicyClassMethodBase:
    @classmethod
    def inherited(cls):
        return cls


class PolicyClassMethodChild(PolicyClassMethodBase):
    pass


class PolicyReduceGlobal:
    def __reduce__(self):
        return f"{__name__}.policy_reduce_global"


policy_reduce_global = PolicyReduceGlobal()


class BlockPolicyHookClass(DeserializationPolicy):
    def __init__(self):
        self.classes = []

    def validate_class(self, cls, is_local, **kwargs):
        self.classes.append(cls)
        if cls is PolicyHookClass:
            raise ValueError("class blocked")


class FakeReadContext:
    def __init__(self, policy, values):
        self.policy = policy
        self._values = iter(values)

    def read_int8(self):
        return next(self._values)

    def read_bool(self):
        return next(self._values)

    def read_string(self):
        return next(self._values)

    def read_ref(self):
        return next(self._values)


class FalseyState:
    bool_called = False

    def __bool__(self):
        type(self).bool_called = True
        return False


class FalseyStatePayload:
    def __getstate__(self):
        return FalseyState()

    def __setstate__(self, state):
        self.state = state


class ObjectSetAttrPayload:
    setattr_called = False

    def __setattr__(self, name, value):
        type(self).setattr_called = True
        super().__setattr__(name, value)


class BlockClassPolicy(DeserializationPolicy):
    """Policy that blocks specific class names from deserialization."""

    def __init__(self, blocked_class_names):
        self.blocked_class_names = blocked_class_names

    def validate_class(self, cls, is_local, **kwargs):
        if cls.__name__ in self.blocked_class_names:
            raise ValueError(f"Class {cls.__name__} is blocked")
        return None


class ReplaceObjectPolicy(DeserializationPolicy):
    """Policy that replaces deserialized objects from reduce."""

    def __init__(self, replacement_value):
        self.replacement_value = replacement_value

    def inspect_reduced_object(self, obj, **kwargs):
        if hasattr(obj, "value"):
            return self.replacement_value
        return None


class BlockReduceCallPolicy(DeserializationPolicy):
    """Policy that blocks specific callable invocations during reduce."""

    def __init__(self, blocked_names):
        self.blocked_names = blocked_names

    def intercept_reduce_call(self, callable_obj, args, **kwargs):
        if hasattr(callable_obj, "__name__") and callable_obj.__name__ in self.blocked_names:
            raise ValueError(f"Callable {callable_obj.__name__} is blocked")
        return None


class SanitizeStatePolicy(DeserializationPolicy):
    """Policy that sanitizes object state during setstate."""

    def intercept_setstate(self, obj, state, **kwargs):
        if isinstance(state, dict) and "password" in state:
            state["password"] = "***REDACTED***"
        return None


def test_block_class_type_deserialization():
    """Test blocking class type (not instance) deserialization."""

    class SafeClass:
        pass

    class UnsafeClass:
        pass

    policy = BlockClassPolicy(blocked_class_names=["UnsafeClass"])
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)

    # Serialize and deserialize the class type itself (not an instance)
    safe_data = fory.serialize(SafeClass)
    result = fory.deserialize(safe_data)
    assert result.__name__ == "SafeClass"

    # Now test blocking
    unsafe_data = fory.serialize(UnsafeClass)
    with pytest.raises(ValueError, match="UnsafeClass is blocked"):
        fory.deserialize(unsafe_data)


def test_block_reduce_call():
    """Test blocking callable invocations during reduce."""

    class ReducibleClass:
        def __init__(self, value):
            self.value = value

        def __reduce__(self):
            return (ReducibleClass, (self.value,))

    policy = BlockReduceCallPolicy(blocked_names=["ReducibleClass"])
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    data = fory.serialize(ReducibleClass(42))

    with pytest.raises(ValueError, match="ReducibleClass is blocked"):
        fory.deserialize(data)


def test_replace_reduced_object():
    """Test replacing objects created via __reduce__."""

    class ReducibleClass:
        def __init__(self, value):
            self.value = value

        def __reduce__(self):
            return (ReducibleClass, (self.value,))

    policy = ReplaceObjectPolicy(replacement_value="REPLACED")
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    data = fory.serialize(ReducibleClass(42))

    result = fory.deserialize(data)
    assert result == "REPLACED"


def test_sanitize_state():
    """Test sanitizing object state during setstate."""

    class SecretHolder:
        def __init__(self, username, password):
            self.username = username
            self.password = password

        def __getstate__(self):
            return {"username": self.username, "password": self.password}

        def __setstate__(self, state):
            self.__dict__.update(state)

    policy = SanitizeStatePolicy()
    fory = Fory(xlang=False, ref=False, strict=False, policy=policy, compatible=False)
    data = fory.serialize(SecretHolder("admin", "secret123"))

    result = fory.deserialize(data)
    assert result.username == "admin"
    assert result.password == "***REDACTED***"


def test_reduce_state_sanitizes_state():
    """Test sanitizing object state restored from __reduce__."""

    class CountingSanitizePolicy(DeserializationPolicy):
        def __init__(self):
            self.intercept_setstate_calls = 0

        def intercept_setstate(self, obj, state, **kwargs):
            self.intercept_setstate_calls += 1
            if isinstance(state, dict) and "password" in state:
                state["password"] = "***REDACTED***"
            return None

    class SecretReduceHolder:
        def __reduce__(self):
            return (
                SecretReduceHolder,
                (),
                {"username": "admin", "password": "secret123"},
            )

        def __setstate__(self, state):
            self.__dict__.update(state)

    policy = CountingSanitizePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    data = fory.serialize(SecretReduceHolder())

    result = fory.deserialize(data)
    assert policy.intercept_setstate_calls == 1
    assert result.username == "admin"
    assert result.password == "***REDACTED***"


def test_stateful_intercepts_falsey_state_before_bool():
    """Test stateful path calls intercept_setstate without evaluating state truthiness."""

    class BlockSetStatePolicy(DeserializationPolicy):
        def intercept_setstate(self, obj, state, **kwargs):
            raise ValueError("state blocked")

    FalseyState.bool_called = False
    fory = Fory(
        xlang=False,
        ref=True,
        strict=False,
        policy=BlockSetStatePolicy(),
        compatible=False,
    )
    data = fory.serialize(FalseyStatePayload())

    with pytest.raises(ValueError, match="state blocked"):
        fory.deserialize(data)
    assert not FalseyState.bool_called


def test_object_serializer_intercepts_state_before_setattr():
    """Test object serializer state hook runs before applying attacker-controlled fields."""

    class BlockSetStatePolicy(DeserializationPolicy):
        def intercept_setstate(self, obj, state, **kwargs):
            raise ValueError("object state blocked")

    obj = ObjectSetAttrPayload()
    obj.value = 1
    ObjectSetAttrPayload.setattr_called = False

    writer = Fory(xlang=False, ref=True, strict=False, compatible=False)
    reader = Fory(
        xlang=False,
        ref=True,
        strict=False,
        policy=BlockSetStatePolicy(),
        compatible=False,
    )
    writer.register(ObjectSetAttrPayload)
    reader.register(ObjectSetAttrPayload)

    with pytest.raises(ValueError, match="object state blocked"):
        reader.deserialize(writer.serialize(obj))
    assert not ObjectSetAttrPayload.setattr_called


def test_policy_with_local_class():
    """Test policy intercepts local class deserialization."""

    def make_local_class():
        class LocalClass:
            pass

        return LocalClass

    LocalCls = make_local_class()

    policy = BlockClassPolicy(blocked_class_names=["LocalClass"])
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)

    # Serialize the local class type
    data = fory.serialize(LocalCls)

    with pytest.raises(ValueError, match="LocalClass is blocked"):
        fory.deserialize(data)


def test_policy_with_ref_tracking():
    """Test policy works with reference tracking."""

    class ReducibleClass:
        def __init__(self, value):
            self.value = value

        def __reduce__(self):
            return (ReducibleClass, (self.value,))

    policy = BlockReduceCallPolicy(blocked_names=["ReducibleClass"])
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)

    data = fory.serialize(ReducibleClass(42))

    with pytest.raises(ValueError, match="ReducibleClass is blocked"):
        fory.deserialize(data)


def test_policy_allows_safe_operations():
    """Test that policy doesn't interfere with safe built-in types."""
    policy = BlockClassPolicy(blocked_class_names=[])
    fory = Fory(xlang=False, ref=False, strict=False, policy=policy, compatible=False)

    assert fory.deserialize(fory.serialize(42)) == 42
    assert fory.deserialize(fory.serialize("test")) == "test"
    assert fory.deserialize(fory.serialize([1, 2, 3])) == [1, 2, 3]


def test_multiple_policy_hooks():
    """Test policy with multiple hooks working together."""

    class MultiHookPolicy(DeserializationPolicy):
        def __init__(self):
            self.hooks_called = []

        def validate_class(self, cls, is_local, **kwargs):
            self.hooks_called.append(("validate_class", cls.__name__))
            return None

        def intercept_reduce_call(self, callable_obj, args, **kwargs):
            if hasattr(callable_obj, "__name__"):
                self.hooks_called.append(("intercept_reduce_call", callable_obj.__name__))
            return None

        def inspect_reduced_object(self, obj, **kwargs):
            self.hooks_called.append(("inspect_reduced_object", type(obj).__name__))
            return None

    class TestClass:
        def __init__(self, value):
            self.value = value

        def __reduce__(self):
            return (TestClass, (self.value,))

    policy = MultiHookPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)

    data = fory.serialize(TestClass(42))
    result = fory.deserialize(data)

    # All hooks should have been called
    assert ("intercept_reduce_call", "TestClass") in policy.hooks_called
    assert ("inspect_reduced_object", "TestClass") in policy.hooks_called
    assert result.value == 42


def test_policy_with_nested_reduce():
    """Test policy handles nested objects with __reduce__."""

    class Inner:
        def __init__(self, value):
            self.value = value

        def __reduce__(self):
            return (Inner, (self.value,))

    class Outer:
        def __init__(self, inner):
            self.inner = inner

        def __reduce__(self):
            return (Outer, (self.inner,))

    policy = BlockReduceCallPolicy(blocked_names=["Inner"])
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)

    data = fory.serialize(Outer(Inner(42)))

    with pytest.raises(ValueError, match="Inner is blocked"):
        fory.deserialize(data)


def test_stateful_authorizes_instantiation():
    """Test authorize_instantiation policy hook for stateful deserialization."""

    class StatefulPayload:
        def __init__(self):
            self.value = 1

        def __getstate__(self):
            return {"value": self.value}

        def __setstate__(self, state):
            self.__dict__.update(state)

    class BlockInstantiationPolicy(DeserializationPolicy):
        def __init__(self):
            self.authorize_instantiation_calls = 0

        def authorize_instantiation(self, cls, **kwargs):
            self.authorize_instantiation_calls += 1
            if cls is StatefulPayload:
                raise ValueError("StatefulPayload blocked")
            return None

    policy = BlockInstantiationPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="StatefulPayload blocked"):
        fory.deserialize(fory.serialize(StatefulPayload()))
    assert policy.authorize_instantiation_calls == 1


def test_reduce_class_callable_authorizes_instantiation():
    """Test authorize_instantiation policy hook for reduce class callables."""

    class ReduceTarget:
        pass

    class ReducePayload:
        def __reduce__(self):
            return (ReduceTarget, ())

    class BlockInstantiationPolicy(DeserializationPolicy):
        def __init__(self):
            self.authorize_instantiation_calls = 0
            self.reduce_target_calls = 0

        def authorize_instantiation(self, cls, **kwargs):
            self.authorize_instantiation_calls += 1
            if cls.__name__ == "ReduceTarget":
                self.reduce_target_calls += 1
                raise ValueError("ReduceTarget blocked")
            return None

    policy = BlockInstantiationPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="ReduceTarget blocked"):
        fory.deserialize(fory.serialize(ReducePayload()))
    assert policy.reduce_target_calls == 1


def test_registered_dataclass_authorizes_instantiation_in_strict_mode():
    """Test registered dataclass reads still honor authorize_instantiation."""
    from dataclasses import dataclass

    @dataclass
    class StrictDataClass:
        value: int

    class BlockInstantiationPolicy(DeserializationPolicy):
        def __init__(self):
            self.authorize_instantiation_calls = 0

        def authorize_instantiation(self, cls, **kwargs):
            self.authorize_instantiation_calls += 1
            if cls is StrictDataClass:
                raise ValueError("StrictDataClass blocked")
            return None

    policy = BlockInstantiationPolicy()
    writer = Fory(xlang=False, ref=True, strict=True, compatible=False)
    reader = Fory(xlang=False, ref=True, strict=True, policy=policy, compatible=False)
    writer.register(StrictDataClass)
    reader.register(StrictDataClass)

    with pytest.raises(ValueError, match="StrictDataClass blocked"):
        reader.deserialize(writer.serialize(StrictDataClass(1)))
    assert policy.authorize_instantiation_calls == 1


def test_function_bound_method_authorizes_before_receiver_read():
    class BlockMethodMaterializationPolicy(DeserializationPolicy):
        def __init__(self):
            self.calls = []

        def authorize_instantiation(self, cls, **kwargs):
            self.calls.append((cls, kwargs))
            if cls is types.MethodType:
                raise ValueError("method materialization blocked")

    policy = BlockMethodMaterializationPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [0])

    with pytest.raises(ValueError, match="method materialization blocked"):
        serializer._deserialize_function(read_context)
    assert policy.calls == [(types.MethodType, {})]


def test_local_function_authorizes_before_body_read():
    class BlockFunctionMaterializationPolicy(DeserializationPolicy):
        def __init__(self):
            self.calls = []

        def authorize_instantiation(self, cls, **kwargs):
            self.calls.append((cls, kwargs))
            if cls is types.FunctionType:
                raise ValueError("function materialization blocked")

    policy = BlockFunctionMaterializationPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [2, __name__, "policy_global_function"])

    with pytest.raises(ValueError, match="function materialization blocked"):
        serializer._deserialize_function(read_context)
    assert policy.calls == [
        (
            types.FunctionType,
            {
                "module": __name__,
                "qualname": "policy_global_function",
                "is_local": True,
            },
        )
    ]


def test_native_bound_method_authorizes_before_receiver_read():
    class BlockMethodMaterializationPolicy(DeserializationPolicy):
        def __init__(self):
            self.calls = []

        def authorize_instantiation(self, cls, **kwargs):
            self.calls.append((cls, kwargs))
            if cls is types.MethodType:
                raise ValueError("method materialization blocked")

    policy = BlockMethodMaterializationPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = NativeFuncMethodSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, ["run", False])

    with pytest.raises(ValueError, match="method materialization blocked"):
        serializer.read(read_context)
    assert policy.calls == [(types.MethodType, {"method_name": "run"})]


def test_method_serializer_authorizes_before_instance_read():
    class BlockMethodMaterializationPolicy(DeserializationPolicy):
        def __init__(self):
            self.calls = []

        def authorize_instantiation(self, cls, **kwargs):
            self.calls.append((cls, kwargs))
            if cls is types.MethodType:
                raise ValueError("method materialization blocked")

    policy = BlockMethodMaterializationPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = MethodSerializer(fory.type_resolver, types.MethodType)
    read_context = FakeReadContext(policy, [])

    with pytest.raises(ValueError, match="method materialization blocked"):
        serializer.read(read_context)
    assert policy.calls == [(types.MethodType, {})]


def test_validate_module():
    """Test validate_module policy hook for module deserialization."""
    import json
    import collections

    class ReturnModulePolicy(DeserializationPolicy):
        def validate_module(self, module_name, is_local, **kwargs):
            assert not is_local
            return collections

    fory1 = Fory(
        xlang=False,
        ref=True,
        strict=False,
        policy=ReturnModulePolicy(),
        compatible=False,
    )
    data = fory1.serialize(json)
    assert fory1.deserialize(data) is json

    class RedirectPolicy(DeserializationPolicy):
        def validate_module(self, module_name, is_local, **kwargs):
            assert not is_local
            return "collections" if module_name == "json" else None

    fory2 = Fory(xlang=False, ref=True, strict=False, policy=RedirectPolicy(), compatible=False)
    assert fory2.deserialize(fory2.serialize(json)).__name__ == "json"

    class BlockPolicy(DeserializationPolicy):
        def validate_module(self, module_name, is_local, **kwargs):
            assert not is_local
            raise ValueError(f"Module {module_name} blocked")

    fory3 = Fory(xlang=False, ref=True, strict=False, policy=BlockPolicy(), compatible=False)
    with pytest.raises(ValueError, match="blocked"):
        fory3.deserialize(fory3.serialize(json))


def test_validator_returns_ignored():
    import json
    import collections

    class ReplacementClass:
        pass

    class ReturnPolicy(DeserializationPolicy):
        def validate_module(self, module_name, is_local, **kwargs):
            assert not is_local
            return collections

        def validate_class(self, cls, is_local, **kwargs):
            return ReplacementClass

        def validate_function(self, func, is_local, **kwargs):
            return policy_replacement_function

        def validate_method(self, method, is_local, **kwargs):
            return policy_replacement_function

    policy = ReturnPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    assert fory.deserialize(fory.serialize(json)) is json
    assert fory.deserialize(fory.serialize(PolicyGlobalClass)) is PolicyGlobalClass
    assert fory.deserialize(fory.serialize(policy_global_function)) is policy_global_function

    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [1, __name__, "policy_global_bound_method"])
    assert serializer._deserialize_function(read_context) is policy_global_bound_method


def test_local_class_return_ignored():
    class SafeClass:
        @classmethod
        def run(cls):
            return "safe"

    def make_payload_class():
        class PayloadClass:
            @classmethod
            def run(cls):
                return "payload"

        return PayloadClass

    class ReturnClassPolicy(DeserializationPolicy):
        def validate_class(self, cls, is_local, **kwargs):
            return SafeClass if is_local else None

    fory = Fory(
        xlang=False,
        ref=True,
        strict=False,
        policy=ReturnClassPolicy(),
        compatible=False,
    )
    decoded = fory.deserialize(fory.serialize(make_payload_class()))
    assert decoded is not SafeClass
    assert decoded.run() == "payload"
    assert SafeClass.run() == "safe"


def test_type_deserialization_validates_module():
    """Test validate_module policy hook for global class deserialization."""
    import subprocess

    class BlockModulePolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0
            self.is_local_values = []

        def validate_module(self, module_name, is_local, **kwargs):
            self.validate_module_calls += 1
            self.is_local_values.append(is_local)
            if module_name == "subprocess":
                raise ValueError("subprocess blocked")
            return None

    policy = BlockModulePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="subprocess blocked"):
        fory.deserialize(fory.serialize(subprocess.Popen))
    assert policy.validate_module_calls == 1
    assert policy.is_local_values == [False]


def test_native_bound_method_uses_validate_method():
    """Test bound native methods are checked by method policy, not function policy."""

    class BlockMethodPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_method_calls = 0
            self.validate_function_calls = 0

        def validate_method(self, method, is_local, **kwargs):
            self.validate_method_calls += 1
            raise ValueError("method blocked")

        def validate_function(self, func, is_local, **kwargs):
            self.validate_function_calls += 1
            return None

    policy = BlockMethodPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)

    with pytest.raises(ValueError, match="method blocked"):
        fory.deserialize(fory.serialize([].append))
    assert policy.validate_method_calls == 1
    assert policy.validate_function_calls == 0


def test_bound_method_policy_runs_before_getattribute_side_effect():
    """Test bound method deserialization validates before dynamic attribute lookup."""

    class GuardedMethod:
        getattribute_called = False

        def __getattribute__(self, name):
            if name == "run":
                type(self).getattribute_called = True
            return super().__getattribute__(name)

        def run(self):
            return "unsafe"

    class BlockMethodPolicy(DeserializationPolicy):
        def validate_method(self, method, is_local, **kwargs):
            raise ValueError("method blocked")

    obj = GuardedMethod()
    method = types.MethodType(GuardedMethod.run, obj)
    fory = Fory(
        xlang=False,
        ref=True,
        strict=False,
        policy=BlockMethodPolicy(),
        compatible=False,
    )
    data = fory.serialize(method)

    GuardedMethod.getattribute_called = False
    with pytest.raises(ValueError, match="method blocked"):
        fory.deserialize(data)
    assert not GuardedMethod.getattribute_called


def test_type_policy_before_class_hook():
    policy = BlockPolicyHookClass()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = TypeSerializer(fory.type_resolver, type)
    read_context = FakeReadContext(policy, [0, __name__, "PolicyHookClass"])

    PolicyHookMeta.attribute_reads.clear()
    with pytest.raises(ValueError, match="class blocked"):
        serializer.read(read_context)
    assert PolicyHookMeta.attribute_reads == []
    assert policy.classes == [PolicyHookClass]


def test_function_policy_before_class_hook():
    policy = BlockPolicyHookClass()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [1, __name__, "PolicyHookClass"])

    PolicyHookMeta.attribute_reads.clear()
    with pytest.raises(ValueError, match="class blocked"):
        serializer._deserialize_function(read_context)
    assert PolicyHookMeta.attribute_reads == []
    assert policy.classes == [PolicyHookClass]


def test_native_policy_before_class_hook():
    policy = BlockPolicyHookClass()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = NativeFuncMethodSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, ["PolicyHookClass", True, __name__])

    PolicyHookMeta.attribute_reads.clear()
    with pytest.raises(ValueError, match="class blocked"):
        serializer.read(read_context)
    assert PolicyHookMeta.attribute_reads == []
    assert policy.classes == [PolicyHookClass]


def test_reduce_policy_before_class_hook():
    policy = BlockPolicyHookClass()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = ReduceSerializer(fory.type_resolver, object)
    read_context = FakeReadContext(policy, [])

    PolicyHookMeta.attribute_reads.clear()
    with pytest.raises(ValueError, match="class blocked"):
        serializer._resolve_global_name(read_context, f"{__name__}.PolicyHookClass")
    assert PolicyHookMeta.attribute_reads == []
    assert policy.classes == [PolicyHookClass]


def test_named_type_policy_before_owner_hook():
    class BlockNestedPolicy(DeserializationPolicy):
        def __init__(self):
            self.classes = []

        def validate_class(self, cls, is_local, **kwargs):
            self.classes.append(cls)
            if cls is PolicyNestedClass:
                raise ValueError("nested class blocked")

    policy = BlockNestedPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    from pyfory.registry import SharedRegistry, TypeResolver

    resolver = TypeResolver(fory.config, shared_registry=SharedRegistry())
    namespace = resolver.namespace_encoder.encode(__name__)
    ns_metabytes = resolver.shared_registry.get_encoded_meta_string(namespace)
    typename = resolver.typename_encoder.encode("PolicyTypeOwner.Nested")
    type_metabytes = resolver.shared_registry.get_encoded_meta_string(typename)

    PolicyNestedMeta.nested_reads = 0
    with pytest.raises(ValueError, match="nested class blocked"):
        resolver._load_metabytes_to_type_info(ns_metabytes, type_metabytes)
    assert PolicyNestedMeta.nested_reads == 0
    assert policy.classes == [PolicyTypeOwner, PolicyNestedClass]


def test_custom_policy_skips_module_hook(monkeypatch):
    module_name = f"{__name__}_dynamic"
    module = types.ModuleType(module_name)
    module_calls = []

    def module_getattr(name):
        module_calls.append(name)
        return PolicyHookClass

    module.__getattr__ = module_getattr
    monkeypatch.setitem(sys.modules, module_name, module)

    policy = BlockPolicyHookClass()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = TypeSerializer(fory.type_resolver, type)
    read_context = FakeReadContext(policy, [0, module_name, "missing"])

    with pytest.raises(AttributeError):
        serializer.read(read_context)
    assert module_calls == []
    assert policy.classes == []


def test_custom_policy_skips_descriptor():
    class OwnerPolicy(DeserializationPolicy):
        def validate_class(self, cls, is_local, **kwargs):
            if cls is not PolicyDescriptorOwner:
                raise ValueError("class blocked")

    policy = OwnerPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = TypeSerializer(fory.type_resolver, type)
    read_context = FakeReadContext(policy, [0, __name__, "PolicyDescriptorOwner.target"])

    PolicyDescriptor.called = False
    with pytest.raises(ValueError):
        serializer.read(read_context)
    assert not PolicyDescriptor.called


def test_custom_policy_skips_metaclass_data():
    class BlockNestedPolicy(DeserializationPolicy):
        def __init__(self):
            self.classes = []

        def validate_class(self, cls, is_local, **kwargs):
            self.classes.append(cls)
            if cls is PolicyDataNested:
                raise ValueError("nested class blocked")

    policy = BlockNestedPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = TypeSerializer(fory.type_resolver, type)
    read_context = FakeReadContext(policy, [0, __name__, "PolicyDataOwner.Nested"])

    PolicyDataMeta.attribute_reads.clear()
    with pytest.raises(ValueError, match="nested class blocked"):
        serializer.read(read_context)
    assert PolicyDataMeta.attribute_reads == []
    assert policy.classes == [PolicyDataOwner, PolicyDataNested]


def test_custom_policy_skips_module_data(monkeypatch):
    class DataModule(types.ModuleType):
        attribute_reads = 0

        @property
        def __dict__(self):
            type(self).attribute_reads += 1
            descriptor = types.ModuleType.__dict__["__dict__"]
            return descriptor.__get__(self, type(self))

    module_name = f"{__name__}_data"
    module = DataModule(module_name)
    descriptor = types.ModuleType.__dict__["__dict__"]
    descriptor.__get__(module, DataModule)["target"] = PolicyHookClass
    monkeypatch.setitem(sys.modules, module_name, module)

    policy = BlockPolicyHookClass()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = TypeSerializer(fory.type_resolver, type)
    read_context = FakeReadContext(policy, [0, module_name, "target"])

    with pytest.raises(ValueError, match="class blocked"):
        serializer.read(read_context)
    assert DataModule.attribute_reads == 0


def test_custom_policy_skips_class_data():
    class BlockCallablePolicy(DeserializationPolicy):
        def validate_function(self, func, is_local, **kwargs):
            raise ValueError("callable blocked")

    policy = BlockCallablePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [1, __name__, "policy_callable_object"])

    PolicyCallableObject.class_reads = 0
    with pytest.raises(ValueError, match="callable blocked"):
        serializer._deserialize_function(read_context)
    assert PolicyCallableObject.class_reads == 0


@pytest.mark.parametrize(
    ("module_name", "qualname", "owners", "method_type", "receiver"),
    [
        (
            __name__,
            "PolicyClassMethodOwner.direct",
            (PolicyClassMethodOwner,),
            types.MethodType,
            PolicyClassMethodOwner,
        ),
        (
            __name__,
            "PolicyClassMethodChild.inherited",
            (PolicyClassMethodChild, PolicyClassMethodBase),
            types.MethodType,
            PolicyClassMethodChild,
        ),
        (
            "builtins",
            "dict.fromkeys",
            (dict,),
            types.BuiltinMethodType,
            dict,
        ),
    ],
)
def test_classmethod_policy_order(module_name, qualname, owners, method_type, receiver):
    class CapturePolicy(DeserializationPolicy):
        def __init__(self):
            self.events = []

        def validate_module(self, name, is_local, **kwargs):
            self.events.append(("module", name, is_local))

        def validate_class(self, cls, is_local, **kwargs):
            self.events.append(("class", cls, is_local))

        def authorize_instantiation(self, cls, **kwargs):
            self.events.append(("authorize", cls, kwargs["method_name"]))

        def validate_method(self, method, is_local, **kwargs):
            method_self = object.__getattribute__(method, "__self__")
            self.events.append(("method", type(method), method_self, is_local))

    policy = CapturePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [1, module_name, qualname])

    method = serializer._deserialize_function(read_context)
    expected = [("module", module_name, False)]
    expected.extend(("class", owner, False) for owner in owners)
    expected.append(("authorize", method_type, qualname.rsplit(".", 1)[1]))
    expected.append(("method", method_type, receiver, False))
    assert policy.events == expected
    assert type(method) is method_type


def test_default_type_keeps_dynamic_lookup():
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)
    serializer = TypeSerializer(fory.type_resolver, type)
    read_context = FakeReadContext(DEFAULT_POLICY, [0, __name__, "PolicyHookClass"])

    PolicyHookMeta.attribute_reads.clear()
    assert serializer.read(read_context) is PolicyHookClass
    assert PolicyHookMeta.attribute_reads == ["__module__", "__qualname__"]


def test_default_load_class_keeps_lookup():
    PolicyNestedMeta.nested_reads = 0
    assert load_class(f"{__name__}#PolicyTypeOwner.Nested", policy=DEFAULT_POLICY) is PolicyNestedClass
    assert PolicyNestedMeta.nested_reads == 1


def test_default_global_round_trips():
    import time

    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)
    for value in (PolicyGlobalClass, policy_global_function, time.time, policy_reduce_global):
        assert fory.deserialize(fory.serialize(value)) is value


def test_custom_policy_uses_target_locality(monkeypatch):
    class CaptureLocalityPolicy(DeserializationPolicy):
        def __init__(self):
            self.modules = []
            self.classes = []
            self.functions = []

        def validate_module(self, module_name, is_local, **kwargs):
            self.modules.append((module_name, is_local))

        def validate_class(self, cls, is_local, **kwargs):
            self.classes.append((cls, is_local))

        def validate_function(self, func, is_local, **kwargs):
            self.functions.append((func, is_local))

    module = sys.modules[__name__]
    class_alias = "PolicyClass<locals>"
    function_alias = "policy_function<locals>"
    monkeypatch.setitem(module.__dict__, class_alias, PolicyGlobalClass)
    monkeypatch.setitem(module.__dict__, function_alias, policy_global_function)

    policy = CaptureLocalityPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)

    type_serializer = TypeSerializer(fory.type_resolver, type)
    assert type_serializer.read(FakeReadContext(policy, [0, __name__, class_alias])) is PolicyGlobalClass
    assert policy.modules == [(__name__, False)]
    assert policy.classes == [(PolicyGlobalClass, False)]

    policy.modules.clear()
    policy.classes.clear()
    assert load_class(f"{__name__}#{class_alias}", policy=policy) is PolicyGlobalClass
    assert policy.modules == [(__name__, False)]
    assert policy.classes == [(PolicyGlobalClass, False)]

    policy.modules.clear()
    function_serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    context = FakeReadContext(policy, [1, __name__, function_alias])
    assert function_serializer._deserialize_function(context) is policy_global_function
    assert policy.modules == [(__name__, False)]
    assert policy.functions == [(policy_global_function, False)]

    policy.modules.clear()
    policy.functions.clear()
    native_serializer = NativeFuncMethodSerializer(fory.type_resolver, type(policy_global_function))
    context = FakeReadContext(policy, [function_alias, True, __name__])
    assert native_serializer.read(context) is policy_global_function
    assert policy.modules == [(__name__, False)]
    assert policy.functions == [(policy_global_function, False)]

    policy.modules.clear()
    policy.classes.clear()
    reduce_serializer = ReduceSerializer(fory.type_resolver, object)
    context = FakeReadContext(policy, [])
    assert reduce_serializer._resolve_global_name(context, f"{__name__}.{class_alias}") is PolicyGlobalClass
    assert policy.modules == [(__name__, False)]
    assert policy.classes == [(PolicyGlobalClass, False)]


def test_type_global_path_reports_main_class_as_local():
    class CaptureClassPolicy(DeserializationPolicy):
        def __init__(self):
            self.is_local_values = []

        def validate_class(self, cls, is_local, **kwargs):
            self.is_local_values.append(is_local)
            return None

    original_module = PolicyGlobalClass.__module__
    PolicyGlobalClass.__module__ = "__main__"
    try:
        policy = CaptureClassPolicy()
        fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
        serializer = TypeSerializer(fory.type_resolver, type)
        read_context = FakeReadContext(policy, [0, __name__, "PolicyGlobalClass"])

        assert serializer.read(read_context) is PolicyGlobalClass
        assert policy.is_local_values == [True]
    finally:
        PolicyGlobalClass.__module__ = original_module


def test_type_deserialization_rejects_non_class_before_policy():
    class CaptureClassPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_class_calls = 0

        def validate_class(self, cls, is_local, **kwargs):
            self.validate_class_calls += 1

    policy = CaptureClassPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = TypeSerializer(fory.type_resolver, type)
    read_context = FakeReadContext(policy, [0, __name__, "policy_global_function"])

    with pytest.raises(TypeError, match="resolved non-class object"):
        serializer.read(read_context)
    assert policy.validate_class_calls == 0


def test_local_class_classmethod_policy():
    def make_local_class():
        class LocalClass:
            @classmethod
            def run(cls):
                return "safe"

        return LocalClass

    class ClassMethodPolicy(DeserializationPolicy):
        def __init__(self):
            self.materializations = []
            self.methods = []

        def authorize_instantiation(self, cls, **kwargs):
            if cls is types.MethodType:
                self.materializations.append((cls, kwargs))

        def validate_method(self, method, is_local, **kwargs):
            self.methods.append((method, is_local))
            raise ValueError("classmethod blocked")

    writer = Fory(xlang=False, ref=True, strict=False, compatible=False)
    policy = ClassMethodPolicy()
    reader = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    data = writer.serialize(make_local_class())

    with pytest.raises(ValueError, match="classmethod blocked"):
        reader.deserialize(data)
    assert policy.materializations == [(types.MethodType, {"method_name": "run"})]
    assert len(policy.methods) == 1
    assert isinstance(policy.methods[0][0], types.MethodType)
    assert policy.methods[0][1] is True


def test_function_bound_method_reports_receiver_locality_to_policy():
    class LocalReceiver:
        def run(self):
            return "safe"

    class CaptureMethodPolicy(DeserializationPolicy):
        def __init__(self):
            self.is_local_values = []

        def validate_method(self, method, is_local, **kwargs):
            self.is_local_values.append(is_local)
            raise ValueError("method blocked")

    policy = CaptureMethodPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [0, LocalReceiver(), "run"])

    with pytest.raises(ValueError, match="method blocked"):
        serializer._deserialize_function(read_context)
    assert policy.is_local_values == [True]


def test_native_bound_method_reports_receiver_locality_to_policy():
    class LocalReceiver:
        def run(self):
            return "safe"

    class CaptureMethodPolicy(DeserializationPolicy):
        def __init__(self):
            self.is_local_values = []

        def validate_method(self, method, is_local, **kwargs):
            self.is_local_values.append(is_local)
            raise ValueError("method blocked")

    policy = CaptureMethodPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = NativeFuncMethodSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, ["run", False, LocalReceiver()])

    with pytest.raises(ValueError, match="method blocked"):
        serializer.read(read_context)
    assert policy.is_local_values == [True]


def test_function_serializer_rejects_class_resolution():
    """Test function deserialization cannot resolve classes through the function policy."""

    class BlockClassPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_class_calls = 0
            self.validate_function_calls = 0

        def validate_class(self, cls, is_local, **kwargs):
            self.validate_class_calls += 1
            raise ValueError("class blocked")

        def validate_function(self, func, is_local, **kwargs):
            self.validate_function_calls += 1
            return None

    policy = BlockClassPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [1, "subprocess", "Popen"])

    with pytest.raises(ValueError, match="class blocked"):
        serializer._deserialize_function(read_context)
    assert policy.validate_class_calls == 1
    assert policy.validate_function_calls == 0


def test_function_global_method_resolution_uses_validate_method():
    class MethodPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_method_calls = 0
            self.validate_function_calls = 0

        def validate_method(self, method, is_local, **kwargs):
            self.validate_method_calls += 1
            raise ValueError("method blocked")

        def validate_function(self, func, is_local, **kwargs):
            self.validate_function_calls += 1
            return None

    policy = MethodPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [1, __name__, "policy_global_bound_method"])

    with pytest.raises(ValueError, match="method blocked"):
        serializer._deserialize_function(read_context)
    assert policy.validate_method_calls == 1
    assert policy.validate_function_calls == 0


def test_function_global_path_reports_main_function_as_local():
    class CaptureFunctionPolicy(DeserializationPolicy):
        def __init__(self):
            self.is_local_values = []

        def validate_function(self, func, is_local, **kwargs):
            self.is_local_values.append(is_local)
            return None

    original_module = policy_global_function.__module__
    policy_global_function.__module__ = "__main__"
    try:
        policy = CaptureFunctionPolicy()
        fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
        serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
        read_context = FakeReadContext(policy, [1, __name__, "policy_global_function"])

        assert serializer._deserialize_function(read_context) is policy_global_function
        assert policy.is_local_values == [True]
    finally:
        policy_global_function.__module__ = original_module


def test_native_function_serializer_rejects_class_resolution():
    """Test native function deserialization cannot resolve classes through the function policy."""

    class BlockClassPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_class_calls = 0
            self.validate_function_calls = 0

        def validate_class(self, cls, is_local, **kwargs):
            self.validate_class_calls += 1
            raise ValueError("class blocked")

        def validate_function(self, func, is_local, **kwargs):
            self.validate_function_calls += 1
            return None

    policy = BlockClassPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = NativeFuncMethodSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, ["Popen", True, "subprocess"])

    with pytest.raises(ValueError, match="class blocked"):
        serializer.read(read_context)
    assert policy.validate_class_calls == 1
    assert policy.validate_function_calls == 0


def test_native_function_global_method_resolution_uses_validate_method():
    class MethodPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_method_calls = 0
            self.validate_function_calls = 0

        def validate_method(self, method, is_local, **kwargs):
            self.validate_method_calls += 1
            raise ValueError("method blocked")

        def validate_function(self, func, is_local, **kwargs):
            self.validate_function_calls += 1
            return None

    policy = MethodPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = NativeFuncMethodSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, ["policy_global_bound_method", True, __name__])

    with pytest.raises(ValueError, match="method blocked"):
        serializer.read(read_context)
    assert policy.validate_method_calls == 1
    assert policy.validate_function_calls == 0


def test_native_function_global_path_reports_main_function_as_local():
    class CaptureFunctionPolicy(DeserializationPolicy):
        def __init__(self):
            self.is_local_values = []

        def validate_function(self, func, is_local, **kwargs):
            self.is_local_values.append(is_local)
            return None

    original_module = policy_global_function.__module__
    policy_global_function.__module__ = "__main__"
    try:
        policy = CaptureFunctionPolicy()
        fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
        serializer = NativeFuncMethodSerializer(fory.type_resolver, type(policy_global_function))
        read_context = FakeReadContext(policy, ["policy_global_function", True, __name__])

        assert serializer.read(read_context) is policy_global_function
        assert policy.is_local_values == [True]
    finally:
        policy_global_function.__module__ = original_module


def test_global_function_deserialization_validates_module():
    """Test validate_module policy hook for global function deserialization."""

    class BlockModulePolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0
            self.is_local_values = []

        def validate_module(self, module_name, is_local, **kwargs):
            self.validate_module_calls += 1
            self.is_local_values.append(is_local)
            if module_name == policy_global_function.__module__:
                raise ValueError("function module blocked")
            return None

    policy = BlockModulePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="function module blocked"):
        fory.deserialize(fory.serialize(policy_global_function))
    assert policy.validate_module_calls == 1
    assert policy.is_local_values == [False]


def test_local_function_deserialization_validates_module():
    """Test local function code does not reclassify its module owner."""

    def local_function():
        return "safe"

    class BlockModulePolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0
            self.is_local_values = []

        def validate_module(self, module_name, is_local, **kwargs):
            self.validate_module_calls += 1
            self.is_local_values.append(is_local)
            if module_name == local_function.__module__:
                raise ValueError("local function module blocked")
            return None

    policy = BlockModulePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="local function module blocked"):
        fory.deserialize(fory.serialize(local_function))
    assert policy.validate_module_calls == 1
    assert policy.is_local_values == [False]


def test_local_code_uses_module_locality():
    class BlockRemoteModulePolicy(DeserializationPolicy):
        def __init__(self):
            self.module_calls = []
            self.instantiation_calls = []

        def validate_module(self, module_name, is_local, **kwargs):
            self.module_calls.append((module_name, is_local))
            if not is_local:
                raise ValueError("remote module blocked")

        def authorize_instantiation(self, cls, **kwargs):
            self.instantiation_calls.append((cls, kwargs))

    policy = BlockRemoteModulePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    serializer = FunctionSerializer(fory.type_resolver, type(policy_global_function))
    read_context = FakeReadContext(policy, [2, "subprocess", "forged<locals>"])

    with pytest.raises(ValueError, match="remote module blocked"):
        serializer._deserialize_function(read_context)
    assert policy.module_calls == [("subprocess", False)]
    assert policy.instantiation_calls == []


def test_native_function_deserialization_validates_module():
    """Test validate_module policy hook for native function deserialization."""
    import time

    class BlockModulePolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0
            self.is_local_values = []

        def validate_module(self, module_name, is_local, **kwargs):
            self.validate_module_calls += 1
            self.is_local_values.append(is_local)
            if module_name == "time":
                raise ValueError("time blocked")
            return None

    policy = BlockModulePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="time blocked"):
        fory.deserialize(fory.serialize(time.time))
    assert policy.validate_module_calls == 1
    assert policy.is_local_values == [False]


def test_type_metadata_load_validates_module():
    """Test validate_module policy hook for by-name type metadata loading."""

    class BlockModulePolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0
            self.is_local_values = []

        def validate_module(self, module_name, is_local, **kwargs):
            self.validate_module_calls += 1
            self.is_local_values.append(is_local)
            if module_name == "subprocess":
                raise ValueError("subprocess blocked")
            return None

    policy = BlockModulePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    from pyfory.registry import SharedRegistry, TypeResolver

    resolver = TypeResolver(fory.config, shared_registry=SharedRegistry())
    namespace = resolver.namespace_encoder.encode("subprocess")
    ns_metabytes = resolver.shared_registry.get_encoded_meta_string(namespace)
    typename = resolver.typename_encoder.encode("Popen")
    type_metabytes = resolver.shared_registry.get_encoded_meta_string(typename)

    with pytest.raises(ValueError, match="subprocess blocked"):
        resolver._load_metabytes_to_type_info(ns_metabytes, type_metabytes)
    assert policy.validate_module_calls == 1
    assert policy.is_local_values == [False]


def test_type_metadata_load_validates_class():
    """Test validate_class policy hook for by-name type metadata loading."""

    class BlockClassPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_class_calls = 0

        def validate_class(self, cls, is_local, **kwargs):
            self.validate_class_calls += 1
            if cls.__module__ == "subprocess" and cls.__name__ == "Popen":
                raise ValueError("Popen blocked")
            return None

    policy = BlockClassPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    from pyfory.registry import SharedRegistry, TypeResolver

    resolver = TypeResolver(fory.config, shared_registry=SharedRegistry())
    namespace = resolver.namespace_encoder.encode("subprocess")
    ns_metabytes = resolver.shared_registry.get_encoded_meta_string(namespace)
    typename = resolver.typename_encoder.encode("Popen")
    type_metabytes = resolver.shared_registry.get_encoded_meta_string(typename)

    with pytest.raises(ValueError, match="Popen blocked"):
        resolver._load_metabytes_to_type_info(ns_metabytes, type_metabytes)
    assert policy.validate_class_calls == 1


def test_reduce_global_name_validates_module():
    """Test validate_module policy hook for reduce global-name deserialization."""

    class GlobalNamePayload:
        def __reduce__(self):
            return "subprocess.Popen"

    class BlockModulePolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0
            self.is_local_values = []

        def validate_module(self, module_name, is_local, **kwargs):
            self.validate_module_calls += 1
            self.is_local_values.append(is_local)
            if module_name == "subprocess":
                raise ValueError(f"Module {module_name} blocked")
            return None

    policy = BlockModulePolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="subprocess blocked"):
        fory.deserialize(fory.serialize(GlobalNamePayload()))
    assert policy.validate_module_calls == 1
    assert policy.is_local_values == [False]


def test_reduce_global_name_validates_class():
    """Test validate_class policy hook for reduce global-name deserialization."""

    class GlobalNamePayload:
        def __reduce__(self):
            return "subprocess.Popen"

    class BlockClassPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0
            self.validate_class_calls = 0

        def validate_module(self, module_name, is_local, **kwargs):
            self.validate_module_calls += 1
            assert not is_local
            return None

        def validate_class(self, cls, is_local, **kwargs):
            self.validate_class_calls += 1
            if cls.__module__ == "subprocess" and cls.__name__ == "Popen":
                raise ValueError("subprocess.Popen blocked")
            return None

    policy = BlockClassPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="subprocess.Popen blocked"):
        fory.deserialize(fory.serialize(GlobalNamePayload()))
    assert policy.validate_module_calls == 1
    assert policy.validate_class_calls == 1


def test_reduce_global_name_validates_function():
    """Test validate_function policy hook for reduce builtins-name deserialization."""

    class GlobalNamePayload:
        def __reduce__(self):
            return "eval"

    class BlockFunctionPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0
            self.validate_function_calls = 0

        def validate_module(self, module_name, is_local, **kwargs):
            self.validate_module_calls += 1
            assert not is_local
            return None

        def validate_function(self, func, is_local, **kwargs):
            self.validate_function_calls += 1
            if func.__name__ == "eval":
                raise ValueError("eval blocked")
            return None

    policy = BlockFunctionPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="eval blocked"):
        fory.deserialize(fory.serialize(GlobalNamePayload()))
    assert policy.validate_module_calls == 1
    assert policy.validate_function_calls == 1


def test_reduce_global_method_resolution_uses_validate_method():
    """Test reduce global-name method deserialization uses validate_method."""

    class GlobalNamePayload:
        def __reduce__(self):
            return f"{__name__}.policy_global_bound_method"

    class MethodPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0
            self.validate_method_calls = 0
            self.validate_function_calls = 0

        def validate_module(self, module_name, is_local, **kwargs):
            self.validate_module_calls += 1
            assert not is_local
            return None

        def validate_method(self, method, is_local, **kwargs):
            self.validate_method_calls += 1
            raise ValueError("method blocked")

        def validate_function(self, func, is_local, **kwargs):
            self.validate_function_calls += 1
            return None

    policy = MethodPolicy()
    fory = Fory(xlang=False, ref=True, strict=False, policy=policy, compatible=False)
    with pytest.raises(ValueError, match="method blocked"):
        fory.deserialize(fory.serialize(GlobalNamePayload()))
    assert policy.validate_module_calls == 1
    assert policy.validate_method_calls == 1
    assert policy.validate_function_calls == 0
