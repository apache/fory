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

from dataclasses import dataclass
from typing import Dict, Any, List

import os
import pytest
import typing

import pyfory
from pyfory import Fory
from pyfory.error import TypeUnregisteredError
from pyfory.serializer import DataClassSerializer


def ser_de(fory, obj):
    binary = fory.serialize(obj)
    return fory.deserialize(binary)


@dataclass
class SimpleObject:
    f1: Dict[pyfory.Int32Type, pyfory.Float64Type] = None


@dataclass
class ComplexObject:
    f1: Any = None
    f2: Any = None
    f3: pyfory.Int8Type = 0
    f4: pyfory.Int16Type = 0
    f5: pyfory.Int32Type = 0
    f6: pyfory.Int64Type = 0
    f7: pyfory.Float32Type = 0
    f8: pyfory.Float64Type = 0
    f9: List[pyfory.Int16Type] = None
    f10: Dict[pyfory.Int32Type, pyfory.Float64Type] = None


def test_struct():
    fory = Fory(xlang=True, ref=True)
    fory.register_type(SimpleObject, typename="SimpleObject")
    fory.register_type(ComplexObject, typename="example.ComplexObject")
    o = SimpleObject(f1={1: 1.0 / 3})
    assert ser_de(fory, o) == o

    o = ComplexObject(
        f1="str",
        f2={"k1": -1, "k2": [1, 2]},
        f3=2**7 - 1,
        f4=2**15 - 1,
        f5=2**31 - 1,
        f6=2**63 - 1,
        f7=1.0 / 2,
        f8=2.0 / 3,
        f9=[1, 2],
        f10={1: 1.0 / 3, 100: 2 / 7.0},
    )
    assert ser_de(fory, o) == o
    with pytest.raises(AssertionError):
        assert ser_de(fory, ComplexObject(f7=1.0 / 3)) == ComplexObject(f7=1.0 / 3)
    with pytest.raises(OverflowError):
        assert ser_de(fory, ComplexObject(f3=2**8)) == ComplexObject(f3=2**8)
    with pytest.raises(OverflowError):
        assert ser_de(fory, ComplexObject(f4=2**16)) == ComplexObject(f4=2**16)
    with pytest.raises(OverflowError):
        assert ser_de(fory, ComplexObject(f5=2**32)) == ComplexObject(f5=2**32)
    with pytest.raises(OverflowError):
        assert ser_de(fory, ComplexObject(f6=2**64)) == ComplexObject(f6=2**64)


@dataclass
class SuperClass1:
    f1: Any = None
    f2: pyfory.Int8Type = 0


@dataclass
class ChildClass1(SuperClass1):
    f3: Dict[str, pyfory.Float64Type] = None


def test_strict():
    fory = Fory(xlang=False, ref=True)
    obj = ChildClass1(f1="a", f2=-10, f3={"a": -10.0, "b": 1 / 3})
    with pytest.raises(TypeUnregisteredError):
        fory.serialize(obj)


def test_inheritance():
    type_hints = typing.get_type_hints(ChildClass1)
    print(type_hints)
    assert type_hints.keys() == {"f1", "f2", "f3"}
    fory = Fory(xlang=False, ref=True, strict=False)
    obj = ChildClass1(f1="a", f2=-10, f3={"a": -10.0, "b": 1 / 3})
    assert ser_de(fory, obj) == obj
    assert type(fory.type_resolver.get_serializer(ChildClass1)) is pyfory.DataClassSerializer


@dataclass
class DataClassObject:
    f_int: int
    f_float: float
    f_str: str
    f_bool: bool
    f_list: List[int]
    f_dict: Dict[str, float]
    f_any: Any
    f_complex: ComplexObject = None

    @classmethod
    def create(cls):
        return cls(
            f_int=42,
            f_float=3.14159,
            f_str="test_codegen",
            f_bool=True,
            f_list=[1, 2, 3],
            f_dict={"key": 1.5},
            f_any="any_data",
            f_complex=None,
        )


