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

"""Hand-written lexer for proto3."""

from dataclasses import dataclass
from enum import Enum, auto
from typing import List


class TokenType(Enum):
    """Token types for proto3."""

    # Keywords
    SYNTAX = auto()
    PACKAGE = auto()
    IMPORT = auto()
    PUBLIC = auto()
    WEAK = auto()
    OPTION = auto()
    MESSAGE = auto()
    ENUM = auto()
    ONEOF = auto()
    MAP = auto()
    REPEATED = auto()
    OPTIONAL = auto()
    REQUIRED = auto()
    RESERVED = auto()
    EXTENSIONS = auto()
    SERVICE = auto()
    RPC = auto()
    RETURNS = auto()
    TRUE = auto()
    FALSE = auto()
    TO = auto()
    MAX = auto()
    STREAM = auto()

    # Literals
    IDENT = auto()
    INT = auto()
    STRING = auto()

    # Punctuation
    LBRACE = auto()
    RBRACE = auto()
    LBRACKET = auto()
    RBRACKET = auto()
    LPAREN = auto()
    RPAREN = auto()
    LANGLE = auto()
    RANGLE = auto()
    SEMI = auto()
    COMMA = auto()
    EQUALS = auto()
    DOT = auto()

    EOF = auto()


@dataclass
class Token:
    """A token produced by the lexer."""

    type: TokenType
    value: str
    line: int
    column: int

    def __repr__(self) -> str:
        return f"Token({self.type.name}, {self.value!r}, {self.line}:{self.column})"


class LexerError(Exception):
    """Error during lexing."""

    def __init__(self, message: str, line: int, column: int):
        super().__init__(f"Line {line}, Column {column}: {message}")
        self.message = message
        self.line = line
        self.column = column


class Lexer:
    """Hand-written tokenizer for proto3."""

    KEYWORDS = {
        "syntax": TokenType.SYNTAX,
        "package": TokenType.PACKAGE,
        "import": TokenType.IMPORT,
        "public": TokenType.PUBLIC,
        "weak": TokenType.WEAK,
        "option": TokenType.OPTION,
        "message": TokenType.MESSAGE,
        "enum": TokenType.ENUM,
        "oneof": TokenType.ONEOF,
        "map": TokenType.MAP,
        "repeated": TokenType.REPEATED,
        "optional": TokenType.OPTIONAL,
        "required": TokenType.REQUIRED,
        "reserved": TokenType.RESERVED,
        "extensions": TokenType.EXTENSIONS,
        "service": TokenType.SERVICE,
        "rpc": TokenType.RPC,
        "returns": TokenType.RETURNS,
        "true": TokenType.TRUE,
        "false": TokenType.FALSE,
        "to": TokenType.TO,
        "max": TokenType.MAX,
        "stream": TokenType.STREAM,
    }

    PUNCTUATION = {
        "{": TokenType.LBRACE,
        "}": TokenType.RBRACE,
        "[": TokenType.LBRACKET,
        "]": TokenType.RBRACKET,
        "(": TokenType.LPAREN,
        ")": TokenType.RPAREN,
        "<": TokenType.LANGLE,
        ">": TokenType.RANGLE,
        ";": TokenType.SEMI,
        ",": TokenType.COMMA,
        "=": TokenType.EQUALS,
        ".": TokenType.DOT,
    }

    def __init__(self, source: str, filename: str = "<input>"):
        self.source = source
        self.filename = filename
        self.pos = 0
        self.line = 1
        self.column = 1
        self.line_start = 0

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
            self.line_start = self.pos
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
        self.advance()  # consume '*'
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
        start = self.pos
        while not self.at_end():
            ch = self.peek()
            if ch.isalnum() or ch == "_":
                self.advance()
            else:
                break
        return self.source[start : self.pos]

    def read_number(self) -> str:
        start = self.pos
        if self.peek() == "-":
            self.advance()
        while not self.at_end() and self.peek().isdigit():
            self.advance()
        return self.source[start : self.pos]

    def read_string(self) -> str:
        quote_char = self.advance()
        start_line = self.line
        start_col = self.column - 1
        result = []
        while not self.at_end():
            ch = self.peek()
            if ch == quote_char:
                self.advance()
                return "".join(result)
            if ch == "\\":
                self.advance()
                if self.at_end():
                    raise LexerError("Unterminated string", start_line, start_col)
                escape_ch = self.advance()
                if escape_ch == "n":
                    result.append("\n")
                elif escape_ch == "t":
                    result.append("\t")
                elif escape_ch == "r":
                    result.append("\r")
                else:
                    result.append(escape_ch)
            else:
                result.append(self.advance())
        raise LexerError("Unterminated string", start_line, start_col)

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
                continue

            if ch.isdigit() or ch == "-":
                num = self.read_number()
                tokens.append(Token(TokenType.INT, num, start_line, start_col))
                continue

            if ch in "\"'":
                value = self.read_string()
                tokens.append(Token(TokenType.STRING, value, start_line, start_col))
                continue

            if ch in self.PUNCTUATION:
                token_type = self.PUNCTUATION[ch]
                self.advance()
                tokens.append(Token(token_type, ch, start_line, start_col))
                continue

            raise LexerError(f"Unexpected character '{ch}'", start_line, start_col)

        tokens.append(Token(TokenType.EOF, "", self.line, self.column))
        return tokens
