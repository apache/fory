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

import pytest

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.java import JavaGenerator
from fory_compiler.ir.validator import SchemaValidator


def generate_java(source: str):
    schema = Parser(Lexer(source).tokenize()).parse()
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    generator = JavaGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    return {item.path: item.content for item in generator.generate()}


def test_union_temporal_and_decimal_case_type_ids():
    files = generate_java(
        """
        package demo;

        union Mixed [id=104] {
            duration dur = 0;
            decimal dec = 1;
        }
        """
    )

    union = files["demo/Mixed.java"]
    assert "return Types.DURATION;" in union
    assert "return Types.DECIMAL;" in union
    assert "this.typeId = Types.DURATION;" in union
    assert "this.typeId = Types.DECIMAL;" in union
    assert "Types.UNKNOWN" not in union


def test_reserved_field_names_are_escaped():
    files = generate_java(
        """
        package demo;

        message Reserved [id=100] {
            string class = 1;
            int32 new = 2;
        }
        """
    )

    reserved = files["demo/Reserved.java"]
    assert "private String class_;" in reserved
    assert "private int new_;" in reserved
    # getClass() would clash with the final Object.getClass().
    assert "public String getClass_()" in reserved
    assert "public void setClass_(String class_)" in reserved
    assert "public int getNew()" in reserved
    assert 'sb.append("class=");' in reserved
    assert "sb.append(class_);" in reserved
    assert "Objects.equals(class_, that.class_)" in reserved
    assert "Objects.hash(class_, new_)" in reserved


def test_union_class_case_accessors_avoid_get_class():
    files = generate_java(
        """
        package demo;

        union Pick [id=105] {
            string class = 0;
        }
        """
    )

    union = files["demo/Pick.java"]
    assert "public String getClass_()" in union
    assert "public void setClass_(String v)" in union
    assert "public boolean hasClass_()" in union
    assert "public static Pick ofClass_(String v)" in union


def test_optional_elements_and_values_use_nullable_type_use():
    files = generate_java(
        """
        package demo;

        message Inner [id=91] { string s = 1; }

        message Holder [id=92] {
            list<optional string> tags = 1;
            list<optional Inner> inners = 2;
            map<string, optional Inner> by_name = 3;
            map<string, optional int64> counts = 4;
        }
        """
    )

    holder = files["demo/Holder.java"]
    assert "import org.apache.fory.annotation.Nullable;" in holder
    assert "private List<@Nullable String> tags;" in holder
    assert "private List<@Nullable @Ref(enable=false) Inner> inners;" in holder
    assert "private Map<String, @Nullable @Ref(enable=false) Inner> byName;" in holder
    assert "private Map<String, @Nullable Long> counts;" in holder


def test_module_qualifies_types_shadowed_by_module_imports():
    files = generate_java(
        """
        package app;

        message Fory [id=1] { string name = 1; }
        message ThreadSafeFory [id=2] { string name = 1; }
        message Holder [id=3] { string name = 1; }
        """
    )

    module = files["app/AppForyModule.java"]
    # Fory and ThreadSafeFory are imported inside the module file, so
    # unqualified references would register the imported classes instead.
    assert "resolver.register(app.Fory.class, 1L);" in module
    assert "resolver.register(app.ThreadSafeFory.class, 2L);" in module
    assert "resolver.register(Holder.class, 3L);" in module


def test_module_rejects_shadowed_type_in_default_package():
    schema = Parser(
        Lexer("message Fory [id=1] { string name = 1; }").tokenize()
    ).parse()
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    generator = JavaGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    with pytest.raises(ValueError, match="Fory"):
        generator.generate()
