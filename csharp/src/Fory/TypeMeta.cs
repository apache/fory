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

using System.Buffers;

namespace Apache.Fory;

internal static class TypeMetaConstants
{
    // 8 size bits + 1 compression bit + 3 reserved bits.
    public const int TypeMetaHashShift = 12;
    public const ulong TypeMetaHashMask = ulong.MaxValue << TypeMetaHashShift;
    public const int SmallNumFieldsThreshold = 0b1_1111;
    public const byte RegisterByNameFlag = 0b10_0000;
    public const byte CompatibleFlag = 0b0100_0000;
    public const byte StructFlag = 0b1000_0000;
    public const int FieldNameSizeThreshold = 0b1111;
    public const int BigNameThreshold = 0b11_1111;
    public const ulong TypeMetaCompressedFlag = 1UL << 8;
    public const ulong TypeMetaReservedFlags = 0b111UL << 9;
    public const ulong TypeMetaSizeMask = 0xFF;
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

        return sb.ToString().Replace("b_float16", "bfloat16");
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
        AssignedFieldId = -1;
    }

    public short? FieldId { get; }

    public string FieldName { get; }

    public TypeMetaFieldType FieldType { get; }

    public int AssignedFieldId { get; internal set; }

    internal CompatibleScalarRead? CompatibleScalarRead { get; set; }

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
    private bool _hasAssignedFieldIds;

    public TypeMeta(
        uint? typeId,
        uint? userTypeId,
        MetaString namespaceName,
        MetaString typeName,
        bool registerByName,
        IReadOnlyList<TypeMetaFieldInfo> fields,
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
        Compressed = compressed;
        HeaderHash = headerHash;
    }

    public uint? TypeId { get; }

    public uint? UserTypeId { get; }

    public MetaString NamespaceName { get; }

    public MetaString TypeName { get; }

    public bool RegisterByName { get; }

    public IReadOnlyList<TypeMetaFieldInfo> Fields { get; }

    public bool Compressed { get; }

    public ulong HeaderHash { get; }

    internal void EnsureAssignedFieldIds(IReadOnlyList<TypeMetaFieldInfo> localFieldInfos)
    {
        if (_hasAssignedFieldIds)
        {
            return;
        }

        AssignFieldIds(this, localFieldInfos);
        _hasAssignedFieldIds = true;
    }

    public byte[] Encode()
    {
        if (Compressed)
        {
            throw new EncodingException("compressed TypeMeta is not supported yet");
        }

        byte[] body = EncodeBody();
        ulong headerLowBits = ComputeHeaderLowBits(body.Length, compressed: false);
        ulong header = ComputeHeaderHashBits(body, headerLowBits) | headerLowBits;
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
        ValidateGlobalHeader(header);
        int metaSize = ReadBodySize(reader, header);
        byte[] encodedBody = reader.ReadBytes(metaSize);
        ByteReader bodyReader = new(encodedBody);
        byte metaHeader = bodyReader.ReadUInt8();
        bool isStruct = (metaHeader & TypeMetaConstants.StructFlag) != 0;
        int numFields = 0;
        bool registerByName;
        uint? typeId;
        uint? userTypeId;
        MetaString namespaceName;
        MetaString typeName;
        if (isStruct)
        {
            registerByName = (metaHeader & TypeMetaConstants.RegisterByNameFlag) != 0;
            bool compatible = (metaHeader & TypeMetaConstants.CompatibleFlag) != 0;
            typeId = (uint)(registerByName
                ? compatible ? global::Apache.Fory.TypeId.NamedCompatibleStruct : global::Apache.Fory.TypeId.NamedStruct
                : compatible ? global::Apache.Fory.TypeId.CompatibleStruct : global::Apache.Fory.TypeId.Struct);
            numFields = metaHeader & TypeMetaConstants.SmallNumFieldsThreshold;
            if (numFields == TypeMetaConstants.SmallNumFieldsThreshold)
            {
                numFields += (int)bodyReader.ReadVarUInt32();
            }
        }
        else
        {
            if ((metaHeader & 0b0111_0000) != 0)
            {
                throw new InvalidDataException("invalid TypeMeta kind header");
            }

            typeId = NonStructTypeId(metaHeader & 0b1111);
            registerByName = IsNamedKind(typeId.Value);
        }

        if (registerByName)
        {
            namespaceName = ReadName(bodyReader, MetaStringDecoder.Namespace, TypeMetaEncodings.NamespaceMetaStringEncodings);
            typeName = ReadName(bodyReader, MetaStringDecoder.TypeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
            userTypeId = null;
        }
        else
        {
            userTypeId = bodyReader.ReadVarUInt32();
            namespaceName = MetaString.Empty('.', '_');
            typeName = MetaString.Empty('$', '_');
        }

        bodyReader.CheckBound(numFields);
        List<TypeMetaFieldInfo> fields = new(numFields);
        for (int i = 0; i < numFields; i++)
        {
            fields.Add(TypeMetaFieldInfo.Read(bodyReader));
        }

        if (!isStruct && fields.Count != 0)
        {
            throw new InvalidDataException("non-struct TypeMeta cannot carry field metadata");
        }

        if (bodyReader.Remaining != 0)
        {
            throw new InvalidDataException("unexpected trailing bytes in TypeMeta body");
        }

        ValidateParsedTypeMetaHash(header, encodedBody);
        return new TypeMeta(
            typeId,
            userTypeId,
            namespaceName,
            typeName,
            registerByName,
            fields,
            compressed: false,
            header >> TypeMetaConstants.TypeMetaHashShift);
    }

    internal static void ValidateAndSkipBody(ByteReader reader, ulong header)
    {
        ValidateGlobalHeader(header);
        int metaSize = ReadBodySize(reader, header);
        ReadOnlySpan<byte> encodedBody = reader.ReadSpan(metaSize);
        ValidateParsedTypeMetaHash(header, encodedBody);
    }

    private static void ValidateGlobalHeader(ulong header)
    {
        if ((header & TypeMetaConstants.TypeMetaReservedFlags) != 0)
        {
            throw new InvalidDataException("invalid TypeMeta global header");
        }

        if ((header & TypeMetaConstants.TypeMetaCompressedFlag) != 0)
        {
            throw new EncodingException("compressed TypeMeta is not supported yet");
        }
    }

    private static int ReadBodySize(ByteReader reader, ulong header)
    {
        int metaSize = (int)(header & TypeMetaConstants.TypeMetaSizeMask);
        if (metaSize == (int)TypeMetaConstants.TypeMetaSizeMask)
        {
            uint moreSize = reader.ReadVarUInt32();
            if (moreSize > int.MaxValue - metaSize)
            {
                throw new InvalidDataException("invalid TypeMeta metadata size");
            }

            metaSize += (int)moreSize;
        }

        return metaSize;
    }

    internal static void SkipBody(ByteReader reader, ulong header)
    {
        reader.Skip(ReadBodySize(reader, header));
    }

    private static ulong ComputeHeaderLowBits(int bodyLength, bool compressed)
    {
        ulong headerLowBits = (ulong)Math.Min(bodyLength, (int)TypeMetaConstants.TypeMetaSizeMask);
        if (compressed)
        {
            headerLowBits |= TypeMetaConstants.TypeMetaCompressedFlag;
        }

        return headerLowBits;
    }

    private static ulong ComputeHeaderHashBits(ReadOnlySpan<byte> body, ulong headerLowBits)
    {
        int hashInputLength = body.Length + sizeof(ushort);
        byte[]? rented = null;
        Span<byte> hashInput = hashInputLength <= 1024
            ? stackalloc byte[hashInputLength]
            : (rented = ArrayPool<byte>.Shared.Rent(hashInputLength)).AsSpan(0, hashInputLength);
        try
        {
            body.CopyTo(hashInput);
            hashInput[body.Length] = unchecked((byte)headerLowBits);
            hashInput[body.Length + 1] = unchecked((byte)(headerLowBits >> 8));
            (ulong bodyHash, _) = MurmurHash3.X64_128(hashInput, TypeMetaConstants.TypeMetaHashSeed);
            ulong shifted = bodyHash << TypeMetaConstants.TypeMetaHashShift;
            long signed = unchecked((long)shifted);
            long absSigned = signed == long.MinValue ? signed : Math.Abs(signed);
            return unchecked((ulong)absSigned) & TypeMetaConstants.TypeMetaHashMask;
        }
        finally
        {
            if (rented is not null)
            {
                ArrayPool<byte>.Shared.Return(rented);
            }
        }
    }

    private static void ValidateParsedTypeMetaHash(ulong header, ReadOnlySpan<byte> body)
    {
        ulong expectedHeaderHash = ComputeHeaderHashBits(body, header & ~TypeMetaConstants.TypeMetaHashMask);
        ulong actualHeaderHash = header & TypeMetaConstants.TypeMetaHashMask;
        if (actualHeaderHash != expectedHeaderHash)
        {
            throw new InvalidDataException("TypeMeta metadata hash mismatch");
        }
    }

    private static uint NonStructKindCode(uint typeId)
    {
        return (global::Apache.Fory.TypeId)typeId switch
        {
            global::Apache.Fory.TypeId.Enum => 0,
            global::Apache.Fory.TypeId.NamedEnum => 1,
            global::Apache.Fory.TypeId.Ext => 2,
            global::Apache.Fory.TypeId.NamedExt => 3,
            global::Apache.Fory.TypeId.TypedUnion => 4,
            global::Apache.Fory.TypeId.NamedUnion => 5,
            _ => throw new EncodingException($"unsupported TypeMeta kind {typeId}"),
        };
    }

    private static uint NonStructTypeId(int kindCode)
    {
        return kindCode switch
        {
            0 => (uint)global::Apache.Fory.TypeId.Enum,
            1 => (uint)global::Apache.Fory.TypeId.NamedEnum,
            2 => (uint)global::Apache.Fory.TypeId.Ext,
            3 => (uint)global::Apache.Fory.TypeId.NamedExt,
            4 => (uint)global::Apache.Fory.TypeId.TypedUnion,
            5 => (uint)global::Apache.Fory.TypeId.NamedUnion,
            _ => throw new InvalidDataException($"unsupported TypeMeta kind code {kindCode}"),
        };
    }

    private static bool IsStructKind(uint typeId)
    {
        return (global::Apache.Fory.TypeId)typeId is
            global::Apache.Fory.TypeId.Struct or
            global::Apache.Fory.TypeId.CompatibleStruct or
            global::Apache.Fory.TypeId.NamedStruct or
            global::Apache.Fory.TypeId.NamedCompatibleStruct;
    }

    private static bool IsNamedKind(uint typeId)
    {
        return (global::Apache.Fory.TypeId)typeId is
            global::Apache.Fory.TypeId.NamedStruct or
            global::Apache.Fory.TypeId.NamedCompatibleStruct or
            global::Apache.Fory.TypeId.NamedEnum or
            global::Apache.Fory.TypeId.NamedExt or
            global::Apache.Fory.TypeId.NamedUnion;
    }

    /// <summary>
    /// Assigns doubled compatible dispatch ids for a remote compatible type meta.
    /// The result is written to each remote field's <see cref="TypeMetaFieldInfo.AssignedFieldId"/>:
    /// - local sorted field index * 2 when the matched field schema is exact
    /// - local sorted field index * 2 + 1 when the matched field schema needs compatible conversion
    /// - -1 when no local field identity is found and the field should be skipped
    /// </summary>
    public static void AssignFieldIds(
        TypeMeta remoteTypeMeta,
        IReadOnlyList<TypeMetaFieldInfo> localFieldInfos)
    {
        ArgumentNullException.ThrowIfNull(remoteTypeMeta);
        ArgumentNullException.ThrowIfNull(localFieldInfos);

        Dictionary<string, (int Index, TypeMetaFieldInfo Field)> localByName = new(localFieldInfos.Count, StringComparer.Ordinal);
        Dictionary<short, (int Index, TypeMetaFieldInfo Field)> localById = new(localFieldInfos.Count);
        for (int i = 0; i < localFieldInfos.Count; i++)
        {
            TypeMetaFieldInfo localField = localFieldInfos[i];
            if (localField.FieldId.HasValue && localField.FieldId.Value >= 0)
            {
                short fieldId = localField.FieldId.Value;
                if (!localById.TryAdd(fieldId, (i, localField)))
                {
                    throw new InvalidDataException(
                        $"duplicate local field id {fieldId} in compatible type metadata");
                }
            }
            else if (!string.IsNullOrEmpty(localField.FieldName))
            {
                localByName.TryAdd(localField.FieldName, (i, localField));
            }
        }

        HashSet<short>? remoteFieldIds = null;
        HashSet<int> usedLocalFields = [];
        for (int i = 0; i < remoteTypeMeta.Fields.Count; i++)
        {
            TypeMetaFieldInfo remoteField = remoteTypeMeta.Fields[i];
            remoteField.CompatibleScalarRead = null;
            if (remoteField.FieldId.HasValue && remoteField.FieldId.Value >= 0)
            {
                short fieldId = remoteField.FieldId.Value;
                remoteFieldIds ??= [];
                if (!remoteFieldIds.Add(fieldId))
                {
                    throw new InvalidDataException(
                        $"duplicate remote field id {fieldId} in compatible type metadata");
                }
            }

            int localIndex = -1;
            TypeMetaFieldInfo? localMatch = null;

            if (remoteField.FieldId.HasValue &&
                remoteField.FieldId.Value >= 0 &&
                localById.TryGetValue(remoteField.FieldId.Value, out (int Index, TypeMetaFieldInfo Field) byId))
            {
                localIndex = byId.Index;
                localMatch = byId.Field;
            }
            else if (!remoteField.FieldId.HasValue)
            {
                if (localByName.TryGetValue(remoteField.FieldName, out (int Index, TypeMetaFieldInfo Field) byName))
                {
                    localIndex = byName.Index;
                    localMatch = byName.Field;
                }
                else
                {
                    string normalizedName = TypeMetaUtils.LowerCamelToLowerUnderscore(remoteField.FieldName);
                    if (!ReferenceEquals(normalizedName, remoteField.FieldName) &&
                        localByName.TryGetValue(normalizedName, out byName))
                    {
                        localIndex = byName.Index;
                        localMatch = byName.Field;
                    }
                }
            }

            if (localIndex >= 0 && localMatch is not null)
            {
                if (!usedLocalFields.Add(localIndex))
                {
                    throw new InvalidDataException(
                        $"compatible field {remoteField.FieldName} duplicates local field {localMatch.FieldName}");
                }
                if (IsDirectFieldType(remoteField.FieldType, localMatch.FieldType))
                {
                    remoteField.AssignedFieldId = localIndex * 2;
                }
                else if (CompatibleScalarConverter.TryBuildRead(remoteField, localMatch) is { } scalarRead)
                {
                    remoteField.AssignedFieldId = localIndex * 2 + 1;
                    remoteField.CompatibleScalarRead = scalarRead;
                }
                else if (IsCompatibleFieldType(remoteField.FieldType, localMatch.FieldType, topLevel: true))
                {
                    remoteField.AssignedFieldId = localIndex * 2 + 1;
                }
                else
                {
                    throw new InvalidDataException(
                        $"compatible field {remoteField.FieldName} cannot be read as local field {localMatch.FieldName}: remote and local field schemas are not compatible");
                }
            }
            else
            {
                remoteField.AssignedFieldId = -1;
            }
        }
    }

    private static bool IsCompatibleFieldType(TypeMetaFieldType remote, TypeMetaFieldType local, bool topLevel)
    {
        if (topLevel && IsCompatibleByteSequenceFieldPair(remote, local))
        {
            return true;
        }

        if (topLevel && IsCompatibleListArrayFieldPair(remote, local))
        {
            return true;
        }

        bool remoteScalar = CompatibleScalarConverter.IsScalarType(remote.TypeId);
        bool localScalar = CompatibleScalarConverter.IsScalarType(local.TypeId);
        if (remoteScalar || localScalar)
        {
            return !remote.TrackRef &&
                   !local.TrackRef &&
                   remote.TypeId == local.TypeId &&
                   remote.Generics.Count == local.Generics.Count;
        }

        if (NormalizeTypeIdForMatch(remote.TypeId) != NormalizeTypeIdForMatch(local.TypeId))
        {
            return false;
        }

        if (remote.Generics.Count != local.Generics.Count)
        {
            return false;
        }

        for (int i = 0; i < remote.Generics.Count; i++)
        {
            if (!IsCompatibleFieldType(remote.Generics[i], local.Generics[i], topLevel: false))
            {
                return false;
            }
        }

        return true;
    }

    private static bool IsDirectFieldType(TypeMetaFieldType remote, TypeMetaFieldType local)
    {
        if (remote.Equals(local))
        {
            return true;
        }

        if (remote.Nullable != local.Nullable || remote.TrackRef != local.TrackRef)
        {
            return false;
        }

        if (NormalizeTypeIdForMatch(remote.TypeId) != NormalizeTypeIdForMatch(local.TypeId))
        {
            return false;
        }

        if (remote.Generics.Count != local.Generics.Count)
        {
            return false;
        }

        for (int i = 0; i < remote.Generics.Count; i++)
        {
            if (!IsDirectFieldType(remote.Generics[i], local.Generics[i]))
            {
                return false;
            }
        }

        return true;
    }

    private static bool IsCompatibleByteSequenceFieldPair(TypeMetaFieldType remote, TypeMetaFieldType local)
    {
        if (remote.TrackRef || local.TrackRef || remote.Nullable != local.Nullable)
        {
            return false;
        }

        return (remote.TypeId == (uint)global::Apache.Fory.TypeId.Binary &&
                local.TypeId == (uint)global::Apache.Fory.TypeId.UInt8Array) ||
               (remote.TypeId == (uint)global::Apache.Fory.TypeId.UInt8Array &&
                local.TypeId == (uint)global::Apache.Fory.TypeId.Binary);
    }

    private static bool IsCompatibleListArrayFieldPair(TypeMetaFieldType remote, TypeMetaFieldType local)
    {
        if (remote.Nullable || local.Nullable || remote.TrackRef || local.TrackRef)
        {
            return false;
        }

        uint? localArrayElementTypeId = TryPackedArrayElementTypeId(local.TypeId);
        uint? remoteArrayElementTypeId = TryPackedArrayElementTypeId(remote.TypeId);
        // Nullable element schema is allowed for list<T?> -> array<T>; actual null payload
        // elements fail in the dense-array reader. Ref-tracked element framing is rejected here
        // because this path stays primitive-only.
        bool remoteListLocalArray = remote.TypeId == (uint)global::Apache.Fory.TypeId.List &&
                                    localArrayElementTypeId.HasValue &&
                                    remote.Generics.Count == 1 &&
                                    !remote.Generics[0].TrackRef &&
                                    NormalizeScalarTypeIdForMatch(localArrayElementTypeId.Value) ==
                                    NormalizeScalarTypeIdForMatch(remote.Generics[0].TypeId);
        if (remoteListLocalArray)
        {
            return true;
        }

        return local.TypeId == (uint)global::Apache.Fory.TypeId.List &&
               remoteArrayElementTypeId.HasValue &&
               local.Generics.Count == 1 &&
               NormalizeScalarTypeIdForMatch(remoteArrayElementTypeId.Value) ==
               NormalizeScalarTypeIdForMatch(local.Generics[0].TypeId);
    }

    private static uint? TryPackedArrayElementTypeId(uint typeId)
    {
        return typeId switch
        {
            (uint)global::Apache.Fory.TypeId.BoolArray => (uint)global::Apache.Fory.TypeId.Bool,
            (uint)global::Apache.Fory.TypeId.Int8Array => (uint)global::Apache.Fory.TypeId.Int8,
            (uint)global::Apache.Fory.TypeId.Int16Array => (uint)global::Apache.Fory.TypeId.Int16,
            (uint)global::Apache.Fory.TypeId.Int32Array => (uint)global::Apache.Fory.TypeId.VarInt32,
            (uint)global::Apache.Fory.TypeId.Int64Array => (uint)global::Apache.Fory.TypeId.VarInt64,
            (uint)global::Apache.Fory.TypeId.Float16Array => (uint)global::Apache.Fory.TypeId.Float16,
            (uint)global::Apache.Fory.TypeId.Float32Array => (uint)global::Apache.Fory.TypeId.Float32,
            (uint)global::Apache.Fory.TypeId.Float64Array => (uint)global::Apache.Fory.TypeId.Float64,
            (uint)global::Apache.Fory.TypeId.UInt8Array => (uint)global::Apache.Fory.TypeId.UInt8,
            (uint)global::Apache.Fory.TypeId.UInt16Array => (uint)global::Apache.Fory.TypeId.UInt16,
            (uint)global::Apache.Fory.TypeId.UInt32Array => (uint)global::Apache.Fory.TypeId.UInt32,
            (uint)global::Apache.Fory.TypeId.UInt64Array => (uint)global::Apache.Fory.TypeId.UInt64,
            (uint)global::Apache.Fory.TypeId.BFloat16Array => (uint)global::Apache.Fory.TypeId.BFloat16,
            _ => null,
        };
    }

    private static uint NormalizeScalarTypeIdForMatch(uint typeId)
    {
        return typeId switch
        {
            (uint)global::Apache.Fory.TypeId.Int32 or
            (uint)global::Apache.Fory.TypeId.VarInt32 => (uint)global::Apache.Fory.TypeId.VarInt32,
            (uint)global::Apache.Fory.TypeId.Int64 or
            (uint)global::Apache.Fory.TypeId.VarInt64 or
            (uint)global::Apache.Fory.TypeId.TaggedInt64 => (uint)global::Apache.Fory.TypeId.VarInt64,
            (uint)global::Apache.Fory.TypeId.UInt32 or
            (uint)global::Apache.Fory.TypeId.VarUInt32 => (uint)global::Apache.Fory.TypeId.VarUInt32,
            (uint)global::Apache.Fory.TypeId.UInt64 or
            (uint)global::Apache.Fory.TypeId.VarUInt64 or
            (uint)global::Apache.Fory.TypeId.TaggedUInt64 => (uint)global::Apache.Fory.TypeId.VarUInt64,
            _ => NormalizeTypeIdForMatch(typeId),
        };
    }

    private static uint NormalizeTypeIdForMatch(uint typeId)
    {
        return typeId switch
        {
            (uint)global::Apache.Fory.TypeId.Struct or
            (uint)global::Apache.Fory.TypeId.CompatibleStruct or
            (uint)global::Apache.Fory.TypeId.NamedStruct or
            (uint)global::Apache.Fory.TypeId.NamedCompatibleStruct or
            (uint)global::Apache.Fory.TypeId.Ext or
            (uint)global::Apache.Fory.TypeId.NamedExt or
            (uint)global::Apache.Fory.TypeId.Unknown => (uint)global::Apache.Fory.TypeId.Struct,
            (uint)global::Apache.Fory.TypeId.Enum or
            (uint)global::Apache.Fory.TypeId.NamedEnum => (uint)global::Apache.Fory.TypeId.Enum,
            (uint)global::Apache.Fory.TypeId.Union or
            (uint)global::Apache.Fory.TypeId.NamedUnion or
            (uint)global::Apache.Fory.TypeId.TypedUnion => (uint)global::Apache.Fory.TypeId.Union,
            _ => typeId,
        };
    }

    private byte[] EncodeBody()
    {
        ByteWriter writer = new(128);
        if (!TypeId.HasValue)
        {
            throw new EncodingException("type id is required");
        }

        bool isStruct = IsStructKind(TypeId.Value);
        if (!isStruct && Fields.Count != 0)
        {
            throw new EncodingException("non-struct TypeMeta cannot carry field metadata");
        }

        byte metaHeader;
        if (isStruct)
        {
            metaHeader = (byte)(TypeMetaConstants.StructFlag |
                                Math.Min(Fields.Count, TypeMetaConstants.SmallNumFieldsThreshold));
            if (TypeId.Value is (uint)global::Apache.Fory.TypeId.CompatibleStruct or
                (uint)global::Apache.Fory.TypeId.NamedCompatibleStruct)
            {
                metaHeader |= TypeMetaConstants.CompatibleFlag;
            }

            if (RegisterByName)
            {
                metaHeader |= TypeMetaConstants.RegisterByNameFlag;
            }
        }
        else
        {
            metaHeader = (byte)NonStructKindCode(TypeId.Value);
        }

        writer.WriteUInt8(metaHeader);
        if (isStruct && Fields.Count >= TypeMetaConstants.SmallNumFieldsThreshold)
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
            if (!UserTypeId.HasValue || UserTypeId == TypeMetaConstants.NoUserTypeId)
            {
                throw new EncodingException("user type id is required in register-by-id mode");
            }

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
        hc.Add(Compressed);
        hc.Add(HeaderHash);
        foreach (TypeMetaFieldInfo f in Fields)
        {
            hc.Add(f);
        }

        return hc.ToHashCode();
    }

}
