using System;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using Fury.Context;

namespace Fury.Serialization;

internal sealed class EnumSerializer<TEnum> : AbstractSerializer<TEnum>
    where TEnum : unmanaged, Enum
{
    private static readonly int Size = Unsafe.SizeOf<TEnum>();

    public override bool Serialize(SerializationWriter writer, in TEnum value)
    {
        // TODO: Serialize by name

        var v = value;
        var underlyingValue64 = Size switch
        {
            sizeof(byte) => Unsafe.As<TEnum, byte>(ref v),
            sizeof(ushort) => Unsafe.As<TEnum, ushort>(ref v),
            sizeof(uint) => Unsafe.As<TEnum, uint>(ref v),
            sizeof(ulong) => Unsafe.As<TEnum, ulong>(ref v),
            _ => ThrowHelper.ThrowUnreachableException<ulong>(),
        };

        if (underlyingValue64 > uint.MaxValue)
        {
            ThrowNotSupportedException_TooLong();
        }
        return writer.Write7BitEncodedUInt32((uint)underlyingValue64);
    }

    public override void Reset() { }

    [DoesNotReturn]
    private static void ThrowNotSupportedException_TooLong()
    {
        throw new NotSupportedException(
            $"Cannot serialize ${typeof(TEnum).Name} with value greater than {uint.MaxValue}"
        );
    }
}

internal sealed class EnumDeserializer<TEnum> : AbstractDeserializer<TEnum>
    where TEnum : unmanaged, Enum
{
    private static readonly TypeCode UnderlyingTypeCode = Type.GetTypeCode(Enum.GetUnderlyingType(typeof(TEnum)));

    public override object ReferenceableObject => ThrowInvalidOperationException_AcyclicType();

    public override ReadValueResult<TEnum> Deserialize(DeserializationReader reader)
    {
        var task = CreateAndFillInstance(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public override async ValueTask<ReadValueResult<TEnum>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    )
    {
        return await CreateAndFillInstance(reader, true, cancellationToken);
    }

    private static async ValueTask<ReadValueResult<TEnum>> CreateAndFillInstance(
        DeserializationReader reader,
        bool isAsync,
        CancellationToken cancellationToken = default
    )
    {
        TEnum e;
        var enumValueResult = await reader.Read7BitEncodedUint(isAsync, cancellationToken);
        if (!enumValueResult.IsSuccess)
        {
            return ReadValueResult<TEnum>.Failed;
        }

        var value = enumValueResult.Value;

        switch (UnderlyingTypeCode)
        {
            case TypeCode.Byte:
            case TypeCode.SByte:
                var byteValue = (byte)value;
                e = Unsafe.As<byte, TEnum>(ref byteValue);
                break;
            case TypeCode.UInt16:
            case TypeCode.Int16:
                var shortValue = (ushort)value;
                e = Unsafe.As<ushort, TEnum>(ref shortValue);
                break;
            case TypeCode.UInt32:
            case TypeCode.Int32:
                e = Unsafe.As<uint, TEnum>(ref value);
                break;

            case TypeCode.UInt64:
            case TypeCode.Int64:
                var longValue = (ulong)value;
                e = Unsafe.As<ulong, TEnum>(ref longValue);
                break;
            default:
                e = default;
                ThrowHelper.ThrowUnreachableException();
                break;
        }

        return ReadValueResult<TEnum>.FromValue(e);
    }

    public override void Reset() { }
}
