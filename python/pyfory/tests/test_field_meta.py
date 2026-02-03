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
Comprehensive tests for pyfory.field() field metadata support.
"""

import pytest
from dataclasses import dataclass, fields
from typing import Optional, List, Dict

import pyfory
from pyfory import Fory, int32, float64
from pyfory.field import (
    ForyFieldMeta,
    extract_field_meta,
)


class TestFieldFunction:
    """Tests for the pyfory.field() function."""

    def test_basic_field_creation(self):
        """Test basic field creation with tag ID."""

        @dataclass
        class TestClass:
            name: str = pyfory.field(0)
            age: int32 = pyfory.field(1)

        # Check that fields have metadata
        for f in fields(TestClass):
            meta = extract_field_meta(f)
            assert meta is not None
            assert meta.id >= 0

    def test_field_with_default_value(self):
        """Test field with default value."""

        @dataclass
        class TestClass:
            name: str = pyfory.field(0, default="default_name")
            count: int32 = pyfory.field(1, default=0)

        obj = TestClass()
        assert obj.name == "default_name"
        assert obj.count == 0

    def test_field_with_default_factory(self):
        """Test field with default_factory."""

        @dataclass
        class TestClass:
            items: List[int] = pyfory.field(0, default_factory=list)
            data: Dict[str, int] = pyfory.field(1, default_factory=dict)

        obj = TestClass()
        assert obj.items == []
        assert obj.data == {}

    def test_field_name_encoding(self):
        """Test field with id=-1 uses field name encoding."""

        @dataclass
        class TestClass:
            name: str = pyfory.field(-1)

        meta = extract_field_meta(fields(TestClass)[0])
        assert meta.id == -1
        assert not meta.uses_tag_id()

    def test_field_tag_id_encoding(self):
        """Test field with id>=0 uses tag ID encoding."""

        @dataclass
        class TestClass:
            name: str = pyfory.field(0)
            age: int32 = pyfory.field(5)
            score: float64 = pyfory.field(100)

        for f in fields(TestClass):
            meta = extract_field_meta(f)
            assert meta.uses_tag_id()

    def test_nullable_field(self):
        """Test nullable field."""

        @dataclass
        class TestClass:
            optional_name: Optional[str] = pyfory.field(0, nullable=True)

        meta = extract_field_meta(fields(TestClass)[0])
        assert meta.nullable is True

    def test_ref_field(self):
        """Test ref tracking field."""

        @dataclass
        class TestClass:
            friends: List["TestClass"] = pyfory.field(0, ref=True, default_factory=list)

        meta = extract_field_meta(fields(TestClass)[0])
        assert meta.ref is True

    def test_ignore_field(self):
        """Test ignored field."""

        @dataclass
        class TestClass:
            name: str = pyfory.field(0)
            _cache: dict = pyfory.field(-1, ignore=True, default_factory=dict)

        cache_field = [f for f in fields(TestClass) if f.name == "_cache"][0]
        meta = extract_field_meta(cache_field)
        assert meta.ignore is True


class TestForyFieldMeta:
    """Tests for ForyFieldMeta dataclass."""

    def test_uses_tag_id(self):
        """Test uses_tag_id method."""
        meta_tag = ForyFieldMeta(id=0)
        meta_name = ForyFieldMeta(id=-1)

        assert meta_tag.uses_tag_id() is True
        assert meta_name.uses_tag_id() is False

    def test_default_values(self):
        """Test default values for ForyFieldMeta."""
        meta = ForyFieldMeta(id=0)
        assert meta.nullable is False
        assert meta.ref is False
        assert meta.ignore is False


class TestValidation:
    """Tests for field metadata validation."""

    def test_duplicate_tag_id_validation(self):
        """Test that duplicate tag IDs raise ValueError at serialization time."""

        @dataclass
        class TestClass:
            field1: str = pyfory.field(0)
            field2: int32 = pyfory.field(0)  # Duplicate ID

        fory = Fory(xlang=True)
        fory.register_type(TestClass, typename="test.TestClass")
        obj = TestClass(field1="a", field2=1)
        # Validation happens when serializer is created
        with pytest.raises(ValueError, match="Duplicate tag ID"):
            fory.serialize(obj)

    def test_optional_without_nullable_raises(self):
        """Test that Optional[T] with nullable=False raises ValueError at serialization time."""

        @dataclass
        class TestClass:
            # This should raise because Optional requires nullable=True
            name: Optional[str] = pyfory.field(0, nullable=False)

        fory = Fory(xlang=True)
        fory.register_type(TestClass, typename="test.TestClass")
        obj = TestClass(name="test")
        # Validation happens when serializer is created
        with pytest.raises(ValueError, match="Optional"):
            fory.serialize(obj)

    def test_negative_id_below_minus_one(self):
        """Test that id < -1 raises ValueError."""
        with pytest.raises(ValueError, match="id must be >= -1"):
            pyfory.field(-2)

    def test_non_int_id(self):
        """Test that non-integer id raises TypeError."""
        with pytest.raises(TypeError, match="id must be an int"):
            pyfory.field("invalid")


class TestSerialization:
    """Tests for serialization with field metadata."""

    def test_basic_serialization(self):
        """Test basic serialization with field metadata."""

        @dataclass
        class User:
            id: int32 = pyfory.field(0)
            name: str = pyfory.field(1)

        fory = Fory(xlang=True, ref=True)
        fory.register_type(User, typename="test.User")

        user = User(id=42, name="Alice")
        data = fory.serialize(user)
        restored = fory.deserialize(data)

        assert restored.id == 42
        assert restored.name == "Alice"

    def test_nullable_serialization(self):
        """Test serialization of nullable fields."""

        @dataclass
        class Profile:
            name: str = pyfory.field(0)
            email: Optional[str] = pyfory.field(1, nullable=True)

        fory = Fory(xlang=True, ref=True)
        fory.register_type(Profile, typename="test.Profile")

        # Test with value
        profile1 = Profile(name="Bob", email="bob@test.com")
        data1 = fory.serialize(profile1)
        restored1 = fory.deserialize(data1)
        assert restored1.email == "bob@test.com"

        # Test with None
        profile2 = Profile(name="Charlie", email=None)
        data2 = fory.serialize(profile2)
        restored2 = fory.deserialize(data2)
        assert restored2.email is None

    def test_ignore_field_serialization(self):
        """Test that ignored fields are not serialized."""

        @dataclass
        class CachedData:
            value: int32 = pyfory.field(0)
            _cache: dict = pyfory.field(-1, ignore=True, default_factory=dict)

        fory = Fory(xlang=True, ref=True)
        fory.register_type(CachedData, typename="test.CachedData")

        obj = CachedData(value=100)
        obj._cache = {"key": "should_not_serialize"}

        data = fory.serialize(obj)
        restored = fory.deserialize(data)

        assert restored.value == 100
        # Ignored fields are not serialized/deserialized, so the attribute
        # won't exist on the restored object (created via __new__)
        assert not hasattr(restored, "_cache") or restored._cache == {}

    def test_ref_tracking_field(self):
        """Test ref tracking with field-level control."""

        # Test ref tracking using a list with shared object references
        @dataclass
        class Container:
            name: str = pyfory.field(0)
            items: List[str] = pyfory.field(1, ref=True, default_factory=list)

        fory = Fory(xlang=True, ref=True)
        fory.register_type(Container, typename="test.Container")

        # Create shared list reference
        shared_list = ["a", "b", "c"]
        container = Container(name="test", items=shared_list)

        data = fory.serialize(container)
        restored = fory.deserialize(data)

        assert restored.name == "test"
        assert restored.items == ["a", "b", "c"]

    def test_mixed_fields(self):
        """Test mixing fields with and without explicit metadata."""

        @dataclass
        class MixedClass:
            # Explicit tag ID
            id: int32 = pyfory.field(0)
            # Field name encoding
            description: str = pyfory.field(-1)
            # No pyfory.field() - uses defaults
            count: int = 0

        fory = Fory(xlang=True, ref=True)
        fory.register_type(MixedClass, typename="test.MixedClass")

        obj = MixedClass(id=1, description="test", count=5)
        data = fory.serialize(obj)
        restored = fory.deserialize(data)

        assert restored.id == 1
        assert restored.description == "test"
        assert restored.count == 5


class TestInheritance:
    """Tests for field metadata with inheritance."""

    def test_parent_child_fields(self):
        """Test that parent fields come before child fields."""

        @dataclass
        class Parent:
            parent_field: str = pyfory.field(0)

        @dataclass
        class Child(Parent):
            child_field: int32 = pyfory.field(1)

        fory = Fory(xlang=True, ref=True)
        fory.register_type(Child, typename="test.Child")

        obj = Child(parent_field="parent", child_field=42)
        data = fory.serialize(obj)
        restored = fory.deserialize(data)

        assert restored.parent_field == "parent"
        assert restored.child_field == 42


class TestFingerprint:
    """Tests for fingerprint computation with field metadata."""

    def test_fingerprint_includes_tag_id(self):
        """Test that fingerprint changes when tag ID changes."""

        @dataclass
        class V1:
            name: str = pyfory.field(0)

        @dataclass
        class V2:
            name: str = pyfory.field(1)  # Different tag ID

        fory1 = Fory(xlang=True)
        fory2 = Fory(xlang=True)

        fory1.register_type(V1, typename="test.Type")
        fory2.register_type(V2, typename="test.Type")

        # Serialize to trigger actual serializer creation
        fory1.serialize(V1(name="a"))
        fory2.serialize(V2(name="b"))

        # Get the actual serializers after serialization
        serializer1 = fory1.type_resolver.get_type_info(V1).serializer
        serializer2 = fory2.type_resolver.get_type_info(V2).serializer

        # Fingerprints should be different due to different tag IDs
        assert serializer1._hash != serializer2._hash

    def test_fingerprint_includes_ref_flag(self):
        """Test that fingerprint includes ref flag."""

        @dataclass
        class WithRef:
            items: List[int] = pyfory.field(0, ref=True, default_factory=list)

        @dataclass
        class WithoutRef:
            items: List[int] = pyfory.field(0, ref=False, default_factory=list)

        fory1 = Fory(xlang=True, ref=True)
        fory2 = Fory(xlang=True, ref=True)

        fory1.register_type(WithRef, typename="test.Type")
        fory2.register_type(WithoutRef, typename="test.Type")

        # Serialize to trigger actual serializer creation
        fory1.serialize(WithRef())
        fory2.serialize(WithoutRef())

        # Get the actual serializers after serialization
        serializer1 = fory1.type_resolver.get_type_info(WithRef).serializer
        serializer2 = fory2.type_resolver.get_type_info(WithoutRef).serializer

        # Fingerprints should be different due to different ref flags
        assert serializer1._hash != serializer2._hash


class TestTypeDefEncoding:
    """Tests for TypeDef encoding with TAG_ID support."""

    def test_tag_id_encoding(self):
        """Test that TAG_ID encoding is used for fields with tag_id >= 0."""

        @dataclass
        class TestClass:
            field0: int32 = pyfory.field(0)
            field1: str = pyfory.field(5)
            field2: float64 = pyfory.field(15)  # Overflow threshold

        fory = Fory(xlang=True, compatible=True)
        fory.register_type(TestClass, typename="test.TestClass")

        obj = TestClass(field0=1, field1="test", field2=3.14)
        data = fory.serialize(obj)
        restored = fory.deserialize(data)

        assert restored.field0 == 1
        assert restored.field1 == "test"
        assert abs(restored.field2 - 3.14) < 0.001

    def test_large_tag_id(self):
        """Test TAG_ID encoding with large tag IDs (>= 15)."""

        @dataclass
        class TestClass:
            field: int32 = pyfory.field(100)

        fory = Fory(xlang=True, compatible=True)
        fory.register_type(TestClass, typename="test.TestClass")

        obj = TestClass(field=42)
        data = fory.serialize(obj)
        restored = fory.deserialize(data)

        assert restored.field == 42
