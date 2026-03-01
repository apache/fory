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

from __future__ import annotations

import dataclasses
import datetime
import enum
import inspect
import itertools
import logging
import os
import typing
from typing import List, Dict

from pyfory.lib.mmh3 import hash_buffer
from pyfory.types import (
    TypeId,
    int8,
    int16,
    int32,
    int64,
    fixed_int32,
    fixed_int64,
    tagged_int64,
    uint8,
    uint16,
    uint32,
    fixed_uint32,
    uint64,
    fixed_uint64,
    tagged_uint64,
    float32,
    float64,
    is_primitive_array_type,
    is_list_type,
    is_map_type,
    get_primitive_type_size,
    is_polymorphic_type,
    is_primitive_type,
    is_union_type,
)
from pyfory.type_util import (
    TypeVisitor,
    infer_field,
    is_subclass,
    get_type_hints,
    unwrap_optional,
)
from pyfory.serialization import Buffer
from pyfory.codegen import (
    gen_write_nullable_basic_stmts,
    gen_read_nullable_basic_stmts,
    compile_function,
)
from pyfory.error import TypeNotCompatibleError
from pyfory.resolver import NULL_FLAG, NOT_NULL_VALUE_FLAG
from pyfory.field import (
    ForyFieldMeta,
    extract_field_meta,
    validate_field_metas,
)

from pyfory import (
    Serializer,
    BooleanSerializer,
    ByteSerializer,
    Int16Serializer,
    Int32Serializer,
    Int64Serializer,
    Float32Serializer,
    Float64Serializer,
    StringSerializer,
)

logger = logging.getLogger(__name__)


@dataclasses.dataclass
class FieldInfo:
    """Pre-computed field information for serialization."""

    # Identity
    name: str  # Field name (snake_case)
    index: int  # Field index in the serialization order
    type_hint: type  # Type annotation

    # Fory metadata (from pyfory.field()) - used for hash computation
    tag_id: int  # -1 = use field name, >=0 = use tag ID
    nullable: bool  # Effective nullable flag (considers Optional[T])
    ref: bool  # Field-level ref setting (for hash computation)
    dynamic: bool  # Whether type info is written for this field

    # Runtime flags (combines field metadata with global Fory config)
    runtime_ref_tracking: bool  # Actual ref tracking: field.ref AND fory.track_ref

    # Derived info
    type_id: int  # Fory TypeId
    serializer: Serializer  # Field serializer
    unwrapped_type: type  # Type with Optional unwrapped


def _is_abstract_type(type_hint: type) -> bool:
    """Check if a type is abstract (has abstract methods or is ABC subclass)."""
    if type_hint is None:
        return False
    try:
        # Check if it's an abstract class using inspect.isabstract
        return inspect.isabstract(type_hint)
    except TypeError:
        # Not a class (e.g., generic type)
        return False


def _default_field_meta(type_hint: type, field_nullable: bool = False) -> ForyFieldMeta:
    """Returns default field metadata for fields without pyfory.field().

    A field is considered nullable if:
    1. It's Optional[T], OR
    2. Global field_nullable is True

    For ref, defaults to False to preserve original serialization behavior.
    Non-nullable complex fields use write_no_ref (no ref header in buffer).
    Users can explicitly set ref=True in pyfory.field() to enable ref tracking.

    For dynamic, defaults to None (auto-detect):
    - Abstract classes: always True (type info must be written)
    - Concrete types use type-id based dynamic detection
    """
    unwrapped_type, is_optional = unwrap_optional(type_hint)
    nullable = is_optional or field_nullable
    # Default ref=False to preserve original serialization behavior where non-nullable
    # fields use write_no_ref. Users can explicitly set ref=True in pyfory.field()
    # to enable per-field ref tracking when fory.track_ref is enabled.
    # Default dynamic=None for auto-detection based on type and mode
    return ForyFieldMeta(id=-1, nullable=nullable, ref=False, ignore=False, dynamic=None)


def _extract_field_infos(
    fory,
    clz: type,
    type_hints: dict,
) -> tuple[list[FieldInfo], dict[str, ForyFieldMeta]]:
    """
    Extract FieldInfo list from a dataclass.

    This handles:
    - Extracting field metadata from pyfory.field() annotations
    - Filtering out ignored fields
    - Computing effective nullable based on Optional[T]
    - Computing runtime ref tracking based on global config
    - Inheritance: parent fields first, subclass fields override parent fields

    Returns:
        Tuple of (field_infos, field_metas) where field_metas maps field name to ForyFieldMeta
    """
    if not dataclasses.is_dataclass(clz):
        # For non-dataclass, return empty - will use legacy path
        return [], {}

    # Collect fields from class hierarchy (parent first, child last)
    # Child fields override parent fields with same name
    all_fields: Dict[str, dataclasses.Field] = {}
    for klass in clz.__mro__[::-1]:  # Reverse MRO: base classes first
        if dataclasses.is_dataclass(klass) and klass is not clz:
            for f in dataclasses.fields(klass):
                all_fields[f.name] = f
    # Add current class fields (override parent)
    for f in dataclasses.fields(clz):
        all_fields[f.name] = f

    # Extract field metas and filter ignored fields
    field_metas: Dict[str, ForyFieldMeta] = {}
    active_fields: List[tuple] = []

    # Check if fory has field_nullable global setting
    global_field_nullable = getattr(fory, "field_nullable", False)

    for field_name, dc_field in all_fields.items():
        meta = extract_field_meta(dc_field)
        if meta is None:
            # Field without pyfory.field() - use defaults
            # Auto-detect Optional[T] for nullable, also respect global field_nullable
            field_type = type_hints.get(field_name, typing.Any)
            meta = _default_field_meta(field_type, global_field_nullable)

        field_metas[field_name] = meta

        if not meta.ignore:
            active_fields.append((field_name, dc_field))

    # Validate field metas
    validate_field_metas(clz, field_metas, type_hints)

    # Build FieldInfo list
    field_infos: List[FieldInfo] = []
    visitor = StructFieldSerializerVisitor(fory)
    global_ref_tracking = fory.track_ref

    for index, (field_name, dc_field) in enumerate(active_fields):
        meta = field_metas[field_name]
        type_hint = type_hints.get(field_name, typing.Any)
        unwrapped_type, is_optional = unwrap_optional(type_hint)

        # Optional[T] should always be nullable regardless of explicit meta.
        effective_nullable = meta.nullable or is_optional

        # Compute runtime ref tracking: field.ref AND global config
        runtime_ref = meta.ref and global_ref_tracking

        # Infer serializer
        serializer = infer_field(field_name, unwrapped_type, visitor, types_path=[])

        # Get type_id from serializer
        if serializer is not None:
            type_id = fory.type_resolver.get_type_info(serializer.type_).type_id
        else:
            type_id = TypeId.UNKNOWN

        # Compute effective dynamic based on type.
        # - Abstract classes: always True (type info must be written)
        # - If explicitly set (not None): use that value
        # - Otherwise: write type info for polymorphic types that are not registered by id
        is_abstract = _is_abstract_type(unwrapped_type)
        if is_abstract:
            # Abstract classes always need type info
            effective_dynamic = True
        elif meta.dynamic is not None:
            # Explicit configuration takes precedence
            effective_dynamic = meta.dynamic
        else:
            # Registered-by-id types have stable serializers, so no per-field type info is needed.
            effective_dynamic = is_polymorphic_type(type_id) and not fory.type_resolver.is_registered_by_id(unwrapped_type)

        field_info = FieldInfo(
            name=field_name,
            index=index,
            type_hint=type_hint,
            tag_id=meta.id,
            nullable=effective_nullable,
            ref=meta.ref,
            dynamic=effective_dynamic,
            runtime_ref_tracking=runtime_ref,
            type_id=type_id,
            serializer=serializer,
            unwrapped_type=unwrapped_type,
        )
        field_infos.append(field_info)

    return field_infos, field_metas


