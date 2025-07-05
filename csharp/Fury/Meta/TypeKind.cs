using System;

namespace Fury.Meta;

public enum TypeKind : byte
{
    /// <inheritdoc cref="InternalTypeKind.String"/>
    String = 12,

    /// <inheritdoc cref="InternalTypeKind.List"/>
    List = 27,

    /// <inheritdoc cref="InternalTypeKind.Set"/>
    Set = 28,

    /// <inheritdoc cref="InternalTypeKind.Map"/>
    Map = 29,

    /// <inheritdoc cref="InternalTypeKind.Duration"/>
    Duration = 30,

    /// <inheritdoc cref="InternalTypeKind.Timestamp"/>
    Timestamp = 31,

    /// <inheritdoc cref="InternalTypeKind.LocalDate"/>
    LocalDate = 32,

    /// <inheritdoc cref="InternalTypeKind.Binary"/>
    Binary = 34,

    /// <inheritdoc cref="InternalTypeKind.Array"/>
    Array = 35,

    /// <inheritdoc cref="InternalTypeKind.BoolArray"/>
    BoolArray = 36,

    /// <inheritdoc cref="InternalTypeKind.Int8Array"/>
    Int8Array = 37,

    /// <inheritdoc cref="InternalTypeKind.Int16Array"/>
    Int16Array = 38,

    /// <inheritdoc cref="InternalTypeKind.Int32Array"/>
    Int32Array = 39,

    /// <inheritdoc cref="InternalTypeKind.Int64Array"/>
    Int64Array = 40,

    /// <inheritdoc cref="InternalTypeKind.Float16Array"/>
    Float16Array = 41,

    /// <inheritdoc cref="InternalTypeKind.Float32Array"/>
    Float32Array = 42,

    /// <inheritdoc cref="InternalTypeKind.Float64Array"/>
    Float64Array = 43,
}

internal static class TypeKindHelper
{
    public static InternalTypeKind ToInternal(this TypeKind typeKind)
    {
        return (InternalTypeKind)(byte)typeKind;
    }

    public static TypeKind SelectListTypeKind(Type elementType)
    {
        var typeCode = Type.GetTypeCode(elementType);
        return typeCode switch
        {
            TypeCode.Boolean => TypeKind.BoolArray,
            TypeCode.SByte => TypeKind.Int8Array,
            TypeCode.Byte => TypeKind.Int8Array,
            TypeCode.Int16 => TypeKind.Int16Array,
            TypeCode.UInt16 => TypeKind.Int16Array,
            TypeCode.Int32 => TypeKind.Int32Array,
            TypeCode.UInt32 => TypeKind.Int32Array,
            TypeCode.Int64 => TypeKind.Int64Array,
            TypeCode.UInt64 => TypeKind.Int64Array,
#if NET5_0_OR_GREATER
            _ when elementType == typeof(Half) => TypeKind.Float16Array,
#endif
            TypeCode.Single => TypeKind.Float32Array,
            TypeCode.Double => TypeKind.Float64Array,
            _ => TypeKind.List
        };
    }
}

/// <summary>
/// Represents various data types used in the system.
/// </summary>
internal enum InternalTypeKind : byte
{
    /// <summary>
    /// bool: a boolean value (true or false).
    /// </summary>
    Bool = 1,

    /// <summary>
    /// int8: an 8-bit signed integer.
    /// </summary>
    Int8 = 2,

    /// <summary>
    /// int16: a 16-bit signed integer.
    /// </summary>
    Int16 = 3,

    /// <summary>
    /// int32: a 32-bit signed integer.
    /// </summary>
    Int32 = 4,

    /// <summary>
    /// var_int32: a 32-bit signed integer which uses fury var_int32 encoding.
    /// </summary>
    VarInt32 = 5,

    /// <summary>
    /// int64: a 64-bit signed integer.
    /// </summary>
    Int64 = 6,

    /// <summary>
    /// var_int64: a 64-bit signed integer which uses fury PVL encoding.
    /// </summary>
    VarInt64 = 7,

