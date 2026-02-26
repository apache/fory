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

"""Recursive descent parser for FlatBuffers."""

from typing import Dict, List, Optional

from fory_compiler.frontend.fbs.ast import (
    FbsEnum,
    FbsEnumValue,
    FbsField,
    FbsSchema,
    FbsStruct,
    FbsTable,
    FbsUnion,
    FbsTypeName,
    FbsTypeRef,
    FbsVectorType,
    FbsService,
    FbsRpcMethod,
)
from fory_compiler.frontend.fbs.lexer import Token, TokenType


class ParseError(Exception):
    """Error during parsing."""

    def __init__(self, message: str, line: int, column: int):
        self.message = message
        self.line = line
        self.column = column
        super().__init__(f"Line {line}, Column {column}: {message}")


class Parser:
    """Recursive descent parser for FlatBuffers."""

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

    def parse(self) -> FbsSchema:
        namespace: Optional[str] = None
        includes: List[str] = []
        attributes: List[str] = []
        enums: List[FbsEnum] = []
        unions: List[FbsUnion] = []
        tables: List[FbsTable] = []
        tables: List[FbsTable] = []
        structs: List[FbsStruct] = []
        services: List[FbsService] = []
        root_type: Optional[str] = None

        while not self.at_end():
            if self.check(TokenType.NAMESPACE):
                if namespace is not None:
                    raise self.error("Duplicate namespace declaration")
                namespace = self.parse_namespace()
            elif self.check(TokenType.INCLUDE):
                includes.append(self.parse_include())
            elif self.check(TokenType.ATTRIBUTE):
                attributes.append(self.parse_attribute())
            elif self.check(TokenType.ENUM):
                enums.append(self.parse_enum())
            elif self.check(TokenType.TABLE):
                tables.append(self.parse_table())
            elif self.check(TokenType.STRUCT):
                structs.append(self.parse_struct())
            elif self.check(TokenType.ROOT_TYPE):
                root_type = self.parse_root_type()
            elif self.check(TokenType.FILE_IDENTIFIER):
                self.parse_file_identifier()
            elif self.check(TokenType.FILE_EXTENSION):
                self.parse_file_extension()
            elif self.check(TokenType.UNION):
                unions.append(self.parse_union())
            elif self.check(TokenType.SERVICE) or self.check(TokenType.RPC):
                services.append(self.parse_service())
            elif self.check(TokenType.SEMI):
                self.advance()
            else:
                raise self.error(f"Unexpected token: {self.current().value}")

        return FbsSchema(
            namespace=namespace,
            includes=includes,
            attributes=attributes,
            enums=enums,
            unions=unions,
            tables=tables,
            structs=structs,
            services=services,
            root_type=root_type,
            source_file=self.filename,
        )

    def parse_namespace(self) -> str:
        self.consume(TokenType.NAMESPACE, "Expected 'namespace'")
        name = self.parse_qualified_ident()
        self.consume(TokenType.SEMI, "Expected ';' after namespace")
        return name

    def parse_include(self) -> str:
        self.consume(TokenType.INCLUDE, "Expected 'include'")
        path = self.consume(TokenType.STRING, "Expected include path").value
        self.consume(TokenType.SEMI, "Expected ';' after include")
        return path

    def parse_attribute(self) -> str:
        self.consume(TokenType.ATTRIBUTE, "Expected 'attribute'")
        if self.check(TokenType.STRING):
            value = self.advance().value
        else:
            value = self.consume(TokenType.IDENT, "Expected attribute name").value
        self.consume(TokenType.SEMI, "Expected ';' after attribute")
        return value

    def parse_root_type(self) -> str:
        self.consume(TokenType.ROOT_TYPE, "Expected 'root_type'")
        name = self.parse_qualified_ident()
        self.consume(TokenType.SEMI, "Expected ';' after root_type")
        return name

    def parse_file_identifier(self) -> None:
        self.consume(TokenType.FILE_IDENTIFIER, "Expected 'file_identifier'")
        self.consume(TokenType.STRING, "Expected file identifier string")
        self.consume(TokenType.SEMI, "Expected ';' after file_identifier")

    def parse_file_extension(self) -> None:
        self.consume(TokenType.FILE_EXTENSION, "Expected 'file_extension'")
        self.consume(TokenType.STRING, "Expected file extension string")
        self.consume(TokenType.SEMI, "Expected ';' after file_extension")

    def parse_enum(self) -> FbsEnum:
        start = self.current()
        self.consume(TokenType.ENUM, "Expected 'enum'")
        name = self.consume(TokenType.IDENT, "Expected enum name").value
        base_type = None
        if self.match(TokenType.COLON):
            base_type = self.consume(TokenType.IDENT, "Expected enum base type").value
        attributes = self.parse_metadata()
        self.consume(TokenType.LBRACE, "Expected '{' after enum name")

        values: List[FbsEnumValue] = []
        next_value = 0
        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.COMMA):
                self.advance()
                continue
            name_token = self.consume(TokenType.IDENT, "Expected enum value name")
            value = next_value
            if self.match(TokenType.EQUALS):
                value_token = self.consume(TokenType.INT, "Expected integer enum value")
                value = int(value_token.value, 0)
            values.append(
                FbsEnumValue(
                    name=name_token.value,
                    value=value,
                    line=name_token.line,
                    column=name_token.column,
                )
            )
            next_value = value + 1
            if self.match(TokenType.COMMA):
                continue
            if self.check(TokenType.RBRACE):
                break
            self.consume(TokenType.COMMA, "Expected ',' or '}' after enum value")

        self.consume(TokenType.RBRACE, "Expected '}' after enum")
        if self.check(TokenType.SEMI):
            self.advance()
        return FbsEnum(
            name=name,
            base_type=base_type,
            values=values,
            attributes=attributes,
            line=start.line,
            column=start.column,
        )

    def parse_table(self) -> FbsTable:
        start = self.current()
        self.consume(TokenType.TABLE, "Expected 'table'")
        name = self.consume(TokenType.IDENT, "Expected table name").value
        attributes = self.parse_metadata()
        fields = self.parse_fields()
        return FbsTable(
            name=name,
            fields=fields,
            attributes=attributes,
            line=start.line,
            column=start.column,
        )

    def parse_struct(self) -> FbsStruct:
        start = self.current()
        self.consume(TokenType.STRUCT, "Expected 'struct'")
        name = self.consume(TokenType.IDENT, "Expected struct name").value
        attributes = self.parse_metadata()
        fields = self.parse_fields()
        return FbsStruct(
            name=name,
            fields=fields,
            attributes=attributes,
            line=start.line,
            column=start.column,
        )

    def parse_union(self) -> FbsUnion:
        start = self.current()
        self.consume(TokenType.UNION, "Expected 'union'")
        name = self.consume(TokenType.IDENT, "Expected union name").value
        attributes = self.parse_metadata()
        self.consume(TokenType.LBRACE, "Expected '{' after union name")

        types: List[str] = []
        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.COMMA):
                self.advance()
                continue
            type_name = self.parse_qualified_ident()
            types.append(type_name)
            if self.match(TokenType.COMMA):
                continue
            if self.check(TokenType.RBRACE):
                break
            self.consume(TokenType.COMMA, "Expected ',' or '}' after union type")

        self.consume(TokenType.RBRACE, "Expected '}' after union")
        if self.check(TokenType.SEMI):
            self.advance()
        return FbsUnion(
            name=name,
            types=types,
            attributes=attributes,
            line=start.line,
            column=start.column,
        )

    def parse_fields(self) -> List[FbsField]:
        self.consume(TokenType.LBRACE, "Expected '{'")
        fields: List[FbsField] = []
        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.SEMI):
                self.advance()
                continue
            fields.append(self.parse_field())
        self.consume(TokenType.RBRACE, "Expected '}'")
        if self.check(TokenType.SEMI):
            self.advance()
        return fields

    def parse_field(self) -> FbsField:
        name_token = self.consume(TokenType.IDENT, "Expected field name")
        self.consume(TokenType.COLON, "Expected ':' after field name")
        field_type = self.parse_type()
        default: Optional[object] = None
        if self.match(TokenType.EQUALS):
            default = self.parse_value()
        attributes = self.parse_metadata()
        self.consume(TokenType.SEMI, "Expected ';' after field")
        return FbsField(
            name=name_token.value,
            field_type=field_type,
            default=default,
            attributes=attributes,
            line=name_token.line,
            column=name_token.column,
        )

    def parse_type(self) -> FbsTypeRef:
        if self.match(TokenType.LBRACKET):
            element_type = self.parse_type()
            end = self.consume(TokenType.RBRACKET, "Expected ']' after vector type")
            return FbsVectorType(
                element_type=element_type, line=end.line, column=end.column
            )
        name_token = self.consume(TokenType.IDENT, "Expected type name")
        name = name_token.value
        if self.check(TokenType.DOT):
            name = self.parse_qualified_ident(start=name_token)
        return FbsTypeName(name=name, line=name_token.line, column=name_token.column)

    def parse_qualified_ident(self, start: Optional[Token] = None) -> str:
        parts: List[str] = []
        if start is not None:
            parts.append(start.value)
        else:
            parts.append(self.consume(TokenType.IDENT, "Expected identifier").value)
        while self.match(TokenType.DOT):
            parts.append(self.consume(TokenType.IDENT, "Expected identifier").value)
        return ".".join(parts)

    def parse_metadata(self) -> Dict[str, object]:
        if not self.match(TokenType.LPAREN):
            return {}
        attributes: Dict[str, object] = {}
        if self.match(TokenType.RPAREN):
            return attributes

        while True:
            name = self.consume(TokenType.IDENT, "Expected attribute name").value
            if name.startswith("fory_"):
                name = name.removeprefix("fory_")
            elif name.startswith("fory."):
                name = name.removeprefix("fory.")
            value: object = True
            if self.match(TokenType.COLON):
                value = self.parse_value()
            attributes[name] = value
            if self.match(TokenType.COMMA):
                if self.check(TokenType.RPAREN):
                    break
                continue
            break

        self.consume(TokenType.RPAREN, "Expected ')' after attributes")
        return attributes

    def parse_value(self) -> object:
        if self.match(TokenType.TRUE):
            return True
        if self.match(TokenType.FALSE):
            return False
        if self.check(TokenType.INT):
            value = self.advance().value
            try:
                return int(value, 0)
            except ValueError:
                return int(value)
        if self.check(TokenType.FLOAT):
            return float(self.advance().value)
        if self.check(TokenType.STRING):
            return self.advance().value
        if self.check(TokenType.IDENT):
            return self.advance().value
        raise self.error("Expected value")

    def parse_service(self) -> FbsService:
        start = self.current()
        # Support both 'service' and 'rpc_service' keywords for defining services.
        if self.check(TokenType.SERVICE):
            self.advance()
        elif self.check(TokenType.RPC):
            # 'rpc_service' is mapped to TokenType.SERVICE in the lexer,
            # but we also check for separate 'rpc' token just in case.
            self.advance()
            if self.check(TokenType.SERVICE):
                self.advance()
        else:
            raise self.error("Expected 'service' or 'rpc_service'")

        name = self.consume(TokenType.IDENT, "Expected service name").value
        attributes = self.parse_metadata()
        self.consume(TokenType.LBRACE, "Expected '{' after service name")

        methods: List[FbsRpcMethod] = []
        while not self.check(TokenType.RBRACE):
            if self.check(TokenType.SEMI):
                self.advance()
                continue
            methods.append(self.parse_rpc_method())

        self.consume(TokenType.RBRACE, "Expected '}' after service body")
        if self.check(TokenType.SEMI):
            self.advance()

        return FbsService(
            name=name,
            methods=methods,
            attributes=attributes,
            line=start.line,
            column=start.column,
        )

    def parse_rpc_method(self) -> FbsRpcMethod:
        # Parse method signature: name(RequestType):ResponseType;
        start = self.current()
        name = self.consume(TokenType.IDENT, "Expected method name").value

        self.consume(TokenType.LPAREN, "Expected '(' after method name")
        # Parsing request type. FBS allows type name here.
        req_type = self.parse_qualified_ident()
        self.consume(TokenType.RPAREN, "Expected ')' after request type")

        self.consume(TokenType.COLON, "Expected ':' before response type")
        res_type = self.parse_qualified_ident()

        attributes = self.parse_metadata()
        self.consume(TokenType.SEMI, "Expected ';' after method declaration")

        return FbsRpcMethod(
            name=name,
            request_type=req_type,
            response_type=res_type,
            attributes=attributes,
            line=start.line,
            column=start.column,
        )
