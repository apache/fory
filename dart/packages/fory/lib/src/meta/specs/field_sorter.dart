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

import 'package:fory/src/const/types.dart';
import 'package:fory/src/util/string_util.dart';
import 'package:fory/src/meta/specs/field_spec.dart';

final class FieldSorter {
  static const int _primitiveCategory = 0;
  static const int _boxedCategory = 1;
  static const int _buildInCategory = 2;
  static const int _collectionCategory = 3;
  static const int _mapCategory = 4;
  static const int _otherCategory = 5;

  static List<int> sortedIndices(List<FieldSpec> fields) {
    final List<int> indices = List<int>.generate(
        fields.length, (int index) => index,
        growable: false);
    indices.sort((int leftIndex, int rightIndex) {
      final FieldSpec left = fields[leftIndex];
      final FieldSpec right = fields[rightIndex];
      final int leftCategory =
          _categoryOf(left.typeSpec.objType, left.typeSpec.nullable);
      final int rightCategory =
          _categoryOf(right.typeSpec.objType, right.typeSpec.nullable);
      if (leftCategory != rightCategory) {
        return leftCategory - rightCategory;
      }
      if (leftCategory == _primitiveCategory ||
          leftCategory == _boxedCategory) {
        return _comparePrimitive(left, right);
      }
      if (leftCategory == _otherCategory) {
        return _compareBySortKey(left, right);
      }
      return _compareByTypeAndSortKey(left, right);
    });
    return indices;
  }

  static List<FieldSpec> sort(List<FieldSpec> fields) {
    final List<int> indices = sortedIndices(fields);
    return reorderByIndices<FieldSpec>(fields, indices);
  }

  static List<T> reorderByIndices<T>(List<T> values, List<int> indices) {
    assert(values.length == indices.length);
    return List<T>.generate(
      indices.length,
      (int i) => values[indices[i]],
      growable: false,
    );
  }

  static int _categoryOf(ObjType objType, bool nullable) {
    if (_isPrimitive(objType)) {
      return nullable ? _boxedCategory : _primitiveCategory;
    }
    if (_isCollection(objType)) {
      return _collectionCategory;
    }
    if (objType == ObjType.MAP) {
      return _mapCategory;
    }
    if (_isBuildIn(objType)) {
      return _buildInCategory;
    }
    return _otherCategory;
  }

  static int _comparePrimitive(FieldSpec left, FieldSpec right) {
    final ObjType leftType = left.typeSpec.objType;
    final ObjType rightType = right.typeSpec.objType;
    final bool leftCompressed = _isCompressed(leftType);
    final bool rightCompressed = _isCompressed(rightType);
    if (leftCompressed != rightCompressed) {
      return leftCompressed ? 1 : -1;
    }
    final int sizeCmp = _primitiveSize(rightType) - _primitiveSize(leftType);
    if (sizeCmp != 0) {
      return sizeCmp;
    }
    final int typeCmp = rightType.id - leftType.id;
    if (typeCmp != 0) {
      return typeCmp;
    }
    return _compareBySortKey(left, right);
  }

  static int _compareByTypeAndSortKey(FieldSpec left, FieldSpec right) {
    final int typeCmp = _normalizedTypeId(left.typeSpec.objType) -
        _normalizedTypeId(right.typeSpec.objType);
    if (typeCmp != 0) {
      return typeCmp;
    }
    return _compareBySortKey(left, right);
  }

  static int _compareBySortKey(FieldSpec left, FieldSpec right) {
    final String leftKey = _fieldSortKey(left);
    final String rightKey = _fieldSortKey(right);
    final int keyCmp = leftKey.compareTo(rightKey);
    if (keyCmp != 0) {
      return keyCmp;
    }
    return left.name.compareTo(right.name);
  }

  static bool _isPrimitive(ObjType objType) {
    return objType.id >= ObjType.BOOL.id && objType.id <= ObjType.FLOAT64.id;
  }

  static bool _isCollection(ObjType objType) {
    return objType == ObjType.LIST ||
        objType == ObjType.SET ||
        objType == ObjType.ARRAY;
  }

  static bool _isBuildIn(ObjType objType) {
    return !_isUserDefined(objType) && objType != ObjType.UNKNOWN;
  }

  static bool _isUserDefined(ObjType objType) {
    return objType == ObjType.ENUM ||
        objType == ObjType.NAMED_ENUM ||
        objType == ObjType.STRUCT ||
        objType == ObjType.COMPATIBLE_STRUCT ||
        objType == ObjType.NAMED_STRUCT ||
        objType == ObjType.NAMED_COMPATIBLE_STRUCT ||
        objType == ObjType.EXT ||
        objType == ObjType.NAMED_EXT ||
        objType == ObjType.UNION ||
        objType == ObjType.TYPED_UNION ||
        objType == ObjType.NAMED_UNION;
  }

  static bool _isCompressed(ObjType objType) {
    return objType == ObjType.INT32 ||
        objType == ObjType.VAR_INT32 ||
        objType == ObjType.VAR_UINT32 ||
        objType == ObjType.INT64 ||
        objType == ObjType.VAR_INT64 ||
        objType == ObjType.SLI_INT64 ||
        objType == ObjType.VAR_UINT64 ||
        objType == ObjType.TAGGED_UINT64;
  }

  static int _primitiveSize(ObjType objType) {
    switch (objType) {
      case ObjType.BOOL:
      case ObjType.INT8:
      case ObjType.UINT8:
      case ObjType.FLOAT8:
        return 1;
      case ObjType.INT16:
      case ObjType.UINT16:
      case ObjType.FLOAT16:
      case ObjType.BFLOAT16:
        return 2;
      case ObjType.INT32:
      case ObjType.VAR_INT32:
      case ObjType.UINT32:
      case ObjType.VAR_UINT32:
      case ObjType.FLOAT32:
        return 4;
      case ObjType.INT64:
      case ObjType.VAR_INT64:
      case ObjType.SLI_INT64:
      case ObjType.UINT64:
      case ObjType.VAR_UINT64:
      case ObjType.TAGGED_UINT64:
      case ObjType.FLOAT64:
        return 8;
      default:
        return 0;
    }
  }

  static int _normalizedTypeId(ObjType objType) {
    if (objType == ObjType.ARRAY) {
      return ObjType.LIST.id;
    }
    return objType.id;
  }

  static String _fieldSortKey(FieldSpec field) {
    return StringUtil.lowerCamelToLowerUnderscore(field.name);
  }
}
