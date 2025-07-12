using System;
using System.Collections.Generic;
using Fury.Serialization;

namespace Fury;

public sealed record FuryConfig(ITypeRegistrationProvider RegistrationProvider, TimeSpan LockTimeOut);

public sealed record SerializationConfig(
    bool ReferenceTracking,
    IEnumerable<StringEncoding> PreferredStringEncodings,
    bool WriteUtf16ByteCountForUtf8Encoding
)
{
    public static readonly SerializationConfig Default = new(false, [StringEncoding.UTF8], false);
};

public sealed record DeserializationConfig(bool ReadUtf16ByteCountForUtf8Encoding)
{
    public static readonly DeserializationConfig Default = new(false);
};
