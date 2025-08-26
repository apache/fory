using System;
using System.Diagnostics.CodeAnalysis;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using Fury.Helpers;
using Fury.Serialization.Meta;
using JetBrains.Annotations;

namespace Fury.Context;

/// <summary>
/// A struct that provides a fast way to write data to a <see cref="BatchWriter"/>.
/// It caches the span internally and reduces the potential overhead of virtual calls and type cast from <see cref="Memory{T}"/>.
/// </summary>
public ref struct SerializationWriterRef
{
    private Span<byte> _buffer = Span<byte>.Empty;
    private int Consumed => _batchWriter.Consumed;
    private int _version;

    private readonly BatchWriter _batchWriter;
    public SerializationWriter InnerWriter { get; }

    public SerializationConfig Config => InnerWriter.Config;
    public TypeRegistry TypeRegistry => InnerWriter.TypeRegistry;

    internal SerializationWriterRef(SerializationWriter innerWriter, BatchWriter batchWriter)
    {
        InnerWriter = innerWriter;
        _batchWriter = batchWriter;
        _version = _batchWriter.Version;
    }

    [MustUseReturnValue]
    public bool Write<TTarget>(in TTarget? value, TypeRegistration? registrationHint = null)
    {
        _version--; // make sure the version is out of date
        return InnerWriter.Write(in value, registrationHint);
    }

    [MustUseReturnValue]
    public bool Write<TTarget>(in TTarget? value, TypeRegistration? registrationHint = null)
        where TTarget : struct
    {
        _version--; // make sure the version is out of date
        return InnerWriter.WriteNullable(in value, registrationHint);
    }

    [MustUseReturnValue]
    internal bool Write<TTarget>(in TTarget? value, ObjectMetaOption metaOption, TypeRegistration? registrationHint = null)
    {
        _version--; // make sure the version is out of date
        return InnerWriter.Write(in value, metaOption, registrationHint);
    }

    [MustUseReturnValue]
    internal bool Write<TTarget>(in TTarget? value, ObjectMetaOption metaOption, TypeRegistration? registrationHint = null)
        where TTarget : struct
    {
        _version--; // make sure the version is out of date
        return InnerWriter.WriteNullable(in value, metaOption, registrationHint);
    }

    public void Advance(int count)
    {
        if (_version != _batchWriter.Version)
        {
            ThrowInvalidOperationException_VersionMismatch();
        }
        _batchWriter.Advance(count);
        _version = _batchWriter.Version;
    }

    [MustUseReturnValue]
    public Span<byte> GetSpan(int sizeHint = 0)
    {
        if (_version != _batchWriter.Version)
        {
            SyncToInnerWriter();
        }
        var result = _buffer.Slice(Consumed);
        if (result.Length < sizeHint)
        {
            result = _batchWriter.GetSpan(sizeHint);
            SyncToInnerWriter();
        }

        return result;
    }

    private void SyncToInnerWriter()
    {
        _buffer = _batchWriter.Buffer.Span;
        _version = _batchWriter.Version;
    }

    [DoesNotReturn]
    private static void ThrowInvalidOperationException_VersionMismatch()
    {
        throw new InvalidOperationException(
            $"The {nameof(SerializationWriterRef)} is out of date. Call {nameof(GetSpan)} again to write data."
        );
    }

    #region Write Methods

    /// <summary>
    /// Writes the given bytes to the writer.
    /// </summary>
    /// <param name="bytes">
    /// The byte span to write.
    /// </param>
    /// <returns>
    /// The number of bytes written.
    /// </returns>
    [MustUseReturnValue]
    public int WriteBytes(scoped ReadOnlySpan<byte> bytes)
    {
        var destination = GetSpan(bytes.Length);
        var consumed = bytes.CopyUpTo(destination);
        Advance(consumed);
        return consumed;
    }

    [MustUseReturnValue]
    internal bool WriteUnmanaged<T>(T value)
        where T : unmanaged
    {
        var size = Unsafe.SizeOf<T>();
        var buffer = GetSpan(size);
        if (buffer.Length < size)
        {
            return false;
        }
#if NET8_0_OR_GREATER
        MemoryMarshal.Write(buffer, in value);
#else
        MemoryMarshal.Write(buffer, ref value);
#endif
        Advance(size);

        return true;
    }

    [MustUseReturnValue]
    public bool WriteUInt8(byte value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteInt8(sbyte value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteUInt16(ushort value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteInt16(short value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteUInt32(uint value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteInt32(int value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteInt64(ulong value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteUInt64(long value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteFloat32(float value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteFloat64(double value) => WriteUnmanaged(value);

    [MustUseReturnValue]
    public bool WriteBool(bool value) => WriteUnmanaged(value);

    private bool TryGetSpan(int sizeHint, out Span<byte> span)
    {
        span = GetSpan(sizeHint);
        return span.Length >= sizeHint;
    }

    [MustUseReturnValue]
    public bool Write7BitEncodedInt32(int value)
    {
        var zigzag = BitOperations.RotateLeft((uint)value, 1);
        return Write7BitEncodedUInt32(zigzag);
    }

    [MustUseReturnValue]
    public bool Write7BitEncodedUInt32(uint value)
    {
        Span<byte> buffer;
        switch (value)
        {
            case < 1u << 7:
                return WriteUInt8((byte)value);
            case < 1u << 14:
            {
                const int size = 2;
                if (!TryGetSpan(size, out buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)(value >>> 7);
                Advance(size);
                return true;
            }
            case < 1u << 21:
            {
                const int size = 3;
                if (!TryGetSpan(size, out buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)(value >>> 14);
                Advance(size);
                return true;
            }
            case < 1u << 28:
            {
                const int size = 4;
                if (!TryGetSpan(size, out buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)(value >>> 21);
                Advance(size);
                return true;
            }
            default:
            {
                const int size = 5;
                if (!TryGetSpan(size, out buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)(value >>> 28);
                Advance(size);
                return true;
            }
        }
    }

    [MustUseReturnValue]
    public bool Write7BitEncodedInt64(long value)
    {
        var zigzag = BitOperations.RotateLeft((ulong)value, 1);
        return Write7BitEncodedUInt64(zigzag);
    }

    [MustUseReturnValue]
    public bool Write7BitEncodedUInt64(ulong value)
    {
        switch (value)
        {
            case < 1ul << 7:
                return WriteUInt8((byte)value);
            case < 1ul << 14:
            {
                const int size = 2;
                if (!TryGetSpan(size, out var buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)(value >>> 7);
                Advance(size);
                return true;
            }
            case < 1ul << 21:
            {
                const int size = 3;
                if (!TryGetSpan(size, out var buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)(value >>> 14);
                Advance(size);
                return true;
            }
            case < 1ul << 28:
            {
                const int size = 4;
                if (!TryGetSpan(size, out var buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)(value >>> 21);
                Advance(size);
                return true;
            }
            case < 1ul << 35:
            {
                const int size = 5;
                if (!TryGetSpan(size, out var buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)(value >>> 28);
                Advance(size);
                return true;
            }
            case < 1ul << 42:
            {
                const int size = 6;
                if (!TryGetSpan(size, out var buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)((value >>> 28) | ~0x7Fu);
                buffer[5] = (byte)(value >>> 35);
                Advance(size);
                return true;
            }
            case < 1ul << 49:
            {
                const int size = 7;
                if (!TryGetSpan(size, out var buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)((value >>> 28) | ~0x7Fu);
                buffer[5] = (byte)((value >>> 35) | ~0x7Fu);
                buffer[6] = (byte)(value >>> 42);
                Advance(size);
                return true;
            }
            case < 1ul << 56:
            {
                const int size = 8;
                if (!TryGetSpan(size, out var buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)((value >>> 28) | ~0x7Fu);
                buffer[5] = (byte)((value >>> 35) | ~0x7Fu);
                buffer[6] = (byte)((value >>> 42) | ~0x7Fu);
                buffer[7] = (byte)(value >>> 49);
                Advance(size);
                return true;
            }
            case < 1ul << 63:
            {
                const int size = 9;
                if (!TryGetSpan(size, out var buffer))
                {
                    return false;
                }
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)((value >>> 28) | ~0x7Fu);
                buffer[5] = (byte)((value >>> 35) | ~0x7Fu);
                buffer[6] = (byte)((value >>> 42) | ~0x7Fu);
                buffer[7] = (byte)((value >>> 49) | ~0x7Fu);
                buffer[8] = (byte)(value >>> 56);
                Advance(size);
                return true;
            }
            default:
                ThrowHelper.ThrowUnreachableException();
                return false;
        }
    }

    #endregion
}