def test_data_class_serializer_xlang():
    fory = Fory(xlang=True, ref=True)
    fory.register_type(ComplexObject, typename="example.ComplexObject")
    fory.register_type(DataClassObject, typename="example.TestDataClassObject")

    complex_data = ComplexObject(
        f1="nested_str",
        f5=100,
        f8=3.14,
        f10={10: 1.0, 20: 2.0},
    )
    obj_original = DataClassObject(
        f_int=123,
        f_float=45.67,
        f_str="hello xlang",
        f_bool=True,
        f_list=[1, 2, 3, 4, 5],
        f_dict={"a": 1.1, "b": 2.2},
        f_any="any_value",
        f_complex=complex_data,
    )

    obj_deserialized = ser_de(fory, obj_original)

    assert obj_deserialized == obj_original
    assert obj_deserialized.f_int == obj_original.f_int
    assert obj_deserialized.f_float == obj_original.f_float
    assert obj_deserialized.f_str == obj_original.f_str
    assert obj_deserialized.f_bool == obj_original.f_bool
    assert obj_deserialized.f_list == obj_original.f_list
    assert obj_deserialized.f_dict == obj_original.f_dict
    assert obj_deserialized.f_any == obj_original.f_any
    assert obj_deserialized.f_complex == obj_original.f_complex
    assert type(fory.type_resolver.get_serializer(DataClassObject)) is pyfory.DataClassSerializer
    # Ensure it's using xlang mode indirectly, by checking no JIT methods if possible,
    # or by ensuring it was registered with _register_xtype which now uses DataClassSerializer(xlang=True)
    # For now, the registration path check is implicit via Language.XLANG usage.
    # We can also check if the hash is non-zero if it was computed,
    # or if the _serializers attribute exists.
    serializer_instance = fory.type_resolver.get_serializer(DataClassObject)
    assert hasattr(serializer_instance, "_serializers")  # xlang mode creates this
    assert serializer_instance._xlang is True

    # Test with None for a complex field
    obj_with_none_complex = DataClassObject(
        f_int=789,
        f_float=12.34,
        f_str="another string",
        f_bool=False,
        f_list=[10, 20],
        f_dict={"x": 7.7, "y": 8.8},
        f_any=None,
        f_complex=None,
    )
    obj_deserialized_none = ser_de(fory, obj_with_none_complex)
    assert obj_deserialized_none == obj_with_none_complex


def test_data_class_serializer_xlang_codegen():
    """Test that DataClassSerializer generates xwrite/xread methods correctly in xlang mode."""
    fory = Fory(xlang=True, ref=True)

    # Register types first
    fory.register_type(ComplexObject, typename="example.ComplexObject")
    fory.register_type(DataClassObject, typename="example.TestDataClassObject")

    # trigger lazy serializer replace
    fory.serialize(DataClassObject.create())
    # Get the serializer that was created during registration
    serializer = fory.type_resolver.get_serializer(DataClassObject)

    # Check that the generated methods exist
    assert hasattr(serializer, "_generated_xwrite_method"), "Generated xwrite method should exist"
    assert hasattr(serializer, "_generated_xread_method"), "Generated xread method should exist"
    assert hasattr(serializer, "_xwrite_method_code"), "Generated xwrite method code should exist"
    assert hasattr(serializer, "_xread_method_code"), "Generated xread method code should exist"

    # Check that the serializer is in xlang mode
    assert serializer._xlang is True
    assert hasattr(serializer, "_serializers")
    assert len(serializer._serializers) == len(serializer._field_names)

    # Test that the generated methods work correctly through the normal serialization flow
    test_obj = DataClassObject(
        f_int=42,
        f_float=3.14159,
        f_str="test_codegen",
        f_bool=True,
        f_list=[1, 2, 3],
        f_dict={"key": 1.5},
        f_any="any_data",
        f_complex=None,
    )

    # Test serialization and deserialization using the normal fory flow
    # This will use the generated methods internally
    binary = fory.serialize(test_obj)
    deserialized_obj = fory.deserialize(binary)

    # Verify the results
    assert deserialized_obj.f_int == test_obj.f_int
    assert deserialized_obj.f_float == test_obj.f_float
    assert deserialized_obj.f_str == test_obj.f_str
    assert deserialized_obj.f_bool == test_obj.f_bool
    assert deserialized_obj.f_list == test_obj.f_list
    assert deserialized_obj.f_dict == test_obj.f_dict
    assert deserialized_obj.f_any == test_obj.f_any
    assert deserialized_obj.f_complex == test_obj.f_complex


