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

"""Recursive descent parser for FDL."""

import warnings
from typing import List, Optional, Set

from fory_compiler.parser.lexer import Lexer, Token, TokenType

# Known file-level options (standard protobuf options)
KNOWN_FILE_OPTIONS: Set[str] = {
    "java_package",
    "java_outer_classname",
    "java_multiple_files",
    "go_package",
    "deprecated",
}

# Known Fory file-level options (extension options)
KNOWN_FORY_FILE_OPTIONS: Set[str] = {
    "use_record_for_java_message",
    "polymorphism",
}

# Known field-level options (standard protobuf options)
KNOWN_FIELD_OPTIONS: Set[str] = {
    "deprecated",
    "json_name",
    "packed",
    "lazy",
    "unverified_lazy",
    "weak",
    "debug_redact",
    "retention",
    "targets",
    "edition_defaults",
    "features",
}

# Known Fory field-level options (extension options)
KNOWN_FORY_FIELD_OPTIONS: Set[str] = {
    "ref",
    "nullable",
    "deprecated",
}

# Known type-level options for inline syntax: [id=100, deprecated=true]
KNOWN_TYPE_OPTIONS: Set[str] = {
    "id",
    "deprecated",
}

# Known Fory message-level options (option statements inside message body)
KNOWN_FORY_MESSAGE_OPTIONS: Set[str] = {
    "id",
    "compatible",
    "use_record_for_java",
    "deprecated",
    "namespace",
}

# Known Fory enum-level options (option statements inside enum body)
KNOWN_FORY_ENUM_OPTIONS: Set[str] = {
    "id",
    "deprecated",
}
from fory_compiler.parser.ast import (
    Schema,
    Message,
    Enum,
    Field,
    EnumValue,
    Import,
    FieldType,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
    PRIMITIVE_TYPES,
)


class ParseError(Exception):
    """Error during parsing."""

    def __init__(self, message: str, line: int, column: int):
        self.message = message
        self.line = line
        self.column = column
        super().__init__(f"Line {line}, Column {column}: {message}")