    /// <summary>
    /// sli_int64: a 64-bit signed integer which uses fury SLI encoding.
    /// </summary>
    SliInt64 = 8,

    /// <summary>
    /// float16: a 16-bit floating point number.
    /// </summary>
    Float16 = 9,

    /// <summary>
    /// float32: a 32-bit floating point number.
    /// </summary>
    Float32 = 10,

    /// <summary>
    /// float64: a 64-bit floating point number including NaN and Infinity.
    /// </summary>
    Float64 = 11,

    /// <summary>
    /// string: a text string encoded using Latin1/UTF16/UTF-8 encoding.
    /// </summary>
    String = 12,

    /// <summary>
    /// enum: a data type consisting of a set of named values.
    /// </summary>
    Enum = 13,

    /// <summary>
    /// named_enum: an enum whose value will be serialized as the registered name.
    /// </summary>
    NamedEnum = 14,

    /// <summary>
    /// A morphic(sealed) type serialized by Fury Struct serializer. i.e. it doesn't have subclasses.
    /// We can save dynamic serializer dispatch if target type is morphic(sealed).
    /// </summary>
    Struct = 15,

    /// <summary>
    /// A morphic(sealed) type serialized by Fury compatible Struct serializer.
    /// </summary>
    CompatibleStruct = 16,

    /// <summary>
    /// A <see cref="Struct"/> whose type mapping will be encoded as a name.
    /// </summary>
    NamedStruct = 17,

    /// <summary>
    /// A <see cref="CompatibleStruct"/> whose type mapping will be encoded as a name.
    /// </summary>
    NamedCompatibleStruct = 18,

    /// <summary>
    /// A type which will be serialized by a customized serializer.
    /// </summary>
    Ext = 19,

    /// <summary>
    /// An <see cref="Ext"/> type whose type mapping will be encoded as a name.
    /// </summary>
    NamedExt = 20,

    /// <summary>
    /// A sequence of objects.
    /// </summary>
    List = 21,

    /// <summary>
    /// An unordered set of unique elements.
    /// </summary>
    Set = 22,

    /// <summary>
    /// A map of key-value pairs. Mutable types such as `list/map/set/array/tensor/arrow` are not
    /// allowed as key of map.
    /// </summary>
    Map = 23,

    /// <summary>
    /// An absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
    /// </summary>
    Duration = 24,

    /// <summary>
    /// A point in time, independent of any calendar/timezone, as a count of nanoseconds. The count is
    /// relative to an epoch at UTC midnight on January 1, 1970.
    /// </summary>
    Timestamp = 25,

    /// <summary>
    /// A naive date without timezone. The count is days relative to an epoch at UTC midnight on Jan 1,
    /// 1970.
    /// </summary>
    LocalDate = 26,

    /// <summary>
    /// Exact decimal value represented as an integer value in two's complement.
    /// </summary>
    Decimal = 27,

    /// <summary>
    /// A variable-length array of bytes.
    /// </summary>
    Binary = 28,

    /// <summary>
    /// A multidimensional array where every sub-array can have different sizes but all have the same
    /// type. Only numeric components allowed. Other arrays will be taken as List. The implementation
    /// should support interoperability between array and list.
    /// </summary>
    Array = 29,

    /// <summary>
    /// One dimensional bool array.
    /// </summary>
    BoolArray = 30,

    /// <summary>
    /// One dimensional int8 array.
    /// </summary>
    Int8Array = 31,

    /// <summary>
    /// One dimensional int16 array.
    /// </summary>
    Int16Array = 32,

    /// <summary>
    /// One dimensional int32 array.
    /// </summary>
    Int32Array = 33,

    /// <summary>
    /// One dimensional int64 array.
    /// </summary>
    Int64Array = 34,

    /// <summary>
    /// One dimensional half_float_16 array.
    /// </summary>
    Float16Array = 35,

    /// <summary>
    /// One dimensional float32 array.
    /// </summary>
    Float32Array = 36,

