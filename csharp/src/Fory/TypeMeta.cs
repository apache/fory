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

namespace Apache.Fory;

internal static class TypeMetaConstants
{
    public const int SmallNumFieldsThreshold = 0b1_1111;
    public const byte RegisterByNameFlag = 0b10_0000;
    public const int FieldNameSizeThreshold = 0b1111;
    public const int BigNameThreshold = 0b11_1111;
    public const ulong TypeMetaHasFieldsMetaFlag = 1UL << 8;
    public const ulong TypeMetaCompressedFlag = 1UL << 9;
    public const ulong TypeMetaSizeMask = 0xFF;
    public const ulong TypeMetaNumHashBits = 50;
    public const ulong TypeMetaHashSeed = 47;
    public const uint NoUserTypeId = uint.MaxValue;
}

public static class TypeMetaEncodings
{
    public static readonly MetaStringEncoding[] NamespaceMetaStringEncodings =
    [
        MetaStringEncoding.Utf8,
        MetaStringEncoding.AllToLowerSpecial,
        MetaStringEncoding.LowerUpperDigitSpecial,
    ];

    public static readonly MetaStringEncoding[] TypeNameMetaStringEncodings =
    [
        MetaStringEncoding.Utf8,
        MetaStringEncoding.AllToLowerSpecial,
        MetaStringEncoding.LowerUpperDigitSpecial,
        MetaStringEncoding.FirstToLowerSpecial,
    ];

    public static readonly MetaStringEncoding[] FieldNameMetaStringEncodings =
    [
        MetaStringEncoding.Utf8,
        MetaStringEncoding.AllToLowerSpecial,
        MetaStringEncoding.LowerUpperDigitSpecial,
    ];
}

internal static class TypeMetaUtils
{
    public static int EncodingIndexOf(IReadOnlyList<MetaStringEncoding> encodings, MetaStringEncoding encoding)
    {
        for (int i = 0; i < encodings.Count; i++)
        {
            if (encodings[i] == encoding)
            {
                return i;
            }
        }

        return -1;
    }

    public static string LowerCamelToLowerUnderscore(string name)
    {
        if (name.Length == 0)
        {
            return name;
        }

        Span<char> chars = name.ToCharArray();
        var sb = new System.Text.StringBuilder(name.Length + 4);
        for (int i = 0; i < chars.Length; i++)
        {
            char c = chars[i];
            if (char.IsUpper(c))
            {
                if (i > 0)
                {
                    bool prevUpper = char.IsUpper(chars[i - 1]);
                    bool nextUpperOrEnd = i + 1 >= chars.Length || char.IsUpper(chars[i + 1]);
                    if (!prevUpper || !nextUpperOrEnd)
                    {
                        sb.Append('_');
                    }
                }

                sb.Append(char.ToLowerInvariant(c));
            }
            else
            {
                sb.Append(c);
            }
        }

        return sb.ToString();
    }
}

public sealed class TypeMetaFieldType : IEquatable<TypeMetaFieldType>
{
    public TypeMetaFieldType(
        uint typeId,
        bool nullable,
        bool trackRef = false,
        IReadOnlyList<TypeMetaFieldType>? generics = null)
    {
        TypeId = typeId;
        Nullable = nullable;
        TrackRef = trackRef;
        Generics = generics ?? [];
    }

    public uint TypeId { get; }

    public bool Nullable { get; }

    public bool TrackRef { get; }

    public IReadOnlyList<TypeMetaFieldType> Generics { get; }

    internal void Write(ByteWriter writer, bool writeFlags, bool? nullableOverride = null)
    {
        if (writeFlags)
        {
            uint header = TypeId << 2;
            if (nullableOverride ?? Nullable)
            {
                header |= 0b10;
            }

            if (TrackRef)
            {
                header |= 0b1;
            }

            writer.WriteVarUInt32(header);
        }
        else
        {
            writer.WriteUInt8(unchecked((byte)TypeId));
        }

        if (TypeId is (uint)global::Apache.Fory.TypeId.List or (uint)global::Apache.Fory.TypeId.Set)
        {
            TypeMetaFieldType element = Generics.Count > 0
                ? Generics[0]
                : new TypeMetaFieldType((uint)global::Apache.Fory.TypeId.Unknown, true);
            element.Write(writer, true, element.Nullable);
        }
        else if (TypeId == (uint)global::Apache.Fory.TypeId.Map)
        {
            TypeMetaFieldType key = Generics.Count > 0
                ? Generics[0]
                : new TypeMetaFieldType((uint)global::Apache.Fory.TypeId.Unknown, true);
            TypeMetaFieldType value = Generics.Count > 1
                ? Generics[1]
                : new TypeMetaFieldType((uint)global::Apache.Fory.TypeId.Unknown, true);
            key.Write(writer, true, key.Nullable);
            value.Write(writer, true, value.Nullable);
        }
    }

