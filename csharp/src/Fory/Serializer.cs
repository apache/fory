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

public interface IStaticSerializer<TSerializer, T>
    where TSerializer : IStaticSerializer<TSerializer, T>
{
    static abstract ForyTypeId StaticTypeId { get; }

    static virtual bool IsNullableType => false;

    static virtual bool IsReferenceTrackableType => false;

    static virtual T DefaultValue => default!;

    static virtual bool IsNone(in T value)
    {
        _ = value;
        return false;
    }

    static abstract void WriteData(ref WriteContext context, in T value, bool hasGenerics);

    static abstract T ReadData(ref ReadContext context);

    static virtual IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        _ = trackRef;
        return [];
    }

    static virtual void WriteTypeInfo(ref WriteContext context)
    {
        SerializerTypeInfo.WriteTypeInfo<T, TSerializer>(ref context);
    }

    static virtual void ReadTypeInfo(ref ReadContext context)
    {
        SerializerTypeInfo.ReadTypeInfo<T, TSerializer>(ref context);
    }

    static virtual void Write(ref WriteContext context, in T value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        if (refMode != RefMode.None)
        {
            bool wroteTrackingRefFlag = false;
            if (refMode == RefMode.Tracking &&
                TSerializer.IsReferenceTrackableType &&
                value is object obj)
            {
                if (context.RefWriter.TryWriteReference(context.Writer, obj))
                {
                    return;
                }

                wroteTrackingRefFlag = true;
            }

            if (!wroteTrackingRefFlag)
            {
                if (TSerializer.IsNullableType && TSerializer.IsNone(value))
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    return;
                }

                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
            }
        }

        if (writeTypeInfo)
        {
            TSerializer.WriteTypeInfo(ref context);
        }

        TSerializer.WriteData(ref context, value, hasGenerics);
    }

    static virtual T Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        if (refMode != RefMode.None)
        {
            sbyte rawFlag = context.Reader.ReadInt8();
            RefFlag flag = (RefFlag)rawFlag;
            switch (flag)
            {
                case RefFlag.Null:
                    return TSerializer.DefaultValue;
                case RefFlag.Ref:
                    uint refId = context.Reader.ReadVarUInt32();
                    return context.RefReader.ReadRef<T>(refId);
                case RefFlag.RefValue:
                {
                    uint reservedRefId = context.RefReader.ReserveRefId();
                    context.PushPendingReference(reservedRefId);
                    if (readTypeInfo)
                    {
                        TSerializer.ReadTypeInfo(ref context);
                    }

                    T value = TSerializer.ReadData(ref context);
                    context.FinishPendingReferenceIfNeeded(value);
                    context.PopPendingReference();
                    return value;
                }
                case RefFlag.NotNullValue:
                    break;
                default:
                    throw new ForyRefException($"invalid ref flag {rawFlag}");
            }
        }

        if (readTypeInfo)
        {
            TSerializer.ReadTypeInfo(ref context);
        }

        return TSerializer.ReadData(ref context);
    }
}

public readonly struct Serializer<T>
{
    private readonly TypedSerializerBinding<T>? _binding;

    internal Serializer(TypedSerializerBinding<T> binding)
    {
        _binding = binding;
    }

    private TypedSerializerBinding<T> Binding
    {
        get
        {
            if (_binding is null)
            {
                throw new ForyInvalidDataException($"serializer handle for {typeof(T)} is not initialized");
            }

            return _binding;
        }
    }

    public Type Type => typeof(T);

    public ForyTypeId StaticTypeId => Binding.StaticTypeId;

    public bool IsNullableType => Binding.IsNullableType;

    public bool IsReferenceTrackableType => Binding.IsReferenceTrackableType;

    public T DefaultValue => Binding.DefaultValue;

    public object? DefaultObject => Binding.DefaultValue;

    public bool IsNone(in T value)
    {
        return Binding.IsNone(value);
    }

    public bool IsNoneObject(object? value)
    {
        if (value is null)
        {
            return IsNullableType;
        }

        return value is T typed && IsNone(typed);
    }

    public void WriteData(ref WriteContext context, in T value, bool hasGenerics)
    {
        Binding.WriteData(ref context, value, hasGenerics);
    }

    public T ReadData(ref ReadContext context)
    {
        return Binding.ReadData(ref context);
    }

    public void Write(ref WriteContext context, in T value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        Binding.Write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public T Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return Binding.Read(ref context, refMode, readTypeInfo);
    }

    public void WriteTypeInfo(ref WriteContext context)
    {
        Binding.WriteTypeInfo(ref context);
    }

    public void ReadTypeInfo(ref ReadContext context)
    {
        Binding.ReadTypeInfo(ref context);
    }

    public IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return Binding.CompatibleTypeMetaFields(trackRef);
    }
}