_jit_context = locals()


_ENABLE_FORY_PYTHON_JIT = os.environ.get("ENABLE_FORY_PYTHON_JIT", "True").lower() in (
    "true",
    "1",
)
_ENABLE_FORY_DEBUG_OUTPUT = os.environ.get("ENABLE_FORY_DEBUG_OUTPUT", "False").lower() in (
    "true",
    "1",
)


class DataClassSerializer(Serializer):
    def __init__(
        self,
        fory,
        clz: type,
        field_names: List[str] = None,
        serializers: List[Serializer] = None,
        nullable_fields: Dict[str, bool] = None,
        dynamic_fields: Dict[str, bool] = None,
    ):
        super().__init__(fory, clz)

        self._type_hints = get_type_hints(clz)
        self._has_slots = hasattr(clz, "__slots__")

        # When field_names is explicitly passed (from TypeDef.create_serializer during schema evolution),
        # use those fields instead of extracting from the class. This is critical for schema evolution
        # where the sender's schema (in TypeDef) differs from the receiver's registered class.
        # Track whether field order comes from wire (TypeDef) - don't re-sort these
        self._fields_from_typedef = field_names is not None and serializers is not None
        if self._fields_from_typedef:
            # Use the passed-in field_names and serializers from TypeDef
            self._field_names = field_names
            self._serializers = serializers
            self._nullable_fields = nullable_fields or {}
            self._ref_fields = {}
            self._dynamic_fields = dynamic_fields or {}
            self._field_infos = []
            self._field_metas = {}
        else:
            # Extract field infos using new pyfory.field() metadata
            self._field_infos, self._field_metas = _extract_field_infos(fory, clz, self._type_hints)

            if self._field_infos:
                # Use new field info based approach
                self._field_names = [fi.name for fi in self._field_infos]
                self._serializers = [fi.serializer for fi in self._field_infos]
                self._nullable_fields = {fi.name: fi.nullable for fi in self._field_infos}
                self._ref_fields = {fi.name: fi.runtime_ref_tracking for fi in self._field_infos}
                self._dynamic_fields = {fi.name: fi.dynamic for fi in self._field_infos}
            else:
                # Fallback for non-dataclass types
                self._field_names = field_names or self._get_field_names(clz)
                self._nullable_fields = nullable_fields or {}
                self._ref_fields = {}
                self._dynamic_fields = {}  # Empty dict, will use mode defaults

                if self._field_names and not self._nullable_fields:
                    for field_name in self._field_names:
                        if field_name in self._type_hints:
                            unwrapped_type, is_optional = unwrap_optional(self._type_hints[field_name])
                            is_nullable = is_optional or not is_primitive_type(unwrapped_type)
                            self._nullable_fields[field_name] = is_nullable

                self._serializers = serializers or [None] * len(self._field_names)
                if serializers is None:
                    visitor = StructFieldSerializerVisitor(fory)
                    for index, key in enumerate(self._field_names):
                        unwrapped_type, _ = unwrap_optional(self._type_hints.get(key, typing.Any))
                        serializer = infer_field(key, unwrapped_type, visitor, types_path=[])
                        self._serializers[index] = serializer

        # Cache unwrapped type hints
        self._unwrapped_hints = self._compute_unwrapped_hints()

        # Compute struct hash and field order.
        # If fields come from TypeDef (wire schema), preserve field order and only compute hash.
        if self._fields_from_typedef:
            hash_str = compute_struct_fingerprint(fory.type_resolver, self._field_names, self._serializers, self._nullable_fields, self._field_infos)
            hash_bytes = hash_str.encode("utf-8")
            if len(hash_bytes) == 0:
                self._hash = 47
            else:
                from pyfory.lib.mmh3 import hash_buffer

                full_hash = hash_buffer(hash_bytes, seed=47)[0]
                type_hash_32 = full_hash & 0xFFFFFFFF
                if full_hash & 0x80000000:
                    type_hash_32 = type_hash_32 - 0x100000000
                self._hash = type_hash_32
        else:
            self._hash, self._field_names, self._serializers = compute_struct_meta(
                fory.type_resolver, self._field_names, self._serializers, self._nullable_fields, self._field_infos
            )

        self._generated_write_method = self._gen_generated_write_method()
        self._generated_read_method = self._gen_generated_read_method()
        if _ENABLE_FORY_PYTHON_JIT:
            self.write = self._generated_write_method
            self.read = self._generated_read_method

    def _get_field_names(self, clz):
        if hasattr(clz, "__dict__"):
            # Regular object with __dict__
            # For dataclasses, preserve field definition order
            # In compatible mode, stable field ordering is critical for schema evolution
            if dataclasses.is_dataclass(clz):
                # Use dataclasses.fields() to get fields in definition order
                return [field.name for field in dataclasses.fields(clz)]
            # For non-dataclass objects, sort by key names for consistency
            return sorted(self._type_hints.keys())
        elif hasattr(clz, "__slots__"):
            # Object with __slots__
            return sorted(clz.__slots__)
        return []

    def _compute_unwrapped_hints(self):
        """Compute unwrapped type hints once and cache."""
        from pyfory.type_util import unwrap_optional

        return {field_name: unwrap_optional(hint)[0] for field_name, hint in self._type_hints.items()}

    def _write_header(self, buffer):
        """Write serialization header (hash or field count based on compatible mode)."""
        if not self.fory.compatible:
            buffer.write_int32(self._hash)
        else:
            buffer.write_var_uint32(len(self._field_names))

    def _read_header(self, buffer):
        """Read serialization header and return number of fields written.

        Returns:
            int: Number of fields that were written

        Raises:
            TypeNotCompatibleError: If hash doesn't match in non-compatible mode
        """
        if not self.fory.compatible:
            hash_ = buffer.read_int32()
            expected_hash = self._hash
            if hash_ != expected_hash:
                raise TypeNotCompatibleError(f"Hash {hash_} is not consistent with {expected_hash} for type {self.type_}")
            return len(self._field_names)
        else:
            return buffer.read_var_uint32()

    def _get_write_stmt_for_codegen(self, serializer, buffer, field_value):
        """Generate write statement for code generation based on serializer type."""
        if isinstance(serializer, BooleanSerializer):
            return f"{buffer}.write_bool({field_value})"
        elif isinstance(serializer, ByteSerializer):
            return f"{buffer}.write_int8({field_value})"
        elif isinstance(serializer, Int16Serializer):
            return f"{buffer}.write_int16({field_value})"
        elif isinstance(serializer, Int32Serializer):
            return f"{buffer}.write_varint32({field_value})"
        elif isinstance(serializer, Int64Serializer):
            return f"{buffer}.write_varint64({field_value})"
        elif isinstance(serializer, Float32Serializer):
            return f"{buffer}.write_float32({field_value})"
        elif isinstance(serializer, Float64Serializer):
            return f"{buffer}.write_float64({field_value})"
        elif isinstance(serializer, StringSerializer):
            return f"{buffer}.write_string({field_value})"
        else:
            return None  # Complex type, needs ref handling

    def _get_read_stmt_for_codegen(self, serializer, buffer, field_value):
        """Generate read statement for code generation based on serializer type."""
        if isinstance(serializer, BooleanSerializer):
            return f"{field_value} = {buffer}.read_bool()"
        elif isinstance(serializer, ByteSerializer):
            return f"{field_value} = {buffer}.read_int8()"
        elif isinstance(serializer, Int16Serializer):
            return f"{field_value} = {buffer}.read_int16()"
        elif isinstance(serializer, Int32Serializer):
            return f"{field_value} = {buffer}.read_varint32()"
        elif isinstance(serializer, Int64Serializer):
            return f"{field_value} = {buffer}.read_varint64()"
        elif isinstance(serializer, Float32Serializer):
            return f"{field_value} = {buffer}.read_float32()"
        elif isinstance(serializer, Float64Serializer):
            return f"{field_value} = {buffer}.read_float64()"
        elif isinstance(serializer, StringSerializer):
            return f"{field_value} = {buffer}.read_string()"
        else:
            return None  # Complex type, needs ref handling

    def _write_non_nullable_field(self, buffer, field_value, serializer, typeinfo=None):
        """Write a non-nullable field value at runtime."""
        if isinstance(serializer, BooleanSerializer):
            buffer.write_bool(field_value)
        elif isinstance(serializer, ByteSerializer):
            buffer.write_int8(field_value)
        elif isinstance(serializer, Int16Serializer):
            buffer.write_int16(field_value)
        elif isinstance(serializer, Int32Serializer):
            buffer.write_varint32(field_value)
        elif isinstance(serializer, Int64Serializer):
            buffer.write_varint64(field_value)
        elif isinstance(serializer, Float32Serializer):
            buffer.write_float32(field_value)
        elif isinstance(serializer, Float64Serializer):
            buffer.write_float64(field_value)
        elif isinstance(serializer, StringSerializer):
            buffer.write_string(field_value)
        else:
            self.fory.write_ref_pyobject(buffer, field_value, typeinfo=typeinfo)

    def _read_non_nullable_field(self, buffer, serializer):
        """Read a non-nullable field value at runtime."""
        if isinstance(serializer, BooleanSerializer):
            return buffer.read_bool()
        elif isinstance(serializer, ByteSerializer):
            return buffer.read_int8()
        elif isinstance(serializer, Int16Serializer):
            return buffer.read_int16()
        elif isinstance(serializer, Int32Serializer):
            return buffer.read_varint32()
        elif isinstance(serializer, Int64Serializer):
            return buffer.read_varint64()
        elif isinstance(serializer, Float32Serializer):
            return buffer.read_float32()
        elif isinstance(serializer, Float64Serializer):
            return buffer.read_float64()
        elif isinstance(serializer, StringSerializer):
            return buffer.read_string()
        else:
            return self.fory.read_ref_pyobject(buffer)

    def _write_nullable_field(self, buffer, field_value, serializer, typeinfo=None):
        """Write a nullable field value at runtime."""
        if field_value is None:
            buffer.write_int8(NULL_FLAG)
        else:
            buffer.write_int8(NOT_NULL_VALUE_FLAG)
            if isinstance(serializer, StringSerializer):
                buffer.write_string(field_value)
            else:
                self.fory.write_ref_pyobject(buffer, field_value, typeinfo=typeinfo)

    def _read_nullable_field(self, buffer, serializer):
        """Read a nullable field value at runtime."""
        flag = buffer.read_int8()
        if flag == NULL_FLAG:
            return None
        else:
            if isinstance(serializer, StringSerializer):
                return buffer.read_string()
            else:
                return self.fory.read_ref_pyobject(buffer)

    def _gen_write_method(self):
        context = {}
        counter = itertools.count(0)
        buffer, fory, value, value_dict = "buffer", "fory", "value", "value_dict"
        context[fory] = self.fory
        context["_serializers"] = self._serializers

        stmts = [
            f'"""write method for {self.type_}"""',
        ]

        # Write hash only in non-compatible mode; in compatible mode, write field count
        if not self.fory.compatible:
            stmts.append(f"{buffer}.write_int32({self._hash})")
        else:
            stmts.append(f"{buffer}.write_var_uint32({len(self._field_names)})")

        if not self._has_slots:
            stmts.append(f"{value_dict} = {value}.__dict__")

        # Write field values in order
        for index, field_name in enumerate(self._field_names):
            field_value = f"field_value{next(counter)}"
            serializer_var = f"serializer{index}"
            serializer = self._serializers[index]
            context[serializer_var] = serializer

            if not self._has_slots:
                stmts.append(f"{field_value} = {value_dict}['{field_name}']")
            else:
                stmts.append(f"{field_value} = {value}.{field_name}")

            is_nullable = self._nullable_fields.get(field_name, False)
            is_dynamic = self._dynamic_fields.get(field_name, False)
            # For dynamic=False, get typeinfo for declared type to use its serializer
            typeinfo_var = f"typeinfo{index}"
            if not is_dynamic and serializer is not None:
                context[typeinfo_var] = self.fory.type_resolver.get_type_info(serializer.type_)
            if is_nullable:
                # Use gen_write_nullable_basic_stmts for nullable basic types
                if isinstance(serializer, BooleanSerializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, bool))
                elif isinstance(serializer, ByteSerializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, "int8"))
                elif isinstance(serializer, Int16Serializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, "int16"))
                elif isinstance(serializer, Int32Serializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, "int32"))
                elif isinstance(serializer, Int64Serializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, "int64"))
                elif isinstance(serializer, Float32Serializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, "float32"))
                elif isinstance(serializer, Float64Serializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, "float64"))
                elif isinstance(serializer, StringSerializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, str))
                else:
                    # For complex types, use write_ref_pyobject
                    # dynamic=True or serializer is None: pass None to use actual type
                    # dynamic=False: pass typeinfo to use declared type
                    typeinfo_arg = "None" if is_dynamic or serializer is None else typeinfo_var
                    stmts.append(f"{fory}.write_ref_pyobject({buffer}, {field_value}, typeinfo={typeinfo_arg})")
            else:
                stmt = self._get_write_stmt_for_codegen(serializer, buffer, field_value)
                if stmt is None:
                    # dynamic=True or serializer is None: pass None to use actual type
                    # dynamic=False: pass typeinfo to use declared type
                    typeinfo_arg = "None" if is_dynamic or serializer is None else typeinfo_var
                    stmt = f"{fory}.write_ref_pyobject({buffer}, {field_value}, typeinfo={typeinfo_arg})"
                stmts.append(stmt)

        self._write_method_code, func = compile_function(
            f"write_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
            [buffer, value],
            stmts,
            context,
        )
        return func

    def _gen_read_method(self):
        context = dict(_jit_context)
        buffer, fory, obj_class, obj, obj_dict = (
            "buffer",
            "fory",
            "obj_class",
            "obj",
            "obj_dict",
        )
        ref_resolver = "ref_resolver"
        context[fory] = self.fory
        context[obj_class] = self.type_
        context[ref_resolver] = self.fory.ref_resolver
        context["_serializers"] = self._serializers
        current_class_field_names = set(self._get_field_names(self.type_))

        stmts = [
            f'"""read method for {self.type_}"""',
        ]
        if not self.fory.strict:
            context["checker"] = self.fory.policy
            stmts.append(f"checker.authorize_instantiation({obj_class})")

        # Read hash only in non-compatible mode; in compatible mode, read field count
        if not self.fory.compatible:
            stmts.extend(
                [
                    f"read_hash = {buffer}.read_int32()",
                    f"if read_hash != {self._hash}:",
                    f"""   raise TypeNotCompatibleError(
            f"Hash {{read_hash}} is not consistent with {self._hash} for type {self.type_}")""",
                ]
            )
        else:
            stmts.append(f"num_fields_written = {buffer}.read_var_uint32()")

        stmts.extend(
            [
                f"{obj} = {obj_class}.__new__({obj_class})",
                f"{ref_resolver}.reference({obj})",
            ]
        )

        if not self._has_slots:
            stmts.append(f"{obj_dict} = {obj}.__dict__")

        # Read field values in order
        for index, field_name in enumerate(self._field_names):
            serializer_var = f"serializer{index}"
            serializer = self._serializers[index]
            context[serializer_var] = serializer
            field_value = f"field_value{index}"
            is_nullable = self._nullable_fields.get(field_name, False)

            # Build field reading statements
            field_stmts = []

            if is_nullable:
                # Use gen_read_nullable_basic_stmts for nullable basic types
                if isinstance(serializer, BooleanSerializer):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, bool, lambda v: f"{field_value} = {v}"))
                elif isinstance(serializer, ByteSerializer):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, "int8", lambda v: f"{field_value} = {v}"))
                elif isinstance(serializer, Int16Serializer):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, "int16", lambda v: f"{field_value} = {v}"))
                elif isinstance(serializer, Int32Serializer):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, "int32", lambda v: f"{field_value} = {v}"))
                elif isinstance(serializer, Int64Serializer):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, "int64", lambda v: f"{field_value} = {v}"))
                elif isinstance(serializer, Float32Serializer):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, "float32", lambda v: f"{field_value} = {v}"))
                elif isinstance(serializer, Float64Serializer):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, "float64", lambda v: f"{field_value} = {v}"))
                elif isinstance(serializer, StringSerializer):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, str, lambda v: f"{field_value} = {v}"))
                else:
                    # For complex types, use read_ref_pyobject
                    field_stmts.append(f"{field_value} = {fory}.read_ref_pyobject({buffer})")
            else:
                stmt = self._get_read_stmt_for_codegen(serializer, buffer, field_value)
                if stmt is None:
                    stmt = f"{field_value} = {fory}.read_ref_pyobject({buffer})"
                field_stmts.append(stmt)

            # Set field value if it exists in current class
            if field_name not in current_class_field_names:
                field_stmts.append(f"# {field_name} is not in {self.type_}")
            else:
                if not self._has_slots:
                    field_stmts.append(f"{obj_dict}['{field_name}'] = {field_value}")
                else:
                    field_stmts.append(f"{obj}.{field_name} = {field_value}")

            # In compatible mode, wrap field reading in a check
            if self.fory.compatible:
                stmts.append(f"if {index} < num_fields_written:")
                # Indent all field statements
                from pyfory.codegen import ident_lines

                field_stmts = ident_lines(field_stmts)
                stmts.extend(field_stmts)
            else:
                stmts.extend(field_stmts)

        stmts.append(f"return {obj}")
        self._read_method_code, func = compile_function(
            f"read_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
            [buffer],
            stmts,
            context,
        )
        return func

    def _gen_generated_write_method(self):
        """Generate JIT-compiled write method.

        Per xlang spec, struct format is:
        - Schema consistent mode: |4-byte hash|field values|
        - Schema evolution mode (compatible): |field values| (no field count prefix!)
        The field count is in TypeDef meta written at the end, not in object data.
        """
        context = {}
        counter = itertools.count(0)
        buffer, fory, value, value_dict = "buffer", "fory", "value", "value_dict"
        context[fory] = self.fory
        context["_serializers"] = self._serializers
        stmts = [
            f'"""write method for {self.type_}"""',
        ]
        if not self.fory.compatible:
            stmts.append(f"{buffer}.write_int32({self._hash})")
        if not self._has_slots:
            stmts.append(f"{value_dict} = {value}.__dict__")
        for index, field_name in enumerate(self._field_names):
            field_value = f"field_value{next(counter)}"
            serializer_var = f"serializer{index}"
            serializer = self._serializers[index]
            context[serializer_var] = serializer
            is_nullable = self._nullable_fields.get(field_name, False)
            # For schema evolution: use safe access with None default to handle
            # cases where the field might not exist on the object (missing from remote schema)
            # In compatible mode, always use safe access even for non-nullable fields
            if not self._has_slots:
                if is_nullable or self.fory.compatible:
                    stmts.append(f"{field_value} = {value_dict}.get('{field_name}')")
                else:
                    stmts.append(f"{field_value} = {value_dict}['{field_name}']")
            else:
                if is_nullable or self.fory.compatible:
                    stmts.append(f"{field_value} = getattr({value}, '{field_name}', None)")
                else:
                    stmts.append(f"{field_value} = {value}.{field_name}")
            is_dynamic = self._dynamic_fields.get(field_name, False)
            if is_nullable:
                if isinstance(serializer, StringSerializer):
                    stmts.extend(
                        [
                            f"if {field_value} is None:",
                            f"    {buffer}.write_int8({NULL_FLAG})",
                            "else:",
                            f"     {buffer}.write_int8({NOT_NULL_VALUE_FLAG})",
                            f"     {buffer}.write_string({field_value})",
                        ]
                    )
                else:
                    # dynamic=True: don't pass serializer, write actual type info
                    # dynamic=False: pass serializer, use declared type
                    serializer_arg = "None" if is_dynamic else serializer_var
                    stmts.append(f"{fory}.write_ref({buffer}, {field_value}, serializer={serializer_arg})")
            else:
                stmt = self._get_write_stmt_for_codegen(serializer, buffer, field_value)
                if stmt is None:
                    # For non-nullable complex types, use write_no_ref
                    # dynamic=True: don't pass serializer, write actual type info
                    if is_dynamic:
                        stmt = f"{fory}.write_no_ref({buffer}, {field_value})"
                    else:
                        stmt = f"{fory}.write_no_ref({buffer}, {field_value}, serializer={serializer_var})"
                # In compatible mode, handle None for non-nullable fields (schema evolution)
                # Write zero/default value when field is None due to missing from remote schema
                if self.fory.compatible:
                    from pyfory.serializer import EnumSerializer

                    if isinstance(serializer, EnumSerializer):
                        # For enums, write ordinal 0 when None
                        stmts.extend(
                            [
                                f"if {field_value} is None:",
                                f"    {buffer}.write_var_uint32(0)",
                                "else:",
                                f"    {stmt}",
                            ]
                        )
                    else:
                        stmts.append(stmt)
                else:
                    stmts.append(stmt)
        self._generated_write_method_code, func = compile_function(
            f"write_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
            [buffer, value],
            stmts,
            context,
        )
        return func

    def _gen_generated_read_method(self):
        """Generate JIT-compiled read method.

        Per xlang spec, struct format is:
        - Schema consistent mode: |4-byte hash|field values|
        - Schema evolution mode (compatible): |field values| (no field count prefix!)
        The field count is in TypeDef meta written at the end, not in object data.
        """
        context = dict(_jit_context)
        buffer, fory, obj_class, obj, obj_dict = (
            "buffer",
            "fory",
            "obj_class",
            "obj",
            "obj_dict",
        )
        ref_resolver = "ref_resolver"
        context[fory] = self.fory
        context[obj_class] = self.type_
        context[ref_resolver] = self.fory.ref_resolver
        context["_serializers"] = self._serializers
        current_class_field_names = set(self._get_field_names(self.type_))
        stmts = [
            f'"""read method for {self.type_}"""',
        ]
        if not self.fory.strict:
            context["checker"] = self.fory.policy
            stmts.append(f"checker.authorize_instantiation({obj_class})")
        if not self.fory.compatible:
            stmts.extend(
                [
                    f"read_hash = {buffer}.read_int32()",
                    f"if read_hash != {self._hash}:",
                    f"""   raise TypeNotCompatibleError(
                f"Hash {{read_hash}} is not consistent with {self._hash} for type {self.type_}")""",
                ]
            )
        stmts.extend(
            [
                f"{obj} = {obj_class}.__new__({obj_class})",
                f"{ref_resolver}.reference({obj})",
            ]
        )

        if not self._has_slots:
            stmts.append(f"{obj_dict} = {obj}.__dict__")

        for index, field_name in enumerate(self._field_names):
            serializer_var = f"serializer{index}"
            serializer = self._serializers[index]
            context[serializer_var] = serializer
            field_value = f"field_value{index}"
            is_nullable = self._nullable_fields.get(field_name, False)

            is_dynamic = self._dynamic_fields.get(field_name, False)
            if is_nullable:
                if isinstance(serializer, StringSerializer):
                    stmts.extend(
                        [
                            f"if {buffer}.read_int8() >= {NOT_NULL_VALUE_FLAG}:",
                            f"    {field_value} = {buffer}.read_string()",
                            "else:",
                            f"    {field_value} = None",
                        ]
                    )
                else:
                    # dynamic=True: don't pass serializer, read type info from buffer
                    # dynamic=False: pass serializer, use declared type
                    serializer_arg = "None" if is_dynamic else serializer_var
                    stmts.append(f"{field_value} = {fory}.read_ref({buffer}, serializer={serializer_arg})")
            else:
                stmt = self._get_read_stmt_for_codegen(serializer, buffer, field_value)
                if stmt is None:
                    # For non-nullable complex types, use read_no_ref
                    # dynamic=True: don't pass serializer, read type info from buffer
                    if is_dynamic:
                        stmt = f"{field_value} = {fory}.read_no_ref({buffer})"
                    else:
                        stmt = f"{field_value} = {fory}.read_no_ref({buffer}, serializer={serializer_var})"
                stmts.append(stmt)

            if field_name not in current_class_field_names:
                stmts.append(f"# {field_name} is not in {self.type_}")
            elif not self._has_slots:
                stmts.append(f"{obj_dict}['{field_name}'] = {field_value}")
            else:
                stmts.append(f"{obj}.{field_name} = {field_value}")

        # For schema evolution: initialize missing fields with default values
        # This handles cases where the sender's schema has fewer fields than the receiver's
        if self.fory.compatible:
            read_field_names = set(self._field_names)
            missing_fields = current_class_field_names - read_field_names
            if missing_fields and dataclasses.is_dataclass(self.type_):
                for dc_field in dataclasses.fields(self.type_):
                    if dc_field.name in missing_fields:
                        if dc_field.default is not dataclasses.MISSING:
                            default_val = repr(dc_field.default)
                            if not self._has_slots:
                                stmts.append(f"{obj_dict}['{dc_field.name}'] = {default_val}")
                            else:
                                stmts.append(f"{obj}.{dc_field.name} = {default_val}")
                        elif dc_field.default_factory is not dataclasses.MISSING:
                            factory_var = f"_default_factory_{dc_field.name}"
                            context[factory_var] = dc_field.default_factory
                            if not self._has_slots:
                                stmts.append(f"{obj_dict}['{dc_field.name}'] = {factory_var}()")
                            else:
                                stmts.append(f"{obj}.{dc_field.name} = {factory_var}()")
                        # else: field has no default, leave it unset

        stmts.append(f"return {obj}")
        self._generated_read_method_code, func = compile_function(
            f"read_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
            [buffer],
            stmts,
            context,
        )
        return func

    def write(self, buffer: Buffer, value):
        """Write dataclass instance to buffer.

        Struct format:
        - Schema consistent mode: |4-byte hash|field values|
        - Schema evolution mode (compatible): |field values| (no field count prefix!)
        The field count is in TypeDef meta written at the end, not in object data.
        """
        if not self.fory.compatible:
            buffer.write_int32(self._hash)
        for index, field_name in enumerate(self._field_names):
            field_value = getattr(value, field_name)
            serializer = self._serializers[index]
            is_nullable = self._nullable_fields.get(field_name, False)
            is_dynamic = self._dynamic_fields.get(field_name, False)
            if _ENABLE_FORY_DEBUG_OUTPUT:
                print(
                    f"write field '{field_name}': {field_value!r}, writer_index={buffer.get_writer_index()}, "
                    f"nullable={is_nullable}, dynamic={is_dynamic}, serializer={serializer}"
                )
            if is_nullable:
                if field_value is None:
                    buffer.write_int8(-3)
                else:
                    # dynamic=True: don't pass serializer, write actual type info
                    # dynamic=False: pass serializer, use declared type
                    self.fory.write_ref(buffer, field_value, serializer=None if is_dynamic else serializer)
            else:
                if is_dynamic:
                    self.fory.write_no_ref(buffer, field_value)
                else:
                    self.fory.write_no_ref(buffer, field_value, serializer=serializer)

    def read(self, buffer):
        """Read dataclass instance from buffer.

        Struct format:
        - Schema consistent mode: |4-byte hash|field values|
        - Schema evolution mode (compatible): |field values| (no field count prefix!)
        The field count is in TypeDef meta written at the end, not in object data.
        """
        if not self.fory.compatible:
            hash_ = buffer.read_int32()
            if hash_ != self._hash:
                raise TypeNotCompatibleError(
                    f"Hash {hash_} is not consistent with {self._hash} for type {self.type_}",
                )
        obj = self.type_.__new__(self.type_)
        self.fory.ref_resolver.reference(obj)
        current_class_field_names = set(self._get_field_names(self.type_))
        read_field_names = set()
        for index, field_name in enumerate(self._field_names):
            serializer = self._serializers[index]
            is_nullable = self._nullable_fields.get(field_name, False)
            is_dynamic = self._dynamic_fields.get(field_name, False)
            if _ENABLE_FORY_DEBUG_OUTPUT:
                print(
                    f"read field '{field_name}': reader_index={buffer.get_reader_index()}, "
                    f"nullable={is_nullable}, dynamic={is_dynamic}, serializer={serializer}"
                )
            if is_nullable:
                ref_id = buffer.read_int8()
                if ref_id == -3:
                    field_value = None
                else:
                    buffer.set_reader_index(buffer.get_reader_index() - 1)
                    # dynamic=True: don't pass serializer, read type info from buffer
                    # dynamic=False: pass serializer, use declared type
                    field_value = self.fory.read_ref(buffer, serializer=None if is_dynamic else serializer)
            else:
                if is_dynamic:
                    field_value = self.fory.read_no_ref(buffer)
                else:
                    field_value = self.fory.read_no_ref(buffer, serializer=serializer)
            if field_name in current_class_field_names:
                setattr(obj, field_name, field_value)
                read_field_names.add(field_name)
        # For schema evolution: initialize missing fields with default values
        # This handles cases where the sender's schema has fewer fields than the receiver's
        if self.fory.compatible:
            missing_fields = current_class_field_names - read_field_names
            if missing_fields and dataclasses.is_dataclass(self.type_):
                for dc_field in dataclasses.fields(self.type_):
                    if dc_field.name in missing_fields:
                        if dc_field.default is not dataclasses.MISSING:
                            setattr(obj, dc_field.name, dc_field.default)
                        elif dc_field.default_factory is not dataclasses.MISSING:
                            setattr(obj, dc_field.name, dc_field.default_factory())
                        # else: field has no default, leave it unset (will be None for nullable)
        return obj