    internal static TypeMetaFieldType Read(
        ByteReader reader,
        bool readFlags,
        bool? nullable = null,
        bool? trackRef = null)
    {
        uint header = readFlags ? reader.ReadVarUInt32() : reader.ReadUInt8();

        uint typeId;
        bool resolvedNullable;
        bool resolvedTrackRef;
        if (readFlags)
        {
            typeId = header >> 2;
            resolvedNullable = (header & 0b10) != 0;
            resolvedTrackRef = (header & 0b1) != 0;
        }
        else
        {
            typeId = header;
            resolvedNullable = nullable ?? false;
            resolvedTrackRef = trackRef ?? false;
        }

        if (typeId is (uint)global::Apache.Fory.TypeId.List or (uint)global::Apache.Fory.TypeId.Set)
        {
            TypeMetaFieldType element = Read(reader, true);
            return new TypeMetaFieldType(typeId, resolvedNullable, resolvedTrackRef, [element]);
        }

        if (typeId == (uint)global::Apache.Fory.TypeId.Map)
        {
            TypeMetaFieldType key = Read(reader, true);
            TypeMetaFieldType value = Read(reader, true);
            return new TypeMetaFieldType(typeId, resolvedNullable, resolvedTrackRef, [key, value]);
        }

        return new TypeMetaFieldType(typeId, resolvedNullable, resolvedTrackRef);
    }

    public bool Equals(TypeMetaFieldType? other)
    {
        if (other is null)
        {
            return false;
        }

        return TypeId == other.TypeId &&
               Nullable == other.Nullable &&
               TrackRef == other.TrackRef &&
               Generics.SequenceEqual(other.Generics);
    }

    public override bool Equals(object? obj)
    {
        return obj is TypeMetaFieldType other && Equals(other);
    }

    public override int GetHashCode()
    {
        HashCode hc = new();
        hc.Add(TypeId);
        hc.Add(Nullable);
        hc.Add(TrackRef);
        foreach (TypeMetaFieldType t in Generics)
        {
            hc.Add(t);
        }

        return hc.ToHashCode();
    }
}

public sealed class TypeMetaFieldInfo : IEquatable<TypeMetaFieldInfo>
{
    public TypeMetaFieldInfo(short? fieldId, string fieldName, TypeMetaFieldType fieldType)
    {
        FieldId = fieldId;
        FieldName = fieldName;
        FieldType = fieldType;
    }

    public short? FieldId { get; }

    public string FieldName { get; }

    public TypeMetaFieldType FieldType { get; }

    internal void Write(ByteWriter writer)
    {
        byte header = 0;
        if (FieldType.TrackRef)
        {
            header |= 0b1;
        }

        if (FieldType.Nullable)
        {
            header |= 0b10;
        }

        if (FieldId.HasValue)
        {
            short fieldId = FieldId.Value;
            if (fieldId < 0)
            {
                throw new EncodingException("negative field id is invalid");
            }

            int size = fieldId;
            header |= 0b11 << 6;
            if (size >= TypeMetaConstants.FieldNameSizeThreshold)
            {
                header |= 0b0011_1100;
                writer.WriteUInt8(header);
                writer.WriteVarUInt32((uint)(size - TypeMetaConstants.FieldNameSizeThreshold));
            }
            else
            {
                header |= (byte)(size << 2);
                writer.WriteUInt8(header);
            }

            FieldType.Write(writer, false);
            return;
        }

        string snakeName = TypeMetaUtils.LowerCamelToLowerUnderscore(FieldName);
        MetaString encoded = MetaStringEncoder.FieldName.Encode(snakeName, TypeMetaEncodings.FieldNameMetaStringEncodings);
        int encodingIndex = Array.IndexOf(TypeMetaEncodings.FieldNameMetaStringEncodings, encoded.Encoding);
        if (encodingIndex < 0)
        {
            throw new EncodingException("unsupported field name encoding");
        }

        int encodedSize = encoded.Bytes.Length - 1;
        header |= (byte)(encodingIndex << 6);
        if (encodedSize >= TypeMetaConstants.FieldNameSizeThreshold)
        {
            header |= 0b0011_1100;
            writer.WriteUInt8(header);
            writer.WriteVarUInt32((uint)(encodedSize - TypeMetaConstants.FieldNameSizeThreshold));
        }
        else
        {
            header |= (byte)(encodedSize << 2);
            writer.WriteUInt8(header);
        }

        FieldType.Write(writer, false);
        writer.WriteBytes(encoded.Bytes);
    }