internal static class SerializerTypeInfo
{
    public static void WriteTypeInfo<T, TSerializer>(ref WriteContext context)
        where TSerializer : IStaticSerializer<TSerializer, T>
    {
        ForyTypeId staticTypeId = TSerializer.StaticTypeId;
        if (!staticTypeId.IsUserTypeKind())
        {
            context.Writer.WriteUInt8((byte)staticTypeId);
            return;
        }

        Type type = typeof(T);
        RegisteredTypeInfo info = context.TypeResolver.RequireRegisteredTypeInfo(type);
        ForyTypeId wireTypeId = ResolveWireTypeId(info.Kind, info.RegisterByName, context.Compatible);
        context.Writer.WriteUInt8((byte)wireTypeId);
        switch (wireTypeId)
        {
            case ForyTypeId.CompatibleStruct:
            case ForyTypeId.NamedCompatibleStruct:
            {
                TypeMeta typeMeta = BuildCompatibleTypeMeta<T, TSerializer>(info, wireTypeId, context.TrackRef);
                context.WriteCompatibleTypeMeta(type, typeMeta);
                return;
            }
            case ForyTypeId.NamedEnum:
            case ForyTypeId.NamedStruct:
            case ForyTypeId.NamedExt:
            case ForyTypeId.NamedUnion:
            {
                if (context.Compatible)
                {
                    TypeMeta typeMeta = BuildCompatibleTypeMeta<T, TSerializer>(info, wireTypeId, context.TrackRef);
                    context.WriteCompatibleTypeMeta(type, typeMeta);
                }
                else
                {
                    if (info.NamespaceName is null)
                    {
                        throw new ForyInvalidDataException("missing namespace metadata for name-registered type");
                    }

                    WriteMetaString(
                        ref context,
                        info.NamespaceName.Value,
                        TypeMetaEncodings.NamespaceMetaStringEncodings,
                        MetaStringEncoder.Namespace);
                    WriteMetaString(
                        ref context,
                        info.TypeName,
                        TypeMetaEncodings.TypeNameMetaStringEncodings,
                        MetaStringEncoder.TypeName);
                }

                return;
            }
            default:
                if (!info.RegisterByName && WireTypeNeedsUserTypeId(wireTypeId))
                {
                    if (!info.UserTypeId.HasValue)
                    {
                        throw new ForyInvalidDataException("missing user type id for id-registered type");
                    }

                    context.Writer.WriteVarUInt32(info.UserTypeId.Value);
                }

                return;
        }
    }