    /// <summary>
    /// One dimensional float64 array.
    /// </summary>
    Float64Array = 37,

    /// <summary>
    /// An (arrow record batch) object.
    /// </summary>
    ArrowRecordBatch = 38,

    /// <summary>
    /// An (arrow table) object.
    /// </summary>
    ArrowTable = 39,
}

internal static class InternalTypeKindExtensions
{
    private const InternalTypeKind InvalidInternalTypeKind = 0;
    private const TypeKind InvalidTypeKind = 0;

    public static bool IsStructType(this InternalTypeKind typeKind)
    {
        return typeKind switch
        {
            InternalTypeKind.Struct => true,
            InternalTypeKind.CompatibleStruct => true,
            InternalTypeKind.NamedStruct => true,
            InternalTypeKind.NamedCompatibleStruct => true,
            _ => false,
        };
    }

    public static bool IsNamed(this InternalTypeKind typeKind)
    {
        return typeKind switch
        {
            InternalTypeKind.NamedEnum => true,
            InternalTypeKind.NamedStruct => true,
            InternalTypeKind.NamedCompatibleStruct => true,
            InternalTypeKind.NamedExt => true,
            _ => false,
        };
    }

    public static bool IsCompatible(this InternalTypeKind typeKind)
    {
        return typeKind switch
        {
            InternalTypeKind.CompatibleStruct => true,
            InternalTypeKind.NamedCompatibleStruct => true,
            _ => false,
        };
    }

    public static bool IsEnum(this InternalTypeKind typeKind)
    {
        return typeKind switch
        {
            InternalTypeKind.Enum => true,
            InternalTypeKind.NamedEnum => true,
            _ => false,
        };
    }

    public static bool IsCustomSerialization(this InternalTypeKind typeKind)
    {
        return typeKind switch
        {
            InternalTypeKind.Ext => true,
            InternalTypeKind.NamedExt => true,
            _ => false,
        };
    }

    public static bool TryToBeNamed(this InternalTypeKind typeKind, out InternalTypeKind namedTypeKind)
    {
        namedTypeKind = typeKind switch
        {
            InternalTypeKind.Enum => InternalTypeKind.NamedEnum,
            InternalTypeKind.Struct => InternalTypeKind.NamedStruct,
            InternalTypeKind.CompatibleStruct => InternalTypeKind.NamedCompatibleStruct,
            InternalTypeKind.Ext => InternalTypeKind.NamedExt,
            _ => InvalidInternalTypeKind,
        };
        return namedTypeKind != InvalidInternalTypeKind;
    }

    public static bool TryToBePublic(this InternalTypeKind internalTypeKind, out TypeKind typeKind)
    {
        typeKind = internalTypeKind switch
        {
            InternalTypeKind.String => TypeKind.String,
            InternalTypeKind.List => TypeKind.List,
            InternalTypeKind.Set => TypeKind.Set,
            InternalTypeKind.Map => TypeKind.Map,
            InternalTypeKind.Duration => TypeKind.Duration,
            InternalTypeKind.Timestamp => TypeKind.Timestamp,
            InternalTypeKind.LocalDate => TypeKind.LocalDate,
            InternalTypeKind.Binary => TypeKind.Binary,
            InternalTypeKind.Array => TypeKind.Array,
            InternalTypeKind.BoolArray => TypeKind.BoolArray,
            InternalTypeKind.Int8Array => TypeKind.Int8Array,
            InternalTypeKind.Int16Array => TypeKind.Int16Array,
            InternalTypeKind.Int32Array => TypeKind.Int32Array,
            InternalTypeKind.Int64Array => TypeKind.Int64Array,
            InternalTypeKind.Float16Array => TypeKind.Float16Array,
            InternalTypeKind.Float32Array => TypeKind.Float32Array,
            InternalTypeKind.Float64Array => TypeKind.Float64Array,
            _ => InvalidTypeKind,
        };

        return typeKind != InvalidTypeKind;
    }
}