    internal static TypeMetaFieldInfo Read(ByteReader reader)
    {
        byte header = reader.ReadUInt8();
        int encodingFlags = (header >> 6) & 0b11;
        int size = (header >> 2) & 0b1111;
        if (size == TypeMetaConstants.FieldNameSizeThreshold)
        {
            size += (int)reader.ReadVarUInt32();
        }

        size += 1;

        bool nullable = (header & 0b10) != 0;
        bool trackRef = (header & 0b1) != 0;
        TypeMetaFieldType fieldType = TypeMetaFieldType.Read(reader, false, nullable, trackRef);

        if (encodingFlags == 3)
        {
            short fieldId = unchecked((short)(size - 1));
            return new TypeMetaFieldInfo(fieldId, $"$tag{fieldId}", fieldType);
        }

        if (encodingFlags >= TypeMetaEncodings.FieldNameMetaStringEncodings.Length)
        {
            throw new InvalidDataException("invalid field name encoding id");
        }

        byte[] nameBytes = reader.ReadBytes(size);
        string name = MetaStringDecoder.FieldName.Decode(
            nameBytes,
            TypeMetaEncodings.FieldNameMetaStringEncodings[encodingFlags]).Value;
        return new TypeMetaFieldInfo(null, name, fieldType);
    }

    public bool Equals(TypeMetaFieldInfo? other)
    {
        if (other is null)
        {
            return false;
        }

        return FieldId == other.FieldId &&
               FieldName == other.FieldName &&
               FieldType.Equals(other.FieldType);
    }

    public override bool Equals(object? obj)
    {
        return obj is TypeMetaFieldInfo other && Equals(other);
    }

    public override int GetHashCode()
    {
        return HashCode.Combine(FieldId, FieldName, FieldType);
    }
}

public sealed class TypeMeta : IEquatable<TypeMeta>
{
    public TypeMeta(
        uint? typeId,
        uint? userTypeId,
        MetaString namespaceName,
        MetaString typeName,
        bool registerByName,
        IReadOnlyList<TypeMetaFieldInfo> fields,
        bool hasFieldsMeta = true,
        bool compressed = false,
        ulong headerHash = 0)
    {
        if (registerByName)
        {
            if (typeName.Value.Length == 0)
            {
                throw new EncodingException("type name is required in register-by-name mode");
            }
        }
        else
        {
            if (!typeId.HasValue)
            {
                throw new EncodingException("type id is required in register-by-id mode");
            }

            if (!userTypeId.HasValue || userTypeId.Value == TypeMetaConstants.NoUserTypeId)
            {
                throw new EncodingException("user type id is required in register-by-id mode");
            }
        }

        TypeId = typeId;
        UserTypeId = userTypeId;
        NamespaceName = namespaceName;
        TypeName = typeName;
        RegisterByName = registerByName;
        Fields = fields;
        HasFieldsMeta = hasFieldsMeta;
        Compressed = compressed;
        HeaderHash = headerHash;
    }

    public uint? TypeId { get; }

    public uint? UserTypeId { get; }

    public MetaString NamespaceName { get; }

    public MetaString TypeName { get; }

    public bool RegisterByName { get; }

    public IReadOnlyList<TypeMetaFieldInfo> Fields { get; }

    public bool HasFieldsMeta { get; }

    public bool Compressed { get; }

    public ulong HeaderHash { get; }

