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

from pyfory import Fory


def test_local_class_serialization():
    """Test serialization and deserialization of local classes."""

    def create_local_class():
        """Function that creates a local class."""

        class LocalTestClass:
            def __init__(self, value):
                self.value = value

            def get_value(self):
                return self.value

            def __eq__(self, other):
                return isinstance(other, LocalTestClass) and self.value == other.value

        return LocalTestClass

    # Create an instance of the local class
    LocalClass = create_local_class()

    # Test basic serialization of the class type itself
    fory = Fory(ref=True, strict=False)

    # Serialize the class type
    serialized = fory.serialize(LocalClass)
    assert len(serialized) > 0

    # Deserialize the class type
    deserialized_class = fory.deserialize(serialized)
    assert deserialized_class.__name__ == LocalClass.__name__
    assert deserialized_class.__qualname__ == LocalClass.__qualname__

    # Test that we can create instances of the deserialized class
    instance1 = LocalClass(42)
    instance2 = deserialized_class(42)

    assert instance1.get_value() == instance2.get_value()
    assert instance1.get_value() == 42


def test_local_class_with_closure():
    """Test serialization of a local class that uses closure variables."""

    def create_local_class_with_closure(multiplier):
        """Function that creates a local class using closure variables."""

        class LocalClassWithClosure:
            def __init__(self, value):
                self.value = value

            def get_multiplied_value(self):
                return self.value * multiplier  # Uses closure variable

        return LocalClassWithClosure

    # Create a local class with closure
    LocalClassWithClosure = create_local_class_with_closure(3)

    fory = Fory(ref=True, strict=False)

    # Serialize the class type
    serialized = fory.serialize(LocalClassWithClosure)
    assert len(serialized) > 0

    # Deserialize the class type
    deserialized_class = fory.deserialize(serialized)
    assert deserialized_class.__name__ == LocalClassWithClosure.__name__

    # Test that the closure variable works
    instance1 = LocalClassWithClosure(10)
    instance2 = deserialized_class(10)

    assert instance1.get_multiplied_value() == 30
    assert instance2.get_multiplied_value() == 30
    assert instance1.get_multiplied_value() == instance2.get_multiplied_value()


def test_local_class_with_inheritance():
    """Test local class with inheritance"""

    def create_local_class_with_inheritance():
        class BaseClass:
            def base_method(self):
                return "base"

        class LocalDerivedClass(BaseClass):
            def __init__(self, value):
                self.value = value

            def get_value(self):
                return self.value

        return LocalDerivedClass

    LocalClass = create_local_class_with_inheritance()
    fory = Fory(ref=True, strict=False)

    # Serialize and deserialize the class
    serialized = fory.serialize(LocalClass)
    deserialized = fory.deserialize(serialized)

    # Test inheritance works
    instance1 = LocalClass(42)
    instance2 = deserialized(100)

    assert instance1.get_value() == 42
    assert instance1.base_method() == "base"
    assert instance2.get_value() == 100
    assert instance2.base_method() == "base"


def test_local_class_with_class_variables():
    """Test local class with class variables"""

    def create_class_with_vars():
        class LocalClassWithVars:
            class_var = "shared_value"
            counter = 0

            def __init__(self, value):
                self.value = value
                self.counter += 1
                LocalClassWithVars.counter += 1

            def get_value(self):
                return self.value

            def get_class_var(self):
                return LocalClassWithVars.class_var

            def get_info(self):
                return f"value={self.value}, class_var={self.class_var}, counter={self.counter}"

        return LocalClassWithVars

    LocalClass = create_class_with_vars()
    fory = Fory(ref=True, strict=False)

    # Create some instances to modify class state
    LocalClass(1)  # This increments the counter
    LocalClass(2)  # This increments the counter again

    # Serialize and deserialize the class
    serialized = fory.serialize(LocalClass)
    deserialized = fory.deserialize(serialized)

    # Test class variables are preserved
    instance3 = deserialized(3)

    assert instance3.get_value() == 3
    assert instance3.get_class_var() == "shared_value"
    assert deserialized.class_var == LocalClass.class_var == "shared_value"
    # Counter should be preserved from when class was serialized
    assert deserialized.counter >= 0
    # Test that the get_info method works with natural class references
    info = instance3.get_info()
    assert "value=3" in info
    assert "class_var=shared_value" in info


