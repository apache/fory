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

import pyfory


# Global classes for testing global class method serialization
class GlobalTestClass:
    """Global test class for method serialization."""

    class_variable = "global_class_value"

    def __init__(self, value):
        self.instance_value = value

    def instance_method(self):
        """Instance method for testing."""
        return f"instance_{self.instance_value}"

    @classmethod
    def class_method(cls):
        """Class method for testing."""
        return f"class_{cls.class_variable}"

    @classmethod
    def class_method_with_args(cls, arg1, arg2):
        """Class method with arguments for testing."""
        return f"class_{cls.class_variable}_{arg1}_{arg2}"

    @staticmethod
    def static_method():
        """Static method for testing."""
        return "static_global_result"

    @staticmethod
    def static_method_with_args(arg1, arg2):
        """Static method with arguments for testing."""
        return f"static_{arg1}_{arg2}"


class AnotherGlobalClass:
    """Another global class to test cross-class method serialization."""

    @classmethod
    def another_class_method(cls):
        return f"another_{cls.__name__}"


class GlobalClassWithInheritance(GlobalTestClass):
    """Global class with inheritance."""

    class_variable = "inherited_value"

    @classmethod
    def inherited_class_method(cls):
        return f"inherited_{cls.class_variable}"


class TestMethodSerialization:
    """Test class for method serialization scenarios."""

    def test_instance_method_serialization(self):
        """Test serialization of instance methods."""
        fory = pyfory.Fory(strict=False, ref=True)

        class TestClass:
            def __init__(self, value):
                self.value = value

            def instance_method(self):
                return self.value * 2

        obj = TestClass(5)
        method = obj.instance_method

        # Test serialization/deserialization
        serialized = fory.serialize(method)
        deserialized = fory.deserialize(serialized)

        assert method() == deserialized()
        assert method() == 10

    def test_classmethod_serialization(self):
        """Test serialization of class methods."""
        fory = pyfory.Fory(strict=False, ref=True)

        class TestClass:
            class_var = 42

            @classmethod
            def class_method(cls):
                return cls.class_var

        method = TestClass.class_method

        # Test serialization/deserialization
        serialized = fory.serialize(method)
        deserialized = fory.deserialize(serialized)

        assert method() == deserialized()
        assert method() == 42

    def test_staticmethod_serialization(self):
        """Test serialization of static methods."""
        fory = pyfory.Fory(strict=False, ref=True)

        class TestClass:
            @staticmethod
            def static_method():
                return "static_result"

        method = TestClass.static_method

        # Test serialization/deserialization
        serialized = fory.serialize(method)
        deserialized = fory.deserialize(serialized)

        assert method() == deserialized()
        assert method() == "static_result"

    def test_method_with_args_serialization(self):
        """Test serialization of methods with arguments."""
        fory = pyfory.Fory(strict=False, ref=True)

        class TestClass:
            def __init__(self, base):
                self.base = base

            def add(self, x):
                return self.base + x

            @classmethod
            def multiply(cls, a, b):
                return a * b

            @staticmethod
            def subtract(a, b):
                return a - b

        obj = TestClass(10)

        # Test instance method
        instance_method = obj.add
        serialized = fory.serialize(instance_method)
        deserialized = fory.deserialize(serialized)
        assert instance_method(5) == deserialized(5)
        assert instance_method(5) == 15

        # Test classmethod
        class_method = TestClass.multiply
        serialized = fory.serialize(class_method)
        deserialized = fory.deserialize(serialized)
        assert class_method(3, 4) == deserialized(3, 4)
        assert class_method(3, 4) == 12

        # Test staticmethod
        static_method = TestClass.subtract
        serialized = fory.serialize(static_method)
        deserialized = fory.deserialize(serialized)
        assert static_method(10, 3) == deserialized(10, 3)
        assert static_method(10, 3) == 7

    def test_nested_class_method_serialization(self):
        """Test serialization of methods from nested classes."""
        fory = pyfory.Fory(strict=False, ref=True)

        class OuterClass:
            class InnerClass:
                @classmethod
                def inner_class_method(cls):
                    return "inner_result"

        method = OuterClass.InnerClass.inner_class_method

        # Test serialization/deserialization
        serialized = fory.serialize(method)
        deserialized = fory.deserialize(serialized)

        assert method() == deserialized()
        assert method() == "inner_result"


def test_classmethod_serialization():
    """Standalone test for classmethod serialization - reproduces the original error."""
    fory = pyfory.Fory(strict=False, ref=True)

    class A:
        @classmethod
        def f(cls):
            pass

        @staticmethod
        def g():
            return A

    method = A.f
    serialized = fory.serialize(method)
    deserialized = fory.deserialize(serialized)

    assert isinstance(deserialized, type(method))
    # Check that the class names are the same (the classes might be different instances due to deserialization)
    assert deserialized.__self__.__name__ == method.__self__.__name__
    assert deserialized.__func__.__name__ == method.__func__.__name__

    # Most importantly, check that the deserialized method is callable and has the same behavior
    # Both should return None for this test case
    original_result = method()
    deserialized_result = deserialized()
    assert original_result == deserialized_result


