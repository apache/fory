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

"""Schema validation for Fory IDL."""

from dataclasses import dataclass
from typing import List, Optional, Union as TypingUnion

from fory_compiler.ir.ast import (
    Schema,
    Message,
    Enum,
    Union,
    Field,
    FieldType,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
    SourceLocation,
)
from fory_compiler.ir.types import PrimitiveKind
from fory_compiler.ir.type_id import compute_registered_type_id


@dataclass
class ValidationIssue:
    """Validation issue with optional source location."""

    message: str
    location: Optional[SourceLocation]
    severity: str

    def __str__(self) -> str:
        if not self.location:
            return self.message
        return f"{self.location.file}:{self.location.line}:{self.location.column}: {self.message}"


class SchemaValidator:
    """Validates a Fory IR schema."""

    def __init__(self, schema: Schema):
        self.schema = schema
        self.errors: List[ValidationIssue] = []
        self.warnings: List[ValidationIssue] = []

    def validate(self) -> bool:
        self._apply_field_defaults()
        self._apply_type_id_defaults()
        self._check_duplicate_type_names()
        self._check_duplicate_type_ids()
        self._check_messages()
        self._check_type_references()
        self._check_collection_nesting()
        self._check_ref_rules()
        self._check_weak_refs()
        return not self.errors

    def _error(self, message: str, location: Optional[SourceLocation]) -> None:
        self.errors.append(ValidationIssue(message, location, "error"))

    def _warning(self, message: str, location: Optional[SourceLocation]) -> None:
        self.warnings.append(ValidationIssue(message, location, "warning"))

    def _apply_type_id_defaults(self) -> None:
        enable_auto_type_id = self.schema.get_option("enable_auto_type_id", True)
        if enable_auto_type_id is False:
            return
        used_ids = {}
        for t in self.schema.get_all_types():
            if t.type_id is not None:
                used_ids[t.type_id] = t

        def qualify(full_name: str) -> str:
            package = self.schema.package_alias or self.schema.package
            if package:
                return f"{package}.{full_name}"
            return full_name

        def resolve_hash_source(full_name: str, alias: Optional[str]) -> str:
            if not alias:
                return qualify(full_name)
            if "." in alias:
                return alias
            package = self.schema.package_alias or self.schema.package
            if package:
                return f"{package}.{alias}"
            return alias

        def assign_id(type_def, full_name: str) -> None:
            if type_def.type_id is not None:
                return
            alias = type_def.options.get("alias")
            source_name = resolve_hash_source(full_name, alias)
            generated_id = compute_registered_type_id(source_name)
            if generated_id in used_ids:
                self._error(
                    (
                        "Auto-generated type id collision for "
                        f"'{source_name}'. Specify an explicit [id=...] or "
                        'use [alias="..."] to change the hash source.'
                    ),
                    type_def.location,
                )
                return
            type_def.type_id = generated_id
            type_def.id_generated = True
            type_def.id_source = source_name
            used_ids[generated_id] = type_def
            # Do not emit warnings for generated ids.

        def walk_message(message: Message, parent_path: str = "") -> None:
            full_name = f"{parent_path}.{message.name}" if parent_path else message.name
            assign_id(message, full_name)
            for nested_enum in message.nested_enums:
                nested_name = f"{full_name}.{nested_enum.name}"
                assign_id(nested_enum, nested_name)
            for nested_union in message.nested_unions:
                nested_name = f"{full_name}.{nested_union.name}"
                assign_id(nested_union, nested_name)
            for nested_msg in message.nested_messages:
                walk_message(nested_msg, full_name)

        for enum in self.schema.enums:
            assign_id(enum, enum.name)
        for union in self.schema.unions:
            assign_id(union, union.name)
        for message in self.schema.messages:
            walk_message(message)

    def _check_duplicate_type_names(self) -> None:
        names = {}
        for enum in self.schema.enums:
            if enum.name in names:
                self._error(
                    f"Duplicate type name: {enum.name}",
                    enum.location or names[enum.name],
                )
            names.setdefault(enum.name, enum.location)
        for union in self.schema.unions:
            if union.name in names:
                self._error(
                    f"Duplicate type name: {union.name}",
                    union.location or names[union.name],
                )
            names.setdefault(union.name, union.location)
        for message in self.schema.messages:
            if message.name in names:
                self._error(
                    f"Duplicate type name: {message.name}",
                    message.location or names[message.name],
                )
            names.setdefault(message.name, message.location)

    def _check_duplicate_type_ids(self) -> None:
        type_ids = {}
        for t in self.schema.get_all_types():
            if t.type_id is None:
                continue
            if t.type_id in type_ids:
                self._error(
                    f"Duplicate type ID @{t.type_id}: {t.name} and {type_ids[t.type_id].name}",
                    t.location,
                )
            type_ids.setdefault(t.type_id, t)

    def _check_messages(self) -> None:
        def validate_message(message: Message, parent_path: str = ""):
            full_name = f"{parent_path}.{message.name}" if parent_path else message.name

            nested_names = {}
            for nested_enum in message.nested_enums:
                if nested_enum.name in nested_names:
                    self._error(
                        f"Duplicate nested type name in {full_name}: {nested_enum.name}",
                        nested_enum.location,
                    )
                nested_names.setdefault(nested_enum.name, nested_enum.location)
            for nested_union in message.nested_unions:
                if nested_union.name in nested_names:
                    self._error(
                        f"Duplicate nested type name in {full_name}: {nested_union.name}",
                        nested_union.location,
                    )
                nested_names.setdefault(nested_union.name, nested_union.location)
            for nested_msg in message.nested_messages:
                if nested_msg.name in nested_names:
                    self._error(
                        f"Duplicate nested type name in {full_name}: {nested_msg.name}",
                        nested_msg.location,
                    )
                nested_names.setdefault(nested_msg.name, nested_msg.location)

            field_numbers = {}
            field_names = {}
            for f in message.fields:
                if f.number in field_numbers:
                    self._error(
                        f"Duplicate field number {f.number} in {full_name}: {f.name} and {field_numbers[f.number].name}",
                        f.location,
                    )
                field_numbers.setdefault(f.number, f)
                if f.name in field_names:
                    self._error(
                        f"Duplicate field name in {full_name}: {f.name}", f.location
                    )
                field_names.setdefault(f.name, f)

            for nested_enum in message.nested_enums:
                validate_enum(nested_enum, full_name)

            for nested_union in message.nested_unions:
                validate_union(nested_union, full_name)

            for nested_msg in message.nested_messages:
                validate_message(nested_msg, full_name)

        def validate_enum(enum: Enum, parent_path: str = ""):
            full_name = f"{parent_path}.{enum.name}" if parent_path else enum.name
            value_numbers = {}
            value_names = {}
            for v in enum.values:
                if v.value in value_numbers:
                    self._error(
                        f"Duplicate enum value {v.value} in {full_name}: {v.name} and {value_numbers[v.value].name}",
                        v.location,
                    )
                value_numbers.setdefault(v.value, v)
                if v.name in value_names:
                    self._error(
                        f"Duplicate enum value name in {full_name}: {v.name}",
                        v.location,
                    )
                value_names.setdefault(v.name, v)

        def validate_union(union: Union, parent_path: str = ""):
            full_name = f"{parent_path}.{union.name}" if parent_path else union.name
            case_numbers = {}
            case_names = {}
            for f in union.fields:
                if f.number in case_numbers:
                    self._error(
                        f"Duplicate union case id {f.number} in {full_name}: {f.name} and {case_numbers[f.number].name}",
                        f.location,
                    )
                case_numbers.setdefault(f.number, f)
                if f.name in case_names:
                    self._error(
                        f"Duplicate union case name in {full_name}: {f.name}",
                        f.location,
                    )
                case_names.setdefault(f.name, f)

        for enum in self.schema.enums:
            validate_enum(enum)

        for union in self.schema.unions:
            validate_union(union)

        for message in self.schema.messages:
            validate_message(message)

    def _apply_field_defaults(self) -> None:
        def apply_message_fields(
            message: Message,
            enclosing_messages: Optional[List[Message]] = None,
        ) -> None:
            lineage = (enclosing_messages or []) + [message]
            for field in message.fields:
                if self.schema.source_format == "fdl" and field.tag_id is None:
                    field.tag_id = field.number
                if self._is_message_type(field.field_type, lineage):
                    explicit_optional = field.optional
                    if self.schema.source_format == "fdl" and explicit_optional:
                        self._error(
                            "Message fields are always optional; remove the optional modifier",
                            field.location,
                        )
                    field.optional = True
            for nested_msg in message.nested_messages:
                apply_message_fields(nested_msg, lineage)

        for message in self.schema.messages:
            apply_message_fields(message)

    def _is_message_type(
        self, field_type: FieldType, parent_stack: List[Message]
    ) -> bool:
        if not isinstance(field_type, NamedType):
            return False
        resolved = self._resolve_named_type(field_type.name, parent_stack)
        return isinstance(resolved, Message)

    def _resolve_named_type(
        self, name: str, parent_stack: List[Message]
    ) -> Optional[TypingUnion[Message, Enum, Union]]:
        parts = name.split(".")
        if len(parts) > 1:
            current = self._find_top_level_type(parts[0])
            for part in parts[1:]:
                if isinstance(current, Message):
                    current = current.get_nested_type(part)
                else:
                    return None
            return current
        for msg in reversed(parent_stack):
            nested = msg.get_nested_type(name)
            if nested is not None:
                return nested
        return self._find_top_level_type(name)

    def _find_top_level_type(
        self, name: str
    ) -> Optional[TypingUnion[Message, Enum, Union]]:
        for enum in self.schema.enums:
            if enum.name == name:
                return enum
        for union in self.schema.unions:
            if union.name == name:
                return union
        for message in self.schema.messages:
            if message.name == name:
                return message
        return None

    def _check_type_references(self) -> None:
        def check_type_ref(
            field_type: FieldType,
            field: Field,
            enclosing_messages: Optional[List[Message]] = None,
        ):
            if isinstance(field_type, NamedType):
                type_name = field_type.name
                found = False

                if enclosing_messages and "." not in type_name:
                    for message in reversed(enclosing_messages):
                        if message.get_nested_type(type_name) is not None:
                            found = True
                            break

                if not found and self.schema.get_type(type_name) is not None:
                    found = True

                if not found:
                    self._error(f"Unknown type '{type_name}'", field.location)
            elif isinstance(field_type, ListType):
                check_type_ref(field_type.element_type, field, enclosing_messages)
            elif isinstance(field_type, MapType):
                check_type_ref(field_type.key_type, field, enclosing_messages)
                check_type_ref(field_type.value_type, field, enclosing_messages)

        def check_message_refs(
            message: Message,
            enclosing_messages: Optional[List[Message]] = None,
        ):
            lineage = (enclosing_messages or []) + [message]
            for f in message.fields:
                check_type_ref(f.field_type, f, lineage)
            for nested_msg in message.nested_messages:
                check_message_refs(nested_msg, lineage)

            for nested_union in message.nested_unions:
                for f in nested_union.fields:
                    check_type_ref(f.field_type, f, lineage)

        for message in self.schema.messages:
            check_message_refs(message)

        for union in self.schema.unions:
            for f in union.fields:
                check_type_ref(f.field_type, f, None)

    def _check_ref_rules(self) -> None:
        def is_any_type(field_type: FieldType) -> bool:
            return (
                isinstance(field_type, PrimitiveType)
                and field_type.kind == PrimitiveKind.ANY
            )

        def resolve_target(
            target: NamedType,
            enclosing_messages: Optional[List[Message]],
        ) -> Optional[TypingUnion[Message, Enum, Union]]:
            if enclosing_messages is not None:
                return self._resolve_named_type(target.name, enclosing_messages)
            return self._find_top_level_type(target.name)

        def ensure_message_or_union(
            target: FieldType,
            field: Field,
            enclosing_messages: Optional[List[Message]],
            context: str,
        ) -> None:
            if not isinstance(target, NamedType):
                self._error(
                    f"{context} is only valid for message/union types",
                    field.location,
                )
                return
            resolved = resolve_target(target, enclosing_messages)
            if isinstance(resolved, Enum):
                self._error(
                    f"{context} is only valid for message/union types, not enums",
                    field.location,
                )

        def check_field(
            field: Field,
            enclosing_messages: Optional[List[Message]] = None,
        ) -> None:
            if is_any_type(field.field_type) and field.ref:
                self._error(
                    "ref is not allowed on any fields",
                    field.location,
                )

            if (
                isinstance(field.field_type, ListType)
                and is_any_type(field.field_type.element_type)
                and field.element_ref
            ):
                self._error(
                    "ref is not allowed on list<any> fields",
                    field.location,
                )

            if (
                isinstance(field.field_type, MapType)
                and is_any_type(field.field_type.value_type)
                and field.field_type.value_ref
            ):
                self._error(
                    "ref is not allowed on map values of any type",
                    field.location,
                )

            if field.ref:
                if isinstance(field.field_type, (ListType, MapType)):
                    self._error(
                        "ref is not allowed on list/map fields; "
                        "use `list<ref T>` (or `repeated ref T`) for list elements or `map<..., ref T>` for map values",
                        field.location,
                    )
                else:
                    ensure_message_or_union(
                        field.field_type,
                        field,
                        enclosing_messages,
                        "ref",
                    )

            if field.element_ref:
                if not isinstance(field.field_type, ListType):
                    self._error(
                        "`list<ref T>` (or `repeated ref T`) is only valid for list fields",
                        field.location,
                    )
                else:
                    ensure_message_or_union(
                        field.field_type.element_type,
                        field,
                        enclosing_messages,
                        "ref",
                    )

            if isinstance(field.field_type, MapType) and field.field_type.value_ref:
                ensure_message_or_union(
                    field.field_type.value_type,
                    field,
                    enclosing_messages,
                    "ref",
                )

        def check_message_fields(
            message: Message,
            enclosing_messages: Optional[List[Message]] = None,
        ) -> None:
            lineage = (enclosing_messages or []) + [message]
            for f in message.fields:
                check_field(f, lineage)
            for nested_msg in message.nested_messages:
                check_message_fields(nested_msg, lineage)
            for nested_union in message.nested_unions:
                for f in nested_union.fields:
                    check_field(f, lineage)

        for message in self.schema.messages:
            check_message_fields(message)
        for union in self.schema.unions:
            for f in union.fields:
                check_field(f, None)

    def _check_collection_nesting(self) -> None:
        def check_field(
            field: Field, enclosing_messages: Optional[List[Message]] = None
        ):
            field_type = field.field_type
            if isinstance(field_type, ListType):
                if isinstance(field_type.element_type, (ListType, MapType)):
                    self._error(
                        "nested list/map types are not allowed; only one collection layer is supported",
                        field.location,
                    )
            elif isinstance(field_type, MapType):
                if isinstance(field_type.key_type, (ListType, MapType)) or isinstance(
                    field_type.value_type, (ListType, MapType)
                ):
                    self._error(
                        "nested list/map types are not allowed; only one collection layer is supported",
                        field.location,
                    )

        def check_message_fields(
            message: Message,
            enclosing_messages: Optional[List[Message]] = None,
        ) -> None:
            lineage = (enclosing_messages or []) + [message]
            for f in message.fields:
                check_field(f, lineage)
            for nested_msg in message.nested_messages:
                check_message_fields(nested_msg, lineage)
            for nested_union in message.nested_unions:
                for f in nested_union.fields:
                    check_field(f, lineage)

        for message in self.schema.messages:
            check_message_fields(message)
        for union in self.schema.unions:
            for f in union.fields:
                check_field(f, None)

    def _check_weak_refs(self) -> None:
        def check_field(
            field: Field,
            enclosing_messages: Optional[List[Message]] = None,
        ) -> None:
            if isinstance(field.field_type, ListType):
                weak_ref = field.element_ref_options.get("weak_ref")
            elif isinstance(field.field_type, MapType):
                weak_ref = field.field_type.value_ref_options.get("weak_ref")
            else:
                weak_ref = field.ref_options.get("weak_ref")

            if weak_ref is not True:
                if field.options.get("weak_ref") is True:
                    weak_ref = True
                else:
                    return

            if isinstance(field.field_type, ListType):
                if not field.element_ref:
                    self._error(
                        "weak_ref requires list element refs (use `list<ref T>` or `repeated ref T`)",
                        field.location,
                    )
                    return
                target_type = field.field_type.element_type
            elif isinstance(field.field_type, MapType):
                if not field.field_type.value_ref:
                    self._error(
                        "weak_ref requires ref tracking on map values (use `map<..., ref T>`)",
                        field.location,
                    )
                    return
                target_type = field.field_type.value_type
            else:
                if not field.ref:
                    self._error(
                        "weak_ref requires ref tracking (use `ref` modifier or `ref = true`)",
                        field.location,
                    )
                    return
                target_type = field.field_type

            if not isinstance(target_type, NamedType):
                self._error(
                    "weak_ref is only valid for named message types",
                    field.location,
                )
                return

            resolved = None
            if enclosing_messages is not None:
                resolved = self._resolve_named_type(
                    target_type.name, enclosing_messages
                )
            else:
                resolved = self._find_top_level_type(target_type.name)

            if isinstance(resolved, Enum):
                self._error(
                    "weak_ref is only valid for message/union types, not enums",
                    field.location,
                )

        def check_message_fields(
            message: Message,
            enclosing_messages: Optional[List[Message]] = None,
        ) -> None:
            lineage = (enclosing_messages or []) + [message]
            for f in message.fields:
                check_field(f, lineage)
            for nested_msg in message.nested_messages:
                check_message_fields(nested_msg, lineage)
            for nested_union in message.nested_unions:
                for f in nested_union.fields:
                    check_field(f, lineage)

        for message in self.schema.messages:
            check_message_fields(message)
        for union in self.schema.unions:
            for f in union.fields:
                check_field(f, None)


def validate_schema(schema: Schema) -> List[str]:
    """Validate a schema and return a list of error messages."""
    validator = SchemaValidator(schema)
    validator.validate()
    return [str(err) for err in validator.errors]
