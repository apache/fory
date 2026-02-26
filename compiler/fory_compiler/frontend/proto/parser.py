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

"""Recursive descent parser for proto3."""

from typing import List

from fory_compiler.frontend.proto.ast import (
    ProtoSchema,
    ProtoMessage,
    ProtoEnum,
    ProtoEnumValue,
    ProtoField,
    ProtoOneof,
    ProtoType,
    ProtoService,
    ProtoRpcMethod,
)
from fory_compiler.frontend.proto.lexer import Token, TokenType


class ParseError(Exception):
    """Error during proto parsing."""

    def __init__(self, message: str, line: int, column: int):
        super().__init__(f"Line {line}, Column {column}: {message}")
        self.message = message
        self.line = line
        self.column = column


class Parser:
    """Recursive descent parser for proto3."""

    def __init__(self, tokens: List[Token], filename: str = "<input>"):
        self.tokens = tokens
        self.pos = 0
        self.filename = filename

    def at_end(self) -> bool:
        return self.current().type == TokenType.EOF

    def current(self) -> Token:
        if self.pos >= len(self.tokens):
            return self.tokens[-1]
        return self.tokens[self.pos]

    def previous(self) -> Token:
        return self.tokens[self.pos - 1]

    def check(self, token_type: TokenType) -> bool:
        return self.current().type == token_type

    def match(self, *types: TokenType) -> bool:
        for token_type in types:
            if self.check(token_type):
                self.advance()
                return True
        return False

    def advance(self) -> Token:
        token = self.current()
        if not self.at_end():
            self.pos += 1
        return token

    def consume(self, token_type: TokenType, message: str) -> Token:
        if self.check(token_type):
            return self.advance()
        token = self.current()
        raise ParseError(message, token.line, token.column)

    def error(self, message: str) -> ParseError:
        token = self.current()
        return ParseError(message, token.line, token.column)

    def parse(self) -> ProtoSchema:
        syntax = None
        package = None
        imports: List[str] = []
        enums: List[ProtoEnum] = []
        messages: List[ProtoMessage] = []
        services: List[ProtoService] = []
        options = {}

        while not self.at_end():
            if self.check(TokenType.SYNTAX):
                if syntax is not None:
                    raise self.error("Duplicate syntax declaration")
                syntax = self.parse_syntax()
            elif self.check(TokenType.PACKAGE):
                if package is not None:
                    raise self.error("Duplicate package declaration")
                package = self.parse_package()
            elif self.check(TokenType.IMPORT):
                path = self.parse_import()
                normalized_path = path.lstrip("/")
                if not normalized_path.startswith("google/protobuf/"):
                    imports.append(path)
            elif self.check(TokenType.OPTION):
                name, value = self.parse_option_statement()
                options[name] = value
            elif self.check(TokenType.MESSAGE):
                messages.append(self.parse_message())
            elif self.check(TokenType.ENUM):
                enums.append(self.parse_enum())
            elif self.check(TokenType.SERVICE):
                services.append(self.parse_service())
            elif self.check(TokenType.SEMI):
                self.advance()
            else:
                raise self.error(f"Unexpected token: {self.current().value}")

        if syntax is None:
            raise self.error('Missing syntax declaration (syntax = "proto3";)')
        if syntax != "proto3":
            raise self.error(f"Unsupported syntax '{syntax}', only proto3 is supported")

        return ProtoSchema(
            syntax=syntax,
            package=package,
            imports=imports,
            enums=enums,
            messages=messages,
            services=services,
            options=options,
            source_file=self.filename,
        )

    def parse_syntax(self) -> str:
        self.consume(TokenType.SYNTAX, "Expected 'syntax'")
        self.consume(TokenType.EQUALS, "Expected '=' after syntax")
        value = self.consume(TokenType.STRING, "Expected syntax string").value
        self.consume(TokenType.SEMI, "Expected ';' after syntax declaration")
        return value

    def parse_package(self) -> str:
        self.consume(TokenType.PACKAGE, "Expected 'package'")
        name = self.parse_full_ident()
        self.consume(TokenType.SEMI, "Expected ';' after package declaration")
        return name

    def parse_import(self) -> str:
        self.consume(TokenType.IMPORT, "Expected 'import'")
        if self.match(TokenType.PUBLIC) or self.match(TokenType.WEAK):
            pass
        path = self.consume(TokenType.STRING, "Expected import path").value
        self.consume(TokenType.SEMI, "Expected ';' after import")
        return path

    def parse_option_statement(self) -> tuple[str, object]:
        self.consume(TokenType.OPTION, "Expected 'option'")
        name = self.parse_option_name()
        self.consume(TokenType.EQUALS, "Expected '=' after option name")
        value = self.parse_option_value()
        self.consume(TokenType.SEMI, "Expected ';' after option")
        return name, value

    def parse_enum(self) -> ProtoEnum:
        start = self.current()
        self.consume(TokenType.ENUM, "Expected 'enum'")
        name = self.consume(TokenType.IDENT, "Expected enum name").value
        self.consume(TokenType.LBRACE, "Expected '{' after enum name")

        values: List[ProtoEnumValue] = []
        options = {}

        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.OPTION):
                opt_name, opt_value = self.parse_option_statement()
                options[opt_name] = opt_value
            elif self.check(TokenType.RESERVED):
                self.parse_reserved()
            else:
                values.append(self.parse_enum_value())

        self.consume(TokenType.RBRACE, "Expected '}' after enum")
        return ProtoEnum(
            name=name,
            values=values,
            options=options,
            line=start.line,
            column=start.column,
        )

    def parse_enum_value(self) -> ProtoEnumValue:
        name_token = self.consume(TokenType.IDENT, "Expected enum value name")
        self.consume(TokenType.EQUALS, "Expected '=' after enum value name")
        number = int(self.consume(TokenType.INT, "Expected enum value").value)
        if self.check(TokenType.LBRACKET):
            self.parse_field_options()
        self.consume(TokenType.SEMI, "Expected ';' after enum value")
        return ProtoEnumValue(
            name=name_token.value,
            value=number,
            line=name_token.line,
            column=name_token.column,
        )

    def parse_message(self) -> ProtoMessage:
        start = self.current()
        self.consume(TokenType.MESSAGE, "Expected 'message'")
        name = self.consume(TokenType.IDENT, "Expected message name").value
        self.consume(TokenType.LBRACE, "Expected '{' after message name")

        fields: List[ProtoField] = []
        nested_messages: List[ProtoMessage] = []
        nested_enums: List[ProtoEnum] = []
        oneofs = []
        options = {}

        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.OPTION):
                opt_name, opt_value = self.parse_option_statement()
                options[opt_name] = opt_value
            elif self.check(TokenType.MESSAGE):
                nested_messages.append(self.parse_message())
            elif self.check(TokenType.ENUM):
                nested_enums.append(self.parse_enum())
            elif self.check(TokenType.RESERVED):
                self.parse_reserved()
            elif self.check(TokenType.EXTENSIONS):
                self.parse_extensions()
            elif self.check(TokenType.ONEOF):
                oneofs.append(self.parse_oneof())
            elif self.check(TokenType.SEMI):
                self.advance()
            else:
                fields.append(self.parse_field())

        self.consume(TokenType.RBRACE, "Expected '}' after message")
        return ProtoMessage(
            name=name,
            fields=fields,
            nested_messages=nested_messages,
            nested_enums=nested_enums,
            oneofs=oneofs,
            options=options,
            line=start.line,
            column=start.column,
        )

    def parse_oneof(self) -> ProtoOneof:
        start = self.current()
        self.consume(TokenType.ONEOF, "Expected 'oneof'")
        name = self.consume(TokenType.IDENT, "Expected oneof name").value
        self.consume(TokenType.LBRACE, "Expected '{' after oneof name")

        fields: List[ProtoField] = []
        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.SEMI):
                self.advance()
                continue
            fields.append(self.parse_oneof_field())

        self.consume(TokenType.RBRACE, "Expected '}' after oneof")
        return ProtoOneof(
            name=name,
            fields=fields,
            line=start.line,
            column=start.column,
        )

    def parse_oneof_field(self) -> ProtoField:
        start = self.current()
        if self.check(TokenType.OPTIONAL) or self.check(TokenType.REPEATED):
            token = self.current()
            raise ParseError(
                "oneof fields must not be labeled optional or repeated",
                token.line,
                token.column,
            )
        if self.check(TokenType.REQUIRED):
            token = self.current()
            raise ParseError(
                "proto2 required fields are not supported", token.line, token.column
            )

        if self.check(TokenType.MAP):
            field_type = self.parse_map_type()
        else:
            type_name = self.parse_type_name()
            field_type = ProtoType(name=type_name, line=start.line, column=start.column)

        name = self.consume(TokenType.IDENT, "Expected field name").value
        self.consume(TokenType.EQUALS, "Expected '=' after field name")
        number = int(self.consume(TokenType.INT, "Expected field number").value)
        options = {}
        if self.check(TokenType.LBRACKET):
            options = self.parse_field_options()
        self.consume(TokenType.SEMI, "Expected ';' after oneof field")

        return ProtoField(
            name=name,
            field_type=field_type,
            number=number,
            label=None,
            options=options,
            line=start.line,
            column=start.column,
        )

    def parse_field(self) -> ProtoField:
        start = self.current()
        label = None
        if self.match(TokenType.OPTIONAL):
            label = "optional"
        elif self.match(TokenType.REPEATED):
            label = "repeated"
        elif self.match(TokenType.REQUIRED):
            token = self.previous()
            raise ParseError(
                "proto2 required fields are not supported", token.line, token.column
            )

        if self.check(TokenType.MAP):
            field_type = self.parse_map_type()
        else:
            type_name = self.parse_type_name()
            field_type = ProtoType(name=type_name, line=start.line, column=start.column)

        name = self.consume(TokenType.IDENT, "Expected field name").value
        self.consume(TokenType.EQUALS, "Expected '=' after field name")
        number = int(self.consume(TokenType.INT, "Expected field number").value)
        options = {}
        if self.check(TokenType.LBRACKET):
            options = self.parse_field_options()
        self.consume(TokenType.SEMI, "Expected ';' after field declaration")

        return ProtoField(
            name=name,
            field_type=field_type,
            number=number,
            label=label,
            options=options,
            line=start.line,
            column=start.column,
        )

    def parse_map_type(self) -> ProtoType:
        start = self.current()
        self.consume(TokenType.MAP, "Expected 'map'")
        self.consume(TokenType.LANGLE, "Expected '<' after map")
        key_type = self.parse_type_name()
        self.consume(TokenType.COMMA, "Expected ',' in map type")
        value_type = self.parse_type_name()
        self.consume(TokenType.RANGLE, "Expected '>' after map type")
        return ProtoType(
            name="map",
            is_map=True,
            map_key_type=key_type,
            map_value_type=value_type,
            line=start.line,
            column=start.column,
        )

    def parse_field_options(self) -> dict:
        self.consume(TokenType.LBRACKET, "Expected '[' for field options")
        options = {}
        while True:
            name = self.parse_option_name()
            self.consume(TokenType.EQUALS, "Expected '=' after option name")
            value = self.parse_option_value()
            options[name] = value
            if self.match(TokenType.COMMA):
                continue
            if self.check(TokenType.RBRACKET):
                break
            raise self.error("Expected ',' or ']' in field options")
        self.consume(TokenType.RBRACKET, "Expected ']' after field options")
        return options

    def parse_reserved(self) -> None:
        self.consume(TokenType.RESERVED, "Expected 'reserved'")
        while not self.check(TokenType.SEMI):
            self.advance()
        self.consume(TokenType.SEMI, "Expected ';' after reserved")

    def parse_extensions(self) -> None:
        self.consume(TokenType.EXTENSIONS, "Expected 'extensions'")
        while not self.check(TokenType.SEMI):
            self.advance()
        self.consume(TokenType.SEMI, "Expected ';' after extensions")

    def parse_service(self) -> ProtoService:
        start = self.current()
        self.consume(TokenType.SERVICE, "Expected 'service'")
        name = self.consume(TokenType.IDENT, "Expected service name").value
        self.consume(TokenType.LBRACE, "Expected '{' after service name")

        methods: List[ProtoRpcMethod] = []
        options = {}

        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.OPTION):
                opt_name, opt_value = self.parse_option_statement()
                options[opt_name] = opt_value
            elif self.check(TokenType.RPC):
                methods.append(self.parse_rpc_method())
            elif self.check(TokenType.SEMI):
                self.advance()
            else:
                raise self.error("Expected 'rpc' or 'option' inside service")

        self.consume(TokenType.RBRACE, "Expected '}' after service")
        if self.check(TokenType.SEMI):
            self.advance()

        return ProtoService(
            name=name,
            methods=methods,
            options=options,
            line=start.line,
            column=start.column,
        )

    def parse_rpc_method(self) -> ProtoRpcMethod:
        start = self.current()
        self.consume(TokenType.RPC, "Expected 'rpc'")
        name = self.consume(TokenType.IDENT, "Expected method name").value

        # Request
        self.consume(TokenType.LPAREN, "Expected '(' before request type")
        client_streaming = False
        if self.match(TokenType.STREAM):
            client_streaming = True

        req_type = self.parse_full_ident()
        self.consume(TokenType.RPAREN, "Expected ')' after request type")

        self.consume(TokenType.RETURNS, "Expected 'returns'")

        # Response
        self.consume(TokenType.LPAREN, "Expected '(' before response type")
        server_streaming = False
        if self.match(TokenType.STREAM):
            server_streaming = True

        res_type = self.parse_full_ident()
        self.consume(TokenType.RPAREN, "Expected ')' after response type")

        options = {}
        if self.check(TokenType.LBRACE):
            self.consume(TokenType.LBRACE, "Expected '{'")
            while not self.check(TokenType.RBRACE):
                if self.check(TokenType.OPTION):
                    opt_name, opt_value = self.parse_option_statement()
                    options[opt_name] = opt_value
                elif self.check(TokenType.SEMI):
                    self.advance()
                else:
                    raise self.error("Expected 'option' in method body")
            self.consume(TokenType.RBRACE, "Expected '}'")
            if self.check(TokenType.SEMI):
                self.advance()
        else:
            self.consume(TokenType.SEMI, "Expected ';' or '{' after method signature")

        return ProtoRpcMethod(
            name=name,
            request_type=req_type,
            response_type=res_type,
            client_streaming=client_streaming,
            server_streaming=server_streaming,
            options=options,
            line=start.line,
            column=start.column,
        )

    def parse_option_name(self) -> str:
        if self.match(TokenType.LPAREN):
            ext = self.parse_full_ident().lstrip(".")
            self.consume(TokenType.RPAREN, "Expected ')' after extension name")
            name = ext
            if self.match(TokenType.DOT):
                suffix = self.parse_full_ident()
                name = f"{name}.{suffix}"
            return name
        return self.parse_full_ident()

    def parse_option_value(self) -> object:
        if self.check(TokenType.STRING):
            return self.advance().value
        if self.check(TokenType.TRUE):
            self.advance()
            return True
        if self.check(TokenType.FALSE):
            self.advance()
            return False
        if self.check(TokenType.INT):
            return int(self.advance().value)
        if self.check(TokenType.IDENT):
            return self.advance().value
        token = self.current()
        raise ParseError("Expected option value", token.line, token.column)

    def parse_type_name(self) -> str:
        return self.parse_full_ident()

    def parse_full_ident(self) -> str:
        parts = []
        if self.match(TokenType.DOT):
            parts.append("")
        parts.append(self.consume(TokenType.IDENT, "Expected identifier").value)
        while self.match(TokenType.DOT):
            parts.append(self.consume(TokenType.IDENT, "Expected identifier").value)
        if parts and parts[0] == "":
            return "." + ".".join(parts[1:])
        return ".".join(parts)
