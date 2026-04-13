import 'dart:typed_data';

import 'package:fory/src/meta/field_info.dart';

final class TypeDef {
  final bool evolving;
  final List<FieldInfo> fields;
  final int header;
  final Uint8List encoded;

  const TypeDef({
    required this.evolving,
    required this.fields,
    required this.header,
    required this.encoded,
  });
}