    public byte[] Encode()
    {
        if (Compressed)
        {
            throw new EncodingException("compressed TypeMeta is not supported yet");
        }

        byte[] body = EncodeBody();
        (ulong bodyHash, _) = MurmurHash3.X64_128(body, TypeMetaConstants.TypeMetaHashSeed);
        ulong shifted = bodyHash << (int)(64 - TypeMetaConstants.TypeMetaNumHashBits);
        long signed = unchecked((long)shifted);
        long absSigned = signed == long.MinValue ? signed : Math.Abs(signed);

        ulong header = unchecked((ulong)absSigned);
        if (HasFieldsMeta)
        {
            header |= TypeMetaConstants.TypeMetaHasFieldsMetaFlag;
        }

        if (Compressed)
        {
            header |= TypeMetaConstants.TypeMetaCompressedFlag;
        }

        header |= (ulong)Math.Min(body.Length, (int)TypeMetaConstants.TypeMetaSizeMask);
        ByteWriter writer = new(body.Length + 16);
        writer.WriteUInt64(header);
        if (body.Length >= (int)TypeMetaConstants.TypeMetaSizeMask)
        {
            writer.WriteVarUInt32((uint)(body.Length - (int)TypeMetaConstants.TypeMetaSizeMask));
        }

        writer.WriteBytes(body);
        return writer.ToArray();
    }

    public static TypeMeta Decode(byte[] bytes)
    {
        return Decode(new ByteReader(bytes));
    }

