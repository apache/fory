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

import array
import builtins
import dataclasses
import importlib
import inspect
import itertools
import marshal
import logging
import os
import pickle
import types
import typing
from typing import List, Dict

from pyfory.buffer import Buffer
from pyfory.codegen import (
    gen_write_nullable_basic_stmts,
    gen_read_nullable_basic_stmts,
    compile_function,
)
from pyfory.error import TypeNotCompatibleError
from pyfory.resolver import NULL_FLAG, NOT_NULL_VALUE_FLAG
from pyfory import Language

from pyfory.type import is_primitive_type

try:
    import numpy as np
except ImportError:
    np = None

from pyfory._fory import (
    NOT_NULL_INT64_FLAG,
    BufferObject,
)

_WINDOWS = os.name == "nt"

from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION

if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory.serialization import (  # noqa: F401, F811
        Serializer,
        XlangCompatibleSerializer,
        BooleanSerializer,
        ByteSerializer,
        Int16Serializer,
        Int32Serializer,
        Int64Serializer,
        Float32Serializer,
        Float64Serializer,
        StringSerializer,
        DateSerializer,
        TimestampSerializer,
        CollectionSerializer,
        ListSerializer,
        TupleSerializer,
        StringArraySerializer,
        SetSerializer,
        MapSerializer,
        EnumSerializer,
        SliceSerializer,
    )
else:
    from pyfory._serializer import (  # noqa: F401 # pylint: disable=unused-import
        Serializer,
        XlangCompatibleSerializer,
        BooleanSerializer,
        ByteSerializer,
        Int16Serializer,
        Int32Serializer,
        Int64Serializer,
        Float32Serializer,
        Float64Serializer,
        StringSerializer,
        DateSerializer,
        TimestampSerializer,
        CollectionSerializer,
        ListSerializer,
        TupleSerializer,
        StringArraySerializer,
        SetSerializer,
        MapSerializer,
        EnumSerializer,
        SliceSerializer,
    )

from pyfory.type import (
    int16_array,
    int32_array,
    int64_array,
    float32_array,
    float64_array,
    BoolNDArrayType,
    Int16NDArrayType,
    Int32NDArrayType,
    Int64NDArrayType,
    Float32NDArrayType,
    Float64NDArrayType,
    TypeId,
    infer_field,  # Added infer_field
)


class NoneSerializer(Serializer):
    def __init__(self, fory):
        super().__init__(fory, None)
        self.need_to_write_ref = False

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError

    def write(self, buffer, value):
        pass

    def read(self, buffer):
        return None


class PandasRangeIndexSerializer(Serializer):
    __slots__ = "_cached"

    def __init__(self, fory):
        import pandas as pd

        super().__init__(fory, pd.RangeIndex)

    def write(self, buffer, value):
        fory = self.fory
        start = value.start
        stop = value.stop
        step = value.step
        if type(start) is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(start)
        else:
            if start is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                fory.write_no_ref(buffer, start)
        if type(stop) is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(stop)
        else:
            if stop is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                fory.write_no_ref(buffer, stop)
        if type(step) is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(step)
        else:
            if step is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                fory.write_no_ref(buffer, step)
        fory.write_ref(buffer, value.dtype)
        fory.write_ref(buffer, value.name)

    def read(self, buffer):
        if buffer.read_int8() == NULL_FLAG:
            start = None
        else:
            start = self.fory.read_no_ref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            stop = None
        else:
            stop = self.fory.read_no_ref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            step = None
        else:
            step = self.fory.read_no_ref(buffer)
        dtype = self.fory.read_ref(buffer)
        name = self.fory.read_ref(buffer)
        return self.type_(start, stop, step, dtype=dtype, name=name)

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError


_jit_context = locals()


_ENABLE_FORY_PYTHON_JIT = os.environ.get("ENABLE_FORY_PYTHON_JIT", "True").lower() in (
    "true",
    "1",
)


from pyfory._struct import compute_struct_meta, StructFieldSerializerVisitor


