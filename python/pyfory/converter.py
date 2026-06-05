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

import array
import decimal
import math
import struct as _struct

from pyfory.serialization import _bfloat16_from_bits, _bfloat16_to_bits, _float16_from_bits, _float16_to_bits
from pyfory.serializer import ForyArrayFieldSerializer, PyArraySerializer, Serializer, _is_numpy_1d_array_serializer
from pyfory.types import TypeId

try:
    import numpy as np
except ImportError:
    np = None


_SIGNED_INT_TYPE_IDS = frozenset((TypeId.INT8, TypeId.INT16, TypeId.INT32, TypeId.VARINT32, TypeId.INT64, TypeId.VARINT64, TypeId.TAGGED_INT64))
_UNSIGNED_INT_TYPE_IDS = frozenset(
    (TypeId.UINT8, TypeId.UINT16, TypeId.UINT32, TypeId.VAR_UINT32, TypeId.UINT64, TypeId.VAR_UINT64, TypeId.TAGGED_UINT64)
)
_INT_RANGES_BY_TYPE_ID = {
    TypeId.INT8: (-(1 << 7), (1 << 7) - 1),
    TypeId.INT16: (-(1 << 15), (1 << 15) - 1),
    TypeId.INT32: (-(1 << 31), (1 << 31) - 1),
    TypeId.VARINT32: (-(1 << 31), (1 << 31) - 1),
    TypeId.INT64: (-(1 << 63), (1 << 63) - 1),
    TypeId.VARINT64: (-(1 << 63), (1 << 63) - 1),
    TypeId.TAGGED_INT64: (-(1 << 63), (1 << 63) - 1),
    TypeId.UINT8: (0, (1 << 8) - 1),
    TypeId.UINT16: (0, (1 << 16) - 1),
    TypeId.UINT32: (0, (1 << 32) - 1),
    TypeId.VAR_UINT32: (0, (1 << 32) - 1),
    TypeId.UINT64: (0, (1 << 64) - 1),
    TypeId.VAR_UINT64: (0, (1 << 64) - 1),
    TypeId.TAGGED_UINT64: (0, (1 << 64) - 1),
}
_FLOAT_TYPE_IDS = frozenset((TypeId.FLOAT16, TypeId.BFLOAT16, TypeId.FLOAT32, TypeId.FLOAT64))
_NUMERIC_TYPE_IDS = _SIGNED_INT_TYPE_IDS | _UNSIGNED_INT_TYPE_IDS | _FLOAT_TYPE_IDS | frozenset((TypeId.DECIMAL,))
_SCALAR_CONVERSION_TYPE_IDS = _NUMERIC_TYPE_IDS | frozenset((TypeId.BOOL, TypeId.STRING))
_MAX_COMPATIBLE_DECIMAL_DIGITS = 256
_MAX_COMPATIBLE_NUMERIC_TEXT_LENGTH = 320


def supports_compatible_scalar_conversion(remote_type_id: int, local_type_id: int) -> bool:
    if remote_type_id == local_type_id:
        return remote_type_id in _SCALAR_CONVERSION_TYPE_IDS
    if remote_type_id not in _SCALAR_CONVERSION_TYPE_IDS or local_type_id not in _SCALAR_CONVERSION_TYPE_IDS:
        return False
    if remote_type_id == TypeId.BOOL:
        return local_type_id == TypeId.STRING or local_type_id in _NUMERIC_TYPE_IDS
    if local_type_id == TypeId.BOOL:
        return remote_type_id == TypeId.STRING or remote_type_id in _NUMERIC_TYPE_IDS
    if remote_type_id == TypeId.STRING:
        return local_type_id in _NUMERIC_TYPE_IDS
    if local_type_id == TypeId.STRING:
        return remote_type_id in _NUMERIC_TYPE_IDS
    return remote_type_id in _NUMERIC_TYPE_IDS and local_type_id in _NUMERIC_TYPE_IDS


def _decimal_from_text(value: str) -> decimal.Decimal:
    if not _numeric_literal_fits(value):
        raise ValueError("invalid numeric literal")
    return _canonical_decimal(decimal.Decimal(value))


