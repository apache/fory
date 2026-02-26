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

"""Hand-written lexer for FlatBuffers schemas."""

from dataclasses import dataclass
from enum import Enum, auto
from typing import List


class TokenType(Enum):
    """Token types for FlatBuffers."""

    # Keywords
    NAMESPACE = auto()
    TABLE = auto()
    STRUCT = auto()
    ENUM = auto()
    UNION = auto()
    ROOT_TYPE = auto()
    ATTRIBUTE = auto()
    INCLUDE = auto()
    FILE_IDENTIFIER = auto()
    FILE_EXTENSION = auto()
    TRUE = auto()
    FALSE = auto()
    SERVICE = auto()
    RPC = auto()
    RETURNS = auto()
    STREAM = auto()

    # Literals
    IDENT = auto()
    INT = auto()
    FLOAT = auto()
    STRING = auto()

    # Punctuation
    LBRACE = auto()
    RBRACE = auto()
    LBRACKET = auto()
    RBRACKET = auto()
    LPAREN = auto()
    RPAREN = auto()
    SEMI = auto()
    COMMA = auto()
    EQUALS = auto()
    COLON = auto()
    DOT = auto()

    EOF = auto()


@dataclass
class Token:
    """A token produced by the lexer."""

    type: TokenType
    value: str
    line: int
    column: int


class LexerError(Exception):
    """Error during lexing."""

    def __init__(self, message: str, line: int, column: int):
        self.message = message
        self.line = line
        self.column = column
        super().__init__(f"Line {line}, Column {column}: {message}")


class Lexer:
    """Hand-written tokenizer for FlatBuffers."""

    KEYWORDS = {
        "namespace": TokenType.NAMESPACE,
        "table": TokenType.TABLE,
        "struct": TokenType.STRUCT,
        "enum": TokenType.ENUM,
        "union": TokenType.UNION,
        "root_type": TokenType.ROOT_TYPE,
        "attribute": TokenType.ATTRIBUTE,
        "include": TokenType.INCLUDE,
        "file_identifier": TokenType.FILE_IDENTIFIER,
        "file_extension": TokenType.FILE_EXTENSION,
        "true": TokenType.TRUE,
        "false": TokenType.FALSE,
        "service": TokenType.SERVICE,
        "rpc_service": TokenType.SERVICE,
        "rpc": TokenType.RPC,
        "returns": TokenType.RETURNS,
        "stream": TokenType.STREAM,
    }

    PUNCTUATION = {
        "{": TokenType.LBRACE,
        "}": TokenType.RBRACE,
        "[": TokenType.LBRACKET,
        "]": TokenType.RBRACKET,
        "(": TokenType.LPAREN,
        ")": TokenType.RPAREN,
        ";": TokenType.SEMI,
        ",": TokenType.COMMA,
        "=": TokenType.EQUALS,
        ":": TokenType.COLON,
        ".": TokenType.DOT,
    }

    def __init__(self, source: str, filename: str = "<input>"):
        self.source = source
        self.filename = filename
        self.pos = 0
        self.line = 1
        self.column = 1

    def at_end(self) -> bool:
        return self.pos >= len(self.source)

    def peek(self, offset: int = 0) -> str:
        pos = self.pos + offset
        if pos >= len(self.source):
            return "\0"
        return self.source[pos]

    def advance(self) -> str:
        if self.at_end():
            return "\0"
        ch = self.source[self.pos]
        self.pos += 1
        if ch == "\n":
            self.line += 1
            self.column = 1
        else:
            self.column += 1
        return ch

    def skip_whitespace(self) -> None:
        while not self.at_end() and self.peek() in " \t\r\n":
            self.advance()

    def skip_line_comment(self) -> None:
        while not self.at_end() and self.peek() != "\n":
            self.advance()

    def skip_block_comment(self) -> None:
        start_line = self.line
        start_col = self.column
        self.advance()  # consume *
        while not self.at_end():
            if self.peek() == "*" and self.peek(1) == "/":
                self.advance()
                self.advance()
                return
            self.advance()
        raise LexerError("Unterminated block comment", start_line, start_col)

    def skip_whitespace_and_comments(self) -> None:
        while not self.at_end():
            ch = self.peek()
            if ch in " \t\r\n":
                self.skip_whitespace()
            elif ch == "/" and self.peek(1) == "/":
                self.advance()
                self.advance()
                self.skip_line_comment()
            elif ch == "/" and self.peek(1) == "*":
                self.advance()
                self.skip_block_comment()
            else:
                break

    def read_identifier(self) -> str:
        value = ""
        while self.peek().isalnum() or self.peek() == "_":
            value += self.advance()
        return value

    def read_string(self) -> str:
        value = ""
        start_line = self.line
        start_col = self.column
        self.advance()  # opening quote
        while not self.at_end() and self.peek() != '"':
            if self.peek() == "\\":
                self.advance()
                escaped = self.advance()
                if escaped == "n":
                    value += "\n"
                elif escaped == "t":
                    value += "\t"
                elif escaped == '"':
                    value += '"'
                elif escaped == "\\":
                    value += "\\"
                else:
                    value += escaped
            else:
                value += self.advance()
        if self.at_end():
            raise LexerError("Unterminated string literal", start_line, start_col)
        self.advance()  # closing quote
        return value

    def read_number(self) -> tuple[TokenType, str]:
        value = ""
        if self.peek() == "-":
            value += self.advance()
        if self.peek() == "0" and self.peek(1) in "xX":
            value += self.advance()
            value += self.advance()
            while self.peek().lower() in "0123456789abcdef":
                value += self.advance()
            return TokenType.INT, value

        while self.peek().isdigit():
            value += self.advance()

        token_type = TokenType.INT
        if self.peek() == "." and self.peek(1).isdigit():
            token_type = TokenType.FLOAT
            value += self.advance()
            while self.peek().isdigit():
                value += self.advance()

        if self.peek() in "eE":
            token_type = TokenType.FLOAT
            value += self.advance()
            if self.peek() in "+-":
                value += self.advance()
            while self.peek().isdigit():
                value += self.advance()

        return token_type, value

    def tokenize(self) -> List[Token]:
        tokens: List[Token] = []

        while not self.at_end():
            self.skip_whitespace_and_comments()
            if self.at_end():
                break

            start_line = self.line
            start_col = self.column
            ch = self.peek()

            if ch.isalpha() or ch == "_":
                ident = self.read_identifier()
                token_type = self.KEYWORDS.get(ident, TokenType.IDENT)
                tokens.append(Token(token_type, ident, start_line, start_col))
            elif ch.isdigit() or (ch == "-" and self.peek(1).isdigit()):
                token_type, value = self.read_number()
                tokens.append(Token(token_type, value, start_line, start_col))
            elif ch == '"':
                value = self.read_string()
                tokens.append(Token(TokenType.STRING, value, start_line, start_col))
            elif ch in self.PUNCTUATION:
                token_type = self.PUNCTUATION[ch]
                self.advance()
                tokens.append(Token(token_type, ch, start_line, start_col))
            else:
                raise LexerError(f"Unexpected character '{ch}'", start_line, start_col)

        tokens.append(Token(TokenType.EOF, "", self.line, self.column))
        return tokens
