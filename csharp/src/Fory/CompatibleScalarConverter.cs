// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

using System.Globalization;
using System.Numerics;
using System.Runtime.CompilerServices;

namespace Apache.Fory;

internal readonly struct CompatibleScalarRead(
    TypeId rawRemoteTypeId,
    TypeId remoteTypeId,
    TypeId localTypeId,
    bool remoteNullable,
    bool int64FastSource,
    string fieldName)
{
    public TypeId RawRemoteTypeId { get; } = rawRemoteTypeId;

    public TypeId RemoteTypeId { get; } = remoteTypeId;

    public TypeId LocalTypeId { get; } = localTypeId;

    public bool RemoteNullable { get; } = remoteNullable;

    public bool Int64FastSource { get; } = int64FastSource;

    public string FieldName { get; } = fieldName;
}

/// <summary>
/// Provides compatible scalar field conversion for generated serializers.
/// </summary>
/// <remarks>
/// This API is intended for Fory-generated compatible struct readers. User code should configure
/// compatible mode rather than calling it directly.
/// </remarks>
public static class CompatibleScalarConverter
{
    private static readonly BigInteger SByteMin = sbyte.MinValue;
    private static readonly BigInteger SByteMax = sbyte.MaxValue;
    private static readonly BigInteger Int16Min = short.MinValue;
    private static readonly BigInteger Int16Max = short.MaxValue;
    private static readonly BigInteger Int32Min = int.MinValue;
    private static readonly BigInteger Int32Max = int.MaxValue;
    private static readonly BigInteger Int64Min = long.MinValue;
    private static readonly BigInteger Int64Max = long.MaxValue;
    private static readonly BigInteger ByteMax = byte.MaxValue;
    private static readonly BigInteger UInt16Max = ushort.MaxValue;
    private static readonly BigInteger UInt32Max = uint.MaxValue;
    private static readonly BigInteger UInt64Max = ulong.MaxValue;
    private static readonly BigInteger DecimalMask = uint.MaxValue;
    private const int MaxCompatibleDecimalDigits = 256;
    private const int MaxCompatibleNumericTextLength = 320;
    private static readonly BigInteger MaxCompatibleDecimalMagnitude =
        BigInteger.Pow(10, MaxCompatibleDecimalDigits);

    /// <summary>
    /// Returns whether the field type id is in the compatible scalar family.
    /// </summary>
    public static bool IsScalarType(uint typeId)
    {
        return IsScalar(NormalizeScalarTypeId(typeId));
    }

    /// <summary>
    /// Returns whether a top-level compatible field can use scalar conversion.
    /// </summary>
    public static bool CanConvert(uint remoteTypeId, uint localTypeId)
    {
        TypeId remote = NormalizeScalarTypeId(remoteTypeId);
        TypeId local = NormalizeScalarTypeId(localTypeId);
        if (remote == local)
        {
            return remoteTypeId != localTypeId && IsScalar(remote);
        }

        if (!IsScalar(remote) || !IsScalar(local))
        {
            return false;
        }

        if (remote == TypeId.Bool)
        {
            return local == TypeId.String || IsNumeric(local);
        }

        if (local == TypeId.Bool)
        {
            return remote == TypeId.String || IsNumeric(remote);
        }

        if (remote == TypeId.String)
        {
            return IsNumeric(local);
        }

        if (local == TypeId.String)
        {
            return IsNumeric(remote);
        }

        return IsNumeric(remote) && IsNumeric(local);
    }

