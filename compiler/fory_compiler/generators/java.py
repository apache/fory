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

"""Java code generator."""

from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple, Union as TypingUnion

from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.frontend.utils import parse_idl_file
from fory_compiler.ir.ast import (
    Message,
    Enum,
    Union,
    Field,
    FieldType,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
    Schema,
)
from fory_compiler.ir.types import PrimitiveKind


class JavaGenerator(BaseGenerator):
    """Generates Java POJOs with Fory annotations."""

    language_name = "java"
    file_extension = ".java"

    def get_java_package(self) -> Optional[str]:
        """Get the Java package name.

        Priority:
        1. Command-line override (options.package_override)
        2. java_package option from FDL file
        3. FDL package declaration
        """
        if self.options.package_override:
            return self.options.package_override
        java_package = self.schema.get_option("java_package")
        if java_package:
            return java_package
        return self.schema.package

    def get_registration_class_name(self) -> str:
        """Get the generated registration class name."""
        java_package = self.get_java_package()
        if java_package:
            parts = java_package.split(".")
            return self.to_pascal_case(parts[-1]) + "ForyRegistration"
        return "ForyRegistration"

    def get_java_outer_classname(self) -> Optional[str]:
        """Get the Java outer classname if specified.

        When set, all types are generated as inner classes of this outer class
        in a single file (unless java_multiple_files is true).
        """
        return self.schema.get_option("java_outer_classname")

    def get_java_multiple_files(self) -> bool:
        """Check if java_multiple_files option is set to true.

        When true, each top-level type gets its own file, even if
        java_outer_classname is set.
        """
        value = self.schema.get_option("java_multiple_files")
        return value is True

    def is_imported_type(self, type_def: object) -> bool:
        """Return True if a type definition comes from an imported IDL file."""
        if not self.schema.source_file:
            return False
        location = getattr(type_def, "location", None)
        if location is None or not location.file:
            return False
        try:
            return (
                Path(location.file).resolve() != Path(self.schema.source_file).resolve()
            )
        except Exception:
            return location.file != self.schema.source_file

    def split_imported_types(
        self, items: List[object]
    ) -> Tuple[List[object], List[object]]:
        imported: List[object] = []
        local: List[object] = []
        for item in items:
            if self.is_imported_type(item):
                imported.append(item)
            else:
                local.append(item)
        return imported, local

    def _normalize_import_path(self, path_str: str) -> str:
        if not path_str:
            return path_str
        try:
            return str(Path(path_str).resolve())
        except Exception:
            return path_str

    def _load_schema(self, file_path: str) -> Optional[Schema]:
        if not file_path:
            return None
        if not hasattr(self, "_schema_cache"):
            self._schema_cache = {}
        cache: Dict[Path, Schema] = self._schema_cache
        path = Path(file_path).resolve()
        if path in cache:
            return cache[path]
        try:
            schema = parse_idl_file(path)
        except Exception:
            return None
        cache[path] = schema
        return schema

    def _java_package_for_schema(self, schema: Schema) -> Optional[str]:
        java_package = schema.get_option("java_package")
        if java_package:
            return java_package
        return schema.package

    def _registration_class_name_for_schema(self, schema: Schema) -> str:
        java_package = self._java_package_for_schema(schema)
        if java_package:
            parts = java_package.split(".")
            return self.to_pascal_case(parts[-1]) + "ForyRegistration"
        return "ForyRegistration"

    def _java_package_for_type(self, type_def: object) -> Optional[str]:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return None
        return self._java_package_for_schema(schema)

    def _collect_imported_packages(self) -> List[Tuple[str, str]]:
        packages: Dict[str, str] = {}
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue
            java_package = self._java_package_for_type(type_def)
            if not java_package:
                continue
            if java_package in packages:
                continue
            schema = self._load_schema(
                getattr(getattr(type_def, "location", None), "file", None)
            )
            if schema is None:
                continue
            packages[java_package] = self._registration_class_name_for_schema(schema)

        ordered: List[Tuple[str, str]] = []
        used: Set[str] = set()
        if self.schema.source_file:
            base_dir = Path(self.schema.source_file).resolve().parent
            for imp in self.schema.imports:
                candidate = self._normalize_import_path(
                    str((base_dir / imp.path).resolve())
                )
                schema = self._load_schema(candidate)
                if schema is None:
                    continue
                java_package = self._java_package_for_schema(schema)
                if not java_package or java_package in used:
                    continue
                reg_class = self._registration_class_name_for_schema(schema)
                ordered.append((java_package, reg_class))
                used.add(java_package)
        for pkg, reg in sorted(packages.items()):
            if pkg in used:
                continue
            ordered.append((pkg, reg))
        return ordered

    def generate_bytes_methods(self, class_name: str) -> List[str]:
        reg_class = self.get_registration_class_name()
        lines = []
        lines.append("public byte[] toBytes() {")
        lines.append(f"    return {reg_class}.getFory().serialize(this);")
        lines.append("}")
        lines.append("")
        lines.append(f"public static {class_name} fromBytes(byte[] bytes) {{")
        lines.append(
            f"    return {reg_class}.getFory().deserialize(bytes, {class_name}.class);"
        )
        lines.append("}")
        lines.append("")
        return lines

    # Mapping from FDL primitive types to Java types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "boolean",
        PrimitiveKind.INT8: "byte",
        PrimitiveKind.INT16: "short",
        PrimitiveKind.INT32: "int",
        PrimitiveKind.VARINT32: "int",
        PrimitiveKind.INT64: "long",
        PrimitiveKind.VARINT64: "long",
        PrimitiveKind.TAGGED_INT64: "long",
        PrimitiveKind.UINT8: "byte",
        PrimitiveKind.UINT16: "short",
        PrimitiveKind.UINT32: "int",
        PrimitiveKind.VAR_UINT32: "int",
        PrimitiveKind.UINT64: "long",
        PrimitiveKind.VAR_UINT64: "long",
        PrimitiveKind.TAGGED_UINT64: "long",
        PrimitiveKind.FLOAT16: "Float16",
        PrimitiveKind.FLOAT32: "float",
        PrimitiveKind.FLOAT64: "double",
        PrimitiveKind.STRING: "String",
        PrimitiveKind.BYTES: "byte[]",
        PrimitiveKind.DATE: "java.time.LocalDate",
        PrimitiveKind.TIMESTAMP: "java.time.Instant",
        PrimitiveKind.DURATION: "java.time.Duration",
        PrimitiveKind.DECIMAL: "java.math.BigDecimal",
        PrimitiveKind.ANY: "Object",
    }

    # Boxed versions for nullable primitives
    BOXED_MAP = {
        PrimitiveKind.BOOL: "Boolean",
        PrimitiveKind.INT8: "Byte",
        PrimitiveKind.INT16: "Short",
        PrimitiveKind.INT32: "Integer",
        PrimitiveKind.VARINT32: "Integer",
        PrimitiveKind.INT64: "Long",
        PrimitiveKind.VARINT64: "Long",
        PrimitiveKind.TAGGED_INT64: "Long",
        PrimitiveKind.UINT8: "Byte",
        PrimitiveKind.UINT16: "Short",
        PrimitiveKind.UINT32: "Integer",
        PrimitiveKind.VAR_UINT32: "Integer",
        PrimitiveKind.UINT64: "Long",
        PrimitiveKind.VAR_UINT64: "Long",
        PrimitiveKind.TAGGED_UINT64: "Long",
        PrimitiveKind.FLOAT16: "Float16",
        PrimitiveKind.FLOAT32: "Float",
        PrimitiveKind.FLOAT64: "Double",
        PrimitiveKind.ANY: "Object",
    }

    # Primitive array types for repeated numeric fields
    PRIMITIVE_ARRAY_MAP = {
        PrimitiveKind.BOOL: "boolean[]",
        PrimitiveKind.INT8: "byte[]",
        PrimitiveKind.INT16: "short[]",
        PrimitiveKind.INT32: "int[]",
        PrimitiveKind.VARINT32: "int[]",
        PrimitiveKind.INT64: "long[]",
        PrimitiveKind.VARINT64: "long[]",
        PrimitiveKind.TAGGED_INT64: "long[]",
        PrimitiveKind.UINT8: "byte[]",
        PrimitiveKind.UINT16: "short[]",
        PrimitiveKind.UINT32: "int[]",
        PrimitiveKind.VAR_UINT32: "int[]",
        PrimitiveKind.UINT64: "long[]",
        PrimitiveKind.VAR_UINT64: "long[]",
        PrimitiveKind.TAGGED_UINT64: "long[]",
        PrimitiveKind.FLOAT16: "Float16[]",
        PrimitiveKind.FLOAT32: "float[]",
        PrimitiveKind.FLOAT64: "double[]",
    }

    # Primitive list types for repeated integer fields (default mode)
    PRIMITIVE_LIST_MAP = {
        PrimitiveKind.INT8: "Int8List",
        PrimitiveKind.INT16: "Int16List",
        PrimitiveKind.INT32: "Int32List",
        PrimitiveKind.VARINT32: "Int32List",
        PrimitiveKind.INT64: "Int64List",
        PrimitiveKind.VARINT64: "Int64List",
        PrimitiveKind.TAGGED_INT64: "Int64List",
        PrimitiveKind.UINT8: "Uint8List",
        PrimitiveKind.UINT16: "Uint16List",
        PrimitiveKind.UINT32: "Uint32List",
        PrimitiveKind.VAR_UINT32: "Uint32List",
        PrimitiveKind.UINT64: "Uint64List",
        PrimitiveKind.VAR_UINT64: "Uint64List",
        PrimitiveKind.TAGGED_UINT64: "Uint64List",
    }

    def generate(self) -> List[GeneratedFile]:
        """Generate Java files for the schema.

        Generation mode depends on options:
        - java_multiple_files = true: Separate file per type (default behavior)
        - java_outer_classname set + java_multiple_files = false: Single file with outer class
        - Neither set: Separate file per type
        """
        files = []

        outer_classname = self.get_java_outer_classname()
        multiple_files = self.get_java_multiple_files()

        if outer_classname and not multiple_files:
            # Generate all types in a single outer class file
            files.append(self.generate_outer_class_file(outer_classname))
            # Generate registration helper (with outer class prefix)
            files.append(self.generate_registration_file(outer_classname))
        else:
            # Generate separate files for each type
            # Generate enum files (top-level only, nested enums go inside message files)
            for enum in self.schema.enums:
                if self.is_imported_type(enum):
                    continue
                files.append(self.generate_enum_file(enum))

            # Generate union files (top-level only, nested unions go inside message files)
            for union in self.schema.unions:
                if self.is_imported_type(union):
                    continue
                files.append(self.generate_union_file(union))

            # Generate message files (includes nested types as inner classes)
            for message in self.schema.messages:
                if self.is_imported_type(message):
                    continue
                files.append(self.generate_message_file(message))

            # Generate registration helper
            files.append(self.generate_registration_file())

        return files

    def get_java_package_path(self) -> str:
        """Get the Java package as a path."""
        java_package = self.get_java_package()
        if java_package:
            return java_package.replace(".", "/")
        return ""

    def generate_enum_file(self, enum: Enum) -> GeneratedFile:
        """Generate a Java enum file."""
        lines = []
        java_package = self.get_java_package()

        # License header
        lines.append(self.get_license_header())
        lines.append("")

        # Package
        if java_package:
            lines.append(f"package {java_package};")
            lines.append("")

        # Enum declaration
        lines.append(f"public enum {enum.name} {{")

        # Enum values (strip prefix for scoped enums)
        for i, value in enumerate(enum.values):
            comma = "," if i < len(enum.values) - 1 else ";"
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            lines.append(f"    {stripped_name}{comma}")

        lines.append("}")
        lines.append("")

        # Build file path
        path = self.get_java_package_path()
        if path:
            path = f"{path}/{enum.name}.java"
        else:
            path = f"{enum.name}.java"

        return GeneratedFile(path=path, content="\n".join(lines))

    def generate_union_file(self, union: Union) -> GeneratedFile:
        """Generate a Java union class file."""
        lines = []
        imports: Set[str] = set()
        java_package = self.get_java_package()

        self.collect_union_imports(union, imports)

        lines.append(self.get_license_header())
        lines.append("")

        if java_package:
            lines.append(f"package {java_package};")
            lines.append("")

        if imports:
            for imp in sorted(imports):
                lines.append(f"import {imp};")
            lines.append("")

        for line in self.generate_union_class(union):
            lines.append(line)

        path = self.get_java_package_path()
        if path:
            path = f"{path}/{union.name}.java"
        else:
            path = f"{union.name}.java"

        return GeneratedFile(path=path, content="\n".join(lines))

    def generate_message_file(self, message: Message) -> GeneratedFile:
        """Generate a Java class file for a message."""
        lines = []
        imports: Set[str] = set()
        java_package = self.get_java_package()

        # Collect imports (including from nested types)
        self.collect_message_imports(message, imports)

        # License header
        lines.append(self.get_license_header())
        lines.append("")

        # Package
        if java_package:
            lines.append(f"package {java_package};")
            lines.append("")

        # Imports
        if imports:
            for imp in sorted(imports):
                lines.append(f"import {imp};")
            lines.append("")

        # Class declaration
        comment = self.format_type_id_comment(message, "//")
        if comment:
            lines.append(comment)
        lines.append(f"public class {message.name} {{")

        # Generate nested enums as static inner classes
        for nested_enum in message.nested_enums:
            for line in self.generate_nested_enum(nested_enum):
                lines.append(f"    {line}")

        # Generate nested unions as static inner classes
        for nested_union in message.nested_unions:
            for line in self.generate_union_class(
                nested_union, indent=0, nested=True, parent_stack=[message]
            ):
                lines.append(f"    {line}")

        # Generate nested messages as static inner classes
        for nested_msg in message.nested_messages:
            for line in self.generate_nested_message(
                nested_msg, indent=1, parent_stack=[message]
            ):
                lines.append(f"    {line}")

        # Fields
        for field in message.fields:
            field_lines = self.generate_field(field)
            for line in field_lines:
                lines.append(f"    {line}")

        lines.append("")

        # Default constructor
        lines.append(f"    public {message.name}() {{")
        lines.append("    }")
        lines.append("")

        # Getters and setters
        for field in message.fields:
            getter_setter = self.generate_getter_setter(field)
            for line in getter_setter:
                lines.append(f"    {line}")

        # toBytes/fromBytes
        for line in self.generate_bytes_methods(message.name):
            lines.append(f"    {line}")

        # equals method
        for line in self.generate_equals_method(message):
            lines.append(f"    {line}")

        # hashCode method
        for line in self.generate_hashcode_method(message):
            lines.append(f"    {line}")

        lines.append("}")
        lines.append("")

        # Build file path
        path = self.get_java_package_path()
        if path:
            path = f"{path}/{message.name}.java"
        else:
            path = f"{message.name}.java"

        return GeneratedFile(path=path, content="\n".join(lines))

    def generate_outer_class_file(self, outer_classname: str) -> GeneratedFile:
        """Generate a single Java file with all types as inner classes of an outer class.

        This is used when java_outer_classname option is set.
        """
        lines = []
        imports: Set[str] = set()
        java_package = self.get_java_package()

        # Collect imports from all types
        for message in self.schema.messages:
            self.collect_message_imports(message, imports)
        for enum in self.schema.enums:
            pass  # Enums don't need special imports
        for union in self.schema.unions:
            self.collect_union_imports(union, imports)

        # License header
        lines.append(self.get_license_header())
        lines.append("")

        # Package
        if java_package:
            lines.append(f"package {java_package};")
            lines.append("")

        # Imports
        if imports:
            for imp in sorted(imports):
                lines.append(f"import {imp};")
            lines.append("")

        # Outer class declaration
        lines.append(f"public final class {outer_classname} {{")
        lines.append("")
        lines.append(f"    private {outer_classname}() {{")
        lines.append("        // Prevent instantiation")
        lines.append("    }")
        lines.append("")

        # Generate all top-level enums as static inner classes
        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            for line in self.generate_nested_enum(enum):
                lines.append(f"    {line}")

        # Generate all top-level unions as static inner classes
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            for line in self.generate_union_class(union, indent=0, nested=True):
                lines.append(f"    {line}")

        # Generate all top-level messages as static inner classes
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            for line in self.generate_nested_message(message, indent=1):
                lines.append(f"    {line}")

        lines.append("}")
        lines.append("")

        # Build file path
        path = self.get_java_package_path()
        if path:
            path = f"{path}/{outer_classname}.java"
        else:
            path = f"{outer_classname}.java"

        return GeneratedFile(path=path, content="\n".join(lines))

    def collect_message_imports(self, message: Message, imports: Set[str]):
        """Collect imports for a message and all its nested types recursively."""
        for field in message.fields:
            self.collect_field_imports(field, imports)

        # Add imports for equals/hashCode
        imports.add("java.util.Objects")
        if self.has_array_field_recursive(message):
            imports.add("java.util.Arrays")

        # Collect imports from nested messages
        for nested_msg in message.nested_messages:
            self.collect_message_imports(nested_msg, imports)
        for nested_union in message.nested_unions:
            self.collect_union_imports(nested_union, imports)

    def collect_union_imports(self, union: Union, imports: Set[str]):
        """Collect imports for a union and its cases."""
        imports.add("org.apache.fory.type.union.Union")
        imports.add("org.apache.fory.type.Types")
        imports.add("java.util.Objects")
        for field in union.fields:
            self.collect_type_imports(
                field.field_type,
                imports,
                field.element_optional,
                field.element_ref,
                field,
            )

    def has_array_field_recursive(self, message: Message) -> bool:
        """Check if message or any nested message has array fields."""
        if self.has_array_field(message):
            return True
        for nested_msg in message.nested_messages:
            if self.has_array_field_recursive(nested_msg):
                return True
        return False

    def generate_nested_enum(self, enum: Enum) -> List[str]:
        """Generate a nested enum as a static inner class."""
        lines = []
        lines.append(f"public static enum {enum.name} {{")

        # Enum values (strip prefix for scoped enums)
        for i, value in enumerate(enum.values):
            comma = "," if i < len(enum.values) - 1 else ";"
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            lines.append(f"    {stripped_name}{comma}")

        lines.append("}")
        lines.append("")
        return lines

    def generate_union_class(
        self,
        union: Union,
        indent: int = 0,
        nested: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Java union class."""
        lines: List[str] = []
        ind = "    " * indent
        class_prefix = "public static final class" if nested else "public final class"
        case_enum = f"{union.name}Case"

        comment = self.format_type_id_comment(union, f"{ind}//")
        if comment:
            lines.append(comment)
        lines.append(f"{ind}{class_prefix} {union.name} extends Union {{")
        lines.append(f"{ind}    public enum {case_enum} {{")

        for i, field in enumerate(union.fields):
            comma = "," if i < len(union.fields) - 1 else ";"
            case_name = self.to_upper_snake_case(field.name)
            lines.append(f"{ind}        {case_name}({field.number}){comma}")

        lines.append(f"{ind}        public final int id;")
        lines.append(f"{ind}        {case_enum}(int id) {{")
        lines.append(f"{ind}            this.id = id;")
        lines.append(f"{ind}        }}")
        lines.append(f"{ind}    }}")
        lines.append("")

        lines.append(f"{ind}    private static int resolveTypeId(int caseId) {{")
        lines.append(f"{ind}        switch (caseId) {{")
        for field in union.fields:
            type_id_expr = self.get_union_case_type_id_expr(field, parent_stack)
            lines.append(f"{ind}            case {field.number}:")
            lines.append(f"{ind}                return {type_id_expr};")
        lines.append(f"{ind}            default:")
        lines.append(
            f'{ind}                throw new IllegalStateException("Unknown {union.name} case id: " + caseId);'
        )
        lines.append(f"{ind}        }}")
        lines.append(f"{ind}    }}")
        lines.append("")

        lines.append(f"{ind}    private {union.name}(int caseId, Object v) {{")
        lines.append(f"{ind}        super(caseId, v, resolveTypeId(caseId));")
        lines.append(f"{ind}        if (v == null) {{")
        lines.append(f"{ind}            throw new NullPointerException();")
        lines.append(f"{ind}        }}")
        lines.append(f"{ind}        get{union.name}Case();")
        lines.append(f"{ind}    }}")
        lines.append("")

        for field in union.fields:
            case_name = self.to_pascal_case(field.name)
            case_enum_name = self.to_upper_snake_case(field.name)
            case_type = self.get_union_case_type(field)
            lines.append(
                f"{ind}    public static {union.name} of{case_name}({case_type} v) {{"
            )
            lines.append(
                f"{ind}        return new {union.name}({case_enum}.{case_enum_name}.id, v);"
            )
            lines.append(f"{ind}    }}")
            lines.append("")

        lines.append(f"{ind}    public {case_enum} get{union.name}Case() {{")
        lines.append(f"{ind}        switch (index) {{")
        for field in union.fields:
            case_enum_name = self.to_upper_snake_case(field.name)
            lines.append(f"{ind}            case {field.number}:")
            lines.append(f"{ind}                return {case_enum}.{case_enum_name};")
        lines.append(f"{ind}            default:")
        lines.append(
            f'{ind}                throw new IllegalStateException("Unknown {union.name} case id: " + index);'
        )
        lines.append(f"{ind}        }}")
        lines.append(f"{ind}    }}")
        lines.append("")
        lines.append(f"{ind}    public int get{union.name}CaseId() {{")
        lines.append(f"{ind}        return index;")
        lines.append(f"{ind}    }}")
        lines.append("")

        for field in union.fields:
            case_name = self.to_pascal_case(field.name)
            case_enum_name = self.to_upper_snake_case(field.name)
            case_type = self.get_union_case_type(field)
            cast_type = self.get_union_case_cast_type(field)
            wrap_array_type: Optional[str] = None
            wrap_list_type: Optional[str] = None
            if (
                isinstance(field.field_type, ListType)
                and isinstance(field.field_type.element_type, PrimitiveType)
                and field.field_type.element_type.kind in self.PRIMITIVE_LIST_MAP
                and not self.java_array(field)
            ):
                kind = field.field_type.element_type.kind
                wrap_list_type = self.PRIMITIVE_LIST_MAP[kind]
                wrap_array_type = self.PRIMITIVE_ARRAY_MAP[kind]

            lines.append(f"{ind}    public boolean has{case_name}() {{")
            lines.append(
                f"{ind}        return index == {case_enum}.{case_enum_name}.id;"
            )
            lines.append(f"{ind}    }}")
            lines.append("")
            lines.append(f"{ind}    public {case_type} get{case_name}() {{")
            lines.append(
                f"{ind}        if (index != {case_enum}.{case_enum_name}.id) {{"
            )
            lines.append(
                f'{ind}            throw new IllegalStateException("{union.name} is not {case_enum_name}");'
            )
            lines.append(f"{ind}        }}")
            if wrap_array_type and wrap_list_type:
                lines.append(f"{ind}        if (value instanceof {wrap_array_type}) {{")
                lines.append(
                    f"{ind}            value = new {wrap_list_type}(({wrap_array_type}) value);"
                )
                lines.append(f"{ind}        }}")
            lines.append(f"{ind}        return ({cast_type}) value;")
            lines.append(f"{ind}    }}")
            lines.append("")
            lines.append(f"{ind}    public void set{case_name}({case_type} v) {{")
            if not self.is_java_primitive_type(case_type):
                lines.append(f"{ind}        if (v == null) {{")
                lines.append(f"{ind}            throw new NullPointerException();")
                lines.append(f"{ind}        }}")
            lines.append(f"{ind}        this.index = {case_enum}.{case_enum_name}.id;")
            lines.append(f"{ind}        this.value = v;")
            type_id_expr = self.get_union_case_type_id_expr(field, parent_stack)
            lines.append(f"{ind}        this.typeId = {type_id_expr};")
            lines.append(f"{ind}    }}")
            lines.append("")

        lines.append(f"{ind}    @Override")
        lines.append(f"{ind}    public boolean equals(Object o) {{")
        lines.append(f"{ind}        if (this == o) {{")
        lines.append(f"{ind}            return true;")
        lines.append(f"{ind}        }}")
        lines.append(f"{ind}        if (!(o instanceof {union.name})) {{")
        lines.append(f"{ind}            return false;")
        lines.append(f"{ind}        }}")
        lines.append(f"{ind}        {union.name} that = ({union.name}) o;")
        lines.append(
            f"{ind}        return index == that.index && Objects.equals(value, that.value);"
        )
        lines.append(f"{ind}    }}")
        lines.append("")
        lines.append(f"{ind}    @Override")
        lines.append(f"{ind}    public int hashCode() {{")
        lines.append(f"{ind}        return Objects.hash(index, value);")
        lines.append(f"{ind}    }}")
        lines.append("")

        for line in self.generate_bytes_methods(union.name):
            lines.append(f"{ind}    {line}")

        lines.append(f"{ind}}}")
        lines.append("")
        return lines

    def get_union_case_type(self, field: Field) -> str:
        """Return the Java type for a union case."""
        return self.generate_type(
            field.field_type,
            False,
            field.element_optional,
            field.element_ref,
            field,
        )

    def get_union_case_cast_type(self, field: Field) -> str:
        """Return the Java cast type for a union case value."""
        if isinstance(field.field_type, PrimitiveType):
            boxed = self.BOXED_MAP.get(field.field_type.kind)
            if boxed is not None:
                return boxed
            return self.PRIMITIVE_MAP[field.field_type.kind]
        return self.get_union_case_type(field)

    def get_union_case_type_id_expr(
        self, field: Field, parent_stack: Optional[List[Message]]
    ) -> str:
        """Return the Java expression for a union case value type id."""
        if isinstance(field.field_type, PrimitiveType):
            kind = field.field_type.kind
            primitive_type_ids = {
                PrimitiveKind.BOOL: "Types.BOOL",
                PrimitiveKind.INT8: "Types.INT8",
                PrimitiveKind.INT16: "Types.INT16",
                PrimitiveKind.INT32: "Types.INT32",
                PrimitiveKind.VARINT32: "Types.VARINT32",
                PrimitiveKind.INT64: "Types.INT64",
                PrimitiveKind.VARINT64: "Types.VARINT64",
                PrimitiveKind.TAGGED_INT64: "Types.TAGGED_INT64",
                PrimitiveKind.UINT8: "Types.UINT8",
                PrimitiveKind.UINT16: "Types.UINT16",
                PrimitiveKind.UINT32: "Types.UINT32",
                PrimitiveKind.VAR_UINT32: "Types.VAR_UINT32",
                PrimitiveKind.UINT64: "Types.UINT64",
                PrimitiveKind.VAR_UINT64: "Types.VAR_UINT64",
                PrimitiveKind.TAGGED_UINT64: "Types.TAGGED_UINT64",
                PrimitiveKind.FLOAT16: "Types.FLOAT16",
                PrimitiveKind.FLOAT32: "Types.FLOAT32",
                PrimitiveKind.FLOAT64: "Types.FLOAT64",
                PrimitiveKind.STRING: "Types.STRING",
                PrimitiveKind.BYTES: "Types.BINARY",
                PrimitiveKind.DATE: "Types.DATE",
                PrimitiveKind.TIMESTAMP: "Types.TIMESTAMP",
                PrimitiveKind.ANY: "Types.UNKNOWN",
            }
            return primitive_type_ids.get(kind, "Types.UNKNOWN")
        if isinstance(field.field_type, ListType):
            if (
                isinstance(field.field_type.element_type, PrimitiveType)
                and not field.element_optional
                and not field.element_ref
            ):
                kind = field.field_type.element_type.kind
                array_type_ids = {
                    PrimitiveKind.BOOL: "Types.BOOL_ARRAY",
                    PrimitiveKind.INT8: "Types.INT8_ARRAY",
                    PrimitiveKind.INT16: "Types.INT16_ARRAY",
                    PrimitiveKind.INT32: "Types.INT32_ARRAY",
                    PrimitiveKind.VARINT32: "Types.INT32_ARRAY",
                    PrimitiveKind.INT64: "Types.INT64_ARRAY",
                    PrimitiveKind.VARINT64: "Types.INT64_ARRAY",
                    PrimitiveKind.TAGGED_INT64: "Types.INT64_ARRAY",
                    PrimitiveKind.UINT8: "Types.UINT8_ARRAY",
                    PrimitiveKind.UINT16: "Types.UINT16_ARRAY",
                    PrimitiveKind.UINT32: "Types.UINT32_ARRAY",
                    PrimitiveKind.VAR_UINT32: "Types.UINT32_ARRAY",
                    PrimitiveKind.UINT64: "Types.UINT64_ARRAY",
                    PrimitiveKind.VAR_UINT64: "Types.UINT64_ARRAY",
                    PrimitiveKind.TAGGED_UINT64: "Types.UINT64_ARRAY",
                    PrimitiveKind.FLOAT16: "Types.FLOAT16_ARRAY",
                    PrimitiveKind.FLOAT32: "Types.FLOAT32_ARRAY",
                    PrimitiveKind.FLOAT64: "Types.FLOAT64_ARRAY",
                }
                if kind in array_type_ids:
                    return array_type_ids[kind]
            return "Types.LIST"
        if isinstance(field.field_type, MapType):
            return "Types.MAP"
        if isinstance(field.field_type, NamedType):
            type_def = self.resolve_named_type(field.field_type.name, parent_stack)
            if isinstance(type_def, Enum):
                if type_def.type_id is None:
                    return "Types.NAMED_ENUM"
                return "Types.ENUM"
            if isinstance(type_def, Union):
                if type_def.type_id is None:
                    return "Types.NAMED_UNION"
                return "Types.UNION"
            if isinstance(type_def, Message):
                if type_def.type_id is None:
                    return "Types.NAMED_STRUCT"
                return "Types.STRUCT"
        return "Types.UNKNOWN"

    def resolve_named_type(
        self, name: str, parent_stack: Optional[List[Message]]
    ) -> Optional[TypingUnion[Message, Enum, Union]]:
        """Resolve a named type to a schema definition."""
        parts = name.split(".")
        if len(parts) > 1:
            current = self.find_top_level_type(parts[0])
            for part in parts[1:]:
                if isinstance(current, Message):
                    current = current.get_nested_type(part)
                else:
                    return None
            return current
        if parent_stack:
            for msg in reversed(parent_stack):
                nested = msg.get_nested_type(name)
                if nested is not None:
                    return nested
        return self.find_top_level_type(name)

    def find_top_level_type(
        self, name: str
    ) -> Optional[TypingUnion[Message, Enum, Union]]:
        """Find a top-level type definition by name."""
        for msg in self.schema.messages:
            if msg.name == name:
                return msg
        for enum in self.schema.enums:
            if enum.name == name:
                return enum
        for union in self.schema.unions:
            if union.name == name:
                return union
        return None

    def is_java_primitive_type(self, type_name: str) -> bool:
        """Return True if the Java type name is a primitive type."""
        return type_name in {
            "boolean",
            "byte",
            "short",
            "int",
            "long",
            "float",
            "double",
            "char",
        }

    def generate_nested_message(
        self,
        message: Message,
        indent: int = 1,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a nested message as a static inner class."""
        lines = []
        lineage = (parent_stack or []) + [message]

        # Class declaration
        comment = self.format_type_id_comment(message, "    " * indent + "//")
        if comment:
            lines.append(comment)
        lines.append(f"public static class {message.name} {{")

        # Generate nested enums
        for nested_enum in message.nested_enums:
            for line in self.generate_nested_enum(nested_enum):
                lines.append(f"    {line}")

        # Generate nested unions
        for nested_union in message.nested_unions:
            for line in self.generate_union_class(
                nested_union,
                indent=0,
                nested=True,
                parent_stack=lineage,
            ):
                lines.append(f"    {line}")

        # Generate nested messages (recursively)
        for nested_msg in message.nested_messages:
            for line in self.generate_nested_message(
                nested_msg,
                indent=1,
                parent_stack=lineage,
            ):
                lines.append(f"    {line}")

        # Fields
        for field in message.fields:
            field_lines = self.generate_field(field)
            for line in field_lines:
                lines.append(f"    {line}")

        lines.append("")

        # Default constructor
        lines.append(f"    public {message.name}() {{")
        lines.append("    }")
        lines.append("")

        # Getters and setters
        for field in message.fields:
            getter_setter = self.generate_getter_setter(field)
            for line in getter_setter:
                lines.append(f"    {line}")

        # toBytes/fromBytes
        for line in self.generate_bytes_methods(message.name):
            lines.append(f"    {line}")

        # equals method
        for line in self.generate_equals_method(message):
            lines.append(f"    {line}")

        # hashCode method
        for line in self.generate_hashcode_method(message):
            lines.append(f"    {line}")

        lines.append("}")
        lines.append("")
        return lines

    def generate_field(self, field: Field) -> List[str]:
        """Generate field declaration with annotations."""
        lines = []

        # Generate @ForyField annotation if needed
        annotations = []
        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        nullable = field.optional or is_any
        if field.tag_id is not None:
            annotations.append(f"id = {field.tag_id}")
        if nullable:
            annotations.append("nullable = true")
        if field.ref:
            annotations.append("ref = true")

        if annotations:
            lines.append(f"@ForyField({', '.join(annotations)})")

        array_annotation = self.get_array_annotation(field)
        if array_annotation:
            lines.append(array_annotation)

        int_annotation = self.get_integer_annotation(field.field_type)
        if int_annotation:
            lines.append(int_annotation)

        # Field type
        java_type = self.generate_type(
            field.field_type,
            nullable,
            field.element_optional,
            field.element_ref,
            field,
        )

        lines.append(f"private {java_type} {self.to_camel_case(field.name)};")
        lines.append("")

        return lines

    def generate_getter_setter(self, field: Field) -> List[str]:
        """Generate getter and setter for a field."""
        lines = []
        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        nullable = field.optional or is_any
        java_type = self.generate_type(
            field.field_type,
            nullable,
            field.element_optional,
            field.element_ref,
            field,
        )
        field_name = self.to_camel_case(field.name)
        pascal_name = self.to_pascal_case(field.name)

        # Getter
        lines.append(f"public {java_type} get{pascal_name}() {{")
        lines.append(f"    return {field_name};")
        lines.append("}")
        lines.append("")

        # Setter
        lines.append(f"public void set{pascal_name}({java_type} {field_name}) {{")
        lines.append(f"    this.{field_name} = {field_name};")
        lines.append("}")
        lines.append("")

        return lines

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        element_optional: bool = False,
        element_ref: bool = False,
        field: Optional[Field] = None,
    ) -> str:
        """Generate Java type string."""
        if isinstance(field_type, PrimitiveType):
            if nullable and field_type.kind in self.BOXED_MAP:
                return self.BOXED_MAP[field_type.kind]
            return self.PRIMITIVE_MAP[field_type.kind]

        elif isinstance(field_type, NamedType):
            named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                java_package = self._java_package_for_type(named_type)
                if java_package:
                    return f"{java_package}.{field_type.name}"
            return field_type.name

        elif isinstance(field_type, ListType):
            # Use primitive arrays for numeric types, or primitive lists by default for integers
            if isinstance(field_type.element_type, PrimitiveType):
                if (
                    field_type.element_type.kind in self.PRIMITIVE_ARRAY_MAP
                    and not element_optional
                    and not element_ref
                ):
                    if (
                        field_type.element_type.kind in self.PRIMITIVE_LIST_MAP
                        and not self.java_array(field)
                    ):
                        return self.PRIMITIVE_LIST_MAP[field_type.element_type.kind]
                    return self.PRIMITIVE_ARRAY_MAP[field_type.element_type.kind]
            element_type = self.generate_type(field_type.element_type, True)
            if self.is_ref_target_type(field_type.element_type):
                ref_annotation = "@Ref" if element_ref else "@Ref(enable=false)"
                element_type = f"{ref_annotation} {element_type}"
            return f"List<{element_type}>"

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(field_type.key_type, True)
            value_type = self.generate_type(field_type.value_type, True)
            if self.is_ref_target_type(field_type.value_type):
                ref_annotation = (
                    "@Ref" if field_type.value_ref else "@Ref(enable=false)"
                )
                value_type = f"{ref_annotation} {value_type}"
            return f"Map<{key_type}, {value_type}>"

        return "Object"

    def collect_type_imports(
        self,
        field_type: FieldType,
        imports: Set[str],
        element_optional: bool = False,
        element_ref: bool = False,
        field: Optional[Field] = None,
    ):
        """Collect required imports for a field type."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.DATE:
                imports.add("java.time.LocalDate")
            elif field_type.kind == PrimitiveKind.TIMESTAMP:
                imports.add("java.time.Instant")
            elif field_type.kind == PrimitiveKind.FLOAT16:
                imports.add("org.apache.fory.type.Float16")

        elif isinstance(field_type, ListType):
            # Primitive arrays don't need List import
            if isinstance(field_type.element_type, PrimitiveType):
                if (
                    field_type.element_type.kind in self.PRIMITIVE_ARRAY_MAP
                    and not element_optional
                    and not element_ref
                ):
                    if (
                        field_type.element_type.kind in self.PRIMITIVE_LIST_MAP
                        and not self.java_array(field)
                    ):
                        imports.add(
                            "org.apache.fory.collection."
                            + self.PRIMITIVE_LIST_MAP[field_type.element_type.kind]
                        )
                    return  # No import needed for primitive arrays or primitive lists
            imports.add("java.util.List")
            if self.is_ref_target_type(field_type.element_type):
                imports.add("org.apache.fory.annotation.Ref")
            self.collect_type_imports(field_type.element_type, imports)

        elif isinstance(field_type, MapType):
            imports.add("java.util.Map")
            if self.is_ref_target_type(field_type.value_type):
                imports.add("org.apache.fory.annotation.Ref")
            self.collect_type_imports(field_type.key_type, imports)
            self.collect_type_imports(field_type.value_type, imports)

    def collect_field_imports(self, field: Field, imports: Set[str]):
        """Collect imports for a field, including list modifiers."""
        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        self.collect_type_imports(
            field.field_type,
            imports,
            field.element_optional,
            field.element_ref,
            field,
        )
        self.collect_integer_imports(field.field_type, imports)
        self.collect_array_imports(field, imports)
        if field.optional or field.ref or field.tag_id is not None or is_any:
            imports.add("org.apache.fory.annotation.ForyField")

    def is_ref_target_type(self, field_type: FieldType) -> bool:
        if not isinstance(field_type, NamedType):
            return False
        resolved = self.schema.get_type(field_type.name)
        return isinstance(resolved, (Message, Union))

    def java_array(self, field: Optional[Field]) -> bool:
        if field is None:
            return False
        return bool(field.options.get("java_array"))

    def collect_array_imports(self, field: Field, imports: Set[str]) -> None:
        """Collect imports for primitive array type annotations."""
        if not isinstance(field.field_type, ListType):
            return
        if not self.java_array(field):
            return
        if field.element_optional or field.element_ref:
            return
        element_type = field.field_type.element_type
        if not isinstance(element_type, PrimitiveType):
            return
        kind = element_type.kind
        if kind == PrimitiveKind.INT8:
            imports.add("org.apache.fory.annotation.Int8ArrayType")
        elif kind == PrimitiveKind.UINT8:
            imports.add("org.apache.fory.annotation.Uint8ArrayType")
        elif kind == PrimitiveKind.UINT16:
            imports.add("org.apache.fory.annotation.Uint16ArrayType")
        elif kind in (PrimitiveKind.UINT32, PrimitiveKind.VAR_UINT32):
            imports.add("org.apache.fory.annotation.Uint32ArrayType")
        elif kind in (
            PrimitiveKind.UINT64,
            PrimitiveKind.VAR_UINT64,
            PrimitiveKind.TAGGED_UINT64,
        ):
            imports.add("org.apache.fory.annotation.Uint64ArrayType")

    def collect_integer_imports(self, field_type: FieldType, imports: Set[str]) -> None:
        """Collect imports for integer encoding annotations."""
        if not isinstance(field_type, PrimitiveType):
            return
        kind = field_type.kind
        if kind in (PrimitiveKind.INT32,):
            imports.add("org.apache.fory.annotation.Int32Type")
        if kind in (PrimitiveKind.INT64, PrimitiveKind.TAGGED_INT64):
            imports.add("org.apache.fory.annotation.Int64Type")
            imports.add("org.apache.fory.config.LongEncoding")
        if kind in (PrimitiveKind.UINT8,):
            imports.add("org.apache.fory.annotation.Uint8Type")
        if kind in (PrimitiveKind.UINT16,):
            imports.add("org.apache.fory.annotation.Uint16Type")
        if kind in (PrimitiveKind.UINT32, PrimitiveKind.VAR_UINT32):
            imports.add("org.apache.fory.annotation.Uint32Type")
        if kind in (
            PrimitiveKind.UINT64,
            PrimitiveKind.VAR_UINT64,
            PrimitiveKind.TAGGED_UINT64,
        ):
            imports.add("org.apache.fory.annotation.Uint64Type")
            imports.add("org.apache.fory.config.LongEncoding")

    def get_integer_annotation(self, field_type: FieldType) -> Optional[str]:
        """Return integer encoding annotation for a field type."""
        if not isinstance(field_type, PrimitiveType):
            return None
        kind = field_type.kind
        if kind == PrimitiveKind.INT32:
            return "@Int32Type(compress = false)"
        if kind == PrimitiveKind.INT64:
            return "@Int64Type(encoding = LongEncoding.FIXED)"
        if kind == PrimitiveKind.TAGGED_INT64:
            return "@Int64Type(encoding = LongEncoding.TAGGED)"
        if kind == PrimitiveKind.UINT8:
            return "@Uint8Type"
        if kind == PrimitiveKind.UINT16:
            return "@Uint16Type"
        if kind == PrimitiveKind.UINT32:
            return "@Uint32Type(compress = false)"
        if kind == PrimitiveKind.VAR_UINT32:
            return "@Uint32Type(compress = true)"
        if kind == PrimitiveKind.UINT64:
            return "@Uint64Type(encoding = LongEncoding.FIXED)"
        if kind == PrimitiveKind.VAR_UINT64:
            return "@Uint64Type(encoding = LongEncoding.VARINT)"
        if kind == PrimitiveKind.TAGGED_UINT64:
            return "@Uint64Type(encoding = LongEncoding.TAGGED)"
        return None

    def get_array_annotation(self, field: Field) -> Optional[str]:
        """Return array type annotation for primitive list fields."""
        if not isinstance(field.field_type, ListType):
            return None
        if not self.java_array(field):
            return None
        if field.element_optional or field.element_ref:
            return None
        element_type = field.field_type.element_type
        if not isinstance(element_type, PrimitiveType):
            return None
        kind = element_type.kind
        if kind == PrimitiveKind.INT8:
            return "@Int8ArrayType"
        if kind == PrimitiveKind.UINT8:
            return "@Uint8ArrayType"
        if kind == PrimitiveKind.UINT16:
            return "@Uint16ArrayType"
        if kind in (PrimitiveKind.UINT32, PrimitiveKind.VAR_UINT32):
            return "@Uint32ArrayType"
        if kind in (
            PrimitiveKind.UINT64,
            PrimitiveKind.VAR_UINT64,
            PrimitiveKind.TAGGED_UINT64,
        ):
            return "@Uint64ArrayType"
        return None

    def has_array_field(self, message: Message) -> bool:
        """Check if message has any array fields (byte[] or primitive arrays)."""
        for field in message.fields:
            if isinstance(field.field_type, PrimitiveType):
                if field.field_type.kind == PrimitiveKind.BYTES:
                    return True
            elif self.is_primitive_array_field(field):
                return True
        return False

    def is_primitive_array_field(self, field: Field) -> bool:
        """Check if field is a primitive array type."""
        if isinstance(field.field_type, PrimitiveType):
            return field.field_type.kind == PrimitiveKind.BYTES
        if isinstance(field.field_type, ListType):
            if isinstance(field.field_type.element_type, PrimitiveType):
                if (
                    field.field_type.element_type.kind in self.PRIMITIVE_LIST_MAP
                    and not self.java_array(field)
                ):
                    return False
                return (
                    field.field_type.element_type.kind in self.PRIMITIVE_ARRAY_MAP
                    and not field.element_optional
                    and not field.element_ref
                )
        return False

    def generate_equals_method(self, message: Message) -> List[str]:
        """Generate equals() method for a message."""
        lines = []
        lines.append("@Override")
        lines.append("public boolean equals(Object o) {")
        lines.append("    if (this == o) return true;")
        lines.append("    if (o == null || getClass() != o.getClass()) return false;")
        lines.append(f"    {message.name} that = ({message.name}) o;")

        if not message.fields:
            lines.append("    return true;")
        else:
            comparisons = []
            for field in message.fields:
                field_name = self.to_camel_case(field.name)
                if self.is_primitive_array_field(field):
                    comparisons.append(
                        f"Arrays.equals({field_name}, that.{field_name})"
                    )
                elif isinstance(field.field_type, PrimitiveType):
                    kind = field.field_type.kind
                    if kind in (PrimitiveKind.FLOAT16,):
                        comparisons.append(
                            f"({field_name} == null ? that.{field_name} == null : (that.{field_name} != null && {field_name}.equalsValue(that.{field_name})))"
                        )
                    elif kind in (PrimitiveKind.FLOAT32,):
                        comparisons.append(
                            f"Float.compare({field_name}, that.{field_name}) == 0"
                        )
                    elif kind in (PrimitiveKind.FLOAT64,):
                        comparisons.append(
                            f"Double.compare({field_name}, that.{field_name}) == 0"
                        )
                    elif (
                        kind
                        in (
                            PrimitiveKind.BOOL,
                            PrimitiveKind.INT8,
                            PrimitiveKind.INT16,
                            PrimitiveKind.INT32,
                            PrimitiveKind.INT64,
                        )
                        and not field.optional
                    ):
                        comparisons.append(f"{field_name} == that.{field_name}")
                    else:
                        comparisons.append(
                            f"Objects.equals({field_name}, that.{field_name})"
                        )
                else:
                    comparisons.append(
                        f"Objects.equals({field_name}, that.{field_name})"
                    )

            if len(comparisons) == 1:
                lines.append(f"    return {comparisons[0]};")
            else:
                lines.append(f"    return {comparisons[0]}")
                for i, comp in enumerate(comparisons[1:], 1):
                    if i == len(comparisons) - 1:
                        lines.append(f"        && {comp};")
                    else:
                        lines.append(f"        && {comp}")

        lines.append("}")
        lines.append("")
        return lines

    def generate_hashcode_method(self, message: Message) -> List[str]:
        """Generate hashCode() method for a message."""
        lines = []
        lines.append("@Override")
        lines.append("public int hashCode() {")

        if not message.fields:
            lines.append("    return 0;")
        else:
            hash_args = []
            array_fields = []
            for field in message.fields:
                field_name = self.to_camel_case(field.name)
                if self.is_primitive_array_field(field):
                    array_fields.append(field_name)
                elif (
                    isinstance(field.field_type, PrimitiveType)
                    and field.field_type.kind == PrimitiveKind.FLOAT16
                ):
                    hash_args.append(
                        f"({field_name} == null ? 0 : ((({field_name}.toBits() & 0x7FFF) == 0) ? 0 : Short.hashCode({field_name}.toBits())))"
                    )
                else:
                    hash_args.append(field_name)

            if array_fields and hash_args:
                lines.append(f"    int result = Objects.hash({', '.join(hash_args)});")
                for arr in array_fields:
                    lines.append(f"    result = 31 * result + Arrays.hashCode({arr});")
                lines.append("    return result;")
            elif array_fields:
                if len(array_fields) == 1:
                    lines.append(f"    return Arrays.hashCode({array_fields[0]});")
                else:
                    lines.append(
                        f"    int result = Arrays.hashCode({array_fields[0]});"
                    )
                    for arr in array_fields[1:]:
                        lines.append(
                            f"    result = 31 * result + Arrays.hashCode({arr});"
                        )
                    lines.append("    return result;")
            else:
                lines.append(f"    return Objects.hash({', '.join(hash_args)});")

        lines.append("}")
        lines.append("")
        return lines

    def generate_registration_file(
        self, outer_classname: Optional[str] = None
    ) -> GeneratedFile:
        """Generate the Fory registration helper class.

        Args:
            outer_classname: If set, all type references will be prefixed with this outer class.
        """
        lines = []
        java_package = self.get_java_package()

        # Determine class name
        class_name = self.get_registration_class_name()

        # License header
        lines.append(self.get_license_header())
        lines.append("")

        # Package
        if java_package:
            lines.append(f"package {java_package};")
            lines.append("")

        # Imports
        lines.append("import org.apache.fory.Fory;")
        lines.append("import org.apache.fory.ThreadSafeFory;")
        lines.append("import org.apache.fory.pool.SimpleForyPool;")
        lines.append("")

        # Class
        lines.append(f"public class {class_name} {{")
        lines.append("")
        lines.append("    static ThreadSafeFory getFory() {")
        lines.append("        return Holder.FORY;")
        lines.append("    }")
        lines.append("")
        lines.append("    private static ThreadSafeFory createFory() {")
        lines.append(
            "        ThreadSafeFory fory = new SimpleForyPool(c -> Fory.builder().withXlang(true).withRefTracking(true).build());"
        )
        imported_packages = self._collect_imported_packages()
        if imported_packages:
            lines.append("        fory.registerCallback(f -> {")
            for java_package, reg_class in imported_packages:
                lines.append(f"            {java_package}.{reg_class}.register(f);")
            lines.append("            register(f);")
            lines.append("        });")
        else:
            lines.append("        fory.registerCallback(f -> register(f));")
        lines.append("        return fory;")
        lines.append("    }")
        lines.append("")
        lines.append("    private static class Holder {")
        lines.append("        private static final ThreadSafeFory FORY = createFory();")
        lines.append("    }")
        lines.append("")
        # When outer_classname is set, all top-level types become inner classes
        type_prefix = outer_classname if outer_classname else ""

        local_enums = [e for e in self.schema.enums if not self.is_imported_type(e)]
        local_unions = [u for u in self.schema.unions if not self.is_imported_type(u)]
        local_messages = [
            m for m in self.schema.messages if not self.is_imported_type(m)
        ]
        lines.append("    public static void register(Fory fory) {")
        lines.append(
            "        org.apache.fory.resolver.TypeResolver resolver = fory.getTypeResolver();"
        )

        # Register enums (top-level)
        for enum in local_enums:
            self.generate_enum_registration(lines, enum, type_prefix)

        # Register unions (top-level)
        for union in local_unions:
            self.generate_union_registration(lines, union, type_prefix)

        # Register messages (top-level and nested)
        for message in local_messages:
            self.generate_message_registration(lines, message, type_prefix)

        lines.append("    }")
        lines.append("}")
        lines.append("")

        # Build file path
        path = self.get_java_package_path()
        if path:
            path = f"{path}/{class_name}.java"
        else:
            path = f"{class_name}.java"

        return GeneratedFile(path=path, content="\n".join(lines))

    def generate_enum_registration(
        self, lines: List[str], enum: Enum, parent_path: str
    ):
        """Generate registration code for an enum."""
        # In Java, nested class references use OuterClass.InnerClass
        class_ref = f"{parent_path}.{enum.name}" if parent_path else enum.name
        type_name = class_ref if parent_path else enum.name

        if self.should_register_by_id(enum):
            lines.append(
                f"        resolver.register({class_ref}.class, {enum.type_id}L);"
            )
        else:
            # Use FDL package for namespace (consistent across languages)
            ns = self.schema.package or "default"
            lines.append(
                f'        resolver.register({class_ref}.class, "{ns}", "{type_name}");'
            )

    def generate_message_registration(
        self, lines: List[str], message: Message, parent_path: str
    ):
        """Generate registration code for a message and its nested types."""
        # In Java, nested class references use OuterClass.InnerClass
        class_ref = f"{parent_path}.{message.name}" if parent_path else message.name
        type_name = class_ref if parent_path else message.name

        if self.should_register_by_id(message):
            lines.append(
                f"        resolver.register({class_ref}.class, {message.type_id}L);"
            )
        else:
            # Use FDL package for namespace (consistent across languages)
            ns = self.schema.package or "default"
            lines.append(
                f'        resolver.register({class_ref}.class, "{ns}", "{type_name}");'
            )

        # Register nested enums
        for nested_enum in message.nested_enums:
            self.generate_enum_registration(lines, nested_enum, class_ref)

        # Register nested unions
        for nested_union in message.nested_unions:
            self.generate_union_registration(lines, nested_union, class_ref)

        # Register nested messages
        for nested_msg in message.nested_messages:
            self.generate_message_registration(lines, nested_msg, class_ref)

    def generate_union_registration(
        self, lines: List[str], union: Union, parent_path: str
    ):
        """Generate registration code for a union."""
        class_ref = f"{parent_path}.{union.name}" if parent_path else union.name
        type_name = union.name
        serializer_ref = (
            f"new org.apache.fory.serializer.UnionSerializer(fory, {class_ref}.class)"
        )

        if self.should_register_by_id(union):
            lines.append(
                f"        resolver.registerUnion({class_ref}.class, {union.type_id}L, {serializer_ref});"
            )
        else:
            ns = self.schema.package or "default"
            if parent_path:
                ns = f"{ns}.{parent_path}"
            lines.append(
                f'        resolver.registerUnion({class_ref}.class, "{ns}", "{type_name}", {serializer_ref});'
            )
