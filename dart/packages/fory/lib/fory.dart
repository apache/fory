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

/** --------------- Export Statements --------------- **/

// Base annotations
export 'src/annotation/fory_key.dart';
export 'src/annotation/fory_class.dart';
export 'src/annotation/fory_enum.dart';
export 'src/annotation/fory_constructor.dart';
export 'src/annotation/uint_types.dart';

// Analysis meta
export 'src/meta/specs/type_spec.dart';
export 'src/meta/specs/field_spec.dart';
export 'src/meta/specs/field_type_spec.dart';
export 'src/meta/specs/enum_spec.dart';

// Fory implementation
export 'src/fory_impl.dart';
export 'src/config/fory_config.dart';

// Constants
export 'src/const/types.dart';
export 'src/resolver/spec_lookup.dart';

// User-related
export 'src/fory_type_provider.dart';

// Serialization internals for custom serializer implementations.
export 'src/serialization_context.dart';
export 'src/deserialization_context.dart';

// External packages
export 'package:decimal/decimal.dart';
export 'package:collection/collection.dart';

// Fory data types
export 'src/datatype/fory_fixed_num.dart';
export 'src/datatype/int8.dart';
export 'src/datatype/int16.dart';
export 'src/datatype/int32.dart';
export 'src/datatype/uint8.dart';
export 'src/datatype/uint16.dart';
export 'src/datatype/uint32.dart';
export 'src/datatype/float32.dart';
export 'src/datatype/local_date.dart';
export 'src/datatype/timestamp.dart';

// Serializers
export 'src/serializer/serializer.dart';

// Memory management
export 'src/memory/byte_reader.dart';
export 'src/memory/byte_writer.dart';