class DataClassStubSerializer(DataClassSerializer):
    def __init__(self, fory, clz: type):
        Serializer.__init__(self, fory, clz)

    def write(self, buffer, value):
        self._replace().write(buffer, value)

    def read(self, buffer):
        return self._replace().read(buffer)

    def _replace(self):
        typeinfo = self.fory.type_resolver.get_type_info(self.type_)
        typeinfo.serializer = DataClassSerializer(self.fory, self.type_)
        return typeinfo.serializer


basic_types = {
    bool,
    # Signed integers
    int8,
    int16,
    int32,
    fixed_int32,
    int64,
    fixed_int64,
    tagged_int64,
    # Unsigned integers
    uint8,
    uint16,
    uint32,
    fixed_uint32,
    uint64,
    fixed_uint64,
    tagged_uint64,
    # Floats
    float32,
    float64,
    # Python native types
    int,
    float,
    str,
    bytes,
    datetime.datetime,
    datetime.date,
    datetime.time,
}


class StructFieldSerializerVisitor(TypeVisitor):
    def __init__(
        self,
        fory,
    ):
        self.fory = fory

    def visit_list(self, field_name, elem_type, types_path=None):
        from pyfory.serializer import ListSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        # Infer type recursively for type such as List[Dict[str, str]]
        elem_type, elem_ref_override = unwrap_ref(elem_type)
        elem_serializer = infer_field("item", elem_type, self, types_path=types_path)
        return ListSerializer(self.fory, list, elem_serializer, elem_ref_override)

    def visit_set(self, field_name, elem_type, types_path=None):
        from pyfory.serializer import SetSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_type, elem_ref_override = unwrap_ref(elem_type)
        elem_serializer = infer_field("item", elem_type, self, types_path=types_path)
        return SetSerializer(self.fory, set, elem_serializer, elem_ref_override)

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        from pyfory.serializer import MapSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_type, key_ref_override = unwrap_ref(key_type)
        value_type, value_ref_override = unwrap_ref(value_type)
        key_serializer = infer_field("key", key_type, self, types_path=types_path)
        value_serializer = infer_field("value", value_type, self, types_path=types_path)
        return MapSerializer(
            self.fory,
            dict,
            key_serializer,
            value_serializer,
            key_ref_override,
            value_ref_override,
        )

    def visit_customized(self, field_name, type_, types_path=None):
        if issubclass(type_, enum.Enum):
            return self.fory.type_resolver.get_serializer(type_)
        # For custom types (dataclasses, etc.), try to get or create serializer
        # This enables field-level serializer resolution for types like inner structs
        typeinfo = self.fory.type_resolver.get_type_info(type_, create=False)
        if typeinfo is not None:
            return typeinfo.serializer
        return None

    def visit_other(self, field_name, type_, types_path=None):
        if is_subclass(type_, enum.Enum):
            return self.fory.type_resolver.get_serializer(type_)
        if type_ not in basic_types and not is_primitive_array_type(type_):
            return None
        serializer = self.fory.type_resolver.get_serializer(type_)
        return serializer


