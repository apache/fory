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

import 'package:checks/checks.dart';
import 'package:fory/src/collection/stack.dart';
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/deserialization_dispatcher.dart';
import 'package:fory/src/deserialization_context.dart';
import 'package:fory/src/fory_exception.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/resolver/deserialization_ref_resolver.dart';
import 'package:fory/src/resolver/meta_string_writing_resolver.dart';
import 'package:fory/src/resolver/serialization_ref_resolver.dart';
import 'package:fory/src/resolver/struct_hash_resolver.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serialization_dispatcher.dart';
import 'package:fory/src/serialization_context.dart';
import 'package:fory/src/serializer/enum_serializer.dart';
import 'package:fory_test/entity/enum_id_foo.dart';
import 'package:test/test.dart';

String _unusedTagLookup(Type _) => '';

final ForyConfig _config = ForyConfig();
final TypeResolver _typeResolver = TypeResolver.newOne(_config);

SerializationContext _newSerializationContext() {
  return SerializationContext(
    StructHashResolver.inst,
    _unusedTagLookup,
    SerializationDispatcher.I,
    _typeResolver,
    SerializationRefResolver.getOne(false),
    SerializationRefResolver.noRefResolver,
    MetaStringWritingResolver.newInst,
    Stack<TypeSpecWrap>(),
  );
}

DeserializationContext _newDeserializationContext() {
  return DeserializationContext(
    StructHashResolver.inst,
    _unusedTagLookup,
    _config,
    (isXLang: true, oobEnabled: false),
    DeserializationDispatcher.I,
    DeserializationRefResolver.getOne(false),
    _typeResolver,
    Stack<TypeSpecWrap>(),
  );
}

void main() {
  group('Enum serializer', () {
    test('writes and reads annotated enum ids when all values are annotated',
        () {
      final EnumSerializer serializer = EnumSerializer(false, [
        EnumWithIds.A,
        EnumWithIds.B,
        EnumWithIds.C,
      ], {
        10: EnumWithIds.A,
        20: EnumWithIds.B,
        30: EnumWithIds.C,
      });

      final ByteWriter writer = ByteWriter();
      serializer.write(
        writer,
        EnumWithIds.B,
        _newSerializationContext(),
      );
      final ByteReader encodedIdReader =
          ByteReader.forBytes(writer.takeBytes());
      check(encodedIdReader.readVarUint32Small7()).equals(20);

      final ByteWriter idWriter = ByteWriter();
      idWriter.writeVarUint32Small7(30);
      final Enum value = serializer.read(
        ByteReader.forBytes(idWriter.takeBytes()),
        0,
        _newDeserializationContext(),
      );
      check(value).equals(EnumWithIds.C);
    });

    test('falls back to ordinal serialization when id mapping is absent', () {
      final EnumSerializer serializer = EnumSerializer(false, [
        EnumPartialIds.A,
        EnumPartialIds.B,
        EnumPartialIds.C,
      ]);

      final ByteWriter writer = ByteWriter();
      serializer.write(
        writer,
        EnumPartialIds.B,
        _newSerializationContext(),
      );
      final ByteReader encodedIdReader =
          ByteReader.forBytes(writer.takeBytes());
      check(encodedIdReader.readVarUint32Small7()).equals(1);
    });

    test('throws on unknown annotated enum id', () {
      final EnumSerializer serializer = EnumSerializer(false, [
        EnumWithIds.A,
        EnumWithIds.B,
        EnumWithIds.C,
      ], {
        10: EnumWithIds.A,
        20: EnumWithIds.B,
        30: EnumWithIds.C,
      });

      final ByteWriter writer = ByteWriter();
      writer.writeVarUint32Small7(99);

      check(
        () => serializer.read(
          ByteReader.forBytes(writer.takeBytes()),
          0,
          _newDeserializationContext(),
        ),
      ).throws<DeserializationRangeException>();
    });

    test('writes and reads field-based enum ids', () {
      final EnumSerializer serializer = EnumSerializer(false, [
        EnumFieldBasedIds.A,
        EnumFieldBasedIds.B,
        EnumFieldBasedIds.C,
      ], {
        10: EnumFieldBasedIds.A,
        20: EnumFieldBasedIds.B,
        30: EnumFieldBasedIds.C,
      });

      final ByteWriter writer = ByteWriter();
      serializer.write(writer, EnumFieldBasedIds.B, _newSerializationContext());
      final ByteReader reader = ByteReader.forBytes(writer.takeBytes());
      check(reader.readVarUint32Small7()).equals(20);

      final ByteWriter idWriter = ByteWriter();
      idWriter.writeVarUint32Small7(30);
      final Enum value = serializer.read(
        ByteReader.forBytes(idWriter.takeBytes()),
        0,
        _newDeserializationContext(),
      );
      check(value).equals(EnumFieldBasedIds.C);
    });

    test('field-based duplicate ids fall back to ordinal', () {
      final EnumSerializer serializer = EnumSerializer(false, [
        EnumFieldBasedDuplicateIds.A,
        EnumFieldBasedDuplicateIds.B,
        EnumFieldBasedDuplicateIds.C,
      ]);

      final ByteWriter writer = ByteWriter();
      serializer.write(
        writer,
        EnumFieldBasedDuplicateIds.B,
        _newSerializationContext(),
      );
      final ByteReader reader = ByteReader.forBytes(writer.takeBytes());
      check(reader.readVarUint32Small7()).equals(1);
    });

    test('throws when enum id is outside unsigned 32-bit range', () {
      final EnumSerializer serializer = EnumSerializer(false, [
        EnumWithIds.A,
      ], {
        -1: EnumWithIds.A,
      });

      final ByteWriter writer = ByteWriter();
      check(
        () => serializer.write(
          writer,
          EnumWithIds.A,
          _newSerializationContext(),
        ),
      ).throws<RangeError>();
    });
  });
}
