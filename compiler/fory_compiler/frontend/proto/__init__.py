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

"""Proto frontend."""

import sys
from typing import List, Optional

from fory_compiler.frontend.base import BaseFrontend, FrontendError
from fory_compiler.frontend.proto.ast import ProtoSchema
from fory_compiler.frontend.proto.lexer import Lexer, LexerError
from fory_compiler.frontend.proto.parser import Parser, ParseError
from fory_compiler.frontend.proto.translator import ProtoTranslator
from fory_compiler.ir.ast import Schema


class ProtoFrontend(BaseFrontend):
    """Frontend for Protocol Buffers (.proto)."""

    extensions = [".proto"]

    def parse(self, source: str, filename: str = "<input>") -> Schema:
        return self.parse_with_imports(source, filename)

    def parse_ast(self, source: str, filename: str = "<input>") -> ProtoSchema:
        """Parse proto source into a proto AST without translating to Fory IR."""
        try:
            lexer = Lexer(source, filename)
            tokens = lexer.tokenize()
            parser = Parser(tokens, filename)
            return parser.parse()
        except (LexerError, ParseError) as exc:
            raise FrontendError(exc.message, filename, exc.line, exc.column) from exc

    def parse_with_imports(
        self,
        source: str,
        filename: str = "<input>",
        direct_import_proto_schemas: Optional[List[ProtoSchema]] = None,
    ) -> Schema:
        """Parse proto source and translate to Fory IR.

        `direct_import_proto_schemas` supplies the proto ASTs of **directly**
        imported files so the translator can resolve cross-file type references
        and enforce import-visibility rules.
        """
        proto_schema = self.parse_ast(source, filename)
        translator = ProtoTranslator(proto_schema, direct_import_proto_schemas)
        schema = translator.translate()

        for warning in translator.warnings:
            print(warning, file=sys.stderr)

        return schema


__all__ = ["ProtoFrontend", "Lexer", "Parser", "LexerError", "ParseError"]