def test_staticmethod_serialization():
    """Standalone test for staticmethod serialization."""
    fory = pyfory.Fory(strict=False, ref=True)

    class A:
        @staticmethod
        def g():
            return "static_result"

    method = A.g
    serialized = fory.serialize(method)
    deserialized = fory.deserialize(serialized)

    assert method() == deserialized()
    assert method() == "static_result"


# Global class method tests
def test_global_classmethod_serialization():
    """Test serialization of global class methods."""
    fory = pyfory.Fory(strict=False, ref=True)

    method = GlobalTestClass.class_method
    serialized = fory.serialize(method)
    deserialized = fory.deserialize(serialized)

    assert isinstance(deserialized, type(method))
    assert deserialized() == method()
    assert deserialized() == "class_global_class_value"


def test_global_classmethod_with_args():
    """Test serialization of global class methods with arguments."""
    fory = pyfory.Fory(strict=False, ref=True)

    method = GlobalTestClass.class_method_with_args
    serialized = fory.serialize(method)
    deserialized = fory.deserialize(serialized)

    args = ("arg1", "arg2")
    assert deserialized(*args) == method(*args)
    assert deserialized(*args) == "class_global_class_value_arg1_arg2"


def test_global_staticmethod_serialization():
    """Test serialization of global static methods."""
    fory = pyfory.Fory(strict=False, ref=True)

    method = GlobalTestClass.static_method
    serialized = fory.serialize(method)
    deserialized = fory.deserialize(serialized)

    assert deserialized() == method()
    assert deserialized() == "static_global_result"


def test_global_staticmethod_with_args():
    """Test serialization of global static methods with arguments."""
    fory = pyfory.Fory(strict=False, ref=True)

    method = GlobalTestClass.static_method_with_args
    serialized = fory.serialize(method)
    deserialized = fory.deserialize(serialized)

    args = ("test1", "test2")
    assert deserialized(*args) == method(*args)
    assert deserialized(*args) == "static_test1_test2"


def test_global_instance_method_serialization():
    """Test serialization of global instance methods."""
    fory = pyfory.Fory(strict=False, ref=True)

    obj = GlobalTestClass("test_value")
    method = obj.instance_method
    serialized = fory.serialize(method)
    deserialized = fory.deserialize(serialized)

    assert deserialized() == method()
    assert deserialized() == "instance_test_value"


def test_multiple_global_classes():
    """Test serialization of methods from multiple global classes."""
    fory = pyfory.Fory(strict=False, ref=True)

    # Test methods from different global classes
    method1 = GlobalTestClass.class_method
    method2 = AnotherGlobalClass.another_class_method

    serialized1 = fory.serialize(method1)
    serialized2 = fory.serialize(method2)

    deserialized1 = fory.deserialize(serialized1)
    deserialized2 = fory.deserialize(serialized2)

    assert deserialized1() == method1()
    assert deserialized2() == method2()
    assert deserialized1() == "class_global_class_value"
    assert deserialized2() == "another_AnotherGlobalClass"


def test_global_class_inheritance():
    """Test serialization of methods from global classes with inheritance."""
    fory = pyfory.Fory(strict=False, ref=True)

    # Test inherited class method
    method = GlobalClassWithInheritance.inherited_class_method
    serialized = fory.serialize(method)
    deserialized = fory.deserialize(serialized)

    assert deserialized() == method()
    assert deserialized() == "inherited_inherited_value"

    # Test parent class method on child class
    parent_method = GlobalClassWithInheritance.class_method
    serialized_parent = fory.serialize(parent_method)
    deserialized_parent = fory.deserialize(serialized_parent)

    assert deserialized_parent() == parent_method()
    assert deserialized_parent() == "class_inherited_value"  # Uses child's class_variable


def test_global_methods_without_ref_tracking():
    """Test serialization of global class methods without reference tracking."""
    fory = pyfory.Fory(strict=False, ref=False)

    # Global classes should work even without track_ref
    method = GlobalTestClass.class_method
    serialized = fory.serialize(method)
    deserialized = fory.deserialize(serialized)

    assert deserialized() == method()
    assert deserialized() == "class_global_class_value"


def test_global_method_collection():
    """Test serialization of collections containing global methods."""
    fory = pyfory.Fory(strict=False, ref=True)

    methods = [
        GlobalTestClass.class_method,
        GlobalTestClass.static_method,
        AnotherGlobalClass.another_class_method,
    ]

    serialized = fory.serialize(methods)
    deserialized = fory.deserialize(serialized)

    assert len(deserialized) == len(methods)
    for original, restored in zip(methods, deserialized):
        assert original() == restored()


def test_global_method_in_dict():
    """Test serialization of dictionaries containing global methods."""
    fory = pyfory.Fory(strict=False, ref=True)

    method_dict = {
        "class_method": GlobalTestClass.class_method,
        "static_method": GlobalTestClass.static_method,
        "another_method": AnotherGlobalClass.another_class_method,
    }

    serialized = fory.serialize(method_dict)
    deserialized = fory.deserialize(serialized)

    assert len(deserialized) == len(method_dict)
    for key in method_dict:
        assert method_dict[key]() == deserialized[key]()


if __name__ == "__main__":
    # Run tests
    import pytest

    pytest.main([__file__, "-v"])