    public static void ReadTypeInfo<T, TSerializer>(ref ReadContext context)
        where TSerializer : IStaticSerializer<TSerializer, T>
    {
        uint rawTypeId = context.Reader.ReadVarUInt32();
        if (!Enum.IsDefined(typeof(ForyTypeId), rawTypeId))
        {
            throw new ForyInvalidDataException($"unknown type id {rawTypeId}");
        }

        ForyTypeId typeId = (ForyTypeId)rawTypeId;
        ForyTypeId staticTypeId = TSerializer.StaticTypeId;
        if (!staticTypeId.IsUserTypeKind())
        {
            if (typeId != staticTypeId)
            {
                throw new ForyTypeMismatchException((uint)staticTypeId, rawTypeId);
            }

            return;
        }

        Type type = typeof(T);
        RegisteredTypeInfo info = context.TypeResolver.RequireRegisteredTypeInfo(type);
        HashSet<ForyTypeId> allowed = AllowedWireTypeIds(info.Kind, info.RegisterByName, context.Compatible);
        if (!allowed.Contains(typeId))
        {
            uint expected = allowed.Count > 0 ? (uint)allowed.First() : 0;
            throw new ForyTypeMismatchException(expected, rawTypeId);
        }

        switch (typeId)
        {
            case ForyTypeId.CompatibleStruct:
            case ForyTypeId.NamedCompatibleStruct:
            {
                TypeMeta remoteTypeMeta = context.ReadCompatibleTypeMeta();
                ValidateCompatibleTypeMeta(remoteTypeMeta, info, allowed, typeId);
                context.PushCompatibleTypeMeta(type, remoteTypeMeta);
                return;
            }
            case ForyTypeId.NamedEnum:
            case ForyTypeId.NamedStruct:
            case ForyTypeId.NamedExt:
            case ForyTypeId.NamedUnion:
            {
                if (context.Compatible)
                {
                    TypeMeta remoteTypeMeta = context.ReadCompatibleTypeMeta();
                    ValidateCompatibleTypeMeta(remoteTypeMeta, info, allowed, typeId);
                    if (typeId == ForyTypeId.NamedStruct)
                    {
                        context.PushCompatibleTypeMeta(type, remoteTypeMeta);
                    }
                }
                else
                {
                    MetaString namespaceName = ReadMetaString(
                        ref context,
                        MetaStringDecoder.Namespace,
                        TypeMetaEncodings.NamespaceMetaStringEncodings);
                    MetaString typeName = ReadMetaString(
                        ref context,
                        MetaStringDecoder.TypeName,
                        TypeMetaEncodings.TypeNameMetaStringEncodings);
                    if (!info.RegisterByName || info.NamespaceName is null)
                    {
                        throw new ForyInvalidDataException("received name-registered type info for id-registered local type");
                    }

                    if (namespaceName.Value != info.NamespaceName.Value.Value || typeName.Value != info.TypeName.Value)
                    {
                        throw new ForyInvalidDataException(
                            $"type name mismatch: expected {info.NamespaceName.Value.Value}::{info.TypeName.Value}, got {namespaceName.Value}::{typeName.Value}");
                    }
                }

                return;
            }
            default:
                if (!info.RegisterByName && WireTypeNeedsUserTypeId(typeId))
                {
                    if (!info.UserTypeId.HasValue)
                    {
                        throw new ForyInvalidDataException("missing user type id for id-registered local type");
                    }

                    uint remoteUserTypeId = context.Reader.ReadVarUInt32();
                    if (remoteUserTypeId != info.UserTypeId.Value)
                    {
                        throw new ForyTypeMismatchException(info.UserTypeId.Value, remoteUserTypeId);
                    }
                }

                return;
        }
    }

    public static ForyTypeId ResolveWireTypeId(ForyTypeId declaredKind, bool registerByName, bool compatible)
    {
        ForyTypeId baseKind = NormalizeBaseKind(declaredKind);
        if (registerByName)
        {
            return NamedKind(baseKind, compatible);
        }

        return IdKind(baseKind, compatible);
    }

    public static HashSet<ForyTypeId> AllowedWireTypeIds(ForyTypeId declaredKind, bool registerByName, bool compatible)
    {
        ForyTypeId baseKind = NormalizeBaseKind(declaredKind);
        ForyTypeId expected = ResolveWireTypeId(declaredKind, registerByName, compatible);
        HashSet<ForyTypeId> allowed = [expected];
        if (baseKind == ForyTypeId.Struct && compatible)
        {
            allowed.Add(ForyTypeId.CompatibleStruct);
            allowed.Add(ForyTypeId.NamedCompatibleStruct);
            allowed.Add(ForyTypeId.Struct);
            allowed.Add(ForyTypeId.NamedStruct);
        }

        return allowed;
    }