    public static TypeMeta Decode(ByteReader reader)
    {
        ulong header = reader.ReadUInt64();
        bool compressed = (header & TypeMetaConstants.TypeMetaCompressedFlag) != 0;
        bool hasFieldsMeta = (header & TypeMetaConstants.TypeMetaHasFieldsMetaFlag) != 0;
        int metaSize = (int)(header & TypeMetaConstants.TypeMetaSizeMask);
        if (metaSize == (int)TypeMetaConstants.TypeMetaSizeMask)
        {
            metaSize += (int)reader.ReadVarUInt32();
        }

        byte[] encodedBody = reader.ReadBytes(metaSize);
        if (compressed)
        {
            throw new EncodingException("compressed TypeMeta is not supported yet");
        }

        ByteReader bodyReader = new(encodedBody);
        byte metaHeader = bodyReader.ReadUInt8();
        int numFields = metaHeader & TypeMetaConstants.SmallNumFieldsThreshold;
        if (numFields == TypeMetaConstants.SmallNumFieldsThreshold)
        {
            numFields += (int)bodyReader.ReadVarUInt32();
        }

        bool registerByName = (metaHeader & TypeMetaConstants.RegisterByNameFlag) != 0;
        uint? typeId;
        uint? userTypeId;
        MetaString namespaceName;
        MetaString typeName;
        if (registerByName)
        {
            namespaceName = ReadName(bodyReader, MetaStringDecoder.Namespace, TypeMetaEncodings.NamespaceMetaStringEncodings);
            typeName = ReadName(bodyReader, MetaStringDecoder.TypeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
            typeId = null;
            userTypeId = null;
        }
        else
        {
            typeId = bodyReader.ReadUInt8();
            userTypeId = bodyReader.ReadVarUInt32();
            namespaceName = MetaString.Empty('.', '_');
            typeName = MetaString.Empty('$', '_');
        }

        List<TypeMetaFieldInfo> fields = new(numFields);
        for (int i = 0; i < numFields; i++)
        {
            fields.Add(TypeMetaFieldInfo.Read(bodyReader));
        }

        if (bodyReader.Remaining != 0)
        {
            throw new InvalidDataException("unexpected trailing bytes in TypeMeta body");
        }

        return new TypeMeta(
            typeId,
            userTypeId,
            namespaceName,
            typeName,
            registerByName,
            fields,
            hasFieldsMeta,
            compressed,
            header >> (int)(64 - TypeMetaConstants.TypeMetaNumHashBits));
    }

    private byte[] EncodeBody()
    {
        ByteWriter writer = new(128);
        byte metaHeader = (byte)Math.Min(Fields.Count, TypeMetaConstants.SmallNumFieldsThreshold);
        if (RegisterByName)
        {
            metaHeader |= TypeMetaConstants.RegisterByNameFlag;
        }

        writer.WriteUInt8(metaHeader);
        if (Fields.Count >= TypeMetaConstants.SmallNumFieldsThreshold)
        {
            writer.WriteVarUInt32((uint)(Fields.Count - TypeMetaConstants.SmallNumFieldsThreshold));
        }

        if (RegisterByName)
        {
            WriteName(writer, NamespaceName, TypeMetaEncodings.NamespaceMetaStringEncodings);
            WriteName(writer, TypeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
        }
        else
        {
            if (!TypeId.HasValue)
            {
                throw new EncodingException("type id is required in register-by-id mode");
            }

            if (!UserTypeId.HasValue || UserTypeId == TypeMetaConstants.NoUserTypeId)
            {
                throw new EncodingException("user type id is required in register-by-id mode");
            }

            writer.WriteUInt8(unchecked((byte)TypeId.Value));
            writer.WriteVarUInt32(UserTypeId.Value);
        }

        foreach (TypeMetaFieldInfo field in Fields)
        {
            field.Write(writer);
        }

        return writer.ToArray();
    }

    private static void WriteName(ByteWriter writer, MetaString name, IReadOnlyList<MetaStringEncoding> encodings)
    {
        MetaString normalized = encodings.Contains(name.Encoding)
            ? name
            : (encodings.SequenceEqual(TypeMetaEncodings.NamespaceMetaStringEncodings)
                ? MetaStringEncoder.Namespace.Encode(name.Value, encodings)
                : encodings.SequenceEqual(TypeMetaEncodings.TypeNameMetaStringEncodings)
                    ? MetaStringEncoder.TypeName.Encode(name.Value, encodings)
                    : MetaStringEncoder.FieldName.Encode(name.Value, encodings));

        int encodingIndex = TypeMetaUtils.EncodingIndexOf(encodings, normalized.Encoding);
        if (encodingIndex < 0)
        {
            throw new EncodingException("failed to normalize meta string encoding");
        }

        byte[] bytes = normalized.Bytes;
        if (bytes.Length >= TypeMetaConstants.BigNameThreshold)
        {
            writer.WriteUInt8((byte)((TypeMetaConstants.BigNameThreshold << 2) | encodingIndex));
            writer.WriteVarUInt32((uint)(bytes.Length - TypeMetaConstants.BigNameThreshold));
        }
        else
        {
            writer.WriteUInt8((byte)((bytes.Length << 2) | encodingIndex));
        }

        writer.WriteBytes(bytes);
    }

    private static MetaString ReadName(ByteReader reader, MetaStringDecoder decoder, IReadOnlyList<MetaStringEncoding> encodings)
    {
        byte header = reader.ReadUInt8();
        int encodingIndex = header & 0b11;
        if (encodingIndex >= encodings.Count)
        {
            throw new InvalidDataException("invalid meta string encoding index");
        }

        int length = header >> 2;
        if (length >= TypeMetaConstants.BigNameThreshold)
        {
            length = TypeMetaConstants.BigNameThreshold + (int)reader.ReadVarUInt32();
        }

        byte[] bytes = reader.ReadBytes(length);
        return decoder.Decode(bytes, encodings[encodingIndex]);
    }

    public bool Equals(TypeMeta? other)
    {
        if (other is null)
        {
            return false;
        }

        return TypeId == other.TypeId &&
               UserTypeId == other.UserTypeId &&
               NamespaceName.Equals(other.NamespaceName) &&
               TypeName.Equals(other.TypeName) &&
               RegisterByName == other.RegisterByName &&
               Fields.SequenceEqual(other.Fields) &&
               HasFieldsMeta == other.HasFieldsMeta &&
               Compressed == other.Compressed &&
               HeaderHash == other.HeaderHash;
    }

    public override bool Equals(object? obj)
    {
        return obj is TypeMeta other && Equals(other);
    }

    public override int GetHashCode()
    {
        HashCode hc = new();
        hc.Add(TypeId);
        hc.Add(UserTypeId);
        hc.Add(NamespaceName);
        hc.Add(TypeName);
        hc.Add(RegisterByName);
        hc.Add(HasFieldsMeta);
        hc.Add(Compressed);
        hc.Add(HeaderHash);
        foreach (TypeMetaFieldInfo f in Fields)
        {
            hc.Add(f);
        }

        return hc.ToHashCode();
    }

}
