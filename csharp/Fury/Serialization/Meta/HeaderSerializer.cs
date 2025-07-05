using System;
using System.Diagnostics.CodeAnalysis;
using System.Threading;
using System.Threading.Tasks;
using Fury.Context;
using Fury.Meta;

namespace Fury.Serialization.Meta;

[Flags]
file enum HeaderFlag : byte
{
    NullRootObject = 1,
    LittleEndian = 1 << 1,
    CrossLanguage = 1 << 2,
    OutOfBand = 1 << 3,
}

file static class HeaderHelper
{
    public const short MagicNumber = 0x62D4;
}

internal sealed class HeaderSerializer
{
    private bool _hasWrittenMagicNumber;
    private bool _hasWrittenHeaderFlag;
    private bool _hasWrittenLanguage;

    public void Reset()
    {
        _hasWrittenMagicNumber = false;
        _hasWrittenHeaderFlag = false;
        _hasWrittenLanguage = false;
    }

    public bool Write(ref SerializationWriterRef writerRef, bool rootObjectIsNull)
    {
        if (!_hasWrittenMagicNumber)
        {
            _hasWrittenMagicNumber = writerRef.WriteInt16(HeaderHelper.MagicNumber);
            if (!_hasWrittenMagicNumber)
            {
                return false;
            }
        }

        if (!_hasWrittenHeaderFlag)
        {
            var flag = HeaderFlag.LittleEndian | HeaderFlag.CrossLanguage;
            if (rootObjectIsNull)
            {
                flag |= HeaderFlag.NullRootObject;
            }

            _hasWrittenHeaderFlag = writerRef.WriteUInt8((byte)flag);
            if (!_hasWrittenMagicNumber)
            {
                return false;
            }
        }

        if (!_hasWrittenLanguage)
        {
            _hasWrittenLanguage = writerRef.WriteUInt8((byte)Language.Csharp);
        }

        return _hasWrittenLanguage;
    }
}

internal sealed class HeaderDeserializer
{
    private bool _hasReadMagicNumber;
    private bool _hasReadHeaderFlag;
    private bool _rootObjectIsNull;

    public void Reset()
    {
        _hasReadMagicNumber = false;
        _hasReadHeaderFlag = false;
        _rootObjectIsNull = false;
    }

    public async ValueTask<ReadValueResult<bool>> Read(
        DeserializationReader reader,
        bool isAsync,
        CancellationToken cancellationToken
    )
    {
        if (!_hasReadMagicNumber)
        {
            var magicNumberResult = await reader.ReadInt16(isAsync, cancellationToken);
            if (!magicNumberResult.IsSuccess)
            {
                return ReadValueResult<bool>.Failed;
            }

            _hasReadMagicNumber = true;
            if (magicNumberResult.Value is not HeaderHelper.MagicNumber)
            {
                ThrowBadDeserializationInputException_MagicNumberMismatch();
            }
        }

        if (!_hasReadHeaderFlag)
        {
            var headerFlagResult = await reader.ReadUInt8(isAsync, cancellationToken);
            if (!headerFlagResult.IsSuccess)
            {
                return ReadValueResult<bool>.Failed;
            }

            _hasReadHeaderFlag = true;
            var flag = (HeaderFlag)headerFlagResult.Value;
            if ((flag & HeaderFlag.LittleEndian) == 0)
            {
                ThrowBadDeserializationInputException_BigEndianUnsupported();
            }
            if ((flag & HeaderFlag.CrossLanguage) == 0)
            {
                ThrowBadDeserializationInputException_NonCrossLanguageUnsupported();
            }

            if ((flag & HeaderFlag.OutOfBand) != 0)
            {
                ThrowBadDeserializationInputException_OutOfBandUnsupported();
            }
            _rootObjectIsNull = (flag & HeaderFlag.NullRootObject) != 0;
        }

        return ReadValueResult<bool>.FromValue(_rootObjectIsNull);
    }

    [DoesNotReturn]
    private void ThrowBadDeserializationInputException_MagicNumberMismatch()
    {
        throw new BadDeserializationInputException(
            $"The fury xlang serialization must start with magic number {HeaderHelper.MagicNumber:X}. "
                + $"Please check whether the serialization is based on the xlang protocol and the data didn't corrupt."
        );
    }

    [DoesNotReturn]
    private void ThrowBadDeserializationInputException_BigEndianUnsupported()
    {
        throw new BadSerializationInputException("Non-Little-Endian format detected. Only Little-Endian is supported.");
    }

    [DoesNotReturn]
    private void ThrowBadDeserializationInputException_NonCrossLanguageUnsupported()
    {
        throw new BadSerializationInputException(
            "Non-Cross-Language format detected. Only Cross-Language is supported."
        );
    }

    [DoesNotReturn]
    private void ThrowBadDeserializationInputException_OutOfBandUnsupported()
    {
        throw new BadSerializationInputException("Out-Of-Band format detected. Only In-Band is supported.");
    }
}
