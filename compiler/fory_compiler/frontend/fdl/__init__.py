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

"""FDL frontend."""

from fory_compiler.frontend.base import BaseFrontend, FrontendError
from fory_compiler.ir.ast import Schema
from fory_compiler.frontend.fdl.lexer import Lexer, LexerError
from fory_compiler.frontend.fdl.parser import Parser, ParseError


class FDLFrontend(BaseFrontend):
    """Frontend for Fory Definition Language (.fdl, .idl)."""

    extensions = [".fdl", ".idl"]

    def parse(self, source: str, filename: str = "<input>") -> Schema:
        try:
            lexer = Lexer(source, filename)
            tokens = lexer.tokenize()
            parser = Parser(tokens, filename)
            schema = parser.parse()
        except (LexerError, ParseError) as exc:
            raise FrontendError(exc.message, filename, exc.line, exc.column) from exc
        return schema


__all__ = ["FDLFrontend"]
