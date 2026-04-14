import 'package:meta/meta.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';

@internal
final class StructWriteSlots {
  final List<SerializationFieldInfo> orderedFields;
  final List<SerializationFieldInfo?> _fieldsBySlot;

  StructWriteSlots(this.orderedFields, int fieldCount)
      : _fieldsBySlot = List<SerializationFieldInfo?>.filled(fieldCount, null) {
    for (final field in orderedFields) {
      _fieldsBySlot[field.slot] = field;
    }
  }

  SerializationFieldInfo? fieldForSlot(int slot) => _fieldsBySlot[slot];
}

@internal
final class StructReadSlots {
  final List<Object?> compatibleValues;
  final List<bool> presentSlots;

  const StructReadSlots(this.compatibleValues, this.presentSlots);

  bool containsSlot(int slot) => presentSlots[slot];

  Object? valueForSlot(int slot) => compatibleValues[slot];
}