    private static ForyTypeId NormalizeBaseKind(ForyTypeId kind)
    {
        return kind switch
        {
            ForyTypeId.NamedEnum => ForyTypeId.Enum,
            ForyTypeId.CompatibleStruct or ForyTypeId.NamedCompatibleStruct or ForyTypeId.NamedStruct => ForyTypeId.Struct,
            ForyTypeId.NamedExt => ForyTypeId.Ext,
            ForyTypeId.NamedUnion => ForyTypeId.TypedUnion,
            _ => kind,
        };
    }

    private static ForyTypeId NamedKind(ForyTypeId baseKind, bool compatible)
    {
        return baseKind switch
        {
            ForyTypeId.Struct => compatible ? ForyTypeId.NamedCompatibleStruct : ForyTypeId.NamedStruct,
            ForyTypeId.Enum => ForyTypeId.NamedEnum,
            ForyTypeId.Ext => ForyTypeId.NamedExt,
            ForyTypeId.TypedUnion => ForyTypeId.NamedUnion,
            _ => baseKind,
        };
    }

    private static ForyTypeId IdKind(ForyTypeId baseKind, bool compatible)
    {
        return baseKind switch
        {
            ForyTypeId.Struct => compatible ? ForyTypeId.CompatibleStruct : ForyTypeId.Struct,
            _ => baseKind,
        };
    }

    private static bool WireTypeNeedsUserTypeId(ForyTypeId typeId)
    {
        return typeId is ForyTypeId.Enum or ForyTypeId.Struct or ForyTypeId.Ext or ForyTypeId.TypedUnion;
    }

    private static TypeMeta BuildCompatibleTypeMeta<T, TSerializer>(
        RegisteredTypeInfo info,
        ForyTypeId wireTypeId,
        bool trackRef)
        where TSerializer : IStaticSerializer<TSerializer, T>
    {
        IReadOnlyList<TypeMetaFieldInfo> fields = TSerializer.CompatibleTypeMetaFields(trackRef);
        bool hasFieldsMeta = fields.Count > 0;
        if (info.RegisterByName)
        {
            if (info.NamespaceName is null)
            {
                throw new ForyInvalidDataException("missing namespace metadata for name-registered type");
            }

            return new TypeMeta(
                (uint)wireTypeId,
                null,
                info.NamespaceName.Value,
                info.TypeName,
                true,
                fields,
                hasFieldsMeta);
        }

        if (!info.UserTypeId.HasValue)
        {
            throw new ForyInvalidDataException("missing user type id metadata for id-registered type");
        }

        return new TypeMeta(
            (uint)wireTypeId,
            info.UserTypeId.Value,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            false,
            fields,
            hasFieldsMeta);
    }

    private static void ValidateCompatibleTypeMeta(
        TypeMeta remoteTypeMeta,
        RegisteredTypeInfo localInfo,
        HashSet<ForyTypeId> expectedWireTypes,
        ForyTypeId actualWireTypeId)
    {
        if (remoteTypeMeta.RegisterByName)
        {
            if (!localInfo.RegisterByName || localInfo.NamespaceName is null)
            {
                throw new ForyInvalidDataException(
                    "received name-registered compatible metadata for id-registered local type");
            }

            if (remoteTypeMeta.NamespaceName.Value != localInfo.NamespaceName.Value.Value)
            {
                throw new ForyInvalidDataException(
                    $"namespace mismatch: expected {localInfo.NamespaceName.Value.Value}, got {remoteTypeMeta.NamespaceName.Value}");
            }

            if (remoteTypeMeta.TypeName.Value != localInfo.TypeName.Value)
            {
                throw new ForyInvalidDataException(
                    $"type name mismatch: expected {localInfo.TypeName.Value}, got {remoteTypeMeta.TypeName.Value}");
            }
        }
        else
        {
            if (localInfo.RegisterByName)
            {
                throw new ForyInvalidDataException(
                    "received id-registered compatible metadata for name-registered local type");
            }

            if (!remoteTypeMeta.UserTypeId.HasValue)
            {
                throw new ForyInvalidDataException("missing user type id in compatible type metadata");
            }

            if (!localInfo.UserTypeId.HasValue)
            {
                throw new ForyInvalidDataException("missing local user type id metadata for id-registered type");
            }

            if (remoteTypeMeta.UserTypeId.Value != localInfo.UserTypeId.Value)
            {
                throw new ForyTypeMismatchException(localInfo.UserTypeId.Value, remoteTypeMeta.UserTypeId.Value);
            }
        }

        if (remoteTypeMeta.TypeId.HasValue &&
            Enum.IsDefined(typeof(ForyTypeId), remoteTypeMeta.TypeId.Value))
        {
            ForyTypeId remoteWireTypeId = (ForyTypeId)remoteTypeMeta.TypeId.Value;
            if (!expectedWireTypes.Contains(remoteWireTypeId))
            {
                throw new ForyTypeMismatchException((uint)actualWireTypeId, remoteTypeMeta.TypeId.Value);
            }
        }
    }