    internal static CompatibleScalarRead? TryBuildRead(
        TypeMetaFieldInfo remoteField,
        TypeMetaFieldInfo localField)
    {
        TypeMetaFieldType remoteFieldType = remoteField.FieldType;
        TypeMetaFieldType localFieldType = localField.FieldType;
        if (remoteFieldType.TrackRef || localFieldType.TrackRef)
        {
            return null;
        }

        TypeId rawRemoteTypeId = (TypeId)remoteFieldType.TypeId;
        TypeId remoteTypeId = NormalizeScalarTypeId(remoteFieldType.TypeId);
        TypeId localTypeId = NormalizeScalarTypeId(localFieldType.TypeId);
        if (!IsScalar(remoteTypeId) || !IsScalar(localTypeId))
        {
            return null;
        }

        bool sameWireType = remoteFieldType.TypeId == localFieldType.TypeId;
        bool sameNullable = remoteFieldType.Nullable == localFieldType.Nullable;
        if (sameWireType && sameNullable)
        {
            return null;
        }

        if (!sameWireType && !CanConvert(remoteFieldType.TypeId, localFieldType.TypeId))
        {
            return null;
        }

        return new CompatibleScalarRead(
            rawRemoteTypeId,
            remoteTypeId,
            localTypeId,
            remoteFieldType.Nullable,
            IsInt64FastSource(rawRemoteTypeId),
            localField.FieldName);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static bool ReadBoolField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToBool(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : false;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static bool? ReadNullableBoolField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToBool(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static sbyte ReadSByteField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToSByte(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static sbyte? ReadNullableSByteField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToSByte(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static short ReadInt16Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToInt16(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static short? ReadNullableInt16Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToInt16(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static int ReadInt32Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToInt32(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static int? ReadNullableInt32Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToInt32(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static long ReadInt64Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        if (TryReadIntegralToInt64Field(context, remoteField, out long fastValue, out bool present))
        {
            return present ? fastValue : default;
        }

        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToInt64(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static long? ReadNullableInt64Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        if (TryReadIntegralToInt64Field(context, remoteField, out long fastValue, out bool present))
        {
            return present ? fastValue : null;
        }

        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToInt64(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static byte ReadByteField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToByte(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static byte? ReadNullableByteField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToByte(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static ushort ReadUInt16Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToUInt16(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static ushort? ReadNullableUInt16Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToUInt16(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static uint ReadUInt32Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToUInt32(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static uint? ReadNullableUInt32Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToUInt32(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static ulong ReadUInt64Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToUInt64(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static ulong? ReadNullableUInt64Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToUInt64(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static Half ReadHalfField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToHalfTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static Half? ReadNullableHalfField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToHalfTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static BFloat16 ReadBFloat16Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToBFloat16Target(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static BFloat16? ReadNullableBFloat16Field(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToBFloat16Target(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static float ReadFloatField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToSingleTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static float? ReadNullableFloatField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToSingleTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static double ReadDoubleField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToDoubleTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static double? ReadNullableDoubleField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToDoubleTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static string ReadStringField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToStringValue(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default!;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static string? ReadNullableStringField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToStringValue(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static ForyDecimal ReadForyDecimalField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToForyDecimalTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static ForyDecimal? ReadNullableForyDecimalField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToForyDecimalTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static decimal ReadDecimalField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToSystemDecimalTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : default;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static decimal? ReadNullableDecimalField(ReadContext context, TypeMetaFieldInfo remoteField)
    {
        return TryReadScalarValue(context, remoteField, out CompatibleScalarRead read, out ScalarValue value)
            ? ToSystemDecimalTarget(value, read.RemoteTypeId, read.LocalTypeId, read.FieldName)
            : null;
    }

    private static bool TryReadScalarValue(
        ReadContext context,
        TypeMetaFieldInfo remoteField,
        out CompatibleScalarRead scalarRead,
        out ScalarValue value)
    {
        scalarRead = RequireRead(remoteField);
        value = default;

        if (!scalarRead.RemoteNullable)
        {
            value = ReadScalarPayload(
                context,
                scalarRead.RawRemoteTypeId,
                scalarRead.LocalTypeId,
                scalarRead.FieldName);
            return true;
        }

        RefFlag flag = context.RefReader.ReadRefFlag(context.Reader);
        switch (flag)
        {
            case RefFlag.Null:
                return false;
            case RefFlag.NotNullValue:
                value = ReadScalarPayload(
                    context,
                    scalarRead.RawRemoteTypeId,
                    scalarRead.LocalTypeId,
                    scalarRead.FieldName);
                return true;
            default:
                throw Fail(
                    scalarRead.RemoteTypeId,
                    scalarRead.LocalTypeId,
                    scalarRead.FieldName,
                    $"invalid compatible nullOnly ref flag {(sbyte)flag}");
        }
    }

    private static bool TryReadIntegralToInt64Field(
        ReadContext context,
        TypeMetaFieldInfo remoteField,
        out long value,
        out bool present)
    {
        CompatibleScalarRead scalarRead = RequireRead(remoteField);
        value = default;
        present = false;
        if (!scalarRead.Int64FastSource)
        {
            return false;
        }

        if (!scalarRead.RemoteNullable)
        {
            value = ReadInt64FastPayload(
                context,
                scalarRead.RawRemoteTypeId,
                scalarRead.LocalTypeId,
                scalarRead.FieldName);
            present = true;
            return true;
        }

        RefFlag flag = context.RefReader.ReadRefFlag(context.Reader);
        switch (flag)
        {
            case RefFlag.Null:
                return true;
            case RefFlag.NotNullValue:
                value = ReadInt64FastPayload(
                    context,
                    scalarRead.RawRemoteTypeId,
                    scalarRead.LocalTypeId,
                    scalarRead.FieldName);
                present = true;
                return true;
            default:
                throw Fail(
                    scalarRead.RemoteTypeId,
                    scalarRead.LocalTypeId,
                    scalarRead.FieldName,
                    $"invalid compatible nullOnly ref flag {(sbyte)flag}");
        }
    }

    private static CompatibleScalarRead RequireRead(TypeMetaFieldInfo remoteField)
    {
        return remoteField.CompatibleScalarRead
            ?? throw new InvalidDataException(
                $"compatible scalar field {remoteField.FieldName} was not classified for scalar conversion");
    }

    private static bool IsInt64FastSource(TypeId remoteTypeId)
    {
        return remoteTypeId is TypeId.Bool or TypeId.Int8 or TypeId.Int16 or TypeId.Int32 or
            TypeId.VarInt32 or TypeId.Int64 or TypeId.VarInt64 or TypeId.TaggedInt64 or
            TypeId.UInt8 or TypeId.UInt16 or TypeId.UInt32 or TypeId.VarUInt32 or
            TypeId.UInt64 or TypeId.VarUInt64 or TypeId.TaggedUInt64;
    }

    private static long ReadInt64FastPayload(ReadContext context, TypeId remoteTypeId, TypeId localTypeId, string fieldName)
    {
        return remoteTypeId switch
        {
            TypeId.Bool => ReadBool(context, localTypeId, fieldName) ? 1 : 0,
            TypeId.Int8 => context.Reader.ReadInt8(),
            TypeId.Int16 => context.Reader.ReadInt16(),
            TypeId.Int32 => context.Reader.ReadInt32(),
            TypeId.VarInt32 => context.Reader.ReadVarInt32(),
            TypeId.Int64 => context.Reader.ReadInt64(),
            TypeId.VarInt64 => context.Reader.ReadVarInt64(),
            TypeId.TaggedInt64 => context.Reader.ReadTaggedInt64(),
            TypeId.UInt8 => context.Reader.ReadUInt8(),
            TypeId.UInt16 => context.Reader.ReadUInt16(),
            TypeId.UInt32 => context.Reader.ReadUInt32(),
            TypeId.VarUInt32 => context.Reader.ReadVarUInt32(),
            TypeId.UInt64 => ToInt64(context.Reader.ReadUInt64(), remoteTypeId, localTypeId, fieldName),
            TypeId.VarUInt64 => ToInt64(context.Reader.ReadVarUInt64(), remoteTypeId, localTypeId, fieldName),
            TypeId.TaggedUInt64 => ToInt64(context.Reader.ReadTaggedUInt64(), remoteTypeId, localTypeId, fieldName),
            _ => throw Fail(remoteTypeId, localTypeId, fieldName, $"unsupported compatible scalar type id {remoteTypeId}"),
        };
    }

    private static long ToInt64(ulong value, TypeId remote, TypeId local, string fieldName)
    {
        if (value <= long.MaxValue)
        {
            return (long)value;
        }

        throw Fail(remote, local, fieldName, $"integer value {value} is outside {local} range");
    }

    private static ScalarValue ReadScalarPayload(
        ReadContext context,
        TypeId remoteTypeId,
        TypeId localTypeId,
        string fieldName)
    {
        return remoteTypeId switch
        {
            TypeId.Bool => ScalarValue.ForBool(ReadBool(context, localTypeId, fieldName)),
            TypeId.Int8 => ScalarValue.ForSigned(context.Reader.ReadInt8()),
            TypeId.Int16 => ScalarValue.ForSigned(context.Reader.ReadInt16()),
            TypeId.Int32 => ScalarValue.ForSigned(context.Reader.ReadInt32()),
            TypeId.VarInt32 => ScalarValue.ForSigned(context.Reader.ReadVarInt32()),
            TypeId.Int64 => ScalarValue.ForSigned(context.Reader.ReadInt64()),
            TypeId.VarInt64 => ScalarValue.ForSigned(context.Reader.ReadVarInt64()),
            TypeId.TaggedInt64 => ScalarValue.ForSigned(context.Reader.ReadTaggedInt64()),
            TypeId.UInt8 => ScalarValue.ForUnsigned(context.Reader.ReadUInt8()),
            TypeId.UInt16 => ScalarValue.ForUnsigned(context.Reader.ReadUInt16()),
            TypeId.UInt32 => ScalarValue.ForUnsigned(context.Reader.ReadUInt32()),
            TypeId.VarUInt32 => ScalarValue.ForUnsigned(context.Reader.ReadVarUInt32()),
            TypeId.UInt64 => ScalarValue.ForUnsigned(context.Reader.ReadUInt64()),
            TypeId.VarUInt64 => ScalarValue.ForUnsigned(context.Reader.ReadVarUInt64()),
            TypeId.TaggedUInt64 => ScalarValue.ForUnsigned(context.Reader.ReadTaggedUInt64()),
            TypeId.Float16 => ScalarValue.ForHalf(BitConverter.UInt16BitsToHalf(context.Reader.ReadUInt16())),
            TypeId.BFloat16 => ScalarValue.ForBFloat16(BFloat16.FromBits(context.Reader.ReadUInt16())),
            TypeId.Float32 => ScalarValue.ForSingle(context.Reader.ReadFloat32()),
            TypeId.Float64 => ScalarValue.ForDouble(context.Reader.ReadFloat64()),
            TypeId.Decimal => ScalarValue.ForDecimal(ReadDecimal(context)),
            TypeId.String => ScalarValue.ForString(StringSerializer.ReadString(context)),
            _ => throw Fail(
                remoteTypeId,
                localTypeId,
                fieldName,
                $"unsupported compatible scalar type id {remoteTypeId}"),
        };
    }

    private static bool ReadBool(ReadContext context, TypeId localTypeId, string fieldName)
    {
        byte raw = context.Reader.ReadUInt8();
        return raw switch
        {
            0 => false,
            1 => true,
            _ => throw Fail(TypeId.Bool, localTypeId, fieldName, $"invalid bool payload {raw}"),
        };
    }

    private static ForyDecimal ReadDecimal(ReadContext context)
    {
        (int scale, BigInteger unscaled) = DecimalCodec.Read(context.Reader);
        return new ForyDecimal(unscaled, scale);
    }

    private static bool ToBool(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        switch (value.Kind)
        {
            case ScalarValueKind.Bool:
                return value.BoolValue;
            case ScalarValueKind.String:
                return value.StringValue switch
                {
                    "0" or "false" => false,
                    "1" or "true" => true,
                    string s => throw Fail(remote, local, fieldName, $"cannot convert string '{s}' to bool"),
                    _ => throw Fail(remote, local, fieldName, "remote value is not a string"),
                };
            default:
                DecimalValue numeric = Normalize(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName);
                if (numeric.Unscaled.IsZero)
                {
                    return false;
                }

                if (numeric.Scale == 0 && numeric.Unscaled.IsOne)
                {
                    return true;
                }

                throw Fail(remote, local, fieldName, "numeric value is not exactly 0 or 1");
        }
    }

    private static string ToStringValue(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        switch (value.Kind)
        {
            case ScalarValueKind.Bool:
                return value.BoolValue ? "true" : "false";
            case ScalarValueKind.String:
                return value.StringValue!;
            case ScalarValueKind.Float16:
            case ScalarValueKind.BFloat16:
            case ScalarValueKind.Float32:
            case ScalarValueKind.Float64:
                {
                    DecimalValue numeric = ToDecimalValue(value, remote, local, fieldName);
                    if (numeric.Unscaled.IsZero)
                    {
                        return numeric.NegativeZero ? "-0.0" : "0.0";
                    }

                    return FormatFloating(Normalize(numeric, remote, local, fieldName));
                }
            case ScalarValueKind.Decimal:
                return FormatDecimal(Normalize(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName));
            default:
                return FormatInteger(ToInteger(value, remote, local, fieldName));
        }
    }

    private static sbyte ToSByte(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return (sbyte)CheckedInteger(ToSignedInteger(value, remote, local, fieldName), SByteMin, SByteMax, remote, local, fieldName);
    }

    private static short ToInt16(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return (short)CheckedInteger(ToSignedInteger(value, remote, local, fieldName), Int16Min, Int16Max, remote, local, fieldName);
    }

    private static int ToInt32(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return (int)CheckedInteger(ToSignedInteger(value, remote, local, fieldName), Int32Min, Int32Max, remote, local, fieldName);
    }

    private static long ToInt64(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return (long)CheckedInteger(ToSignedInteger(value, remote, local, fieldName), Int64Min, Int64Max, remote, local, fieldName);
    }

    private static byte ToByte(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return (byte)CheckedInteger(ToUnsignedInteger(value, remote, local, fieldName), BigInteger.Zero, ByteMax, remote, local, fieldName);
    }

    private static ushort ToUInt16(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return (ushort)CheckedInteger(ToUnsignedInteger(value, remote, local, fieldName), BigInteger.Zero, UInt16Max, remote, local, fieldName);
    }

    private static uint ToUInt32(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return (uint)CheckedInteger(ToUnsignedInteger(value, remote, local, fieldName), BigInteger.Zero, UInt32Max, remote, local, fieldName);
    }

    private static ulong ToUInt64(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return (ulong)CheckedInteger(ToUnsignedInteger(value, remote, local, fieldName), BigInteger.Zero, UInt64Max, remote, local, fieldName);
    }

    private static Half ToHalfTarget(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (TryNonFinite(value, out int sign))
        {
            return sign switch
            {
                < 0 => Half.NegativeInfinity,
                > 0 => Half.PositiveInfinity,
                _ => throw Fail(remote, local, fieldName, "NaN cannot convert across floating scalar types"),
            };
        }

        return ToHalf(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName);
    }

    private static BFloat16 ToBFloat16Target(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (TryNonFinite(value, out int sign))
        {
            return sign switch
            {
                < 0 => BFloat16.NegativeInfinity,
                > 0 => BFloat16.PositiveInfinity,
                _ => throw Fail(remote, local, fieldName, "NaN cannot convert across floating scalar types"),
            };
        }

        return ToBFloat16(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName);
    }

    private static float ToSingleTarget(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (TryNonFinite(value, out int sign))
        {
            return sign switch
            {
                < 0 => float.NegativeInfinity,
                > 0 => float.PositiveInfinity,
                _ => throw Fail(remote, local, fieldName, "NaN cannot convert across floating scalar types"),
            };
        }

        return ToSingle(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName);
    }

    private static double ToDoubleTarget(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (TryNonFinite(value, out int sign))
        {
            return sign switch
            {
                < 0 => double.NegativeInfinity,
                > 0 => double.PositiveInfinity,
                _ => throw Fail(remote, local, fieldName, "NaN cannot convert across floating scalar types"),
            };
        }

        return ToDouble(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName);
    }

    private static ForyDecimal ToForyDecimalTarget(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        DecimalValue decimalValue = Normalize(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName);
        return new ForyDecimal(decimalValue.Unscaled, decimalValue.Scale);
    }

    private static decimal ToSystemDecimalTarget(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return ToSystemDecimal(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName);
    }

    private static decimal ToSystemDecimal(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        value = Normalize(value, remote, local, fieldName);
        if (value.Scale is < 0 or > 28)
        {
            throw Fail(remote, local, fieldName, $"decimal scale {value.Scale} is outside System.Decimal range");
        }

        bool negative = value.Unscaled.Sign < 0;
        BigInteger magnitude = BigInteger.Abs(value.Unscaled);
        if ((magnitude >> 96) != BigInteger.Zero)
        {
            throw Fail(remote, local, fieldName, "decimal magnitude exceeds System.Decimal range");
        }

        int lo = unchecked((int)(uint)(magnitude & DecimalMask));
        int mid = unchecked((int)(uint)((magnitude >> 32) & DecimalMask));
        int hi = unchecked((int)(uint)((magnitude >> 64) & DecimalMask));
        return new decimal(lo, mid, hi, negative, (byte)value.Scale);
    }

    private static Half ToHalf(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        float parsed = ParseSingle(value, remote, local, fieldName);
        Half candidate = (Half)parsed;
        CheckFloatExact(FromHalf(candidate, remote, local, fieldName), value, remote, local, fieldName);
        return candidate;
    }

    private static BFloat16 ToBFloat16(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        float parsed = ParseSingle(value, remote, local, fieldName);
        BFloat16 candidate = BFloat16.FromSingle(parsed);
        CheckFloatExact(FromBFloat16(candidate, remote, local, fieldName), value, remote, local, fieldName);
        return candidate;
    }

    private static float ToSingle(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        float candidate = ParseSingle(value, remote, local, fieldName);
        CheckFloatExact(FromSingle(candidate, remote, local, fieldName), value, remote, local, fieldName);
        return candidate;
    }

    private static double ToDouble(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        double candidate = ParseDouble(value, remote, local, fieldName);
        CheckFloatExact(FromDouble(candidate, remote, local, fieldName), value, remote, local, fieldName);
        return candidate;
    }

    private static float ParseSingle(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (value.Unscaled.IsZero)
        {
            return value.NegativeZero ? -0.0f : 0.0f;
        }

        try
        {
            return float.Parse(FormatDecimal(value), NumberStyles.Float, CultureInfo.InvariantCulture);
        }
        catch (Exception ex) when (ex is FormatException or OverflowException)
        {
            throw Fail(remote, local, fieldName, "value is not representable as float32");
        }
    }

    private static double ParseDouble(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (value.Unscaled.IsZero)
        {
            return value.NegativeZero ? -0.0d : 0.0d;
        }

        try
        {
            return double.Parse(FormatDecimal(value), NumberStyles.Float, CultureInfo.InvariantCulture);
        }
        catch (Exception ex) when (ex is FormatException or OverflowException)
        {
            throw Fail(remote, local, fieldName, "value is not representable as float64");
        }
    }

    private static void CheckFloatExact(
        DecimalValue candidate,
        DecimalValue expected,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        candidate = Normalize(candidate, remote, local, fieldName);
        expected = Normalize(expected, remote, local, fieldName);
        if (candidate.Unscaled == expected.Unscaled &&
            candidate.Scale == expected.Scale &&
            (!expected.Unscaled.IsZero || candidate.NegativeZero == expected.NegativeZero))
        {
            return;
        }

        throw Fail(remote, local, fieldName, "numeric value is not exactly representable by target floating type");
    }

    private static BigInteger Integral(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        value = Normalize(value, remote, local, fieldName);
        if (value.Scale == 0)
        {
            return value.Unscaled;
        }

        BigInteger divisor = Pow10(value.Scale);
        BigInteger remainder;
        BigInteger quotient = BigInteger.DivRem(value.Unscaled, divisor, out remainder);
        if (remainder.IsZero)
        {
            return quotient;
        }

        throw Fail(remote, local, fieldName, "numeric value is not integral");
    }

    private static BigInteger UnsignedIntegral(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        BigInteger integer = Integral(value, remote, local, fieldName);
        if (integer.Sign >= 0)
        {
            return integer;
        }

        throw Fail(remote, local, fieldName, "negative value cannot convert to unsigned target");
    }

    private static BigInteger CheckedInteger(
        BigInteger value,
        BigInteger min,
        BigInteger max,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        if (value < min || value > max)
        {
            throw Fail(remote, local, fieldName, $"integer value {value} is outside {local} range");
        }

        return value;
    }

    private static BigInteger ToSignedInteger(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (value.Kind == ScalarValueKind.Bool)
        {
            return value.BoolValue ? BigInteger.One : BigInteger.Zero;
        }

        if (value.Kind is ScalarValueKind.Signed or ScalarValueKind.Unsigned)
        {
            return ToInteger(value, remote, local, fieldName);
        }

        return Integral(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName);
    }

    private static BigInteger ToUnsignedInteger(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (value.Kind == ScalarValueKind.Bool)
        {
            return value.BoolValue ? BigInteger.One : BigInteger.Zero;
        }

        if (value.Kind is ScalarValueKind.Signed or ScalarValueKind.Unsigned)
        {
            BigInteger integer = ToInteger(value, remote, local, fieldName);
            if (integer.Sign >= 0)
            {
                return integer;
            }

            throw Fail(remote, local, fieldName, "negative value cannot convert to unsigned target");
        }

        return UnsignedIntegral(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName);
    }

    private static BigInteger ToInteger(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return value.Kind switch
        {
            ScalarValueKind.Signed => value.SignedValue,
            ScalarValueKind.Unsigned => value.UnsignedValue,
            _ => throw Fail(remote, local, fieldName, $"remote value kind {value.Kind} is not an integer"),
        };
    }

    private static DecimalValue ToDecimalValue(ScalarValue value, TypeId remote, TypeId local, string fieldName)
    {
        return value.Kind switch
        {
            ScalarValueKind.Bool => new DecimalValue(value.BoolValue ? BigInteger.One : BigInteger.Zero, 0, false),
            ScalarValueKind.Signed => new DecimalValue(value.SignedValue, 0, false),
            ScalarValueKind.Unsigned => new DecimalValue(value.UnsignedValue, 0, false),
            ScalarValueKind.Float16 => FromHalfChecked(value.HalfValue, remote, local, fieldName),
            ScalarValueKind.BFloat16 => FromBFloat16Checked(value.BFloat16Value, remote, local, fieldName),
            ScalarValueKind.Float32 => FromSingleChecked(value.SingleValue, remote, local, fieldName),
            ScalarValueKind.Float64 => FromDoubleChecked(value.DoubleValue, remote, local, fieldName),
            ScalarValueKind.Decimal => new DecimalValue(value.DecimalValue.UnscaledValue, value.DecimalValue.Scale, false),
            ScalarValueKind.String => TryParseNumber(value.StringValue!, out DecimalValue parsed)
                ? parsed
                : throw Fail(remote, local, fieldName, "string is not a compatible numeric literal"),
            _ => throw Fail(remote, local, fieldName, $"remote value kind {value.Kind} is not numeric"),
        };
    }

    private static bool TryNonFinite(ScalarValue value, out int sign)
    {
        sign = 0;
        switch (value.Kind)
        {
            case ScalarValueKind.Float16 when Half.IsNaN(value.HalfValue):
                return true;
            case ScalarValueKind.Float16 when Half.IsInfinity(value.HalfValue):
                sign = Half.IsNegative(value.HalfValue) ? -1 : 1;
                return true;
            case ScalarValueKind.BFloat16 when value.BFloat16Value.IsNaN:
                return true;
            case ScalarValueKind.BFloat16 when value.BFloat16Value.IsInfinity:
                sign = value.BFloat16Value.SignBit ? -1 : 1;
                return true;
            case ScalarValueKind.Float32 when float.IsNaN(value.SingleValue):
                return true;
            case ScalarValueKind.Float32 when float.IsInfinity(value.SingleValue):
                sign = float.IsNegative(value.SingleValue) ? -1 : 1;
                return true;
            case ScalarValueKind.Float64 when double.IsNaN(value.DoubleValue):
                return true;
            case ScalarValueKind.Float64 when double.IsInfinity(value.DoubleValue):
                sign = double.IsNegative(value.DoubleValue) ? -1 : 1;
                return true;
            default:
                return false;
        }
    }

    private static DecimalValue FromHalfChecked(Half value, TypeId remote, TypeId local, string fieldName)
    {
        if (!Half.IsFinite(value))
        {
            throw Fail(remote, local, fieldName, "non-finite float16 cannot convert to this target");
        }

        return FromHalf(value, remote, local, fieldName);
    }

    private static DecimalValue FromBFloat16Checked(BFloat16 value, TypeId remote, TypeId local, string fieldName)
    {
        if (!value.IsFinite)
        {
            throw Fail(remote, local, fieldName, "non-finite bfloat16 cannot convert to this target");
        }

        return FromBFloat16(value, remote, local, fieldName);
    }

    private static DecimalValue FromSingleChecked(float value, TypeId remote, TypeId local, string fieldName)
    {
        if (!float.IsFinite(value))
        {
            throw Fail(remote, local, fieldName, "non-finite float32 cannot convert to this target");
        }

        return FromSingle(value, remote, local, fieldName);
    }

    private static DecimalValue FromDoubleChecked(double value, TypeId remote, TypeId local, string fieldName)
    {
        if (!double.IsFinite(value))
        {
            throw Fail(remote, local, fieldName, "non-finite float64 cannot convert to this target");
        }

        return FromDouble(value, remote, local, fieldName);
    }

    private static DecimalValue FromSystemDecimal(decimal value)
    {
        int[] bits = decimal.GetBits(value);
        BigInteger unscaled =
            ((BigInteger)(uint)bits[2] << 64) |
            ((BigInteger)(uint)bits[1] << 32) |
            (uint)bits[0];
        if ((bits[3] & unchecked((int)0x8000_0000)) != 0)
        {
            unscaled = BigInteger.Negate(unscaled);
        }

        return new DecimalValue(unscaled, (bits[3] >> 16) & 0xFF, false);
    }

    private static DecimalValue FromHalf(Half value, TypeId remote, TypeId local, string fieldName)
    {
        ushort bits = BitConverter.HalfToUInt16Bits(value);
        return FromFloatBits(bits, 10, 5, 15, remote, local, fieldName);
    }

    private static DecimalValue FromBFloat16(BFloat16 value, TypeId remote, TypeId local, string fieldName)
    {
        ushort bits = value.ToBits();
        return FromFloatBits(bits, 7, 8, 127, remote, local, fieldName);
    }

    private static DecimalValue FromSingle(float value, TypeId remote, TypeId local, string fieldName)
    {
        uint bits = BitConverter.SingleToUInt32Bits(value);
        return FromFloatBits(bits, 23, 8, 127, remote, local, fieldName);
    }

    private static DecimalValue FromDouble(double value, TypeId remote, TypeId local, string fieldName)
    {
        ulong bits = BitConverter.DoubleToUInt64Bits(value);
        return FromFloatBits(bits, 52, 11, 1023, remote, local, fieldName);
    }

    private static DecimalValue FromFloatBits(
        ulong bits,
        int fractionBits,
        int exponentBits,
        int exponentBias,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        bool negative = (bits & (1UL << (fractionBits + exponentBits))) != 0;
        ulong exponentMask = (1UL << exponentBits) - 1UL;
        ulong exponent = (bits >> fractionBits) & exponentMask;
        ulong fractionMask = (1UL << fractionBits) - 1UL;
        ulong fraction = bits & fractionMask;
        if (exponent == 0 && fraction == 0)
        {
            return new DecimalValue(BigInteger.Zero, 0, negative);
        }

        BigInteger significand = exponent == 0
            ? fraction
            : (BigInteger.One << fractionBits) | fraction;
        int binaryExponent = (exponent == 0 ? 1 - exponentBias : (int)exponent - exponentBias) - fractionBits;
        if (binaryExponent >= 0)
        {
            BigInteger unscaled = significand << binaryExponent;
            return new DecimalValue(negative ? BigInteger.Negate(unscaled) : unscaled, 0, false);
        }

        int scale = checked(-binaryExponent);
        if (scale > MaxCompatibleDecimalDigits)
        {
            throw Fail(remote, local, fieldName, "float decimal expansion is too large");
        }
        BigInteger decimalSignificand = significand * BigInteger.Pow(5, scale);
        return Normalize(new DecimalValue(
            negative ? BigInteger.Negate(decimalSignificand) : decimalSignificand,
            scale,
            false), remote, local, fieldName);
    }

    private static bool TryParseNumber(string value, out DecimalValue result)
    {
        result = default;
        if (value.Length == 0 || value.Length > MaxCompatibleNumericTextLength)
        {
            return false;
        }

        int index = 0;
        bool negative = value[0] == '-';
        if (negative)
        {
            index++;
            if (index == value.Length)
            {
                return false;
            }
        }

        int intStart = index;
        int significantDigits = 0;
        bool seenNonZero = false;
        if (value[index] == '0')
        {
            CountSignificantDigit(value[index], ref seenNonZero, ref significantDigits);
            index++;
            if (index < value.Length && IsDigit(value[index]))
            {
                return false;
            }
        }
        else if (value[index] is >= '1' and <= '9')
        {
            while (index < value.Length && IsDigit(value[index]))
            {
                CountSignificantDigit(value[index], ref seenNonZero, ref significantDigits);
                index++;
            }
        }
        else
        {
            return false;
        }

        int intEnd = index;
        int fracStart = index;
        int fracEnd = index;
        if (index < value.Length && value[index] == '.')
        {
            index++;
            fracStart = index;
            while (index < value.Length && IsDigit(value[index]))
            {
                CountSignificantDigit(value[index], ref seenNonZero, ref significantDigits);
                index++;
            }

            if (index == fracStart)
            {
                return false;
            }

            fracEnd = index;
        }

        int exponent = 0;
        if (index < value.Length && (value[index] == 'e' || value[index] == 'E'))
        {
            index++;
            bool exponentNegative = index < value.Length && value[index] == '-';
            if (exponentNegative)
            {
                index++;
            }

            if (index == value.Length)
            {
                return false;
            }

            if (value[index] == '0')
            {
                index++;
                if (index < value.Length && IsDigit(value[index]))
                {
                    return false;
                }
            }
            else if (value[index] is >= '1' and <= '9')
            {
                while (index < value.Length && IsDigit(value[index]))
                {
                    exponent = exponent * 10 + value[index] - '0';
                    if (exponent > MaxCompatibleDecimalDigits)
                    {
                        return false;
                    }
                    index++;
                }
            }
            else
            {
                return false;
            }

            if (exponentNegative)
            {
                exponent = -exponent;
            }
        }

        if (index != value.Length)
        {
            return false;
        }

        if (significantDigits > MaxCompatibleDecimalDigits)
        {
            return false;
        }

        int scale = fracEnd - fracStart - exponent;
        if (!CompatibleDecimalShape(significantDigits, scale))
        {
            return false;
        }

        string digits = string.Concat(value.AsSpan(intStart, intEnd - intStart), value.AsSpan(fracStart, fracEnd - fracStart));
        BigInteger unscaled = BigInteger.Parse(digits, CultureInfo.InvariantCulture);
        if (negative)
        {
            unscaled = BigInteger.Negate(unscaled);
        }

        return TryNormalize(new DecimalValue(unscaled, scale, negative && unscaled.IsZero), out result);
    }

    private static void CountSignificantDigit(char digit, ref bool seenNonZero, ref int significantDigits)
    {
        if (digit != '0' || seenNonZero)
        {
            seenNonZero = true;
            significantDigits++;
        }
    }

    private static bool CompatibleDecimalShape(int significantDigits, int scale)
    {
        if (scale > MaxCompatibleDecimalDigits)
        {
            return false;
        }
        return scale >= 0 || significantDigits + (-scale) <= MaxCompatibleDecimalDigits;
    }

    private static bool IsDigit(char value)
    {
        return value is >= '0' and <= '9';
    }

    private static DecimalValue Normalize(
        DecimalValue value,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        if (TryNormalize(value, out DecimalValue normalized))
        {
            return normalized;
        }

        throw Fail(remote, local, fieldName, "converted decimal exceeds compatible conversion bounds");
    }

    private static bool TryNormalize(DecimalValue value, out DecimalValue normalized)
    {
        BigInteger unscaled = value.Unscaled;
        long scale = value.Scale;
        if (scale < 0)
        {
            long extraDigits = -scale;
            if (extraDigits > MaxCompatibleDecimalDigits ||
                DecimalDigitCount(unscaled) + extraDigits > MaxCompatibleDecimalDigits)
            {
                normalized = default;
                return false;
            }
            unscaled *= Pow10((int)extraDigits);
            scale = 0;
        }

        while (scale > 0 && !unscaled.IsZero)
        {
            BigInteger remainder;
            BigInteger quotient = BigInteger.DivRem(unscaled, 10, out remainder);
            if (!remainder.IsZero)
            {
                break;
            }

            unscaled = quotient;
            scale--;
        }

        if (unscaled.IsZero)
        {
            normalized = new DecimalValue(BigInteger.Zero, 0, value.NegativeZero);
            return true;
        }

        if (scale > MaxCompatibleDecimalDigits ||
            DecimalDigitCount(unscaled) > MaxCompatibleDecimalDigits)
        {
            normalized = default;
            return false;
        }

        normalized = new DecimalValue(unscaled, (int)scale, false);
        return true;
    }

    private static string FormatInteger(BigInteger value)
    {
        return value.ToString(CultureInfo.InvariantCulture);
    }

    private static string FormatDecimal(DecimalValue value)
    {
        if (value.Unscaled.IsZero)
        {
            return "0";
        }

        bool negative = value.Unscaled.Sign < 0;
        string digits = BigInteger.Abs(value.Unscaled).ToString(CultureInfo.InvariantCulture);
        if (value.Scale == 0)
        {
            return negative ? "-" + digits : digits;
        }

        string text;
        if (digits.Length > value.Scale)
        {
            int point = digits.Length - value.Scale;
            text = digits[..point] + "." + digits[point..];
        }
        else
        {
            text = "0." + new string('0', value.Scale - digits.Length) + digits;
        }

        return negative ? "-" + text : text;
    }

    private static string FormatFloating(DecimalValue value)
    {
        string text = FormatDecimal(value);
        return text.Contains('.', StringComparison.Ordinal) ? text : text + ".0";
    }

    private static BigInteger Pow10(int scale)
    {
        return BigInteger.Pow(10, scale);
    }

    private static int DecimalDigitCount(BigInteger value)
    {
        BigInteger magnitude = BigInteger.Abs(value);
        if (magnitude >= MaxCompatibleDecimalMagnitude)
        {
            return MaxCompatibleDecimalDigits + 1;
        }

        return magnitude.ToString(CultureInfo.InvariantCulture).Length;
    }

    private static TypeId NormalizeScalarTypeId(uint typeId)
    {
        return typeId switch
        {
            (uint)TypeId.Int32 or (uint)TypeId.VarInt32 => TypeId.Int32,
            (uint)TypeId.Int64 or (uint)TypeId.VarInt64 or (uint)TypeId.TaggedInt64 => TypeId.Int64,
            (uint)TypeId.UInt32 or (uint)TypeId.VarUInt32 => TypeId.UInt32,
            (uint)TypeId.UInt64 or (uint)TypeId.VarUInt64 or (uint)TypeId.TaggedUInt64 => TypeId.UInt64,
            _ => (TypeId)typeId,
        };
    }

    private static bool IsInteger(TypeId typeId)
    {
        return typeId is TypeId.Int8 or TypeId.Int16 or TypeId.Int32 or TypeId.Int64 or
            TypeId.UInt8 or TypeId.UInt16 or TypeId.UInt32 or TypeId.UInt64;
    }

    private static bool IsFloating(TypeId typeId)
    {
        return typeId is TypeId.Float16 or TypeId.BFloat16 or TypeId.Float32 or TypeId.Float64;
    }

    private static bool IsNumeric(TypeId typeId)
    {
        return IsInteger(typeId) || IsFloating(typeId) || typeId == TypeId.Decimal;
    }

    private static bool IsScalar(TypeId typeId)
    {
        return typeId == TypeId.Bool || typeId == TypeId.String || IsNumeric(typeId);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static InvalidDataException Fail(TypeId remote, TypeId local, string fieldName, string reason)
    {
        return new InvalidDataException(
            $"compatible scalar conversion failed for field '{fieldName}' from {remote} to {local}: {reason}");
    }

    private enum ScalarValueKind
    {
        Bool,
        String,
        Signed,
        Unsigned,
        Float16,
        BFloat16,
        Float32,
        Float64,
        Decimal,
    }

    private readonly struct ScalarValue
    {
        private ScalarValue(
            ScalarValueKind kind,
            bool boolValue = false,
            string? stringValue = null,
            long signedValue = 0,
            ulong unsignedValue = 0,
            Half halfValue = default,
            BFloat16 bfloat16Value = default,
            float singleValue = default,
            double doubleValue = default,
            ForyDecimal decimalValue = default)
        {
            Kind = kind;
            BoolValue = boolValue;
            StringValue = stringValue;
            SignedValue = signedValue;
            UnsignedValue = unsignedValue;
            HalfValue = halfValue;
            BFloat16Value = bfloat16Value;
            SingleValue = singleValue;
            DoubleValue = doubleValue;
            DecimalValue = decimalValue;
        }

        public ScalarValueKind Kind { get; }

        public bool BoolValue { get; }

        public string? StringValue { get; }

        public long SignedValue { get; }

        public ulong UnsignedValue { get; }

        public Half HalfValue { get; }

        public BFloat16 BFloat16Value { get; }

        public float SingleValue { get; }

        public double DoubleValue { get; }

        public ForyDecimal DecimalValue { get; }

        public static ScalarValue ForBool(bool value)
        {
            return new ScalarValue(ScalarValueKind.Bool, boolValue: value);
        }

        public static ScalarValue ForString(string value)
        {
            return new ScalarValue(ScalarValueKind.String, stringValue: value);
        }

        public static ScalarValue ForSigned(long value)
        {
            return new ScalarValue(ScalarValueKind.Signed, signedValue: value);
        }

        public static ScalarValue ForUnsigned(ulong value)
        {
            return new ScalarValue(ScalarValueKind.Unsigned, unsignedValue: value);
        }

        public static ScalarValue ForHalf(Half value)
        {
            return new ScalarValue(ScalarValueKind.Float16, halfValue: value);
        }

        public static ScalarValue ForBFloat16(BFloat16 value)
        {
            return new ScalarValue(ScalarValueKind.BFloat16, bfloat16Value: value);
        }

        public static ScalarValue ForSingle(float value)
        {
            return new ScalarValue(ScalarValueKind.Float32, singleValue: value);
        }

        public static ScalarValue ForDouble(double value)
        {
            return new ScalarValue(ScalarValueKind.Float64, doubleValue: value);
        }

        public static ScalarValue ForDecimal(ForyDecimal value)
        {
            return new ScalarValue(ScalarValueKind.Decimal, decimalValue: value);
        }
    }

    private readonly record struct DecimalValue(BigInteger Unscaled, int Scale, bool NegativeZero);
}
