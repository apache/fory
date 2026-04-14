/// Cross-language type identifiers for Fory serialization.
abstract final class TypeIds {
  static const int unknown = 0;
  static const int boolType = 1;
  static const int int8 = 2;
  static const int int16 = 3;
  static const int int32 = 4;
  static const int varInt32 = 5;
  static const int int64 = 6;
  static const int varInt64 = 7;
  static const int taggedInt64 = 8;
  static const int uint8 = 9;
  static const int uint16 = 10;
  static const int uint32 = 11;
  static const int varUint32 = 12;
  static const int uint64 = 13;
  static const int varUint64 = 14;
  static const int taggedUint64 = 15;
  static const int float16 = 17;
  static const int bfloat16 = 18;
  static const int float32 = 19;
  static const int float64 = 20;
  static const int string = 21;
  static const int list = 22;
  static const int set = 23;
  static const int map = 24;
  static const int enumById = 25;
  static const int namedEnum = 26;
  static const int struct = 27;
  static const int compatibleStruct = 28;
  static const int namedStruct = 29;
  static const int namedCompatibleStruct = 30;
  static const int ext = 31;
  static const int namedExt = 32;
  static const int union = 33;
  static const int typedUnion = 34;
  static const int namedUnion = 35;
  static const int none = 36;
  static const int timestamp = 38;
  static const int date = 39;
  static const int binary = 41;
  static const int boolArray = 43;
  static const int int8Array = 44;
  static const int int16Array = 45;
  static const int int32Array = 46;
  static const int int64Array = 47;
  static const int uint8Array = 48;
  static const int uint16Array = 49;
  static const int uint32Array = 50;
  static const int uint64Array = 51;
  static const int float16Array = 53;
  static const int bfloat16Array = 54;
  static const int float32Array = 55;
  static const int float64Array = 56;

  static bool isPrimitive(int typeId) =>
      typeId == boolType ||
      typeId == int8 ||
      typeId == int16 ||
      typeId == int32 ||
      typeId == varInt32 ||
      typeId == int64 ||
      typeId == varInt64 ||
      typeId == taggedInt64 ||
      typeId == uint8 ||
      typeId == uint16 ||
      typeId == uint32 ||
      typeId == varUint32 ||
      typeId == uint64 ||
      typeId == varUint64 ||
      typeId == taggedUint64 ||
      typeId == float16 ||
      typeId == bfloat16 ||
      typeId == float32 ||
      typeId == float64;

  static bool isContainer(int typeId) =>
      typeId == list || typeId == set || typeId == map;

  static bool isUserType(int typeId) =>
      typeId == enumById ||
      typeId == namedEnum ||
      typeId == struct ||
      typeId == compatibleStruct ||
      typeId == namedStruct ||
      typeId == namedCompatibleStruct ||
      typeId == ext ||
      typeId == namedExt ||
      typeId == union ||
      typeId == typedUnion ||
      typeId == namedUnion;

  static bool isBasicValue(int typeId) =>
      isPrimitive(typeId) ||
      typeId == string ||
      typeId == binary ||
      typeId == timestamp ||
      typeId == date ||
      typeId == boolArray ||
      typeId == int8Array ||
      typeId == int16Array ||
      typeId == int32Array ||
      typeId == int64Array ||
      typeId == uint8Array ||
      typeId == uint16Array ||
      typeId == uint32Array ||
      typeId == uint64Array ||
      typeId == float16Array ||
      typeId == bfloat16Array ||
      typeId == float32Array ||
      typeId == float64Array;

  static bool supportsRef(int typeId) {
    if (typeId == unknown) {
      return true;
    }
    if (isPrimitive(typeId) || typeId == binary) {
      return false;
    }
    switch (typeId) {
      case boolArray:
      case int8Array:
      case int16Array:
      case int32Array:
      case int64Array:
      case uint8Array:
      case uint16Array:
      case uint32Array:
      case uint64Array:
      case float16Array:
      case bfloat16Array:
      case float32Array:
      case float64Array:
        return false;
      default:
        return true;
    }
  }
}