def test_nested_global_classes():
    """Test serialization of nested global classes"""

    # Define a nested global class (defined at module level but nested in structure)
    class OuterGlobalClass:
        class InnerGlobalClass:
            def __init__(self, inner_value):
                self.inner_value = inner_value

            def get_inner(self):
                return self.inner_value

        def __init__(self, outer_value):
            self.outer_value = outer_value

        def get_outer(self):
            return self.outer_value

        def create_inner(self, inner_val):
            return self.InnerGlobalClass(inner_val)

    fory = Fory(ref=True, strict=False)

    # Test serializing the outer class
    serialized_outer = fory.serialize(OuterGlobalClass)
    deserialized_outer = fory.deserialize(serialized_outer)

    # Test serializing the inner class
    serialized_inner = fory.serialize(OuterGlobalClass.InnerGlobalClass)
    deserialized_inner = fory.deserialize(serialized_inner)

    # Test functionality
    outer_instance1 = OuterGlobalClass(100)
    outer_instance2 = deserialized_outer(200)

    inner_instance1 = OuterGlobalClass.InnerGlobalClass(50)
    inner_instance2 = deserialized_inner(75)

    assert outer_instance1.get_outer() == 100
    assert outer_instance2.get_outer() == 200
    assert inner_instance1.get_inner() == 50
    assert inner_instance2.get_inner() == 75

    # Test that deserialized outer class can create inner instances
    inner_from_deserialized = outer_instance2.create_inner(300)
    assert inner_from_deserialized.get_inner() == 300


def test_complex_local_class_scenarios():
    """Test complex local class scenarios including nested local classes"""

    def create_complex_local_scenario(outer_multiplier):
        def inner_helper(x):
            return x * outer_multiplier

        class OuterLocalClass:
            shared_value = "outer_shared"

            def __init__(self, value):
                self.value = value

            def outer_method(self):
                return inner_helper(self.value)

            def create_inner(self, inner_val):
                def another_helper(y):
                    return y + outer_multiplier

                class InnerLocalClass:
                    def __init__(self, inner_value):
                        self.inner_value = inner_value

                    def inner_method(self):
                        return another_helper(self.inner_value)

                return InnerLocalClass(inner_val)

        return OuterLocalClass

    fory = Fory(ref=True, strict=False)

    # Create complex local class with nested closures
    ComplexLocalClass = create_complex_local_scenario(5)

    # Serialize and deserialize
    serialized = fory.serialize(ComplexLocalClass)
    deserialized = fory.deserialize(serialized)

    # Test functionality
    instance1 = ComplexLocalClass(10)
    instance2 = deserialized(20)

    assert instance1.outer_method() == 50  # 10 * 5
    assert instance2.outer_method() == 100  # 20 * 5

    # Test nested inner class creation and methods
    inner1 = instance1.create_inner(8)
    inner2 = instance2.create_inner(12)

    assert inner1.inner_method() == 13  # 8 + 5
    assert inner2.inner_method() == 17  # 12 + 5


def test_local_class_with_multiple_inheritance():
    """Test local class with multiple inheritance"""

    def create_local_class_with_multiple_inheritance():
        class MixinA:
            def method_a(self):
                return "A"

        class MixinB:
            def method_b(self):
                return "B"

        class LocalMultipleInheritanceClass(MixinA, MixinB):
            def __init__(self, value):
                self.value = value

            def combined_method(self):
                return f"{self.method_a()}{self.method_b()}{self.value}"

        return LocalMultipleInheritanceClass

    fory = Fory(ref=True, strict=False)

    LocalClass = create_local_class_with_multiple_inheritance()

    # Serialize and deserialize
    serialized = fory.serialize(LocalClass)
    deserialized = fory.deserialize(serialized)

    # Test functionality
    instance1 = LocalClass("_test")
    instance2 = deserialized("_check")

    assert instance1.combined_method() == "AB_test"
    assert instance2.combined_method() == "AB_check"
    assert instance1.method_a() == "A"
    assert instance2.method_b() == "B"