    private static void WriteMetaString(
        ref WriteContext context,
        MetaString value,
        IReadOnlyList<MetaStringEncoding> encodings,
        MetaStringEncoder encoder)
    {
        MetaString normalized = encodings.Contains(value.Encoding)
            ? value
            : encoder.Encode(value.Value, encodings);
        if (!encodings.Contains(normalized.Encoding))
        {
            throw new ForyEncodingException("failed to normalize meta string encoding");
        }

        byte[] bytes = normalized.Bytes;
        (uint index, bool isNew) = context.MetaStringWriteState.AssignIndexIfAbsent(normalized);
        if (isNew)
        {
            context.Writer.WriteVarUInt32((uint)(bytes.Length << 1));
            if (bytes.Length > 16)
            {
                context.Writer.WriteInt64(unchecked((long)JavaMetaStringHash(normalized)));
            }
            else if (bytes.Length > 0)
            {
                context.Writer.WriteUInt8((byte)normalized.Encoding);
            }

            context.Writer.WriteBytes(bytes);
        }
        else
        {
            context.Writer.WriteVarUInt32(((index + 1) << 1) | 1);
        }
    }

    private static MetaString ReadMetaString(
        ref ReadContext context,
        MetaStringDecoder decoder,
        IReadOnlyList<MetaStringEncoding> encodings)
    {
        uint header = context.Reader.ReadVarUInt32();
        int length = checked((int)(header >> 1));
        bool isRef = (header & 1) == 1;
        if (isRef)
        {
            int index = length - 1;
            MetaString? cached = context.MetaStringReadState.ValueAt(index);
            if (cached is null)
            {
                throw new ForyInvalidDataException($"unknown meta string ref index {index}");
            }

            return cached.Value;
        }

        MetaString value;
        if (length == 0)
        {
            value = MetaString.Empty(decoder.SpecialChar1, decoder.SpecialChar2);
        }
        else
        {
            MetaStringEncoding encoding;
            if (length > 16)
            {
                long hash = context.Reader.ReadInt64();
                byte rawEncoding = unchecked((byte)(hash & 0xFF));
                encoding = (MetaStringEncoding)rawEncoding;
            }
            else
            {
                encoding = (MetaStringEncoding)context.Reader.ReadUInt8();
            }

            if (!encodings.Contains(encoding))
            {
                throw new ForyInvalidDataException($"meta string encoding {encoding} not allowed in this context");
            }

            byte[] bytes = context.Reader.ReadBytes(length);
            value = decoder.Decode(bytes, encoding);
        }

        context.MetaStringReadState.Append(value);
        return value;
    }

    private static ulong JavaMetaStringHash(MetaString metaString)
    {
        (ulong h1, _) = MurmurHash3.X64_128(metaString.Bytes, 47);
        long hash = unchecked((long)h1);
        if (hash != long.MinValue)
        {
            hash = Math.Abs(hash);
        }

        ulong result = unchecked((ulong)hash);
        if (result == 0)
        {
            result += 256;
        }

        result &= 0xffffffffffffff00;
        result |= (byte)metaString.Encoding;
        return result;
    }
}
