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

from typing import List, Optional, Set

from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.parser.ast import (
    Schema,
    Message,
    Enum,
    Field,
    FieldType,
    PrimitiveType,
    PrimitiveKind,
    NamedType,
    ListType,
    MapType,
)


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

    # Mapping from FDL primitive types to Java types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "boolean",
        PrimitiveKind.INT8: "byte",
        PrimitiveKind.INT16: "short",
        PrimitiveKind.INT32: "int",
        PrimitiveKind.INT64: "long",
        PrimitiveKind.FLOAT32: "float",
        PrimitiveKind.FLOAT64: "double",
        PrimitiveKind.STRING: "String",
        PrimitiveKind.BYTES: "byte[]",
        PrimitiveKind.DATE: "java.time.LocalDate",
        PrimitiveKind.TIMESTAMP: "java.time.Instant",
    }

    # Boxed versions for nullable primitives
    BOXED_MAP = {
        PrimitiveKind.BOOL: "Boolean",
        PrimitiveKind.INT8: "Byte",
        PrimitiveKind.INT16: "Short",
        PrimitiveKind.INT32: "Integer",
        PrimitiveKind.INT64: "Long",
        PrimitiveKind.FLOAT32: "Float",
        PrimitiveKind.FLOAT64: "Double",
    }

    # Primitive array types for repeated numeric fields
    PRIMITIVE_ARRAY_MAP = {
        PrimitiveKind.BOOL: "boolean[]",
        PrimitiveKind.INT8: "byte[]",
        PrimitiveKind.INT16: "short[]",
        PrimitiveKind.INT32: "int[]",
        PrimitiveKind.INT64: "long[]",
        PrimitiveKind.FLOAT32: "float[]",
        PrimitiveKind.FLOAT64: "double[]",
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
                files.append(self.generate_enum_file(enum))

            # Generate message files (includes nested types as inner classes)
            for message in self.schema.messages:
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
        lines.append(f"public class {message.name} {{")

        # Generate nested enums as static inner classes
        for nested_enum in message.nested_enums:
            for line in self.generate_nested_enum(nested_enum):
                lines.append(f"    {line}")

        # Generate nested messages as static inner classes
        for nested_msg in message.nested_messages:
            for line in self.generate_nested_message(nested_msg, indent=1):
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
            for line in self.generate_nested_enum(enum):
                lines.append(f"    {line}")

        # Generate all top-level messages as static inner classes
        for message in self.schema.messages:
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
            self.collect_imports(field.field_type, imports)
            if field.optional or field.ref:
                imports.add("org.apache.fory.annotation.ForyField")

        # Add imports for equals/hashCode
        imports.add("java.util.Objects")
        if self.has_array_field_recursive(message):
            imports.add("java.util.Arrays")

        # Collect imports from nested messages
        for nested_msg in message.nested_messages:
            self.collect_message_imports(nested_msg, imports)

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

    def generate_nested_message(self, message: Message, indent: int = 1) -> List[str]:
        """Generate a nested message as a static inner class."""
        lines = []
        ind = "    " * indent

        # Class declaration
        lines.append(f"public static class {message.name} {{")

        # Generate nested enums
        for nested_enum in message.nested_enums:
            for line in self.generate_nested_enum(nested_enum):
                lines.append(f"    {line}")

        # Generate nested messages (recursively)
        for nested_msg in message.nested_messages:
            for line in self.generate_nested_message(nested_msg, indent=1):
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
        if field.optional:
            annotations.append("nullable = true")
        if field.ref:
            annotations.append("trackingRef = true")

        if annotations:
            lines.append(f"@ForyField({', '.join(annotations)})")

        # Field type
        java_type = self.generate_type(field.field_type, field.optional)

        lines.append(f"private {java_type} {self.to_camel_case(field.name)};")
        lines.append("")

        return lines

    def generate_getter_setter(self, field: Field) -> List[str]:
        """Generate getter and setter for a field."""
        lines = []
        java_type = self.generate_type(field.field_type, field.optional)
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

    def generate_type(self, field_type: FieldType, nullable: bool = False) -> str:
        """Generate Java type string."""
        if isinstance(field_type, PrimitiveType):
            if nullable and field_type.kind in self.BOXED_MAP:
                return self.BOXED_MAP[field_type.kind]
            return self.PRIMITIVE_MAP[field_type.kind]

        elif isinstance(field_type, NamedType):
            return field_type.name

        elif isinstance(field_type, ListType):
            # Use primitive arrays for numeric types
            if isinstance(field_type.element_type, PrimitiveType):
                if field_type.element_type.kind in self.PRIMITIVE_ARRAY_MAP:
                    return self.PRIMITIVE_ARRAY_MAP[field_type.element_type.kind]
            element_type = self.generate_type(field_type.element_type, True)
            return f"List<{element_type}>"

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(field_type.key_type, True)
            value_type = self.generate_type(field_type.value_type, True)
            return f"Map<{key_type}, {value_type}>"

        return "Object"

    def collect_imports(self, field_type: FieldType, imports: Set[str]):
        """Collect required imports for a field type."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.DATE:
                imports.add("java.time.LocalDate")
            elif field_type.kind == PrimitiveKind.TIMESTAMP:
                imports.add("java.time.Instant")

        elif isinstance(field_type, ListType):
            # Primitive arrays don't need List import
            if isinstance(field_type.element_type, PrimitiveType):
                if field_type.element_type.kind in self.PRIMITIVE_ARRAY_MAP:
                    return  # No import needed for primitive arrays
            imports.add("java.util.List")
            self.collect_imports(field_type.element_type, imports)

        elif isinstance(field_type, MapType):
            imports.add("java.util.Map")
            self.collect_imports(field_type.key_type, imports)
            self.collect_imports(field_type.value_type, imports)

    def has_array_field(self, message: Message) -> bool:
        """Check if message has any array fields (byte[] or primitive arrays)."""
        for field in message.fields:
            if isinstance(field.field_type, PrimitiveType):
                if field.field_type.kind == PrimitiveKind.BYTES:
                    return True
            elif isinstance(field.field_type, ListType):
                if isinstance(field.field_type.element_type, PrimitiveType):
                    if field.field_type.element_type.kind in self.PRIMITIVE_ARRAY_MAP:
                        return True
        return False

    def is_primitive_array_field(self, field: Field) -> bool:
        """Check if field is a primitive array type."""
        if isinstance(field.field_type, PrimitiveType):
            return field.field_type.kind == PrimitiveKind.BYTES
        if isinstance(field.field_type, ListType):
            if isinstance(field.field_type.element_type, PrimitiveType):
                return field.field_type.element_type.kind in self.PRIMITIVE_ARRAY_MAP
        return False

    def generate_equals_method(self, message: Message) -> List[str]:
        """Generate equals() method for a message."""
        lines = []
        lines.append("@Override")
        lines.append("public boolean equals(Object o) {")
        lines.append("    if (this == o) return true;")
        lines.append(f"    if (o == null || getClass() != o.getClass()) return false;")
        lines.append(f"    {message.name} that = ({message.name}) o;")

        if not message.fields:
            lines.append("    return true;")
        else:
            comparisons = []
            for field in message.fields:
                field_name = self.to_camel_case(field.name)
                if self.is_primitive_array_field(field):
                    comparisons.append(f"Arrays.equals({field_name}, that.{field_name})")
                elif isinstance(field.field_type, PrimitiveType):
                    kind = field.field_type.kind
                    if kind in (PrimitiveKind.FLOAT32,):
                        comparisons.append(f"Float.compare({field_name}, that.{field_name}) == 0")
                    elif kind in (PrimitiveKind.FLOAT64,):
                        comparisons.append(f"Double.compare({field_name}, that.{field_name}) == 0")
                    elif kind in (PrimitiveKind.BOOL, PrimitiveKind.INT8, PrimitiveKind.INT16,
                                  PrimitiveKind.INT32, PrimitiveKind.INT64) and not field.optional:
                        comparisons.append(f"{field_name} == that.{field_name}")
                    else:
                        comparisons.append(f"Objects.equals({field_name}, that.{field_name})")
                else:
                    comparisons.append(f"Objects.equals({field_name}, that.{field_name})")

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
                    lines.append(f"    int result = Arrays.hashCode({array_fields[0]});")
                    for arr in array_fields[1:]:
                        lines.append(f"    result = 31 * result + Arrays.hashCode({arr});")
                    lines.append("    return result;")
            else:
                lines.append(f"    return Objects.hash({', '.join(hash_args)});")

        lines.append("}")
        lines.append("")
        return lines

    def generate_registration_file(self, outer_classname: Optional[str] = None) -> GeneratedFile:
        """Generate the Fory registration helper class.

        Args:
            outer_classname: If set, all type references will be prefixed with this outer class.
        """
        lines = []
        java_package = self.get_java_package()

        # Determine class name
        if java_package:
            parts = java_package.split(".")
            class_name = self.to_pascal_case(parts[-1]) + "ForyRegistration"
        else:
            class_name = "ForyRegistration"

        # License header
        lines.append(self.get_license_header())
        lines.append("")

        # Package
        if java_package:
            lines.append(f"package {java_package};")
            lines.append("")

        # Imports
        lines.append("import org.apache.fory.Fory;")
        lines.append("")

        # Class
        lines.append(f"public class {class_name} {{")
        lines.append("")
        lines.append("    public static void register(Fory fory) {")

        # When outer_classname is set, all top-level types become inner classes
        type_prefix = outer_classname if outer_classname else ""

        # Register enums (top-level)
        for enum in self.schema.enums:
            self.generate_enum_registration(lines, enum, type_prefix)

        # Register messages (top-level and nested)
        for message in self.schema.messages:
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

    def generate_enum_registration(self, lines: List[str], enum: Enum, parent_path: str):
        """Generate registration code for an enum."""
        # In Java, nested class references use OuterClass.InnerClass
        class_ref = f"{parent_path}.{enum.name}" if parent_path else enum.name
        type_name = class_ref.replace(".", "_") if parent_path else enum.name

        if enum.type_id is not None:
            lines.append(f"        fory.register({class_ref}.class, {enum.type_id});")
        else:
            # Use FDL package for namespace (consistent across languages)
            ns = self.schema.package or "default"
            lines.append(f'        fory.register({class_ref}.class, "{ns}", "{type_name}");')

    def generate_message_registration(self, lines: List[str], message: Message, parent_path: str):
        """Generate registration code for a message and its nested types."""
        # In Java, nested class references use OuterClass.InnerClass
        class_ref = f"{parent_path}.{message.name}" if parent_path else message.name
        type_name = class_ref.replace(".", "_") if parent_path else message.name

        if message.type_id is not None:
            lines.append(f"        fory.register({class_ref}.class, {message.type_id});")
        else:
            # Use FDL package for namespace (consistent across languages)
            ns = self.schema.package or "default"
            lines.append(f'        fory.register({class_ref}.class, "{ns}", "{type_name}");')

        # Register nested enums
        for nested_enum in message.nested_enums:
            self.generate_enum_registration(lines, nested_enum, class_ref)

        # Register nested messages
        for nested_msg in message.nested_messages:
            self.generate_message_registration(lines, nested_msg, class_ref)