class DataClassSerializer(Serializer):
    def __init__(
        self,
        fory,
        clz: type,
        xlang: bool = False,
        field_names: List[str] = None,
        serializers: List[Serializer] = None,
        nullable_fields: Dict[str, bool] = None,
    ):
        super().__init__(fory, clz)
        self._xlang = xlang
        from pyfory.type import unwrap_optional

        self._type_hints = typing.get_type_hints(clz)
        self._field_names = field_names or self._get_field_names(clz)
        self._has_slots = hasattr(clz, "__slots__")
        self._nullable_fields = nullable_fields or {}
        field_nullable = fory.field_nullable
        if self._field_names and not self._nullable_fields:
            for field_name in self._field_names:
                if field_name in self._type_hints:
                    unwrapped_type, is_nullable = unwrap_optional(self._type_hints[field_name], field_nullable=field_nullable)
                    is_nullable = is_nullable or not is_primitive_type(unwrapped_type)
                    self._nullable_fields[field_name] = is_nullable

        # Cache unwrapped type hints
        self._unwrapped_hints = self._compute_unwrapped_hints()

        if self._xlang:
            self._serializers = serializers or [None] * len(self._field_names)
            if serializers is None:
                visitor = StructFieldSerializerVisitor(fory)
                for index, key in enumerate(self._field_names):
                    unwrapped_type, _ = unwrap_optional(self._type_hints[key])
                    serializer = infer_field(key, unwrapped_type, visitor, types_path=[])
                    self._serializers[index] = serializer
            self._hash, self._field_names, self._serializers = compute_struct_meta(
                fory.type_resolver, self._field_names, self._serializers, self._nullable_fields
            )
            self._generated_xwrite_method = self._gen_xwrite_method()
            self._generated_xread_method = self._gen_xread_method()
            if _ENABLE_FORY_PYTHON_JIT:
                # don't use `__slots__`, which will make the instance method read-only
                self.xwrite = self._generated_xwrite_method
                self.xread = self._generated_xread_method
            if self.fory.language == Language.PYTHON:
                logger = logging.getLogger(__name__)
                logger.warning(
                    "Type of class %s shouldn't be serialized using cross-language serializer",
                    clz,
                )
        else:
            # For non-xlang mode, use same infrastructure as xlang mode
            # Python dataclass serialization follows the same spec as xlang
            self._serializers = serializers or [None] * len(self._field_names)
            if serializers is None:
                visitor = StructFieldSerializerVisitor(fory)
                for index, key in enumerate(self._field_names):
                    unwrapped_type, _ = unwrap_optional(self._type_hints[key])
                    serializer = infer_field(key, unwrapped_type, visitor, types_path=[])
                    self._serializers[index] = serializer
            # In compatible mode, maintain stable field ordering (don't sort)
            # In non-compatible mode, sort fields for consistent serialization
            if not fory.compatible:
                self._hash, self._field_names, self._serializers = compute_struct_meta(
                    fory.type_resolver, self._field_names, self._serializers, self._nullable_fields
                )
            self._generated_write_method = self._gen_write_method()
            self._generated_read_method = self._gen_read_method()
            if _ENABLE_FORY_PYTHON_JIT:
                # don't use `__slots__`, which will make instance method readonly
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
        from pyfory.type import unwrap_optional

        return {field_name: unwrap_optional(hint)[0] for field_name, hint in self._type_hints.items()}

    def _write_header(self, buffer):
        """Write serialization header (hash or field count based on compatible mode)."""
        if not self.fory.compatible:
            buffer.write_int32(self._hash)
        else:
            buffer.write_varuint32(len(self._field_names))

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
            return buffer.read_varuint32()

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

    def _write_non_nullable_field(self, buffer, field_value, serializer):
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
            self.fory.write_ref_pyobject(buffer, field_value)

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

    def _write_nullable_field(self, buffer, field_value, serializer):
        """Write a nullable field value at runtime."""
        if field_value is None:
            buffer.write_int8(NULL_FLAG)
        else:
            buffer.write_int8(NOT_NULL_VALUE_FLAG)
            if isinstance(serializer, StringSerializer):
                buffer.write_string(field_value)
            else:
                self.fory.write_ref_pyobject(buffer, field_value)

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
            stmts.append(f"{buffer}.write_varuint32({len(self._field_names)})")

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
            if is_nullable:
                # Use gen_write_nullable_basic_stmts for nullable basic types
                if isinstance(serializer, BooleanSerializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, bool))
                elif isinstance(serializer, (ByteSerializer, Int16Serializer, Int32Serializer, Int64Serializer)):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, int))
                elif isinstance(serializer, (Float32Serializer, Float64Serializer)):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, float))
                elif isinstance(serializer, StringSerializer):
                    stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, str))
                else:
                    # For complex types, use write_ref_pyobject
                    stmts.append(f"{fory}.write_ref_pyobject({buffer}, {field_value})")
            else:
                stmt = self._get_write_stmt_for_codegen(serializer, buffer, field_value)
                if stmt is None:
                    stmt = f"{fory}.write_ref_pyobject({buffer}, {field_value})"
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
            stmts.append(f"num_fields_written = {buffer}.read_varuint32()")

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
                elif isinstance(serializer, (ByteSerializer, Int16Serializer, Int32Serializer, Int64Serializer)):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, int, lambda v: f"{field_value} = {v}"))
                elif isinstance(serializer, (Float32Serializer, Float64Serializer)):
                    field_stmts.extend(gen_read_nullable_basic_stmts(buffer, float, lambda v: f"{field_value} = {v}"))
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

    def _gen_xwrite_method(self):
        context = {}
        counter = itertools.count(0)
        buffer, fory, value, value_dict = "buffer", "fory", "value", "value_dict"
        context[fory] = self.fory
        context["_serializers"] = self._serializers
        stmts = [
            f'"""xwrite method for {self.type_}"""',
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
            if not self._has_slots:
                stmts.append(f"{field_value} = {value_dict}['{field_name}']")
            else:
                stmts.append(f"{field_value} = {value}.{field_name}")
            is_nullable = self._nullable_fields.get(field_name, False)
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
                    stmts.append(f"{fory}.xwrite_ref({buffer}, {field_value}, serializer={serializer_var})")
            else:
                stmt = self._get_write_stmt_for_codegen(serializer, buffer, field_value)
                if stmt is None:
                    stmt = f"{fory}.xwrite_no_ref({buffer}, {field_value}, serializer={serializer_var})"
                stmts.append(stmt)
        self._xwrite_method_code, func = compile_function(
            f"xwrite_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
            [buffer, value],
            stmts,
            context,
        )
        return func

    def _gen_xread_method(self):
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
            f'"""xread method for {self.type_}"""',
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
                    stmts.append(f"{field_value} = {fory}.xread_ref({buffer}, serializer={serializer_var})")
            else:
                stmt = self._get_read_stmt_for_codegen(serializer, buffer, field_value)
                if stmt is None:
                    stmt = f"{field_value} = {fory}.xread_no_ref({buffer}, serializer={serializer_var})"
                stmts.append(stmt)
            if field_name not in current_class_field_names:
                stmts.append(f"# {field_name} is not in {self.type_}")
                continue
            if not self._has_slots:
                stmts.append(f"{obj_dict}['{field_name}'] = {field_value}")
            else:
                stmts.append(f"{obj}.{field_name} = {field_value}")
        stmts.append(f"return {obj}")
        self._xread_method_code, func = compile_function(
            f"xread_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
            [buffer],
            stmts,
            context,
        )
        return func

    def write(self, buffer, value):
        """Write dataclass instance to buffer in Python native format."""
        self._write_header(buffer)

        for index, field_name in enumerate(self._field_names):
            field_value = getattr(value, field_name)
            serializer = self._serializers[index]
            is_nullable = self._nullable_fields.get(field_name, False)

            if is_nullable:
                self._write_nullable_field(buffer, field_value, serializer)
            else:
                self._write_non_nullable_field(buffer, field_value, serializer)

    def read(self, buffer):
        """Read dataclass instance from buffer in Python native format."""
        num_fields_written = self._read_header(buffer)

        obj = self.type_.__new__(self.type_)
        self.fory.ref_resolver.reference(obj)
        current_class_field_names = set(self._get_field_names(self.type_))

        for index, field_name in enumerate(self._field_names):
            # Only read if this field was written
            if index >= num_fields_written:
                break

            serializer = self._serializers[index]
            is_nullable = self._nullable_fields.get(field_name, False)

            if is_nullable:
                field_value = self._read_nullable_field(buffer, serializer)
            else:
                field_value = self._read_non_nullable_field(buffer, serializer)

            if field_name in current_class_field_names:
                setattr(obj, field_name, field_value)
        return obj

    def xwrite(self, buffer: Buffer, value):
        """Write dataclass instance to buffer in cross-language format."""
        if not self._xlang:
            raise TypeError("xwrite can only be called when DataClassSerializer is in xlang mode")
        if not self.fory.compatible:
            buffer.write_int32(self._hash)
        for index, field_name in enumerate(self._field_names):
            field_value = getattr(value, field_name)
            serializer = self._serializers[index]
            is_nullable = self._nullable_fields.get(field_name, False)
            if is_nullable and field_value is None:
                buffer.write_int8(-3)
            else:
                self.fory.xwrite_ref(buffer, field_value, serializer=serializer)

    def xread(self, buffer):
        """Read dataclass instance from buffer in cross-language format."""
        if not self._xlang:
            raise TypeError("xread can only be called when DataClassSerializer is in xlang mode")
        if not self.fory.compatible:
            hash_ = buffer.read_int32()
            if hash_ != self._hash:
                raise TypeNotCompatibleError(
                    f"Hash {hash_} is not consistent with {self._hash} for type {self.type_}",
                )
        obj = self.type_.__new__(self.type_)
        self.fory.ref_resolver.reference(obj)
        current_class_field_names = set(self._get_field_names(self.type_))
        for index, field_name in enumerate(self._field_names):
            serializer = self._serializers[index]
            is_nullable = self._nullable_fields.get(field_name, False)
            if is_nullable:
                ref_id = buffer.read_int8()
                if ref_id == -3:
                    field_value = None
                else:
                    buffer.reader_index -= 1
                    field_value = self.fory.xread_ref(buffer, serializer=serializer)
            else:
                field_value = self.fory.xread_ref(buffer, serializer=serializer)
            if field_name in current_class_field_names:
                setattr(obj, field_name, field_value)
        return obj


class DataClassStubSerializer(DataClassSerializer):
    def __init__(self, fory, clz: type, xlang: bool = False):
        Serializer.__init__(self, fory, clz)
        self.xlang = xlang

    def write(self, buffer, value):
        self._replace().write(buffer, value)

    def read(self, buffer):
        return self._replace().read(buffer)

    def xwrite(self, buffer, value):
        self._replace().xwrite(buffer, value)

    def xread(self, buffer):
        return self._replace().xread(buffer)

    def _replace(self):
        typeinfo = self.fory.type_resolver.get_typeinfo(self.type_)
        typeinfo.serializer = DataClassSerializer(self.fory, self.type_, self.xlang)
        return typeinfo.serializer


# Use numpy array or python array module.
typecode_dict = (
    {
        # use bytes serializer for byte array.
        "h": (2, int16_array, TypeId.INT16_ARRAY),
        "i": (4, int32_array, TypeId.INT32_ARRAY),
        "l": (8, int64_array, TypeId.INT64_ARRAY),
        "f": (4, float32_array, TypeId.FLOAT32_ARRAY),
        "d": (8, float64_array, TypeId.FLOAT64_ARRAY),
    }
    if not _WINDOWS
    else {
        "h": (2, int16_array, TypeId.INT16_ARRAY),
        "l": (4, int32_array, TypeId.INT32_ARRAY),
        "q": (8, int64_array, TypeId.INT64_ARRAY),
        "f": (4, float32_array, TypeId.FLOAT32_ARRAY),
        "d": (8, float64_array, TypeId.FLOAT64_ARRAY),
    }
)

typeid_code = (
    {
        TypeId.INT16_ARRAY: "h",
        TypeId.INT32_ARRAY: "i",
        TypeId.INT64_ARRAY: "l",
        TypeId.FLOAT32_ARRAY: "f",
        TypeId.FLOAT64_ARRAY: "d",
    }
    if not _WINDOWS
    else {
        TypeId.INT16_ARRAY: "h",
        TypeId.INT32_ARRAY: "l",
        TypeId.INT64_ARRAY: "q",
        TypeId.FLOAT32_ARRAY: "f",
        TypeId.FLOAT64_ARRAY: "d",
    }
)


class PyArraySerializer(XlangCompatibleSerializer):
    typecode_dict = typecode_dict
    typecodearray_type = (
        {
            "h": int16_array,
            "i": int32_array,
            "l": int64_array,
            "f": float32_array,
            "d": float64_array,
        }
        if not _WINDOWS
        else {
            "h": int16_array,
            "l": int32_array,
            "q": int64_array,
            "f": float32_array,
            "d": float64_array,
        }
    )

    def __init__(self, fory, ftype, type_id: str):
        super().__init__(fory, ftype)
        self.typecode = typeid_code[type_id]
        self.itemsize, ftype, self.type_id = typecode_dict[self.typecode]

    def xwrite(self, buffer, value):
        assert value.itemsize == self.itemsize
        view = memoryview(value)
        assert view.format == self.typecode
        assert view.itemsize == self.itemsize
        assert view.c_contiguous  # TODO handle contiguous
        nbytes = len(value) * self.itemsize
        buffer.write_varuint32(nbytes)
        buffer.write_buffer(value)

    def xread(self, buffer):
        data = buffer.read_bytes_and_size()
        arr = array.array(self.typecode, [])
        arr.frombytes(data)
        return arr

    def write(self, buffer, value: array.array):
        nbytes = len(value) * value.itemsize
        buffer.write_string(value.typecode)
        buffer.write_varuint32(nbytes)
        buffer.write_buffer(value)

    def read(self, buffer):
        typecode = buffer.read_string()
        data = buffer.read_bytes_and_size()
        arr = array.array(typecode[0], [])  # Take first character
        arr.frombytes(data)
        return arr


class DynamicPyArraySerializer(Serializer):
    """Serializer for dynamic Python arrays that handles any typecode."""

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self._serializer = ReduceSerializer(fory, cls)

    def xwrite(self, buffer, value):
        itemsize, ftype, type_id = typecode_dict[value.typecode]
        view = memoryview(value)
        nbytes = len(value) * itemsize
        buffer.write_varuint32(type_id)
        buffer.write_varuint32(nbytes)
        if not view.c_contiguous:
            buffer.write_bytes(value.tobytes())
        else:
            buffer.write_buffer(value)

    def xread(self, buffer):
        type_id = buffer.read_varint32()
        typecode = typeid_code[type_id]
        data = buffer.read_bytes_and_size()
        arr = array.array(typecode, [])
        arr.frombytes(data)
        return arr

    def write(self, buffer, value):
        self._serializer.write(buffer, value)

    def read(self, buffer):
        return self._serializer.read(buffer)


if np:
    _np_dtypes_dict = (
        {
            # use bytes serializer for byte array.
            np.dtype(np.bool_): (1, "?", BoolNDArrayType, TypeId.BOOL_ARRAY),
            np.dtype(np.int16): (2, "h", Int16NDArrayType, TypeId.INT16_ARRAY),
            np.dtype(np.int32): (4, "i", Int32NDArrayType, TypeId.INT32_ARRAY),
            np.dtype(np.int64): (8, "l", Int64NDArrayType, TypeId.INT64_ARRAY),
            np.dtype(np.float32): (4, "f", Float32NDArrayType, TypeId.FLOAT32_ARRAY),
            np.dtype(np.float64): (8, "d", Float64NDArrayType, TypeId.FLOAT64_ARRAY),
        }
        if not _WINDOWS
        else {
            np.dtype(np.bool_): (1, "?", BoolNDArrayType, TypeId.BOOL_ARRAY),
            np.dtype(np.int16): (2, "h", Int16NDArrayType, TypeId.INT16_ARRAY),
            np.dtype(np.int32): (4, "l", Int32NDArrayType, TypeId.INT32_ARRAY),
            np.dtype(np.int64): (8, "q", Int64NDArrayType, TypeId.INT64_ARRAY),
            np.dtype(np.float32): (4, "f", Float32NDArrayType, TypeId.FLOAT32_ARRAY),
            np.dtype(np.float64): (8, "d", Float64NDArrayType, TypeId.FLOAT64_ARRAY),
        }
    )
else:
    _np_dtypes_dict = {}


class Numpy1DArraySerializer(Serializer):
    dtypes_dict = _np_dtypes_dict

    def __init__(self, fory, ftype, dtype):
        super().__init__(fory, ftype)
        self.dtype = dtype
        self.itemsize, self.format, self.typecode, self.type_id = _np_dtypes_dict[self.dtype]
        self._serializer = ReduceSerializer(fory, np.ndarray)

    def xwrite(self, buffer, value):
        assert value.itemsize == self.itemsize
        view = memoryview(value)
        try:
            assert view.format == self.typecode
        except AssertionError as e:
            raise e
        assert view.itemsize == self.itemsize
        nbytes = len(value) * self.itemsize
        buffer.write_varuint32(nbytes)
        if self.dtype == np.dtype("bool") or not view.c_contiguous:
            buffer.write_bytes(value.tobytes())
        else:
            buffer.write_buffer(value)

    def xread(self, buffer):
        data = buffer.read_bytes_and_size()
        return np.frombuffer(data, dtype=self.dtype)

    def write(self, buffer, value):
        self._serializer.write(buffer, value)

    def read(self, buffer):
        return self._serializer.read(buffer)


class NDArraySerializer(Serializer):
    def xwrite(self, buffer, value):
        itemsize, typecode, ftype, type_id = _np_dtypes_dict[value.dtype]
        view = memoryview(value)
        nbytes = len(value) * itemsize
        buffer.write_varuint32(type_id)
        buffer.write_varuint32(nbytes)
        if value.dtype == np.dtype("bool") or not view.c_contiguous:
            buffer.write_bytes(value.tobytes())
        else:
            buffer.write_buffer(value)

    def xread(self, buffer):
        raise NotImplementedError("Multi-dimensional array not supported currently")

    def write(self, buffer, value):
        fory = self.fory
        dtype = value.dtype
        fory.write_ref(buffer, dtype)
        buffer.write_varuint32(len(value.shape))
        for dim in value.shape:
            buffer.write_varuint32(dim)
        if dtype.kind == "O":
            buffer.write_varint32(len(value))
            for item in value:
                fory.write_ref(buffer, item)
        else:
            fory.write_buffer_object(buffer, NDArrayBufferObject(value))

    def read(self, buffer):
        fory = self.fory
        dtype = fory.read_ref(buffer)
        ndim = buffer.read_varuint32()
        shape = tuple(buffer.read_varuint32() for _ in range(ndim))
        if dtype.kind == "O":
            length = buffer.read_varint32()
            items = [fory.read_ref(buffer) for _ in range(length)]
            return np.array(items, dtype=object)
        fory_buf = fory.read_buffer_object(buffer)
        if isinstance(fory_buf, memoryview):
            return np.frombuffer(fory_buf, dtype=dtype).reshape(shape)
        elif isinstance(fory_buf, bytes):
            return np.frombuffer(fory_buf, dtype=dtype).reshape(shape)
        return np.frombuffer(fory_buf.to_pybytes(), dtype=dtype).reshape(shape)


class BytesSerializer(XlangCompatibleSerializer):
    def write(self, buffer, value):
        self.fory.write_buffer_object(buffer, BytesBufferObject(value))

    def read(self, buffer):
        fory_buf = self.fory.read_buffer_object(buffer)
        if isinstance(fory_buf, memoryview):
            return bytes(fory_buf)
        elif isinstance(fory_buf, bytes):
            return fory_buf
        return fory_buf.to_pybytes()


class BytesBufferObject(BufferObject):
    __slots__ = ("binary",)

    def __init__(self, binary: bytes):
        self.binary = binary

    def total_bytes(self) -> int:
        return len(self.binary)

    def write_to(self, stream):
        if hasattr(stream, "write_bytes"):
            stream.write_bytes(self.binary)
        else:
            stream.write(self.binary)

    def getbuffer(self) -> memoryview:
        return memoryview(self.binary)


class PickleBufferSerializer(XlangCompatibleSerializer):
    def write(self, buffer, value):
        self.fory.write_buffer_object(buffer, PickleBufferObject(value))

    def read(self, buffer):
        fory_buf = self.fory.read_buffer_object(buffer)
        if isinstance(fory_buf, (bytes, memoryview, bytearray, Buffer)):
            return pickle.PickleBuffer(fory_buf)
        return pickle.PickleBuffer(fory_buf.to_pybytes())


class PickleBufferObject(BufferObject):
    __slots__ = ("pickle_buffer",)

    def __init__(self, pickle_buffer):
        self.pickle_buffer = pickle_buffer

    def total_bytes(self) -> int:
        return len(self.pickle_buffer.raw())

    def write_to(self, stream):
        raw = self.pickle_buffer.raw()
        if hasattr(stream, "write_buffer"):
            stream.write_buffer(raw)
        else:
            stream.write(bytes(raw) if isinstance(raw, memoryview) else raw)

    def getbuffer(self) -> memoryview:
        raw = self.pickle_buffer.raw()
        if isinstance(raw, memoryview):
            return raw
        return memoryview(bytes(raw))


class NDArrayBufferObject(BufferObject):
    __slots__ = ("array", "dtype", "shape")

    def __init__(self, array):
        self.array = array
        self.dtype = array.dtype
        self.shape = array.shape

    def total_bytes(self) -> int:
        return self.array.nbytes

    def write_to(self, stream):
        data = self.array.tobytes()
        if hasattr(stream, "write_buffer"):
            stream.write_buffer(data)
        else:
            stream.write(data)

    def getbuffer(self) -> memoryview:
        if self.array.flags.c_contiguous:
            return memoryview(self.array.data)
        return memoryview(self.array.tobytes())


class StatefulSerializer(XlangCompatibleSerializer):
    """
    Serializer for objects that support __getstate__ and __setstate__.
    Uses Fory's native serialization for better cross-language support.
    """

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self.cls = cls
        # Cache the method references as fields in the serializer.
        self._getnewargs_ex = getattr(cls, "__getnewargs_ex__", None)
        self._getnewargs = getattr(cls, "__getnewargs__", None)

    def write(self, buffer, value):
        state = value.__getstate__()
        args = ()
        kwargs = {}
        if self._getnewargs_ex is not None:
            args, kwargs = self._getnewargs_ex(value)
        elif self._getnewargs is not None:
            args = self._getnewargs(value)

        # Serialize constructor arguments first
        self.fory.write_ref(buffer, args)
        self.fory.write_ref(buffer, kwargs)

        # Then serialize the state
        self.fory.write_ref(buffer, state)

    def read(self, buffer):
        fory = self.fory
        args = fory.read_ref(buffer)
        kwargs = fory.read_ref(buffer)
        state = fory.read_ref(buffer)

        if args or kwargs:
            # Case 1: __getnewargs__ was used. Re-create by calling __init__.
            obj = self.cls(*args, **kwargs)
        else:
            # Case 2: Only __getstate__ was used. Create without calling __init__.
            obj = self.cls.__new__(self.cls)

        if state:
            fory.policy.intercept_setstate(obj, state)
            obj.__setstate__(state)
        return obj


class ReduceSerializer(XlangCompatibleSerializer):
    """
    Serializer for objects that support __reduce__ or __reduce_ex__.
    Uses Fory's native serialization for better cross-language support.
    Has higher precedence than StatefulSerializer.
    """

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self.cls = cls
        # Cache the method references as fields in the serializer.
        self._reduce_ex = getattr(cls, "__reduce_ex__", None)
        self._reduce = getattr(cls, "__reduce__", None)
        self._getnewargs_ex = getattr(cls, "__getnewargs_ex__", None)
        self._getnewargs = getattr(cls, "__getnewargs__", None)

    def write(self, buffer, value):
        # Try __reduce_ex__ first (with protocol 5 for pickle5 out-of-band buffer support), then __reduce__
        # Check if the object has a custom __reduce_ex__ method (not just the default from object)
        if hasattr(value, "__reduce_ex__") and value.__class__.__reduce_ex__ is not object.__reduce_ex__:
            try:
                reduce_result = value.__reduce_ex__(5)
            except TypeError:
                # Some objects don't support protocol argument
                reduce_result = value.__reduce_ex__()
        elif hasattr(value, "__reduce__"):
            reduce_result = value.__reduce__()
        else:
            raise ValueError(f"Object {value} has no __reduce__ or __reduce_ex__ method")

        # Handle different __reduce__ return formats
        if isinstance(reduce_result, str):
            # Case 1: Just a global name (simple case)
            reduce_data = (0, reduce_result)
        elif isinstance(reduce_result, tuple):
            if len(reduce_result) == 2:
                # Case 2: (callable, args)
                callable_obj, args = reduce_result
                reduce_data = (1, callable_obj, args)
            elif len(reduce_result) == 3:
                # Case 3: (callable, args, state)
                callable_obj, args, state = reduce_result
                reduce_data = (1, callable_obj, args, state)
            elif len(reduce_result) == 4:
                # Case 4: (callable, args, state, listitems)
                callable_obj, args, state, listitems = reduce_result
                reduce_data = (1, callable_obj, args, state, listitems)
            elif len(reduce_result) == 5:
                # Case 5: (callable, args, state, listitems, dictitems)
                callable_obj, args, state, listitems, dictitems = reduce_result
                reduce_data = (
                    1,
                    callable_obj,
                    args,
                    state,
                    listitems,
                    dictitems,
                )
            else:
                raise ValueError(f"Invalid __reduce__ result length: {len(reduce_result)}")
        else:
            raise ValueError(f"Invalid __reduce__ result type: {type(reduce_result)}")
        buffer.write_varuint32(len(reduce_data))
        fory = self.fory
        for item in reduce_data:
            fory.write_ref(buffer, item)

    def read(self, buffer):
        reduce_data_num_items = buffer.read_varuint32()
        assert reduce_data_num_items <= 6, buffer
        reduce_data = [None] * 6
        fory = self.fory
        for i in range(reduce_data_num_items):
            reduce_data[i] = fory.read_ref(buffer)

        if reduce_data[0] == 0:
            # Case 1: Global name
            global_name = reduce_data[1]
            # Import and return the global object
            if "." in global_name:
                module_name, obj_name = global_name.rsplit(".", 1)
                module = __import__(module_name, fromlist=[obj_name])
                return getattr(module, obj_name)
            else:
                # Handle case where global_name doesn't contain a dot
                # This might be a built-in type or a simple name
                try:
                    import builtins

                    return getattr(builtins, global_name)
                except AttributeError:
                    raise ValueError(f"Cannot resolve global name: {global_name}")
        elif reduce_data[0] == 1:
            # Case 2-5: Callable with args and optional state/items
            callable_obj = reduce_data[1]
            args = reduce_data[2] or ()
            state = reduce_data[3]
            listitems = reduce_data[4]
            dictitems = reduce_data[5] if len(reduce_data) > 5 else None

            obj = fory.policy.intercept_reduce_call(callable_obj, args)
            if obj is None:
                # Create the object using the callable and args
                obj = callable_obj(*args)

            # Restore state if present
            if state is not None:
                if hasattr(obj, "__setstate__"):
                    obj.__setstate__(state)
                else:
                    # Fallback: update __dict__ directly
                    if hasattr(obj, "__dict__"):
                        obj.__dict__.update(state)

            # Restore list items if present
            if listitems is not None:
                obj.extend(listitems)

            # Restore dict items if present
            if dictitems is not None:
                for key, value in dictitems:
                    obj[key] = value

            result = fory.policy.inspect_reduced_object(obj)
            if result is not None:
                obj = result
            return obj
        else:
            raise ValueError(f"Invalid reduce data format flag: {reduce_data[0]}")


__skip_class_attr_names__ = ("__module__", "__qualname__", "__dict__", "__weakref__")


class TypeSerializer(Serializer):
    """Serializer for Python type objects (classes), including local classes."""

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self.cls = cls

    def write(self, buffer, value):
        module_name = value.__module__
        qualname = value.__qualname__

        if module_name == "__main__" or "<locals>" in qualname:
            # Local class - serialize full context
            buffer.write_int8(1)  # Local class marker
            self._serialize_local_class(buffer, value)
        else:
            buffer.write_int8(0)  # Global class marker
            buffer.write_string(module_name)
            buffer.write_string(qualname)

    def read(self, buffer):
        class_type = buffer.read_int8()

        if class_type == 1:
            # Local class - deserialize from full context
            return self._deserialize_local_class(buffer)
        else:
            # Global class - import by module and name
            module_name = buffer.read_string()
            qualname = buffer.read_string()
            cls = importlib.import_module(module_name)
            for name in qualname.split("."):
                cls = getattr(cls, name)
            result = self.fory.policy.validate_class(cls, is_local=False)
            if result is not None:
                cls = result
            return cls

    def _serialize_local_class(self, buffer, cls):
        """Serialize a local class by capturing its creation context."""
        assert self.fory.ref_tracking, "Reference tracking must be enabled for local classes serialization"
        # Basic class information
        module = cls.__module__
        qualname = cls.__qualname__
        buffer.write_string(module)
        buffer.write_string(qualname)
        fory = self.fory

        # Serialize base classes
        # Let Fory's normal serialization handle bases (including other local classes)
        bases = cls.__bases__
        buffer.write_varuint32(len(bases))
        for base in bases:
            fory.write_ref(buffer, base)

        # Serialize class dictionary (excluding special attributes)
        # FunctionSerializer will automatically handle methods with closures
        class_dict = {}
        attr_names, class_methods = [], []
        for attr_name, attr_value in cls.__dict__.items():
            # Skip special attributes that are handled by type() constructor
            if attr_name in __skip_class_attr_names__:
                continue
            if isinstance(attr_value, classmethod):
                attr_names.append(attr_name)
                class_methods.append(attr_value)
            else:
                class_dict[attr_name] = attr_value
        # serialize method specially to avoid circular deps in method deserialization
        buffer.write_varuint32(len(class_methods))
        for i in range(len(class_methods)):
            buffer.write_string(attr_names[i])
            class_method = class_methods[i]
            fory.write_ref(buffer, class_method.__func__)

        # Let Fory's normal serialization handle the class dict
        # This will use FunctionSerializer for methods, which handles closures properly
        fory.write_ref(buffer, class_dict)

    def _deserialize_local_class(self, buffer):
        """Deserialize a local class by recreating it with the captured context."""
        fory = self.fory
        assert fory.ref_tracking, "Reference tracking must be enabled for local classes deserialization"
        # Read basic class information
        module = buffer.read_string()
        qualname = buffer.read_string()
        name = qualname.rsplit(".", 1)[-1]
        ref_id = fory.ref_resolver.last_preserved_ref_id()

        # Read base classes
        num_bases = buffer.read_varuint32()
        bases = tuple([fory.read_ref(buffer) for _ in range(num_bases)])
        # Create the class using type() constructor
        cls = type(name, bases, {})
        # `class_dict` may reference to `cls`, which is a circular reference
        fory.ref_resolver.set_read_object(ref_id, cls)

        # classmethods
        for i in range(buffer.read_varuint32()):
            attr_name = buffer.read_string()
            func = fory.read_ref(buffer)
            method = types.MethodType(func, cls)
            setattr(cls, attr_name, method)
        # Read class dictionary
        # Fory's normal deserialization will handle methods via FunctionSerializer
        class_dict = fory.read_ref(buffer)
        for k, v in class_dict.items():
            setattr(cls, k, v)

        # Set module and qualname
        cls.__module__ = module
        cls.__qualname__ = qualname
        result = fory.policy.validate_class(cls, is_local=True)
        if result is not None:
            cls = result
        return cls


class ModuleSerializer(Serializer):
    """Serializer for python module"""

    def __init__(self, fory):
        super().__init__(fory, types.ModuleType)

    def write(self, buffer, value):
        buffer.write_string(value.__name__)

    def read(self, buffer):
        mod_name = buffer.read_string()
        result = self.fory.policy.validate_module(mod_name)
        if result is not None:
            if isinstance(result, types.ModuleType):
                return result
            assert isinstance(result, str), f"validate_module must return module, str, or None, got {type(result)}"
            mod_name = result
        return importlib.import_module(mod_name)


class MappingProxySerializer(Serializer):
    def __init__(self, fory):
        super().__init__(fory, types.MappingProxyType)

    def write(self, buffer, value):
        self.fory.write_ref(buffer, dict(value))

    def read(self, buffer):
        return types.MappingProxyType(self.fory.read_ref(buffer))


class FunctionSerializer(XlangCompatibleSerializer):
    """Serializer for function objects

    This serializer captures all the necessary information to recreate a function:
    - Function code
    - Function name
    - Module name
    - Closure variables
    - Global variables
    - Default arguments
    - Function attributes

    The code object is serialized with marshal, and all other components
    (defaults, globals, closure cells, attrs) go through Fory’s own
    write_ref/read_ref pipeline to ensure proper type registration
    and reference tracking.
    """

    # Cache for function attributes that are handled separately
    _FUNCTION_ATTRS = frozenset(
        (
            "__code__",
            "__name__",
            "__defaults__",
            "__closure__",
            "__globals__",
            "__module__",
            "__qualname__",
        )
    )

    def _serialize_function(self, buffer, func):
        """Serialize a function by capturing all its components."""
        # Get function metadata
        instance = getattr(func, "__self__", None)
        if instance is not None and not inspect.ismodule(instance):
            # Handle bound methods
            self_obj = instance
            func_name = func.__name__
            # Serialize as a tuple (is_method, self_obj, method_name)
            buffer.write_int8(0)  # is a method
            # For the 'self' object, we need to use fory's serialization
            self.fory.write_ref(buffer, self_obj)
            buffer.write_string(func_name)
            return

        # Regular function or lambda
        code = func.__code__
        module = func.__module__
        qualname = func.__qualname__

        if "<locals>" not in qualname and module != "__main__":
            buffer.write_int8(1)  # Not a method
            buffer.write_string(module)
            buffer.write_string(qualname)
            return

        # Serialize function metadata
        buffer.write_int8(2)  # Not a method
        buffer.write_string(module)
        buffer.write_string(qualname)

        defaults = func.__defaults__
        closure = func.__closure__
        globals_dict = func.__globals__

        # Instead of trying to serialize the code object in parts, use marshal
        # which is specifically designed for code objects
        marshalled_code = marshal.dumps(code)
        buffer.write_bytes_and_size(marshalled_code)

        # Serialize defaults (or None if no defaults)
        # Write whether defaults exist
        buffer.write_bool(defaults is not None)
        if defaults is not None:
            # Write the number of default arguments
            buffer.write_varuint32(len(defaults))
            # Serialize each default value individually
            for default_value in defaults:
                self.fory.write_ref(buffer, default_value)

        # Handle closure
        # We need to serialize both the closure values and the fact that there is a closure
        # The code object's co_freevars tells us what variables are in the closure
        buffer.write_bool(closure is not None)
        buffer.write_varuint32(len(code.co_freevars) if code.co_freevars else 0)

        if closure:
            # Extract and serialize each closure cell's contents
            for cell in closure:
                self.fory.write_ref(buffer, cell.cell_contents)

        # Serialize free variable names as a list of strings
        # Convert tuple to list since tuple might not be registered
        freevars_list = list(code.co_freevars) if code.co_freevars else []
        buffer.write_varuint32(len(freevars_list))
        for name in freevars_list:
            buffer.write_string(name)

        # Handle globals
        # Identify which globals are actually used by the function
        global_names = set()
        for name in code.co_names:
            if name in globals_dict and not hasattr(builtins, name):
                global_names.add(name)

        # Add any globals referenced by nested functions in co_consts
        for const in code.co_consts:
            if isinstance(const, types.CodeType):
                for name in const.co_names:
                    if name in globals_dict and not hasattr(builtins, name):
                        global_names.add(name)

        # Create and serialize a dictionary with only the necessary globals
        globals_to_serialize = {name: globals_dict[name] for name in global_names if name in globals_dict}
        self.fory.write_ref(buffer, globals_to_serialize)

        # Handle additional attributes
        attrs = {}
        for attr in dir(func):
            if attr.startswith("__") and attr.endswith("__"):
                continue
            if attr in self._FUNCTION_ATTRS:
                continue
            try:
                attrs[attr] = getattr(func, attr)
            except (AttributeError, TypeError):
                pass

        self.fory.write_ref(buffer, attrs)

    def _deserialize_function(self, buffer):
        """Deserialize a function from its components."""

        # Check if it's a method
        func_type_id = buffer.read_int8()
        if func_type_id == 0:
            # Handle bound methods
            self_obj = self.fory.read_ref(buffer)
            method_name = buffer.read_string()
            func = getattr(self_obj, method_name)
            result = self.fory.policy.validate_function(func, is_local=False)
            if result is not None:
                func = result
            return func

        if func_type_id == 1:
            module = buffer.read_string()
            qualname = buffer.read_string()
            mod = importlib.import_module(module)
            for name in qualname.split("."):
                mod = getattr(mod, name)
            result = self.fory.policy.validate_function(mod, is_local=False)
            if result is not None:
                mod = result
            return mod

        # Regular function or lambda
        module = buffer.read_string()
        qualname = buffer.read_string()
        name = qualname.rsplit(".")[-1]

        # Use marshal to load the code object, which handles all Python versions correctly
        marshalled_code = buffer.read_bytes_and_size()
        code = marshal.loads(marshalled_code)

        # Deserialize defaults
        has_defaults = buffer.read_bool()
        defaults = None
        if has_defaults:
            # Read the number of default arguments
            num_defaults = buffer.read_varuint32()
            # Deserialize each default value
            default_values = []
            for _ in range(num_defaults):
                default_values.append(self.fory.read_ref(buffer))
            defaults = tuple(default_values)

        # Handle closure
        has_closure = buffer.read_bool()
        num_freevars = buffer.read_varuint32()
        closure = None

        # Read closure values if there are any
        closure_values = []
        if has_closure:
            for _ in range(num_freevars):
                closure_values.append(self.fory.read_ref(buffer))

            # Create closure cells
            closure = tuple(types.CellType(value) for value in closure_values)

        # Read free variable names from strings
        num_freevars = buffer.read_varuint32()
        freevars = []
        for _ in range(num_freevars):
            freevars.append(buffer.read_string())

        # Handle globals
        globals_dict = self.fory.read_ref(buffer)

        # Create a globals dictionary with module's globals as the base
        func_globals = {}
        try:
            mod = importlib.import_module(module)
            if mod:
                func_globals.update(mod.__dict__)
        except (KeyError, AttributeError):
            pass

        # Add the deserialized globals
        func_globals.update(globals_dict)

        # Ensure __builtins__ is available
        if "__builtins__" not in func_globals:
            func_globals["__builtins__"] = builtins

        # Create function
        func = types.FunctionType(code, func_globals, name, defaults, closure)

        # Set function attributes
        func.__module__ = module
        func.__qualname__ = qualname

        # Deserialize and set additional attributes
        attrs = self.fory.read_ref(buffer)
        for attr_name, attr_value in attrs.items():
            setattr(func, attr_name, attr_value)

        result = self.fory.policy.validate_function(func, is_local=True)
        if result is not None:
            func = result
        return func

    def xwrite(self, buffer, value):
        raise NotImplementedError()

    def xread(self, buffer):
        raise NotImplementedError()

    def write(self, buffer, value):
        """Serialize a function for Python-only mode."""
        self._serialize_function(buffer, value)

    def read(self, buffer):
        """Deserialize a function for Python-only mode."""
        return self._deserialize_function(buffer)


class NativeFuncMethodSerializer(Serializer):
    def write(self, buffer, func):
        name = func.__name__
        buffer.write_string(name)
        obj = getattr(func, "__self__", None)
        if obj is None or inspect.ismodule(obj):
            buffer.write_bool(True)
            module = func.__module__
            buffer.write_string(module)
        else:
            buffer.write_bool(False)
            self.fory.write_ref(buffer, obj)

    def read(self, buffer):
        name = buffer.read_string()
        if buffer.read_bool():
            module = buffer.read_string()
            mod = importlib.import_module(module)
            func = getattr(mod, name)
        else:
            obj = self.fory.read_ref(buffer)
            func = getattr(obj, name)
        result = self.fory.policy.validate_function(func, is_local=False)
        if result is not None:
            func = result
        return func


class MethodSerializer(Serializer):
    """Serializer for bound method objects."""

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self.cls = cls

    def write(self, buffer, value):
        # Serialize bound method as (instance, method_name)
        instance = value.__self__
        method_name = value.__func__.__name__

        self.fory.write_ref(buffer, instance)
        buffer.write_string(method_name)

    def read(self, buffer):
        instance = self.fory.read_ref(buffer)
        method_name = buffer.read_string()

        method = getattr(instance, method_name)
        cls = method.__self__.__class__
        is_local = cls.__module__ == "__main__" or "<locals>" in cls.__qualname__
        result = self.fory.policy.validate_method(method, is_local=is_local)
        if result is not None:
            method = result
        return method

    def xwrite(self, buffer, value):
        return self.write(buffer, value)

    def xread(self, buffer):
        return self.read(buffer)


class ObjectSerializer(Serializer):
    """Serializer for regular Python objects.
    It serializes objects based on `__dict__` or `__slots__`.
    """

    def __init__(self, fory, clz: type):
        super().__init__(fory, clz)
        # If the class defines __slots__, compute and store a sorted list once
        slots = getattr(clz, "__slots__", None)
        self._slot_field_names = None
        if slots is not None:
            # __slots__ can be a string or iterable of strings
            if isinstance(slots, str):
                slots = [slots]
            self._slot_field_names = sorted(slots)

    def write(self, buffer, value):
        # Use precomputed slots if available, otherwise sort instance __dict__ keys
        if self._slot_field_names is not None:
            sorted_field_names = self._slot_field_names
        else:
            sorted_field_names = sorted(value.__dict__.keys())

        buffer.write_varuint32(len(sorted_field_names))
        for field_name in sorted_field_names:
            buffer.write_string(field_name)
            field_value = getattr(value, field_name)
            self.fory.write_ref(buffer, field_value)

    def read(self, buffer):
        fory = self.fory
        fory.policy.authorize_instantiation(self.type_)
        obj = self.type_.__new__(self.type_)
        fory.ref_resolver.reference(obj)
        num_fields = buffer.read_varuint32()
        for _ in range(num_fields):
            field_name = buffer.read_string()
            field_value = fory.read_ref(buffer)
            setattr(obj, field_name, field_value)
        return obj

    def xwrite(self, buffer, value):
        # for cross-language or minimal framing, reuse the same logic
        return self.write(buffer, value)

    def xread(self, buffer):
        # symmetric to xwrite
        return self.read(buffer)


@dataclasses.dataclass
class NonExistEnum:
    value: int = -1
    name: str = ""


class NonExistEnumSerializer(Serializer):
    def __init__(self, fory):
        super().__init__(fory, NonExistEnum)
        self.need_to_write_ref = False

    @classmethod
    def support_subclass(cls) -> bool:
        return True

    def write(self, buffer, value):
        buffer.write_string(value.name)

    def read(self, buffer):
        name = buffer.read_string()
        return NonExistEnum(name=name)

    def xwrite(self, buffer, value):
        buffer.write_varuint32(value.value)

    def xread(self, buffer):
        value = buffer.read_varuint32()
        return NonExistEnum(value=value)


class UnsupportedSerializer(Serializer):
    def write(self, buffer, value):
        self.fory.handle_unsupported_write(value)

    def read(self, buffer):
        return self.fory.handle_unsupported_read(buffer)

    def xwrite(self, buffer, value):
        raise NotImplementedError(f"{self.type_} is not supported for xwrite")

    def xread(self, buffer):
        raise NotImplementedError(f"{self.type_} is not supported for xread")