class Parser:
    """Recursive descent parser for FDL."""

    def __init__(self, tokens: List[Token]):
        self.tokens = tokens
        self.pos = 0

    @classmethod
    def from_source(cls, source: str, filename: str = "<input>") -> "Parser":
        """Create a parser from source code."""
        lexer = Lexer(source, filename)
        tokens = lexer.tokenize()
        return cls(tokens)

    def at_end(self) -> bool:
        """Check if we've reached the end of tokens."""
        return self.current().type == TokenType.EOF

    def current(self) -> Token:
        """Get the current token."""
        if self.pos >= len(self.tokens):
            return self.tokens[-1]  # Return EOF
        return self.tokens[self.pos]

    def previous(self) -> Token:
        """Get the previous token."""
        return self.tokens[self.pos - 1]

    def peek(self, offset: int = 0) -> Token:
        """Peek at a token without consuming it."""
        pos = self.pos + offset
        if pos >= len(self.tokens):
            return self.tokens[-1]  # Return EOF
        return self.tokens[pos]

    def check(self, token_type: TokenType) -> bool:
        """Check if the current token has the given type."""
        return self.current().type == token_type

    def match(self, *types: TokenType) -> bool:
        """If current token matches any of the types, consume and return True."""
        for token_type in types:
            if self.check(token_type):
                self.advance()
                return True
        return False

    def advance(self) -> Token:
        """Consume and return the current token."""
        token = self.current()
        if not self.at_end():
            self.pos += 1
        return token

    def consume(self, token_type: TokenType, message: str = None) -> Token:
        """Consume a token of the expected type, or raise an error."""
        if self.check(token_type):
            return self.advance()
        token = self.current()
        if message is None:
            message = f"Expected {token_type.name}, got {token.type.name}"
        raise ParseError(message, token.line, token.column)

    def error(self, message: str) -> ParseError:
        """Create a parse error at the current position."""
        token = self.current()
        return ParseError(message, token.line, token.column)

    def parse(self) -> Schema:
        """Parse the entire input and return a Schema."""
        package = None
        imports = []
        enums = []
        messages = []
        options = {}

        while not self.at_end():
            if self.check(TokenType.PACKAGE):
                if package is not None:
                    raise self.error("Duplicate package declaration")
                package = self.parse_package()
            elif self.check(TokenType.IMPORT):
                imports.append(self.parse_import())
            elif self.check(TokenType.OPTION):
                # File-level option
                name, value = self.parse_file_option()
                options[name] = value
            elif self.check(TokenType.ENUM):
                enums.append(self.parse_enum())
            elif self.check(TokenType.MESSAGE):
                messages.append(self.parse_message())
            else:
                raise self.error(f"Unexpected token: {self.current().value}")

        return Schema(package, imports, enums, messages, options)

    def parse_package(self) -> str:
        """Parse a package declaration: package foo.bar;"""
        self.consume(TokenType.PACKAGE)

        # Package name can be dotted: foo.bar.baz
        parts = [self.consume(TokenType.IDENT).value]
        while self.check(TokenType.DOT):
            self.advance()  # consume the dot
            parts.append(self.consume(TokenType.IDENT, "Expected identifier after '.'").value)

        self.consume(TokenType.SEMI, "Expected ';' after package name")
        return ".".join(parts)

    def parse_option_value(self):
        """Parse an option value (string, bool, int, or identifier)."""
        if self.check(TokenType.STRING):
            return self.advance().value
        elif self.check(TokenType.TRUE):
            self.advance()
            return True
        elif self.check(TokenType.FALSE):
            self.advance()
            return False
        elif self.check(TokenType.INT):
            return int(self.advance().value)
        elif self.check(TokenType.IDENT):
            return self.advance().value
        else:
            raise self.error(f"Expected option value, got {self.current().type.name}")

    def parse_file_option(self) -> tuple:
        """Parse a file-level option.

        Supports two syntaxes:
        1. Standard: option java_package = "com.example";
        2. Extension: option (fory).use_record_for_java_message = true;

        Returns a tuple of (option_name, option_value).
        For extension options, the name is prefixed with the extension name: "fory.use_record_for_java_message"
        """
        option_token = self.consume(TokenType.OPTION)

        # Check for extension syntax: (extension_name).option_name
        extension_name = None
        if self.check(TokenType.LPAREN):
            self.advance()  # consume (
            extension_name = self.consume(TokenType.IDENT, "Expected extension name").value
            self.consume(TokenType.RPAREN, "Expected ')' after extension name")
            self.consume(TokenType.DOT, "Expected '.' after extension name")

        name_token = self.consume(TokenType.IDENT, "Expected option name")
        option_name = name_token.value

        # Build full option name for extension options
        if extension_name:
            full_option_name = f"{extension_name}.{option_name}"
        else:
            full_option_name = option_name

        self.consume(TokenType.EQUALS, "Expected '=' after option name")

        option_value = self.parse_option_value()

        self.consume(TokenType.SEMI, "Expected ';' after option statement")

        # Warn about unknown options
        if extension_name:
            if extension_name == "fory":
                if option_name not in KNOWN_FORY_FILE_OPTIONS:
                    warnings.warn(
                        f"Line {name_token.line}: ignoring unknown fory option '{option_name}'",
                        stacklevel=2
                    )
            else:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown extension '{extension_name}'",
                    stacklevel=2
                )
        else:
            if option_name not in KNOWN_FILE_OPTIONS:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown option '{option_name}'",
                    stacklevel=2
                )

        return (full_option_name, option_value)

    def parse_import(self) -> Import:
        """Parse an import statement: import "path/to/file.fdl";"""
        start = self.current()
        self.consume(TokenType.IMPORT)

        # Check for forbidden import modifiers (protobuf syntax)
        if self.check(TokenType.PUBLIC):
            raise ParseError(
                "'import public' is not supported in FDL.\n"
                "  Reason: FDL uses a simpler import model where all imported types\n"
                "  are available to the importing file. Re-exporting imports is not\n"
                "  supported. Simply use 'import \"path/to/file.fdl\";' instead.\n"
                "  If you need types from multiple files, import each file directly.",
                start.line,
                start.column,
            )

        if self.check(TokenType.WEAK):
            raise ParseError(
                "'import weak' is not supported in FDL.\n"
                "  Reason: Weak imports are a protobuf-specific feature for optional\n"
                "  dependencies. FDL requires all imports to be present at compile time.\n"
                "  Use 'import \"path/to/file.fdl\";' instead.",
                start.line,
                start.column,
            )

        path_token = self.consume(TokenType.STRING, "Expected import path string")

        self.consume(TokenType.SEMI, "Expected ';' after import statement")

        return Import(
            path=path_token.value,
            line=start.line,
            column=start.column,
        )

    def parse_enum(self) -> Enum:
        """Parse an enum: enum Color [id=101] { ... }

        Supports:
        - Inline type options: enum Color [id=101] { ... }
        - Body option statements: option (fory).id = 100;
        """
        start = self.current()
        self.consume(TokenType.ENUM)
        name = self.consume(TokenType.IDENT, "Expected enum name").value

        # Optional inline type options: [id=101, deprecated=true]
        type_id = None
        inline_options = {}
        if self.check(TokenType.LBRACKET):
            inline_options = self.parse_type_options(name)
            if "id" in inline_options:
                type_id = inline_options["id"]

        self.consume(TokenType.LBRACE, "Expected '{' after enum name")

        values = []
        body_options = {}
        while not self.check(TokenType.RBRACE):
            # Check for option statements
            if self.check(TokenType.OPTION):
                opt_name, opt_value = self.parse_enum_option(name)
                body_options[opt_name] = opt_value
                # Handle fory.id option to set type_id
                if opt_name == "fory.id" and type_id is None:
                    if isinstance(opt_value, int) and opt_value > 0:
                        type_id = opt_value
            # Check for reserved statements
            elif self.check(TokenType.RESERVED):
                self.parse_reserved()
            else:
                values.append(self.parse_enum_value())

        self.consume(TokenType.RBRACE, "Expected '}' after enum values")

        # Merge inline options and body options (body options take precedence)
        all_options = {**inline_options, **body_options}

        return Enum(
            name=name,
            type_id=type_id,
            values=values,
            options=all_options,
            line=start.line,
            column=start.column,
        )

    def parse_enum_option(self, enum_name: str) -> tuple:
        """Parse and validate an enum option statement.

        Supports two syntaxes:
        1. Standard: option deprecated = true;
        2. Extension: option (fory).id = 100;

        Forbidden options:
        - allow_alias = true: Enum aliases are not supported

        Returns a tuple of (option_name, option_value).
        """
        option_token = self.consume(TokenType.OPTION)

        # Check for extension syntax: (extension_name).option_name
        extension_name = None
        if self.check(TokenType.LPAREN):
            self.advance()  # consume (
            extension_name = self.consume(TokenType.IDENT, "Expected extension name").value
            self.consume(TokenType.RPAREN, "Expected ')' after extension name")
            self.consume(TokenType.DOT, "Expected '.' after extension name")

        name_token = self.consume(TokenType.IDENT, "Expected option name")
        option_name = name_token.value

        # Build full option name for extension options
        if extension_name:
            full_option_name = f"{extension_name}.{option_name}"
        else:
            full_option_name = option_name

        self.consume(TokenType.EQUALS, "Expected '=' after option name")

        option_value = self.parse_option_value()

        self.consume(TokenType.SEMI, "Expected ';' after option statement")

        # Validate forbidden options
        if option_name == "allow_alias" and option_value is True:
            raise ParseError(
                f"'option allow_alias = true' is forbidden in enum '{enum_name}'. "
                "Enum aliases (multiple names for the same value) are not supported.",
                option_token.line,
                option_token.column,
            )

        # Warn about unknown options
        if extension_name:
            if extension_name == "fory":
                if option_name not in KNOWN_FORY_ENUM_OPTIONS:
                    warnings.warn(
                        f"Line {name_token.line}: ignoring unknown fory enum option '{option_name}' in '{enum_name}'",
                        stacklevel=2
                    )
            else:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown extension '{extension_name}'",
                    stacklevel=2
                )
        else:
            # Standard options - currently we only recognize deprecated and allow_alias
            if option_name not in {"deprecated", "allow_alias"}:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown enum option '{option_name}' in '{enum_name}'",
                    stacklevel=2
                )

        return (full_option_name, option_value)

    def parse_message_option(self, message_name: str) -> tuple:
        """Parse a message-level option statement.

        Supports two syntaxes:
        1. Standard: option deprecated = true;
        2. Extension: option (fory).id = 100;

        Returns a tuple of (option_name, option_value).
        For extension options, the name is prefixed with the extension name.
        """
        option_token = self.consume(TokenType.OPTION)

        # Check for extension syntax: (extension_name).option_name
        extension_name = None
        if self.check(TokenType.LPAREN):
            self.advance()  # consume (
            extension_name = self.consume(TokenType.IDENT, "Expected extension name").value
            self.consume(TokenType.RPAREN, "Expected ')' after extension name")
            self.consume(TokenType.DOT, "Expected '.' after extension name")

        name_token = self.consume(TokenType.IDENT, "Expected option name")
        option_name = name_token.value

        # Build full option name for extension options
        if extension_name:
            full_option_name = f"{extension_name}.{option_name}"
        else:
            full_option_name = option_name

        self.consume(TokenType.EQUALS, "Expected '=' after option name")

        option_value = self.parse_option_value()

        self.consume(TokenType.SEMI, "Expected ';' after option statement")

        # Warn about unknown options
        if extension_name:
            if extension_name == "fory":
                if option_name not in KNOWN_FORY_MESSAGE_OPTIONS:
                    warnings.warn(
                        f"Line {name_token.line}: ignoring unknown fory message option '{option_name}' in '{message_name}'",
                        stacklevel=2
                    )
            else:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown extension '{extension_name}'",
                    stacklevel=2
                )
        else:
            # Standard options - currently we only recognize deprecated
            if option_name not in {"deprecated"}:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown message option '{option_name}' in '{message_name}'",
                    stacklevel=2
                )

        return (full_option_name, option_value)

    def parse_reserved(self):
        """Parse a reserved statement.

        Supports:
        - reserved 2, 15, 9 to 11, 40 to max;  (numbers and ranges)
        - reserved "FOO", "BAR";  (field/value names)
        """
        self.consume(TokenType.RESERVED)

        # Parse comma-separated list of reserved items
        while True:
            if self.check(TokenType.STRING):
                # Reserved name: "FOO"
                self.advance()
            elif self.check(TokenType.INT):
                # Reserved number or range start: 2 or 9 to 11
                self.advance()
                # Check for range: N to M or N to max
                if self.check(TokenType.TO):
                    self.advance()
                    if self.check(TokenType.MAX):
                        self.advance()
                    elif self.check(TokenType.INT):
                        self.advance()
                    else:
                        raise self.error("Expected integer or 'max' after 'to'")
            else:
                raise self.error(
                    f"Expected reserved number or string, got {self.current().type.name}"
                )

            # Check for comma (more items) or semicolon (end)
            if self.check(TokenType.COMMA):
                self.advance()
            elif self.check(TokenType.SEMI):
                break
            else:
                raise self.error("Expected ',' or ';' in reserved statement")

        self.consume(TokenType.SEMI, "Expected ';' after reserved statement")

    def parse_enum_value(self) -> EnumValue:
        """Parse an enum value: NAME = 0;"""
        start = self.current()
        name = self.consume(TokenType.IDENT, "Expected enum value name").value
        self.consume(TokenType.EQUALS, "Expected '=' after enum value name")
        value_token = self.consume(TokenType.INT, "Expected integer value")
        value = int(value_token.value)
        self.consume(TokenType.SEMI, "Expected ';' after enum value")

        return EnumValue(
            name=name,
            value=value,
            line=start.line,
            column=start.column,
        )

    def parse_message(self) -> Message:
        """Parse a message: message Dog [id=102] { ... }

        Supports:
        - Inline type options: message Dog [id=102] { ... }
        - Body option statements: option (fory).id = 100;
        - Nested messages and enums:
            message Outer {
                message Inner { ... }
                enum Status { ... }
                Inner inner = 1;
            }
        """
        start = self.current()
        self.consume(TokenType.MESSAGE)
        name = self.consume(TokenType.IDENT, "Expected message name").value

        # Optional inline type options: [id=102, deprecated=true]
        type_id = None
        inline_options = {}
        if self.check(TokenType.LBRACKET):
            inline_options = self.parse_type_options(name)
            if "id" in inline_options:
                type_id = inline_options["id"]

        self.consume(TokenType.LBRACE, "Expected '{' after message name")

        fields = []
        nested_messages = []
        nested_enums = []
        body_options = {}

        while not self.check(TokenType.RBRACE):
            # Check for reserved statements
            if self.check(TokenType.RESERVED):
                self.parse_reserved()
            # Check for option statements (message-level options)
            elif self.check(TokenType.OPTION):
                opt_name, opt_value = self.parse_message_option(name)
                body_options[opt_name] = opt_value
                # Handle fory.id option to set type_id
                if opt_name == "fory.id" and type_id is None:
                    if isinstance(opt_value, int) and opt_value > 0:
                        type_id = opt_value
            # Check for nested message
            elif self.check(TokenType.MESSAGE):
                nested_messages.append(self.parse_message())
            # Check for nested enum
            elif self.check(TokenType.ENUM):
                nested_enums.append(self.parse_enum())
            else:
                fields.append(self.parse_field())

        self.consume(TokenType.RBRACE, "Expected '}' after message fields")

        # Merge inline options and body options (body options take precedence)
        all_options = {**inline_options, **body_options}

        return Message(
            name=name,
            type_id=type_id,
            fields=fields,
            nested_messages=nested_messages,
            nested_enums=nested_enums,
            options=all_options,
            line=start.line,
            column=start.column,
        )

    def parse_field(self) -> Field:
        """Parse a field: optional ref repeated Type name = 1 [options];

        Supports:
        - Keyword modifiers: optional ref repeated
        - Bracket options: [deprecated=true, (fory).ref=true]
        """
        start = self.current()

        # Parse modifiers
        optional = self.match(TokenType.OPTIONAL)
        ref = self.match(TokenType.REF)
        repeated = self.match(TokenType.REPEATED)

        # Parse type
        field_type = self.parse_type()

        # Wrap in ListType if repeated
        if repeated:
            field_type = ListType(field_type)

        # Parse field name
        name = self.consume(TokenType.IDENT, "Expected field name").value

        # Parse field number
        self.consume(TokenType.EQUALS, "Expected '=' after field name")
        number_token = self.consume(TokenType.INT, "Expected field number")
        number = int(number_token.value)

        # Parse optional field options: [deprecated=true, (fory).ref=true]
        field_options = {}
        if self.check(TokenType.LBRACKET):
            field_options = self.parse_field_options(name)
            # Handle fory.ref or ref option to set ref flag
            if field_options.get("fory.ref") is True or field_options.get("fory.tracking_ref") is True:
                ref = True
            if field_options.get("ref") is True or field_options.get("tracking_ref") is True:
                ref = True
            # Handle fory.nullable or nullable option to set optional flag
            if field_options.get("fory.nullable") is True or field_options.get("nullable") is True:
                optional = True

        self.consume(TokenType.SEMI, "Expected ';' after field declaration")

        return Field(
            name=name,
            field_type=field_type,
            number=number,
            optional=optional,
            ref=ref,
            options=field_options,
            line=start.line,
            column=start.column,
        )

    def parse_field_options(self, field_name: str) -> dict:
        """Parse field options: [deprecated=true, (fory).ref=true]

        Supports two syntaxes:
        1. Standard: [deprecated=true, json_name="foo"]
        2. Extension: [(fory).ref=true, (fory).nullable=true]

        Returns a dict of option names to values.
        For extension options, the name is prefixed: "fory.ref"
        """
        self.consume(TokenType.LBRACKET)
        options = {}

        while True:
            # Check for extension syntax: (extension_name).option_name
            extension_name = None
            if self.check(TokenType.LPAREN):
                self.advance()  # consume (
                extension_name = self.consume(TokenType.IDENT, "Expected extension name").value
                self.consume(TokenType.RPAREN, "Expected ')' after extension name")
                self.consume(TokenType.DOT, "Expected '.' after extension name")

            # Parse option name (can be IDENT or keyword like 'ref', 'optional', etc.)
            name_token = self.current()
            if self.check(TokenType.IDENT):
                self.advance()
                option_name = name_token.value
            elif self.check(TokenType.REF):
                # 'ref' is a keyword but valid as option name
                self.advance()
                option_name = "ref"
            elif self.check(TokenType.OPTIONAL):
                # 'optional' is a keyword but valid as option name
                self.advance()
                option_name = "optional"
            elif self.check(TokenType.REPEATED):
                # 'repeated' is a keyword but valid as option name
                self.advance()
                option_name = "repeated"
            elif self.check(TokenType.WEAK):
                # 'weak' is a keyword but valid as option name
                self.advance()
                option_name = "weak"
            elif self.check(TokenType.TRUE):
                # 'true' can be used as option name in some contexts
                self.advance()
                option_name = "true"
            elif self.check(TokenType.FALSE):
                # 'false' can be used as option name in some contexts
                self.advance()
                option_name = "false"
            else:
                raise self.error(f"Expected option name, got {self.current().type.name}")

            # Build full option name for extension options
            if extension_name:
                full_option_name = f"{extension_name}.{option_name}"
            else:
                full_option_name = option_name

            self.consume(TokenType.EQUALS, "Expected '=' after option name")

            # Parse option value
            option_value = self.parse_option_value()
            options[full_option_name] = option_value

            # Warn about unknown field options
            if extension_name:
                if extension_name == "fory":
                    if option_name not in KNOWN_FORY_FIELD_OPTIONS:
                        warnings.warn(
                            f"Line {name_token.line}: ignoring unknown fory field option '{option_name}' on field '{field_name}'",
                            stacklevel=2
                        )
                else:
                    warnings.warn(
                        f"Line {name_token.line}: ignoring unknown extension '{extension_name}'",
                        stacklevel=2
                    )
            else:
                if option_name not in KNOWN_FIELD_OPTIONS:
                    warnings.warn(
                        f"Line {name_token.line}: ignoring unknown field option '{option_name}' on field '{field_name}'",
                        stacklevel=2
                    )

            # Check for comma (more options) or closing bracket (end)
            if self.check(TokenType.COMMA):
                self.advance()
            elif self.check(TokenType.RBRACKET):
                break
            else:
                raise self.error("Expected ',' or ']' in field options")

        self.consume(TokenType.RBRACKET, "Expected ']' after field options")
        return options

    def parse_type_options(self, type_name: str) -> dict:
        """Parse type options: [id=100, deprecated=true]

        Returns a dict of option names to values.
        Warns about unknown options.
        """
        self.consume(TokenType.LBRACKET)
        options = {}

        while True:
            # Parse option name
            name_token = self.consume(TokenType.IDENT, "Expected option name")
            option_name = name_token.value

            self.consume(TokenType.EQUALS, "Expected '=' after option name")

            # Parse option value (can be string, bool, int, or identifier)
            if self.check(TokenType.STRING):
                option_value = self.advance().value
            elif self.check(TokenType.TRUE):
                self.advance()
                option_value = True
            elif self.check(TokenType.FALSE):
                self.advance()
                option_value = False
            elif self.check(TokenType.INT):
                option_value = int(self.advance().value)
            elif self.check(TokenType.IDENT):
                option_value = self.advance().value
            else:
                raise self.error(f"Expected option value, got {self.current().type.name}")

            # Validate 'id' option must be a positive integer
            if option_name == "id":
                if not isinstance(option_value, int):
                    raise self.error(f"Type option 'id' must be an integer, got {type(option_value).__name__}")
                if option_value <= 0:
                    raise self.error(f"Type option 'id' must be a positive integer, got {option_value}")

            # Warn about unknown type options
            if option_name not in KNOWN_TYPE_OPTIONS:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown type option '{option_name}' on type '{type_name}'",
                    stacklevel=2
                )

            options[option_name] = option_value

            # Check for comma (more options) or closing bracket (end)
            if self.check(TokenType.COMMA):
                self.advance()
            elif self.check(TokenType.RBRACKET):
                break
            else:
                raise self.error("Expected ',' or ']' in type options")

        self.consume(TokenType.RBRACKET, "Expected ']' after type options")
        return options

    def parse_type(self) -> FieldType:
        """Parse a type: int32, string, map<K, V>, Parent.Child, or a named type."""
        if self.check(TokenType.MAP):
            return self.parse_map_type()

        if not self.check(TokenType.IDENT):
            raise self.error(f"Expected type name, got {self.current().type.name}")

        type_name = self.consume(TokenType.IDENT).value

        # Check if it's a primitive type
        if type_name in PRIMITIVE_TYPES:
            return PrimitiveType(PRIMITIVE_TYPES[type_name])

        # Check for qualified name (e.g., Parent.Child or Outer.Middle.Inner)
        while self.check(TokenType.DOT):
            self.advance()  # consume the dot
            if not self.check(TokenType.IDENT):
                raise self.error("Expected identifier after '.'")
            type_name += "." + self.consume(TokenType.IDENT).value

        # It's a named type (reference to message or enum)
        return NamedType(type_name)

    def parse_map_type(self) -> MapType:
        """Parse a map type: map<KeyType, ValueType>"""
        self.consume(TokenType.MAP)
        self.consume(TokenType.LANGLE, "Expected '<' after 'map'")

        key_type = self.parse_type()

        self.consume(TokenType.COMMA, "Expected ',' between map key and value types")

        value_type = self.parse_type()

        self.consume(TokenType.RANGLE, "Expected '>' after map value type")

        return MapType(key_type, value_type)


def parse(source: str, filename: str = "<input>") -> Schema:
    """Parse FDL source code and return a Schema."""
    parser = Parser.from_source(source, filename)
    return parser.parse()