def _numeric_literal_fits(value: str) -> bool:
    if not value or len(value) > _MAX_COMPATIBLE_NUMERIC_TEXT_LENGTH:
        return False
    index = 0
    if value[index] == "-":
        index += 1
        if index == len(value):
            return False

    significant_digits = 0
    seen_nonzero = False
    if value[index] == "0":
        index += 1
        if index < len(value) and _is_digit(value[index]):
            return False
    elif "1" <= value[index] <= "9":
        while index < len(value) and _is_digit(value[index]):
            if value[index] != "0" or seen_nonzero:
                seen_nonzero = True
                significant_digits += 1
            index += 1
    else:
        return False

    fractional_digits = 0
    if index < len(value) and value[index] == ".":
        index += 1
        fraction_start = index
        while index < len(value) and _is_digit(value[index]):
            if value[index] != "0" or seen_nonzero:
                seen_nonzero = True
                significant_digits += 1
            fractional_digits += 1
            index += 1
        if index == fraction_start:
            return False

    exponent = 0
    if index < len(value) and value[index] in ("e", "E"):
        index += 1
        exponent_negative = False
        if index < len(value) and value[index] == "-":
            exponent_negative = True
            index += 1
        if index == len(value):
            return False
        if value[index] == "0":
            index += 1
            if index < len(value) and _is_digit(value[index]):
                return False
        elif "1" <= value[index] <= "9":
            while index < len(value) and _is_digit(value[index]):
                exponent = exponent * 10 + ord(value[index]) - ord("0")
                if exponent > _MAX_COMPATIBLE_DECIMAL_DIGITS:
                    return False
                index += 1
        else:
            return False
        if exponent_negative:
            exponent = -exponent

    if index != len(value) or significant_digits > _MAX_COMPATIBLE_DECIMAL_DIGITS:
        return False
    final_scale = fractional_digits - exponent
    return _decimal_shape_fits(significant_digits, final_scale)


def _decimal_shape_fits(significant_digits: int, scale: int) -> bool:
    if scale > _MAX_COMPATIBLE_DECIMAL_DIGITS:
        return False
    return scale >= 0 or significant_digits + (-scale) <= _MAX_COMPATIBLE_DECIMAL_DIGITS


def _is_digit(value: str) -> bool:
    return "0" <= value <= "9"


def _canonical_decimal(value: decimal.Decimal) -> decimal.Decimal:
    if not value.is_finite():
        raise ValueError("non-finite decimal")
    if value.is_zero():
        return decimal.Decimal(0)
    sign, digits, exponent = value.as_tuple()
    digits = list(digits)
    while exponent < 0 and digits[-1] == 0:
        digits.pop()
        exponent += 1
    scale = -exponent if exponent < 0 else 0
    digit_count = len(digits) + (exponent if exponent > 0 else 0)
    if scale > _MAX_COMPATIBLE_DECIMAL_DIGITS or digit_count > _MAX_COMPATIBLE_DECIMAL_DIGITS:
        raise ValueError("decimal exceeds compatible conversion limit")
    if exponent >= 0:
        text = "".join(str(digit) for digit in digits) + ("0" * exponent)
        integer = int(text)
        return decimal.Decimal(-integer if sign else integer)
    return decimal.Decimal((sign, tuple(digits), exponent))


def _is_negative_zero(value: float) -> bool:
    return value == 0.0 and math.copysign(1.0, value) < 0.0


def _same_float_value(left: float, right: float) -> bool:
    if math.isnan(left) or math.isnan(right):
        return False
    if left == 0.0 and right == 0.0:
        return math.copysign(1.0, left) == math.copysign(1.0, right)
    return left == right


def _to_float32(value: float) -> float:
    return _struct.unpack(">f", _struct.pack(">f", value))[0]


def _to_float_domain(value: float, type_id: int) -> float:
    if type_id == TypeId.FLOAT64:
        return float(value)
    if type_id == TypeId.FLOAT32:
        return _to_float32(value)
    if type_id == TypeId.FLOAT16:
        return _float16_from_bits(_float16_to_bits(value))
    if type_id == TypeId.BFLOAT16:
        return _bfloat16_from_bits(_bfloat16_to_bits(value))
    raise ValueError(f"type id {type_id} is not a floating type")


def _decimal_from_float_value(value: float) -> decimal.Decimal:
    if value == 0.0:
        return decimal.Decimal(0)
    return _canonical_decimal(decimal.Decimal.from_float(value))


def _int_value(value, local_type_id: int) -> int:
    min_value, max_value = _INT_RANGES_BY_TYPE_ID[local_type_id]
    if isinstance(value, decimal.Decimal):
        value = _canonical_decimal(value)
        if not value.is_finite() or value != value.to_integral_exact():
            raise ValueError("decimal is not an integral value")
        result = int(value)
    elif type(value) is float:
        if not math.isfinite(value) or not value.is_integer():
            raise ValueError("floating value is not a finite integer")
        result = int(value)
    elif type(value) is int:
        result = value
    else:
        raise ValueError(f"expected numeric value, got {type(value)!r}")
    if result < min_value or result > max_value:
        raise OverflowError(f"value {result} is outside target integer range")
    return result


