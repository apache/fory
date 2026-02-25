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
from typing import List, Set, Optional

from fory_compiler.ir.ast import (
    Schema,
    Message,
    Enum,
    Union,
    Service,
    RpcMethod,
    Field,
    EnumValue,
    Import,
    FieldType,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
    SourceLocation,
)
from fory_compiler.ir.types import PRIMITIVE_TYPES
from fory_compiler.frontend.fdl.lexer import Lexer, Token, TokenType

# Known file-level options
KNOWN_FILE_OPTIONS: Set[str] = {
    "java_package",
    "java_outer_classname",
    "java_multiple_files",
    "go_package",
    "csharp_namespace",
    "deprecated",
    "use_record_for_java_message",
    "polymorphism",
    "enable_auto_type_id",
    "go_nested_type_style",
    "evolving",
}

# Known field-level options
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
    "ref",
    "nullable",
    "thread_safe_pointer",
    "weak_ref",
    "java_array",
}

KNOWN_REF_OPTIONS: Set[str] = {
    "weak",
    "weak_ref",
    "thread_safe",
    "thread_safe_pointer",
}

REF_OPTION_ALIASES = {
    "weak": "weak_ref",
    "weak_ref": "weak_ref",
    "thread_safe": "thread_safe_pointer",
    "thread_safe_pointer": "thread_safe_pointer",
}

# Known type-level options for inline syntax
KNOWN_ENUM_OPTIONS: Set[str] = {
    "id",
    "deprecated",
}

KNOWN_MESSAGE_OPTIONS: Set[str] = {
    "id",
    "alias",
    "evolving",
    "use_record_for_java",
    "deprecated",
    "namespace",
}

KNOWN_UNION_OPTIONS: Set[str] = {
    "id",
    "alias",
    "deprecated",
}

KNOWN_SERVICE_OPTIONS: Set[str] = {
    "deprecated",
}

KNOWN_METHOD_OPTIONS: Set[str] = {
    "deprecated",
}


class ParseError(Exception):
    """Error during parsing."""

    def __init__(self, message: str, line: int, column: int):
        self.message = message
        self.line = line
        self.column = column
        super().__init__(f"Line {line}, Column {column}: {message}")


