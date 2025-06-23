using System;
using System.Diagnostics.CodeAnalysis;
using Fury.Meta;

namespace Fury;

public class BadDeserializationInputException(string? message = null, Exception? innerException = null) : Exception(message, innerException);

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_UnrecognizedMetaStringCodePoint(byte codePoint)
    {
        throw new BadDeserializationInputException($"Unrecognized MetaString code point: {codePoint}");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_UpperCaseFlagCannotAppearConsecutively()
    {
        throw new BadDeserializationInputException(
            $"The '{AllToLowerSpecialEncoding.UpperCaseFlag}' cannot appear consecutively"
        );
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_UnknownMetaStringId(int id)
    {
        throw new BadDeserializationInputException($"Unknown MetaString ID: {id}");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_InvalidMagicNumber()
    {
        throw new BadDeserializationInputException("Invalid magic number.");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_NotCrossLanguage()
    {
        throw new BadDeserializationInputException("Not cross language.");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_NotLittleEndian()
    {
        throw new BadDeserializationInputException("Not little endian.");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_BadMetaStringHashCodeOrBytes()
    {
        throw new BadDeserializationInputException("The bytes of meta string do not match the prefixed hash code.");
    }
}