_UNKNOWN_TYPE_ID = -1


def _sort_fields(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types) = group_fields(
        type_resolver, field_names, serializers, nullable_map, field_infos_list
    )
    all_types = boxed_types + nullable_boxed_types + internal_types + collection_types + set_types + map_types + other_types
    return [t[2] for t in all_types], [t[1] for t in all_types]


def group_fields(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    nullable_map = nullable_map or {}
    field_info_map = {}
    if field_infos_list:
        field_info_map = {fi.name: fi for fi in field_infos_list}
    boxed_types = []
    nullable_boxed_types = []
    collection_types = []
    set_types = []
    map_types = []
    internal_types = []
    other_types = []
    type_ids = []
    for field_name, serializer in zip(field_names, serializers):
        fi = field_info_map.get(field_name)
        tag_id = fi.tag_id if fi else -1
        if tag_id >= 0:
            sort_key = (0, str(tag_id), "")
        else:
            sort_key = (1, field_name, "")
        if serializer is None:
            other_types.append((_UNKNOWN_TYPE_ID, serializer, field_name, sort_key))
        else:
            type_ids.append(
                (
                    type_resolver.get_type_info(serializer.type_).type_id,
                    serializer,
                    field_name,
                    sort_key,
                )
            )
    for type_id, serializer, field_name, sort_key in type_ids:
        if is_union_type(type_id):
            type_id = TypeId.UNION
        is_nullable = nullable_map.get(field_name, False)
        if is_primitive_type(type_id):
            container = nullable_boxed_types if is_nullable else boxed_types
        elif type_id == TypeId.SET:
            container = set_types
        elif is_list_type(serializer.type_):
            container = collection_types
        elif is_map_type(serializer.type_):
            container = map_types
        elif is_polymorphic_type(type_id) or type_id in {TypeId.ENUM, TypeId.NAMED_ENUM} or is_union_type(type_id):
            container = other_types
        elif type_id >= TypeId.BOUND:
            # Native mode user-registered types have type_id >= BOUND
            container = other_types
        else:
            assert TypeId.UNKNOWN < type_id < TypeId.BOUND, (type_id,)
            container = internal_types
        container.append((type_id, serializer, field_name, sort_key))

    def sorter(item):
        return item[0], item[3]

    def numeric_sorter(item):
        id_ = item[0]
        compress = id_ in {
            # Signed compressed types
            TypeId.VARINT32,
            TypeId.VARINT64,
            TypeId.TAGGED_INT64,
            # Unsigned compressed types
            TypeId.VAR_UINT32,
            TypeId.VAR_UINT64,
            TypeId.TAGGED_UINT64,
        }
        # Sort by: compress flag, -size (largest first), -type_id (higher type ID first), field_name
        # Java sorts by size (largest first), then by primitive type ID (descending)
        return int(compress), -get_primitive_type_size(id_), -id_, item[3]

    boxed_types = sorted(boxed_types, key=numeric_sorter)
    nullable_boxed_types = sorted(nullable_boxed_types, key=numeric_sorter)
    collection_types = sorted(collection_types, key=sorter)
    set_types = sorted(set_types, key=sorter)
    internal_types = sorted(internal_types, key=sorter)
    map_types = sorted(map_types, key=sorter)
    other_types = sorted(other_types, key=lambda item: item[3])
    return (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types)


def compute_struct_fingerprint(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    """
    Computes the fingerprint string for a struct type used in schema versioning.

    Fingerprint Format:
        Each field contributes: <field_id_or_name>,<type_id>,<ref>,<nullable>;
        Fields are sorted by tag ID (if >=0) or field name (if id=-1).

    Field Components:
        - field_id_or_name: Tag ID as string if id >= 0, otherwise field name
        - type_id: Fory TypeId as decimal string (e.g., "4" for INT32)
        - ref: "1" if field has ref=True in pyfory.field(), "0" otherwise
              (based on field annotation, NOT runtime config)
        - nullable: "1" if null flag is written, "0" otherwise

    Example fingerprints:
        With tag IDs: "0,4,0,0;1,12,0,1;2,0,0,1;"
        With field names: "age,4,0,0;email,12,0,1;name,9,0,0;"

    This format is consistent across Go, Java, Rust, C++, and Python implementations.
    """
    if nullable_map is None:
        nullable_map = {}

    # Build field info list for fingerprint: (sort_key, field_id_or_name, type_id, ref_flag, nullable_flag)
    fp_fields = []

    # Build a lookup for field_infos by name if available
    field_info_map = {}
    if field_infos_list:
        field_info_map = {fi.name: fi for fi in field_infos_list}

    for i, field_name in enumerate(field_names):
        serializer = serializers[i]

        # Get field metadata if available
        fi = field_info_map.get(field_name)
        tag_id = fi.tag_id if fi else -1
        ref_flag = "1" if (fi and fi.ref) else "0"

        if serializer is None:
            type_id = TypeId.UNKNOWN
            # For unknown serializers, use nullable from map (defaults to False for xlang)
            nullable_flag = "1" if nullable_map.get(field_name, False) else "0"
        else:
            type_id = type_resolver.get_type_info(serializer.type_).type_id
            if is_union_type(type_id):
                # customized types can't be detected at compile time for some languages
                type_id = TypeId.UNKNOWN
            is_nullable = nullable_map.get(field_name, False)

            # For polymorphic or enum types, set type_id to UNKNOWN but preserve nullable from map
            if is_polymorphic_type(type_id) or type_id in {
                TypeId.ENUM,
                TypeId.NAMED_ENUM,
            }:
                type_id = TypeId.UNKNOWN

            # Use nullable from map - for xlang, this is already computed correctly
            # (False by default except for Optional[T] or explicit annotation)
            nullable_flag = "1" if is_nullable else "0"

        # Determine field identifier for fingerprint
        if tag_id >= 0:
            field_id_or_name = str(tag_id)
            # Sort by tag ID string (lexicographic) for tag ID fields
            sort_key = (0, field_id_or_name, "")  # 0 = tag ID fields come first
        else:
            field_id_or_name = field_name
            # Sort by field name (lexicographic) for name-based fields
            sort_key = (1, field_name, "")  # 1 = name fields come after

        fp_fields.append((sort_key, field_id_or_name, type_id, ref_flag, nullable_flag))

    # Sort fields: tag ID fields first (by ID), then name fields (lexicographically)
    fp_fields.sort(key=lambda x: x[0])

    # Build fingerprint string
    hash_parts = []
    for _, field_id_or_name, type_id, ref_flag, nullable_flag in fp_fields:
        hash_parts.append(f"{field_id_or_name},{type_id},{ref_flag},{nullable_flag};")

    return "".join(hash_parts)


def compute_struct_meta(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    """
    Computes struct metadata including version hash, sorted field names, and serializers.

    Uses compute_struct_fingerprint to build the fingerprint string, then hashes it
    with MurmurHash3 using seed 47, and takes the low 32 bits as signed int32.

    This provides the cross-language struct version ID used by class version checking,
    consistent with Go, Java, Rust, and C++ implementations.
    """
    (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types) = group_fields(
        type_resolver, field_names, serializers, nullable_map, field_infos_list
    )

    # Compute fingerprint string using the new format with field infos
    hash_str = compute_struct_fingerprint(type_resolver, field_names, serializers, nullable_map, field_infos_list)
    hash_bytes = hash_str.encode("utf-8")

    # Handle empty hash_bytes (no fields or all fields are unknown/dynamic)
    if len(hash_bytes) == 0:
        full_hash = 47  # Use seed as default hash for empty structs
    else:
        full_hash = hash_buffer(hash_bytes, seed=47)[0]
    type_hash_32 = full_hash & 0xFFFFFFFF
    if full_hash & 0x80000000:
        # If the sign bit is set, it's a negative number in 2's complement
        # Subtract 2^32 to get the correct negative value
        type_hash_32 = type_hash_32 - 0x100000000
    assert type_hash_32 != 0
    if os.environ.get("ENABLE_FORY_DEBUG_OUTPUT", "").lower() in ("1", "true"):
        print(f'[Python][fory-debug] struct version fingerprint="{hash_str}" version hash={type_hash_32}')

    # Flatten all groups in correct order (already sorted from group_fields)
    all_types = boxed_types + nullable_boxed_types + internal_types + collection_types + set_types + map_types + other_types
    sorted_field_names = [f[2] for f in all_types]
    sorted_serializers = [f[1] for f in all_types]

    return type_hash_32, sorted_field_names, sorted_serializers


class StructTypeIdVisitor(TypeVisitor):
    def __init__(
        self,
        fory,
        cls,
    ):
        self.fory = fory
        self.cls = cls

    def visit_list(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as List[Dict[str, str]]
        elem_ids = infer_field("item", elem_type, self, types_path=types_path)
        return TypeId.LIST, elem_ids

    def visit_set(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_ids = infer_field("item", elem_type, self, types_path=types_path)
        return TypeId.SET, elem_ids

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_ids = infer_field("key", key_type, self, types_path=types_path)
        value_ids = infer_field("value", value_type, self, types_path=types_path)
        return TypeId.MAP, key_ids, value_ids

    def visit_customized(self, field_name, type_, types_path=None):
        typeinfo = self.fory.type_resolver.get_type_info(type_, create=False)
        if typeinfo is None:
            return [TypeId.UNKNOWN]
        return [typeinfo.type_id]

    def visit_other(self, field_name, type_, types_path=None):
        if is_subclass(type_, enum.Enum):
            return [self.fory.type_resolver.get_type_info(type_).type_id]
        if type_ not in basic_types and not is_primitive_array_type(type_):
            return None, None
        typeinfo = self.fory.type_resolver.get_type_info(type_)
        return [typeinfo.type_id]


class StructTypeVisitor(TypeVisitor):
    def __init__(self, cls):
        self.cls = cls

    def visit_list(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as List[Dict[str, str]]
        elem_types = infer_field("item", elem_type, self, types_path=types_path)
        return typing.List, elem_types

    def visit_set(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_types = infer_field("item", elem_type, self, types_path=types_path)
        return typing.Set, elem_types

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_types = infer_field("key", key_type, self, types_path=types_path)
        value_types = infer_field("value", value_type, self, types_path=types_path)
        return typing.Dict, key_types, value_types

    def visit_customized(self, field_name, type_, types_path=None):
        return [type_]

    def visit_other(self, field_name, type_, types_path=None):
        return [type_]


def get_field_names(clz, type_hints=None):
    if hasattr(clz, "__dict__"):
        # Regular object with __dict__
        # We can't know the fields without an instance, so we rely on type hints
        if type_hints is None:
            type_hints = get_type_hints(clz)
        return sorted(type_hints.keys())
    elif hasattr(clz, "__slots__"):
        # Object with __slots__
        return sorted(clz.__slots__)
    return []