class Parser:
    """Recursive descent parser for FDL."""

    def __init__(self, tokens: List[Token], filename: str = "<input>"):
        self.tokens = tokens
        self.pos = 0
        self.filename = filename
        self.source_format = "fdl"

    @classmethod
    def from_source(cls, source: str, filename: str = "<input>") -> "Parser":
        """Create a parser from source code."""
        lexer = Lexer(source, filename)
        tokens = lexer.tokenize()
        return cls(tokens, filename)

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
        package_alias = None
        imports = []
        enums = []
        messages = []
        unions = []
        services = []
        options = {}

        while not self.at_end():
            if self.check(TokenType.PACKAGE):
                if package is not None:
                    raise self.error("Duplicate package declaration")
                package, package_alias = self.parse_package()
            elif self.check(TokenType.IMPORT):
                imports.append(self.parse_import())
            elif self.check(TokenType.OPTION):
                # File-level option
                name, value = self.parse_file_option()
                options[name] = value
            elif self.check(TokenType.ENUM):
                enums.append(self.parse_enum())
            elif self.check(TokenType.UNION):
                unions.append(self.parse_union())
            elif self.check(TokenType.MESSAGE):
                messages.append(self.parse_message())
            elif self.check(TokenType.SERVICE):
                services.append(self.parse_service())
            else:
                raise self.error(f"Unexpected token: {self.current().value}")

        return Schema(
            package=package,
            package_alias=package_alias,
            imports=imports,
            enums=enums,
            messages=messages,
            unions=unions,
            services=services,
            options=options,
            source_file=self.filename,
            source_format=self.source_format,
        )

    def make_location(self, token: Token) -> SourceLocation:
        """Create a source location from a token."""
        return SourceLocation(
            file=self.filename,
            line=token.line,
            column=token.column,
            source_format=self.source_format,
        )

    def parse_package(self) -> tuple[str, Optional[str]]:
        """Parse a package declaration: package foo.bar [alias baz];"""
        self.consume(TokenType.PACKAGE)

        # Package name can be dotted: foo.bar.baz
        parts = [self.consume(TokenType.IDENT).value]
        while self.check(TokenType.DOT):
            self.advance()  # consume the dot
            parts.append(
                self.consume(TokenType.IDENT, "Expected identifier after '.'").value
            )

        alias = None
        if self.check(TokenType.IDENT) and self.current().value == "alias":
            self.advance()  # consume alias keyword
            alias_parts = [
                self.consume(TokenType.IDENT, "Expected identifier after 'alias'").value
            ]
            while self.check(TokenType.DOT):
                self.advance()
                alias_parts.append(
                    self.consume(
                        TokenType.IDENT, "Expected identifier after '.' in alias"
                    ).value
                )
            alias = ".".join(alias_parts)

        self.consume(TokenType.SEMI, "Expected ';' after package declaration")
        return ".".join(parts), alias

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
        """Parse a file-level option: option key = value;"""
        self.consume(TokenType.OPTION)

        name_token = self.consume(TokenType.IDENT, "Expected option name")
        option_name = name_token.value

        self.consume(TokenType.EQUALS, "Expected '=' after option name")
        option_value = self.parse_option_value()
        self.consume(TokenType.SEMI, "Expected ';' after option statement")

        if option_name not in KNOWN_FILE_OPTIONS:
            warnings.warn(
                f"Line {name_token.line}: ignoring unknown option '{option_name}'",
                stacklevel=2,
            )

        return (option_name, option_value)

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
            location=self.make_location(start),
        )

    def parse_enum(self) -> Enum:
        """Parse an enum: enum Color [id=101] { ... }

        Supports:
        - Inline type options: enum Color [id=101] { ... }
        """
        start = self.current()
        self.consume(TokenType.ENUM)
        name = self.consume(TokenType.IDENT, "Expected enum name").value

        # Optional inline type options: [id=101, deprecated=true]
        type_id = None
        inline_options = {}
        if self.check(TokenType.LBRACKET):
            inline_options = self.parse_type_options(name, KNOWN_ENUM_OPTIONS)
            if "id" in inline_options:
                type_id = inline_options["id"]

        self.consume(TokenType.LBRACE, "Expected '{' after enum name")

        values = []
        body_options = {}
        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.OPTION):
                raise self.error("Option statements inside enum are not supported")
            if self.check(TokenType.RESERVED):
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
            location=self.make_location(start),
        )

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
            location=self.make_location(start),
        )

    def parse_message(self) -> Message:
        """Parse a message: message Dog [id=102] { ... }

        Supports:
        - Inline type options: message Dog [id=102] { ... }
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
            inline_options = self.parse_type_options(name, KNOWN_MESSAGE_OPTIONS)
            if "id" in inline_options:
                type_id = inline_options["id"]

        self.consume(TokenType.LBRACE, "Expected '{' after message name")

        fields = []
        nested_messages = []
        nested_enums = []
        nested_unions = []
        body_options = {}

        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.RESERVED):
                self.parse_reserved()
            elif self.check(TokenType.OPTION):
                raise self.error("Option statements inside message are not supported")
            elif self.check(TokenType.MESSAGE):
                nested_messages.append(self.parse_message())
            elif self.check(TokenType.ENUM):
                nested_enums.append(self.parse_enum())
            elif self.check(TokenType.UNION):
                nested_unions.append(self.parse_union())
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
            nested_unions=nested_unions,
            options=all_options,
            line=start.line,
            column=start.column,
            location=self.make_location(start),
        )

    def parse_union(self) -> Union:
        """Parse a union: union Media [id=101] { ... }"""
        start = self.current()
        self.consume(TokenType.UNION)
        name = self.consume(TokenType.IDENT, "Expected union name").value

        type_id = None
        inline_options = {}
        if self.check(TokenType.LBRACKET):
            inline_options = self.parse_type_options(
                name, KNOWN_UNION_OPTIONS, allow_zero_id=True
            )
            if "id" in inline_options:
                type_id = inline_options["id"]

        self.consume(TokenType.LBRACE, "Expected '{' after union name")

        fields = []
        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.RESERVED):
                self.parse_reserved()
            else:
                fields.append(self.parse_union_field())

        self.consume(TokenType.RBRACE, "Expected '}' after union cases")

        return Union(
            name=name,
            type_id=type_id,
            fields=fields,
            options=inline_options,
            line=start.line,
            column=start.column,
            location=self.make_location(start),
        )

    def parse_union_field(self) -> Field:
        """Parse a union case: Type name = 1;"""
        start = self.current()

        if self.check(TokenType.OPTIONAL) or self.check(TokenType.REF):
            raise self.error("Union cases do not support optional/ref modifiers")

        repeated = False
        if self.check(TokenType.REPEATED):
            self.advance()
            repeated = True

        field_type = self.parse_type()
        if repeated:
            field_type = ListType(field_type, location=self.make_location(start))
        name = self.consume(TokenType.IDENT, "Expected union case name").value
        self.consume(TokenType.EQUALS, "Expected '=' after union case name")
        number_token = self.consume(TokenType.INT, "Expected union case id")
        number = int(number_token.value)

        field_options = {}
        if self.check(TokenType.LBRACKET):
            field_options = self.parse_field_options(name)

        self.consume(TokenType.SEMI, "Expected ';' after union case")

        return Field(
            name=name,
            field_type=field_type,
            number=number,
            tag_id=number,
            options=field_options,
            line=start.line,
            column=start.column,
            location=self.make_location(start),
        )

    def parse_field(self) -> Field:
        """Parse a field: optional ref repeated Type name = 1 [options];

        Supports:
        - Keyword modifiers: optional ref repeated (repeated is an alias for list)
        - list<T> type syntax for list fields
        - Bracket options: [deprecated=true, ref=true]
        """
        start = self.current()

        # Parse modifiers (optional/ref before repeated apply to the collection/field,
        # optional/ref after repeated apply to elements).
        optional = False
        ref = False
        ref_options = {}
        element_optional = False
        element_ref = False
        element_ref_options = {}
        repeated = False
        while True:
            if self.match(TokenType.OPTIONAL):
                if repeated:
                    element_optional = True
                else:
                    optional = True
                continue
            if self.match(TokenType.REF):
                options = self.parse_ref_options(name="ref")
                if repeated:
                    element_ref = True
                    element_ref_options = options
                else:
                    ref = True
                    ref_options = options
                continue
            if self.check(TokenType.REPEATED):
                if repeated:
                    raise self.error("Repeated modifier specified more than once")
                self.advance()
                repeated = True
                continue
            break

        # Parse type
        field_type = self.parse_type()
        if not repeated and isinstance(field_type, ListType):
            element_optional = field_type.element_optional
            element_ref = field_type.element_ref
            element_ref_options = field_type.element_ref_options

        # Wrap in ListType if repeated
        if repeated:
            field_type = ListType(
                field_type,
                element_optional=element_optional,
                element_ref=element_ref,
                element_ref_options=element_ref_options,
                location=self.make_location(start),
            )

        # Parse field name
        if self.check(TokenType.IDENT) or self.check(TokenType.TO):
            name = self.advance().value
        else:
            raise self.error("Expected field name")

        # Parse field number
        self.consume(TokenType.EQUALS, "Expected '=' after field name")
        number_token = self.consume(TokenType.INT, "Expected field number")
        number = int(number_token.value)

        # Parse optional field options: [deprecated=true, ref=true]
        field_options = {}
        if self.check(TokenType.LBRACKET):
            field_options = self.parse_field_options(name)
            # Handle ref option to set ref flag
            if field_options.get("ref") is True:
                if isinstance(field_type, ListType):
                    element_ref = True
                elif isinstance(field_type, MapType):
                    field_type.value_ref = True
                else:
                    ref = True
            # Handle nullable option to set optional flag
            if field_options.get("nullable") is True:
                optional = True
            self.merge_ref_options(
                field_type,
                ref,
                element_ref,
                field_options,
                ref_options,
                element_ref_options,
            )
            if isinstance(field_type, ListType):
                field_type.element_optional = element_optional
                field_type.element_ref = element_ref
                field_type.element_ref_options = element_ref_options

        self.consume(TokenType.SEMI, "Expected ';' after field declaration")

        return Field(
            name=name,
            field_type=field_type,
            number=number,
            tag_id=number,
            optional=optional,
            ref=ref,
            ref_options=ref_options,
            element_optional=element_optional,
            element_ref=element_ref,
            element_ref_options=element_ref_options,
            options=field_options,
            line=start.line,
            column=start.column,
            location=self.make_location(start),
        )

    def parse_field_options(self, field_name: str) -> dict:
        """Parse field options: [deprecated=true, ref=true]."""
        self.consume(TokenType.LBRACKET)
        options = {}

        while True:
            name_token = self.current()
            if self.check(TokenType.IDENT):
                self.advance()
                option_name = name_token.value
            elif self.check(TokenType.REF):
                self.advance()
                option_name = "ref"
            elif self.check(TokenType.OPTIONAL):
                self.advance()
                option_name = "optional"
            elif self.check(TokenType.REPEATED):
                self.advance()
                option_name = "repeated"
            elif self.check(TokenType.LIST):
                self.advance()
                option_name = "list"
            elif self.check(TokenType.WEAK):
                self.advance()
                option_name = "weak"
            else:
                raise self.error(
                    f"Expected option name, got {self.current().type.name}"
                )

            self.consume(TokenType.EQUALS, "Expected '=' after option name")
            option_value = self.parse_option_value()
            options[option_name] = option_value

            if option_name not in KNOWN_FIELD_OPTIONS:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown field option '{option_name}' on field '{field_name}'",
                    stacklevel=2,
                )

            if self.check(TokenType.COMMA):
                self.advance()
            elif self.check(TokenType.RBRACKET):
                break
            else:
                raise self.error("Expected ',' or ']' in field options")

        self.consume(TokenType.RBRACKET, "Expected ']' after field options")
        return options

    def parse_ref_options(self, name: str) -> dict:
        """Parse ref keyword options: ref(weak=true, thread_safe=false)."""
        if not self.check(TokenType.LPAREN):
            return {}
        self.consume(TokenType.LPAREN, "Expected '(' after ref")
        options = {}
        while True:
            name_token = self.current()
            if self.check(TokenType.IDENT):
                self.advance()
                option_name = name_token.value
            elif self.check(TokenType.WEAK):
                self.advance()
                option_name = "weak"
            else:
                raise self.error(
                    f"Expected ref option name, got {self.current().type.name}"
                )

            self.consume(TokenType.EQUALS, "Expected '=' after ref option name")
            option_value = self.parse_option_value()
            canonical = REF_OPTION_ALIASES.get(option_name, option_name)
            options[canonical] = option_value

            if option_name not in KNOWN_REF_OPTIONS:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown ref option '{option_name}' on {name}",
                    stacklevel=2,
                )

            if self.check(TokenType.COMMA):
                self.advance()
            elif self.check(TokenType.RPAREN):
                break
            else:
                raise self.error("Expected ',' or ')' in ref options")

        self.consume(TokenType.RPAREN, "Expected ')' after ref options")
        return options

    def merge_ref_options(
        self,
        field_type: FieldType,
        ref: bool,
        element_ref: bool,
        field_options: dict,
        ref_options: dict,
        element_ref_options: dict,
    ) -> None:
        weak_ref = field_options.get("weak_ref")
        if weak_ref is None and field_options.get("weak") is not None:
            weak_ref = field_options.get("weak")
        thread_safe = field_options.get("thread_safe_pointer")
        if thread_safe is None and field_options.get("thread_safe") is not None:
            thread_safe = field_options.get("thread_safe")

        if weak_ref is None and thread_safe is None:
            return

        if isinstance(field_type, MapType):
            target = field_type.value_ref_options
        elif isinstance(field_type, ListType):
            target = element_ref_options
        else:
            target = ref_options

        if weak_ref is not None:
            target["weak_ref"] = weak_ref
        if thread_safe is not None:
            target["thread_safe_pointer"] = thread_safe

    def parse_service(self) -> Service:
        """Parse a service definition: service Greeter { rpc ... }"""
        start = self.current()
        self.consume(TokenType.SERVICE)
        name = self.consume(TokenType.IDENT, "Expected service name").value
        self.consume(TokenType.LBRACE, "Expected '{' after service name")

        methods = []
        options = {}

        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.OPTION):
                # Service-level option
                name_token = self.current()
                opt_name, opt_value = (
                    self.parse_file_option()
                )  # Reusing generic option parser
                options[opt_name] = opt_value
                if opt_name not in KNOWN_SERVICE_OPTIONS:
                    warnings.warn(
                        f"Line {name_token.line}: ignoring unknown service option '{opt_name}'",
                        stacklevel=2,
                    )
            elif self.check(TokenType.RPC):
                methods.append(self.parse_rpc_method())
            else:
                raise self.error("Expected 'rpc' or 'option' inside service block")

        self.consume(TokenType.RBRACE, "Expected '}' after service body")

        return Service(
            name=name,
            methods=methods,
            options=options,
            line=start.line,
            column=start.column,
            location=self.make_location(start),
        )

    def parse_rpc_method(self) -> RpcMethod:
        """Parse an RPC method: rpc Name (stream? Req) returns (stream? Res) { option ... };"""
        start = self.current()
        self.consume(TokenType.RPC)
        name = self.consume(TokenType.IDENT, "Expected method name").value

        # Parse request type
        self.consume(TokenType.LPAREN, "Expected '(' before request type")
        client_streaming = False
        if self.check(TokenType.STREAM):
            self.advance()
            client_streaming = True

        req_type_token = self.consume(TokenType.IDENT, "Expected request message type")
        request_type = NamedType(
            name=req_type_token.value, location=self.make_location(req_type_token)
        )
        self.consume(TokenType.RPAREN, "Expected ')' after request type")

        # Parse return type
        self.consume(TokenType.RETURNS, "Expected 'returns' keyword")
        self.consume(TokenType.LPAREN, "Expected '(' before response type")
        server_streaming = False
        if self.check(TokenType.STREAM):
            self.advance()
            server_streaming = True

        res_type_token = self.consume(TokenType.IDENT, "Expected response message type")
        response_type = NamedType(
            name=res_type_token.value, location=self.make_location(res_type_token)
        )
        self.consume(TokenType.RPAREN, "Expected ')' after response type")

        # Parse optional method options block
        options = {}
        if self.check(TokenType.LBRACE):
            self.consume(TokenType.LBRACE)
            while not self.check(TokenType.RBRACE):
                if self.check(TokenType.OPTION):
                    name_token = self.current()
                    opt_name, opt_value = self.parse_file_option()
                    options[opt_name] = opt_value
                    if opt_name not in KNOWN_METHOD_OPTIONS:
                        warnings.warn(
                            f"Line {name_token.line}: ignoring unknown method option '{opt_name}'",
                            stacklevel=2,
                        )
                elif self.check(TokenType.SEMI):
                    # Allow empty ; inside block
                    self.advance()
                else:
                    raise self.error("Expected 'option' inside method block")
            self.consume(TokenType.RBRACE, "Expected '}' after method options")
        else:
            self.consume(TokenType.SEMI, "Expected ';' after method definition")

        return RpcMethod(
            name=name,
            request_type=request_type,
            response_type=response_type,
            client_streaming=client_streaming,
            server_streaming=server_streaming,
            options=options,
            line=start.line,
            column=start.column,
            location=self.make_location(start),
        )

    def parse_type_options(
        self, type_name: str, known_options: Set[str], allow_zero_id: bool = False
    ) -> dict:
        """Parse type options: [id=100, deprecated=true]."""
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
                raise self.error(
                    f"Expected option value, got {self.current().type.name}"
                )

            # Validate 'id' option must be a non-negative integer (positive by default)
            if option_name == "id":
                if not isinstance(option_value, int):
                    raise self.error(
                        f"Type option 'id' must be an integer, got {type(option_value).__name__}"
                    )
                if allow_zero_id:
                    if option_value < 0:
                        raise self.error(
                            f"Type option 'id' must be a non-negative integer, got {option_value}"
                        )
                elif option_value <= 0:
                    raise self.error(
                        f"Type option 'id' must be a positive integer, got {option_value}"
                    )

            if option_name not in known_options:
                warnings.warn(
                    f"Line {name_token.line}: ignoring unknown type option '{option_name}' on type '{type_name}'",
                    stacklevel=2,
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
        """Parse a type: int32, string, list<T>, map<K, V>, Parent.Child, or a named type."""
        if self.check(TokenType.MAP):
            return self.parse_map_type()
        if self.check(TokenType.LIST):
            return self.parse_list_type()

        if not self.check(TokenType.IDENT):
            raise self.error(f"Expected type name, got {self.current().type.name}")

        type_token = self.consume(TokenType.IDENT)
        type_name = type_token.value
        type_location = self.make_location(type_token)

        # Check if it's a primitive type
        if type_name in PRIMITIVE_TYPES:
            return PrimitiveType(PRIMITIVE_TYPES[type_name], location=type_location)

        # Check for qualified name (e.g., Parent.Child or Outer.Middle.Inner)
        while self.check(TokenType.DOT):
            self.advance()  # consume the dot
            if not self.check(TokenType.IDENT):
                raise self.error("Expected identifier after '.'")
            type_name += "." + self.consume(TokenType.IDENT).value

        # It's a named type (reference to message or enum)
        return NamedType(type_name, location=type_location)

    def parse_list_type(self) -> ListType:
        """Parse a list type: list<ElementType>."""
        start = self.consume(TokenType.LIST)
        self.consume(TokenType.LANGLE, "Expected '<' after 'list'")

        element_optional = False
        element_ref = False
        element_ref_options = {}
        while True:
            if self.match(TokenType.OPTIONAL):
                element_optional = True
                continue
            if self.match(TokenType.REF):
                element_ref = True
                element_ref_options = self.parse_ref_options(name="list element")
                continue
            break

        element_type = self.parse_type()
        self.consume(TokenType.RANGLE, "Expected '>' after list element type")

        return ListType(
            element_type,
            element_optional=element_optional,
            element_ref=element_ref,
            element_ref_options=element_ref_options,
            location=self.make_location(start),
        )

    def parse_map_type(self) -> MapType:
        """Parse a map type: map<KeyType, ValueType>"""
        start = self.consume(TokenType.MAP)
        self.consume(TokenType.LANGLE, "Expected '<' after 'map'")

        if self.check(TokenType.REF):
            raise self.error("Map keys do not support ref modifier")
        key_type = self.parse_type()

        self.consume(TokenType.COMMA, "Expected ',' between map key and value types")

        value_ref = False
        value_ref_options = {}
        if self.match(TokenType.REF):
            value_ref = True
            value_ref_options = self.parse_ref_options(name="map value")
        value_type = self.parse_type()

        self.consume(TokenType.RANGLE, "Expected '>' after map value type")

        return MapType(
            key_type,
            value_type,
            value_ref=value_ref,
            value_ref_options=value_ref_options,
            location=self.make_location(start),
        )


def parse(source: str, filename: str = "<input>") -> Schema:
    """Parse FDL source code and return a Schema."""
    parser = Parser.from_source(source, filename)
    return parser.parse()