def _float_value(value, local_type_id: int) -> float:
    if isinstance(value, decimal.Decimal):
        value = _canonical_decimal(value)
        if value.is_zero():
            return 0.0
        result = _to_float_domain(float(value), local_type_id)
        if _decimal_from_float_value(result) != value:
            raise ValueError("decimal value is not exactly representable by target float")
        return result
    if type(value) is int:
        result = _to_float_domain(float(value), local_type_id)
        if not math.isfinite(result) or int(result) != value:
            raise ValueError("integer value is not exactly representable by target float")
        return result
    if type(value) is float:
        if math.isnan(value):
            raise ValueError("NaN is not convertible across floating types")
        result = _to_float_domain(value, local_type_id)
        if not _same_float_value(_to_float_domain(result, local_type_id), result):
            raise ValueError("target float round-trip failed")
        if not _same_float_value(result, value):
            raise ValueError("floating value is not exactly representable by target float")
        return result
    raise ValueError(f"expected numeric value, got {type(value)!r}")


def _bool_value(value) -> bool:
    if type(value) is bool:
        return value
    if type(value) is str:
        if value in ("1", "true"):
            return True
        if value in ("0", "false"):
            return False
        raise ValueError("string is not a compatible bool literal")
    if isinstance(value, decimal.Decimal):
        value = _canonical_decimal(value)
        if value == 0:
            return False
        if value == 1:
            return True
        raise ValueError("decimal bool value must be 0 or 1")
    if type(value) is float:
        if not math.isfinite(value):
            raise ValueError("floating bool value is not finite")
        if value == 0.0:
            return False
        if value == 1.0:
            return True
        raise ValueError("floating bool value must be 0 or 1")
    if type(value) is int:
        if value == 0:
            return False
        if value == 1:
            return True
    raise ValueError("numeric bool value must be 0 or 1")


def _plain_decimal_text(value: decimal.Decimal) -> str:
    value = _canonical_decimal(value)
    if value.is_zero():
        return "0"
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text


def _float_text(value: float) -> str:
    if math.isnan(value) or math.isinf(value):
        raise ValueError("non-finite float cannot convert to string")
    if value == 0.0:
        return "-0.0" if _is_negative_zero(value) else "0.0"
    text = _plain_decimal_text(decimal.Decimal.from_float(value))
    if "." not in text:
        return f"{text}.0"
    text = text.rstrip("0")
    if text.endswith("."):
        text += "0"
    return text


def _string_value(value) -> str:
    if type(value) is bool:
        return "true" if value else "false"
    if type(value) is int:
        return str(value)
    if type(value) is float:
        return _float_text(value)
    if isinstance(value, decimal.Decimal):
        return _plain_decimal_text(value)
    raise ValueError(f"expected scalar value, got {type(value)!r}")


def _numeric_value(value, local_type_id: int):
    if type(value) is str:
        if local_type_id in _FLOAT_TYPE_IDS and value.startswith("-"):
            decimal_value = _decimal_from_text(value)
            if decimal_value.is_zero():
                return _to_float_domain(-0.0, local_type_id)
        value = _decimal_from_text(value)
    elif type(value) is bool:
        value = 1 if value else 0
    if local_type_id in _INT_RANGES_BY_TYPE_ID:
        return _int_value(value, local_type_id)
    if local_type_id in _FLOAT_TYPE_IDS:
        return _float_value(value, local_type_id)
    if local_type_id == TypeId.DECIMAL:
        if type(value) is int:
            return decimal.Decimal(value)
        if type(value) is float:
            if not math.isfinite(value):
                raise ValueError("non-finite float cannot convert to decimal")
            return _decimal_from_float_value(value)
        if isinstance(value, decimal.Decimal):
            return _canonical_decimal(value)
    raise ValueError(f"type id {local_type_id} is not a numeric scalar")


def compatible_scalar_convert(value, remote_type_id: int, local_type_id: int):
    if remote_type_id == local_type_id:
        return value
    if local_type_id == TypeId.BOOL:
        return _bool_value(value)
    if local_type_id == TypeId.STRING:
        return _string_value(value)
    if local_type_id in _NUMERIC_TYPE_IDS:
        return _numeric_value(value, local_type_id)
    raise ValueError(f"type id {local_type_id} is not a compatible scalar target")


def _read_compatible_scalar_payload(read_context, remote_serializer, remote_type_id: int):
    if remote_type_id == TypeId.BOOL:
        raw = read_context.read_uint8()
        if raw == 0:
            return False
        if raw == 1:
            return True
        raise ValueError("bool payload must be encoded as 0 or 1")
    return remote_serializer.read(read_context)


def _scalar_conversion_error(field_name: str, remote_type_id: int, local_type_id: int, value, cause: Exception):
    from pyfory.error import ForyInvalidDataError

    raise ForyInvalidDataError(
        f"Cannot convert compatible field {field_name!r} from type {remote_type_id} to type {local_type_id}: {value!r}"
    ) from cause


