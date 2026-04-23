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

/// Public API for the Apache Fory Dart xlang runtime.
///
/// Most applications only need [Fory], [Config], [ForyStruct], and [ForyField].
/// [Buffer], [WriteContext], [ReadContext], and [Serializer] are advanced APIs
/// used by generated code, custom serializers, and low-level integrations.
// ignore_for_file: invalid_export_of_internal_element
library;

export 'src/annotation/fory_field.dart';
export 'src/annotation/fory_struct.dart';
export 'src/annotation/fory_union.dart';
export 'src/annotation/type_spec.dart';
export 'src/annotation/numeric_types.dart';
export 'src/memory/buffer.dart'
    hide
        bufferByteData,
        bufferBytes,
        bufferReserveBytes,
        bufferSetReaderIndex,
        bufferSetWriterIndex,
        bufferWriteUint8At,
        bufferReaderIndex,
        bufferWriterIndex;
export 'src/codegen/generated_support.dart';
export 'src/config.dart';
export 'src/context/read_context.dart';
export 'src/context/write_context.dart';
export 'src/fory.dart';
export 'src/meta/type_ids.dart';
export 'src/serializer/enum_serializer.dart';
export 'src/serializer/serializer.dart';
export 'src/serializer/union_serializer.dart';
export 'src/types/bfloat16.dart';
export 'src/types/decimal.dart';
export 'src/types/float16.dart';
export 'src/types/float32.dart';
export 'src/types/int16.dart';
export 'src/types/int32.dart';
export 'src/types/int64.dart';
export 'src/types/int8.dart';
export 'src/types/local_date.dart';
export 'src/types/timestamp.dart';
export 'src/types/uint16.dart';
export 'src/types/uint32.dart';
export 'src/types/uint64.dart';
export 'src/types/uint8.dart';
