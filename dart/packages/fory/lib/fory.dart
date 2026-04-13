/// Public API for the Apache Fory Dart xlang runtime.
///
/// Most applications only need [Fory], [Config], [ForyStruct], and [ForyField].
/// [Buffer], [WriteContext], [ReadContext], and [Serializer] are advanced APIs
/// used by generated code, custom serializers, and low-level integrations.
// ignore_for_file: invalid_export_of_internal_element
library;

export 'src/annotation/fory_field.dart';
export 'src/annotation/fory_struct.dart';
export 'src/annotation/numeric_types.dart';
export 'src/buffer.dart'
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
export 'src/serializer/enum_serializer.dart';
export 'src/serializer/serializer.dart';
export 'src/serializer/union_serializer.dart';
export 'src/types/fixed_ints.dart';
export 'src/types/float16.dart';
export 'src/types/float32.dart';
export 'src/types/local_date.dart';
export 'src/types/timestamp.dart';
