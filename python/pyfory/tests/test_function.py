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


def test_lambda_functions_serialization():
    """Tests serialization of lambda functions."""
    fory = pyfory.Fory()
    test_input = 5

    # Register the necessary types
    fory.register_type(tuple)
    fory.register_type(list)
    # dict is already registered by default with MapSerializer

    # Simple lambda
    simple_lambda = lambda x: x * 2  # noqa: E731
    fory.register_type(type(simple_lambda))
    serialized = fory.serialize(simple_lambda)
    deserialized = fory.deserialize(serialized)
    assert simple_lambda(test_input) == deserialized(test_input)

    # Complex lambda with closure
    multiplier = 3
    closure_lambda = lambda x: x * multiplier  # noqa: E731
    serialized = fory.serialize(closure_lambda)
    deserialized = fory.deserialize(serialized)
    assert closure_lambda(test_input) == deserialized(test_input)


def test_regular_functions_serialization():
    """Tests serialization of regular functions."""
    fory = pyfory.Fory()
    test_input = 5

    def add_one(x):
        return x + 1

    def complex_function(a, b, c=10):
        """A more complex function with default arguments."""
        return a * b + c

    # Test regular function
    fory.register_type(type(add_one))
    serialized = fory.serialize(add_one)
    deserialized = fory.deserialize(serialized)
    assert add_one(test_input) == deserialized(test_input)

    # Register the necessary types for complex functions
    fory.register_type(tuple)
    fory.register_type(list)
    # dict is already registered by default with MapSerializer

    # Test complex function
    serialized = fory.serialize(complex_function)
    deserialized = fory.deserialize(serialized)
    assert complex_function(2, 3) == deserialized(2, 3)


def test_nested_functions_serialization():
    """Tests serialization of nested functions."""
    fory = pyfory.Fory()

    # Register the necessary types
    fory.register_type(tuple)
    fory.register_type(list)
    # dict is already registered by default with MapSerializer

    def outer_function(x):
        def inner_function(y):
            return x + y

        return inner_function

    # Create a nested function
    nested_func = outer_function(10)
    fory.register_type(type(nested_func))

    serialized = fory.serialize(nested_func)
    deserialized = fory.deserialize(serialized)

    assert nested_func(5) == deserialized(5)


def test_local_class_serialization():
    """Tests serialization of local classes."""
    fory = pyfory.Fory()

    # Register the necessary types
    fory.register_type(tuple)
    fory.register_type(list)
    # dict is already registered by default with MapSerializer

    def create_local_class():
        from dataclasses import dataclass

        @dataclass
        class LocalClass:
            value: int
            name: str

        return LocalClass(42, "test")

    local_obj = create_local_class()
    fory.register_type(type(local_obj))

    serialized = fory.serialize(local_obj)
    deserialized = fory.deserialize(serialized)

    assert local_obj == deserialized
