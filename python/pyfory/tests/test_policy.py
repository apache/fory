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
from pyfory import Fory, DeserializationPolicy


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
    fory = Fory(ref=True, strict=False, policy=policy)

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
    fory = Fory(ref=True, strict=False, policy=policy)
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
    fory = Fory(ref=True, strict=False, policy=policy)
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
    fory = Fory(ref=False, strict=False, policy=policy)
    data = fory.serialize(SecretHolder("admin", "secret123"))

    result = fory.deserialize(data)
    assert result.username == "admin"
    assert result.password == "***REDACTED***"


def test_policy_with_local_class():
    """Test policy intercepts local class deserialization."""

    def make_local_class():
        class LocalClass:
            pass

        return LocalClass

    LocalCls = make_local_class()

    policy = BlockClassPolicy(blocked_class_names=["LocalClass"])
    fory = Fory(ref=True, strict=False, policy=policy)

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
    fory = Fory(ref=True, strict=False, policy=policy)

    data = fory.serialize(ReducibleClass(42))

    with pytest.raises(ValueError, match="ReducibleClass is blocked"):
        fory.deserialize(data)


def test_policy_allows_safe_operations():
    """Test that policy doesn't interfere with safe built-in types."""
    policy = BlockClassPolicy(blocked_class_names=[])
    fory = Fory(ref=False, strict=False, policy=policy)

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
    fory = Fory(ref=True, strict=False, policy=policy)

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
    fory = Fory(ref=True, strict=False, policy=policy)

    data = fory.serialize(Outer(Inner(42)))

    with pytest.raises(ValueError, match="Inner is blocked"):
        fory.deserialize(data)
