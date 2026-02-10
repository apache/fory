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

"""Hand-written lexer for FDL."""

from dataclasses import dataclass
from enum import Enum, auto
from typing import List


class TokenType(Enum):
    """Token types for FDL."""

    # Keywords
    PACKAGE = auto()
    IMPORT = auto()
    PUBLIC = auto()
    WEAK = auto()
    MESSAGE = auto()
    ENUM = auto()
    UNION = auto()
    OPTIONAL = auto()
    REF = auto()
    REPEATED = auto()
    LIST = auto()
    MAP = auto()
    OPTION = auto()
    TRUE = auto()
    FALSE = auto()
    RESERVED = auto()
    TO = auto()
    MAX = auto()
    SERVICE = auto()
    RPC = auto()
    RETURNS = auto()
    STREAM = auto()

    # Literals
    IDENT = auto()
    INT = auto()
    STRING = auto()  # "quoted string"

    # Punctuation
    LBRACE = auto()  # {
    RBRACE = auto()  # }
    LBRACKET = auto()  # [
    RBRACKET = auto()  # ]
    LPAREN = auto()  # (
    RPAREN = auto()  # )
    LANGLE = auto()  # <
    RANGLE = auto()  # >
    SEMI = auto()  # ;
    COMMA = auto()  # ,
    EQUALS = auto()  # =
    DOT = auto()  # .

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
        self.message = message
        self.line = line
        self.column = column
        super().__init__(f"Line {line}, Column {column}: {message}")


class Lexer:
    """Hand-written tokenizer for FDL."""

    KEYWORDS = {
        "package": TokenType.PACKAGE,
        "import": TokenType.IMPORT,
        "public": TokenType.PUBLIC,
        "weak": TokenType.WEAK,
        "message": TokenType.MESSAGE,
        "enum": TokenType.ENUM,
        "union": TokenType.UNION,
        "optional": TokenType.OPTIONAL,
        "ref": TokenType.REF,
        "repeated": TokenType.REPEATED,
        "list": TokenType.LIST,
        "map": TokenType.MAP,
        "option": TokenType.OPTION,
        "true": TokenType.TRUE,
        "false": TokenType.FALSE,
        "reserved": TokenType.RESERVED,
        "to": TokenType.TO,
        "max": TokenType.MAX,
        "service": TokenType.SERVICE,
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
        """Check if we've reached the end of input."""
        return self.pos >= len(self.source)

    def peek(self, offset: int = 0) -> str:
        """Peek at a character without consuming it."""
        pos = self.pos + offset
        if pos >= len(self.source):
            return "\0"
        return self.source[pos]

    def advance(self) -> str:
        """Consume and return the current character."""
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

    def skip_whitespace(self):
        """Skip whitespace characters."""
        while not self.at_end() and self.peek() in " \t\r\n":
            self.advance()

    def skip_line_comment(self):
        """Skip a // comment."""
        while not self.at_end() and self.peek() != "\n":
            self.advance()

    def skip_block_comment(self):
        """Skip a /* */ comment."""
        start_line = self.line
        start_col = self.column
        self.advance()  # consume *
        while not self.at_end():
            if self.peek() == "*" and self.peek(1) == "/":
                self.advance()  # consume *
                self.advance()  # consume /
                return
            self.advance()
        raise LexerError("Unterminated block comment", start_line, start_col)

    def skip_whitespace_and_comments(self):
        """Skip whitespace and comments."""
        while not self.at_end():
            ch = self.peek()
            if ch in " \t\r\n":
                self.skip_whitespace()
            elif ch == "/" and self.peek(1) == "/":
                self.advance()  # consume first /
                self.advance()  # consume second /
                self.skip_line_comment()
            elif ch == "/" and self.peek(1) == "*":
                self.advance()  # consume /
                self.skip_block_comment()
            else:
                break

    def read_identifier(self) -> str:
        """Read an identifier."""
        start = self.pos
        while not self.at_end():
            ch = self.peek()
            if ch.isalnum() or ch == "_":
                self.advance()
            else:
                break
        return self.source[start : self.pos]

    def read_number(self) -> str:
        """Read an integer literal."""
        start = self.pos
        # Handle negative numbers
        if self.peek() == "-":
            self.advance()
        while not self.at_end() and self.peek().isdigit():
            self.advance()
        return self.source[start : self.pos]

    def read_string(self) -> str:
        """Read a quoted string literal."""
        quote_char = self.advance()  # consume opening quote
        start_line = self.line
        start_col = self.column - 1
        result = []

        while not self.at_end():
            ch = self.peek()
            if ch == quote_char:
                self.advance()  # consume closing quote
                return "".join(result)
            elif ch == "\\":
                self.advance()  # consume backslash
                if self.at_end():
                    raise LexerError("Unterminated string", start_line, start_col)
                escape_ch = self.advance()
                if escape_ch == "n":
                    result.append("\n")
                elif escape_ch == "t":
                    result.append("\t")
                elif escape_ch == "\\":
                    result.append("\\")
                elif escape_ch == quote_char:
                    result.append(quote_char)
                else:
                    result.append(escape_ch)
            elif ch == "\n":
                raise LexerError(
                    "Unterminated string (newline in string)", start_line, start_col
                )
            else:
                result.append(self.advance())

        raise LexerError("Unterminated string", start_line, start_col)

    def next_token(self) -> Token:
        """Read the next token."""
        self.skip_whitespace_and_comments()

        if self.at_end():
            return Token(TokenType.EOF, "", self.line, self.column)

        line = self.line
        column = self.column
        ch = self.peek()

        # String literal
        if ch == '"' or ch == "'":
            value = self.read_string()
            return Token(TokenType.STRING, value, line, column)

        # Punctuation
        if ch in self.PUNCTUATION:
            self.advance()
            return Token(self.PUNCTUATION[ch], ch, line, column)

        # Identifier or keyword
        if ch.isalpha() or ch == "_":
            ident = self.read_identifier()
            token_type = self.KEYWORDS.get(ident, TokenType.IDENT)
            return Token(token_type, ident, line, column)

        # Integer literal (including negative)
        if ch.isdigit() or (ch == "-" and self.peek(1).isdigit()):
            value = self.read_number()
            return Token(TokenType.INT, value, line, column)

        raise LexerError(f"Unexpected character: '{ch}'", line, column)

    def tokenize(self) -> List[Token]:
        """Tokenize the entire input and return a list of tokens."""
        tokens = []
        while True:
            token = self.next_token()
            tokens.append(token)
            if token.type == TokenType.EOF:
                break
        return tokens