class CompatibleScalarFieldSerializer(Serializer):
    def __init__(self, type_resolver, remote_serializer, remote_type_id: int, local_type_id: int, field_name: str):
        super().__init__(type_resolver, list)
        self.need_to_write_ref = False
        self.remote_serializer = remote_serializer
        self.remote_type_id = remote_type_id
        self.local_type_id = local_type_id
        self.field_name = field_name

    def write(self, write_context, value):
        raise NotImplementedError("compatible scalar field serializer is read-only")

    def read(self, read_context):
        value = None
        try:
            value = _read_compatible_scalar_payload(read_context, self.remote_serializer, self.remote_type_id)
            return compatible_scalar_convert(value, self.remote_type_id, self.local_type_id)
        except (ValueError, OverflowError, decimal.InvalidOperation) as exc:
            _scalar_conversion_error(self.field_name, self.remote_type_id, self.local_type_id, value, exc)


class CompatibleArrayToListFieldSerializer(Serializer):
    def __init__(self, type_resolver, remote_array_serializer, elem_serializer):
        super().__init__(type_resolver, list)
        self.remote_array_serializer = remote_array_serializer
        self.elem_serializer = elem_serializer
        self.need_to_write_ref = False

    def write(self, buffer, value):
        raise TypeError("compatible array-to-list field serializer is read-only")

    def read(self, read_context):
        return list(self.remote_array_serializer.read(read_context))


class CompatibleListToArrayFieldSerializer(Serializer):
    def __init__(self, type_resolver, target_serializer, elem_serializer, field_name=None):
        super().__init__(type_resolver, target_serializer.type_)
        self.target_serializer = target_serializer
        self.elem_serializer = elem_serializer
        self.field_name = field_name or "<array>"
        self.need_to_write_ref = False

    def write(self, buffer, value):
        raise TypeError("compatible list-to-array field serializer is read-only")

    def _empty_target(self):
        if isinstance(self.target_serializer, ForyArrayFieldSerializer):
            return self.target_serializer.wrapper_type()
        if isinstance(self.target_serializer, PyArraySerializer):
            return array.array(self.target_serializer.typecode)
        if np is not None and _is_numpy_1d_array_serializer(self.target_serializer):
            return np.empty(0, dtype=self.target_serializer.dtype)
        raise TypeError(f"Field {self.field_name!r} has unsupported array target serializer {type(self.target_serializer)!r}")

    def _new_target(self, length):
        if isinstance(self.target_serializer, ForyArrayFieldSerializer):
            return self.target_serializer.wrapper_type()
        if isinstance(self.target_serializer, PyArraySerializer):
            return array.array(self.target_serializer.typecode)
        if np is not None and _is_numpy_1d_array_serializer(self.target_serializer):
            return np.empty(length, dtype=self.target_serializer.dtype)
        raise TypeError(f"Field {self.field_name!r} has unsupported array target serializer {type(self.target_serializer)!r}")

    def read(self, read_context):
        from pyfory.collection import (
            COLL_HAS_NULL,
            COLL_IS_DECL_ELEMENT_TYPE,
            COLL_IS_SAME_TYPE,
            COLL_TRACKING_REF,
        )
        from pyfory.error import TypeNotCompatibleError

        length = read_context.read_var_uint32()
        if length > read_context.max_collection_size:
            raise ValueError(f"Collection size {length} exceeds the configured limit of {read_context.max_collection_size}")
        if length == 0:
            return self._empty_target()
        collect_flag = read_context.read_int8()
        if (collect_flag & (COLL_HAS_NULL | COLL_TRACKING_REF)) != 0:
            raise TypeNotCompatibleError(
                f"Field {self.field_name!r} cannot read nullable or ref-tracked list elements as array<T>",
            )
        if (collect_flag & (COLL_IS_SAME_TYPE | COLL_IS_DECL_ELEMENT_TYPE)) != (COLL_IS_SAME_TYPE | COLL_IS_DECL_ELEMENT_TYPE):
            raise TypeNotCompatibleError(
                f"Field {self.field_name!r} requires declared same-type list elements for array<T> compatible read",
            )

        target = self._new_target(length)
        append = None if np is not None and _is_numpy_1d_array_serializer(self.target_serializer) else target.append
        for index in range(length):
            item = read_context.read_no_ref(serializer=self.elem_serializer)
            try:
                if append is None:
                    target[index] = item
                else:
                    append(item)
            except (TypeError, ValueError, OverflowError) as exc:
                raise type(exc)(f"{self.field_name}[{index}] invalid for {type(target).__name__}: {exc}") from exc
        return target