def test_data_class_serializer_xlang_codegen_with_jit():
    """Test that DataClassSerializer JIT compilation works correctly when enabled."""
    # Save the original environment variable
    original_jit_setting = os.environ.get("ENABLE_FORY_PYTHON_JIT")

    try:
        # Enable JIT
        os.environ["ENABLE_FORY_PYTHON_JIT"] = "True"

        # Import after setting environment variable to ensure it takes effect
        import importlib
        import pyfory.serializer

        importlib.reload(pyfory.serializer)

        fory = Fory(xlang=True, ref=True)

        # Register types first
        fory.register_type(ComplexObject, typename="example.ComplexObject")
        fory.register_type(DataClassObject, typename="example.TestDataClassObject")

        # Get the serializer that was created during registration
        serializer = fory.type_resolver.get_serializer(DataClassObject)

        # Check that JIT methods are assigned when JIT is enabled
        # The methods should be the generated functions, not the original instance methods
        assert callable(serializer.xwrite)
        assert callable(serializer.xread)

        # Test that the JIT-compiled methods work through normal serialization
        test_obj = DataClassObject(
            f_int=123,
            f_float=45.67,
            f_str="jit_test",
            f_bool=False,
            f_list=[10, 20, 30],
            f_dict={"jit": 2.5},
            f_any={"nested": "data"},
            f_complex=None,
        )

        # Use normal serialization flow which will use the JIT-compiled methods internally
        binary = fory.serialize(test_obj)
        deserialized_obj = fory.deserialize(binary)

        assert deserialized_obj.f_int == test_obj.f_int
        assert deserialized_obj.f_float == test_obj.f_float
        assert deserialized_obj.f_str == test_obj.f_str
        assert deserialized_obj.f_bool == test_obj.f_bool
        assert deserialized_obj.f_list == test_obj.f_list
        assert deserialized_obj.f_dict == test_obj.f_dict
        assert deserialized_obj.f_any == test_obj.f_any
        assert deserialized_obj.f_complex == test_obj.f_complex

    finally:
        # Restore original environment variable
        if original_jit_setting is None:
            os.environ.pop("ENABLE_FORY_PYTHON_JIT", None)
        else:
            os.environ["ENABLE_FORY_PYTHON_JIT"] = original_jit_setting

        # Reload to restore the original state
        importlib.reload(pyfory.serializer)


def test_data_class_serializer_xlang_codegen_generated_code():
    """Test that the generated code contains expected elements."""
    fory = Fory(xlang=True, ref=True)

    # Register types first
    fory.register_type(ComplexObject, typename="example.ComplexObject")
    fory.register_type(DataClassObject, typename="example.TestDataClassObject")

    # trigger lazy serializer replace
    fory.serialize(DataClassObject.create())
    # Get the serializer that was created during registration
    serializer = fory.type_resolver.get_serializer(DataClassObject)

    # Check that generated code exists and contains expected elements
    xwrite_code = serializer._xwrite_method_code
    xread_code = serializer._xread_method_code

    assert isinstance(xwrite_code, str)
    assert isinstance(xread_code, str)

    # Check that xwrite code contains expected elements
    assert "def xwrite_" in xwrite_code
    assert "buffer.write_int32" in xwrite_code  # Hash writing
    assert "fory.xserialize_ref" in xwrite_code  # Field serialization

    # Check that xread code contains expected elements
    assert "def xread_" in xread_code
    assert "buffer.read_int32" in xread_code  # Hash reading
    assert "fory.xdeserialize_ref" in xread_code  # Field deserialization
    assert "TypeNotCompatibleError" in xread_code  # Hash validation

    # Check that field names are referenced in the code
    for field_name in serializer._field_names:
        # Field names should appear in the generated code
        assert field_name in xwrite_code or field_name in xread_code


def test_data_class_serializer_xlang_vs_non_xlang():
    """Test that xlang and non-xlang modes produce different serializers."""
    fory_xlang = Fory(xlang=True, ref=True)
    fory_python = Fory(xlang=False, ref=True, strict=False)

    # Register types for xlang
    fory_xlang.register_type(ComplexObject, typename="example.ComplexObject")
    fory_xlang.register_type(DataClassObject, typename="example.TestDataClassObject")

    # trigger lazy serializer replace
    fory_xlang.serialize(DataClassObject.create())
    # For Python mode, we can create the serializer directly since it doesn't require registration
    serializer_xlang = fory_xlang.type_resolver.get_serializer(DataClassObject)
    serializer_python = DataClassSerializer(fory_python, DataClassObject, xlang=False)

    # xlang serializer should have xlang-specific attributes
    assert serializer_xlang._xlang is True
    assert hasattr(serializer_xlang, "_serializers")
    assert hasattr(serializer_xlang, "_generated_xwrite_method")
    assert hasattr(serializer_xlang, "_generated_xread_method")

    # non-xlang serializer should have different attributes
    assert serializer_python._xlang is False
    assert hasattr(serializer_python, "_generated_write_method")
    assert hasattr(serializer_python, "_generated_read_method")

    # They should have different method implementations
    assert serializer_xlang._generated_xwrite_method != serializer_python._generated_write_method
    assert serializer_xlang._generated_xread_method != serializer_python._generated_read_method
