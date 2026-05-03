/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

library;

enum Encoding { fixed, varint, tagged }

abstract class TypeSpec {
  final bool? nullable;
  final bool? ref;
  final bool? dynamic;

  const TypeSpec({
    this.nullable,
    this.ref,
    this.dynamic,
  });
}

abstract class ScalarTypeSpec extends TypeSpec {
  const ScalarTypeSpec({
    super.nullable,
    super.ref,
    super.dynamic,
  });
}

final class DeclaredType extends TypeSpec {
  const DeclaredType({
    super.nullable,
    super.ref,
    super.dynamic,
  });
}

final class BoolType extends ScalarTypeSpec {
  const BoolType({super.nullable, super.ref, super.dynamic});
}

final class Int8Type extends ScalarTypeSpec {
  const Int8Type({super.nullable, super.ref, super.dynamic});
}

final class Int16Type extends ScalarTypeSpec {
  const Int16Type({super.nullable, super.ref, super.dynamic});
}

final class Int32Type extends ScalarTypeSpec {
  final Encoding? encoding;

  const Int32Type({
    this.encoding,
    super.nullable,
    super.ref,
    super.dynamic,
  });
}

final class Int64Type extends ScalarTypeSpec {
  final Encoding? encoding;

  const Int64Type({
    this.encoding,
    super.nullable,
    super.ref,
    super.dynamic,
  });
}

final class Uint8Type extends ScalarTypeSpec {
  const Uint8Type({super.nullable, super.ref, super.dynamic});
}

final class Uint16Type extends ScalarTypeSpec {
  const Uint16Type({super.nullable, super.ref, super.dynamic});
}

final class Uint32Type extends ScalarTypeSpec {
  final Encoding? encoding;

  const Uint32Type({
    this.encoding,
    super.nullable,
    super.ref,
    super.dynamic,
  });
}

final class Uint64Type extends ScalarTypeSpec {
  final Encoding? encoding;

  const Uint64Type({
    this.encoding,
    super.nullable,
    super.ref,
    super.dynamic,
  });
}

final class Float16Type extends ScalarTypeSpec {
  const Float16Type({super.nullable, super.ref, super.dynamic});
}

final class Bfloat16Type extends ScalarTypeSpec {
  const Bfloat16Type({super.nullable, super.ref, super.dynamic});
}

final class Float32Type extends ScalarTypeSpec {
  const Float32Type({super.nullable, super.ref, super.dynamic});
}

final class Float64Type extends ScalarTypeSpec {
  const Float64Type({super.nullable, super.ref, super.dynamic});
}

final class StringType extends ScalarTypeSpec {
  const StringType({super.nullable, super.ref, super.dynamic});
}

final class DecimalType extends ScalarTypeSpec {
  const DecimalType({super.nullable, super.ref, super.dynamic});
}

final class TimestampType extends ScalarTypeSpec {
  const TimestampType({super.nullable, super.ref, super.dynamic});
}

final class DateType extends ScalarTypeSpec {
  const DateType({super.nullable, super.ref, super.dynamic});
}

final class DurationType extends ScalarTypeSpec {
  const DurationType({super.nullable, super.ref, super.dynamic});
}

final class BinaryType extends ScalarTypeSpec {
  const BinaryType({super.nullable, super.ref, super.dynamic});
}

final class ListType extends TypeSpec {
  final TypeSpec? element;

  const ListType({
    this.element,
    super.nullable,
    super.ref,
    super.dynamic,
  });
}

final class ArrayType extends TypeSpec {
  final TypeSpec element;

  const ArrayType({
    required this.element,
    super.nullable,
    super.ref,
    super.dynamic,
  });
}

final class SetType extends TypeSpec {
  final TypeSpec? element;

  const SetType({
    this.element,
    super.nullable,
    super.ref,
    super.dynamic,
  });
}

final class MapType extends TypeSpec {
  final TypeSpec? key;
  final TypeSpec? value;

  const MapType({
    this.key,
    this.value,
    super.nullable,
    super.ref,
    super.dynamic,
  });
}
