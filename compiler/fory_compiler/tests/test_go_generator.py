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

from pathlib import Path

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.go import GoGenerator
from fory_compiler.ir.validator import SchemaValidator


def parse_schema(source: str):
    schema = Parser(Lexer(source).tokenize()).parse()
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    return schema


def generate_go(source: str) -> str:
    schema = parse_schema(source)
    generator = GoGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    files = generator.generate()
    return "\n".join(file.content for file in files)


def test_time_import_only_for_timestamp_and_duration():
    date_content = generate_go(
        """
        package demo;

        message Calendar {
            date day = 1;
        }
        """
    )
    assert "fory.Date" in date_content
    assert '"time"' not in date_content

    clock_content = generate_go(
        """
        package demo;

        message Clock {
            timestamp at = 1;
            duration span = 2;
        }
        """
    )
    assert '"time"' in clock_content
    assert "time.Time" in clock_content
    assert "time.Duration" in clock_content
