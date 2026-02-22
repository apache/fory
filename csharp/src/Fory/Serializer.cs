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

public readonly struct Serializer<T>
{
    private readonly ITypedSerializer<T>? _serializer;

    internal Serializer(ITypedSerializer<T> serializer)
    {
        _serializer = serializer;
    }

    private ITypedSerializer<T> SerializerImpl
    {
        get
        {
            if (_serializer is null)
            {
                throw new InvalidDataException($"serializer handle for {typeof(T)} is not initialized");
            }

            return _serializer;
        }
    }

    public Type Type => typeof(T);

    public TypeId StaticTypeId => SerializerImpl.StaticTypeId;

    public bool IsNullableType => SerializerImpl.IsNullableType;

    public bool IsReferenceTrackableType => SerializerImpl.IsReferenceTrackableType;

    public T DefaultValue => SerializerImpl.DefaultValue;

    public object? DefaultObject => SerializerImpl.DefaultValue;

    public bool IsNone(in T value)
    {
        return SerializerImpl.IsNone(value);
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
        SerializerImpl.WriteData(ref context, value, hasGenerics);
    }

    public T ReadData(ref ReadContext context)
    {
        return SerializerImpl.ReadData(ref context);
    }

    public void Write(ref WriteContext context, in T value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        SerializerImpl.Write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public T Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return SerializerImpl.Read(ref context, refMode, readTypeInfo);
    }

    public void WriteTypeInfo(ref WriteContext context)
    {
        SerializerImpl.WriteTypeInfo(ref context);
    }

    public void ReadTypeInfo(ref ReadContext context)
    {
        SerializerImpl.ReadTypeInfo(ref context);
    }

    public IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return SerializerImpl.CompatibleTypeMetaFields(trackRef);
    }
}

internal static class SerializerTypeInfo
{
    public static void WriteTypeInfo(Type type, SerializerBinding serializer, ref WriteContext context)
    {
        TypeId staticTypeId = serializer.StaticTypeId;
        if (!staticTypeId.IsUserTypeKind())
        {
            context.Writer.WriteUInt8((byte)staticTypeId);
            return;
        }

        RegisteredTypeInfo info = context.TypeResolver.RequireRegisteredTypeInfo(type);
        TypeId wireTypeId = ResolveWireTypeId(info.Kind, info.RegisterByName, context.Compatible);
        context.Writer.WriteUInt8((byte)wireTypeId);
        switch (wireTypeId)
        {
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
            {
                TypeMeta typeMeta = BuildCompatibleTypeMeta(info, wireTypeId, context.TrackRef);
                context.WriteCompatibleTypeMeta(type, typeMeta);
                return;
            }
            case TypeId.NamedEnum:
            case TypeId.NamedStruct:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
            {
                if (context.Compatible)
                {
                    TypeMeta typeMeta = BuildCompatibleTypeMeta(info, wireTypeId, context.TrackRef);
                    context.WriteCompatibleTypeMeta(type, typeMeta);
                }
                else
                {
                    if (info.NamespaceName is null)
                    {
                        throw new InvalidDataException("missing namespace metadata for name-registered type");
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
                        throw new InvalidDataException("missing user type id for id-registered type");
                    }

                    context.Writer.WriteVarUInt32(info.UserTypeId.Value);
                }

                return;
        }
    }

    public static void ReadTypeInfo(Type type, SerializerBinding serializer, ref ReadContext context)
    {
        uint rawTypeId = context.Reader.ReadVarUInt32();
        if (!Enum.IsDefined(typeof(TypeId), rawTypeId))
        {
            throw new InvalidDataException($"unknown type id {rawTypeId}");
        }

        TypeId typeId = (TypeId)rawTypeId;
        TypeId staticTypeId = serializer.StaticTypeId;
        if (!staticTypeId.IsUserTypeKind())
        {
            if (typeId != staticTypeId)
            {
                throw new TypeMismatchException((uint)staticTypeId, rawTypeId);
            }

            return;
        }

        RegisteredTypeInfo info = context.TypeResolver.RequireRegisteredTypeInfo(type);
        HashSet<TypeId> allowed = AllowedWireTypeIds(info.Kind, info.RegisterByName, context.Compatible);
        if (!allowed.Contains(typeId))
        {
            uint expected = allowed.Count > 0 ? (uint)allowed.First() : 0;
            throw new TypeMismatchException(expected, rawTypeId);
        }

        switch (typeId)
        {
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
            {
                TypeMeta remoteTypeMeta = context.ReadCompatibleTypeMeta();
                ValidateCompatibleTypeMeta(remoteTypeMeta, info, allowed, typeId);
                context.PushCompatibleTypeMeta(type, remoteTypeMeta);
                return;
            }
            case TypeId.NamedEnum:
            case TypeId.NamedStruct:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
            {
                if (context.Compatible)
                {
                    TypeMeta remoteTypeMeta = context.ReadCompatibleTypeMeta();
                    ValidateCompatibleTypeMeta(remoteTypeMeta, info, allowed, typeId);
                    if (typeId == TypeId.NamedStruct)
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
                        throw new InvalidDataException("received name-registered type info for id-registered local type");
                    }

                    if (namespaceName.Value != info.NamespaceName.Value.Value || typeName.Value != info.TypeName.Value)
                    {
                        throw new InvalidDataException(
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
                        throw new InvalidDataException("missing user type id for id-registered local type");
                    }

                    uint remoteUserTypeId = context.Reader.ReadVarUInt32();
                    if (remoteUserTypeId != info.UserTypeId.Value)
                    {
                        throw new TypeMismatchException(info.UserTypeId.Value, remoteUserTypeId);
                    }
                }

                return;
        }
    }

    public static TypeId ResolveWireTypeId(TypeId declaredKind, bool registerByName, bool compatible)
    {
        TypeId baseKind = NormalizeBaseKind(declaredKind);
        if (registerByName)
        {
            return NamedKind(baseKind, compatible);
        }

        return IdKind(baseKind, compatible);
    }

    public static HashSet<TypeId> AllowedWireTypeIds(TypeId declaredKind, bool registerByName, bool compatible)
    {
        TypeId baseKind = NormalizeBaseKind(declaredKind);
        TypeId expected = ResolveWireTypeId(declaredKind, registerByName, compatible);
        HashSet<TypeId> allowed = [expected];
        if (baseKind == TypeId.Struct && compatible)
        {
            allowed.Add(TypeId.CompatibleStruct);
            allowed.Add(TypeId.NamedCompatibleStruct);
            allowed.Add(TypeId.Struct);
            allowed.Add(TypeId.NamedStruct);
        }

        return allowed;
    }

    private static TypeId NormalizeBaseKind(TypeId kind)
    {
        return kind switch
        {
            TypeId.NamedEnum => TypeId.Enum,
            TypeId.CompatibleStruct or TypeId.NamedCompatibleStruct or TypeId.NamedStruct => TypeId.Struct,
            TypeId.NamedExt => TypeId.Ext,
            TypeId.NamedUnion => TypeId.TypedUnion,
            _ => kind,
        };
    }

    private static TypeId NamedKind(TypeId baseKind, bool compatible)
    {
        return baseKind switch
        {
            TypeId.Struct => compatible ? TypeId.NamedCompatibleStruct : TypeId.NamedStruct,
            TypeId.Enum => TypeId.NamedEnum,
            TypeId.Ext => TypeId.NamedExt,
            TypeId.TypedUnion => TypeId.NamedUnion,
            _ => baseKind,
        };
    }

    private static TypeId IdKind(TypeId baseKind, bool compatible)
    {
        return baseKind switch
        {
            TypeId.Struct => compatible ? TypeId.CompatibleStruct : TypeId.Struct,
            _ => baseKind,
        };
    }

    private static bool WireTypeNeedsUserTypeId(TypeId typeId)
    {
        return typeId is TypeId.Enum or TypeId.Struct or TypeId.Ext or TypeId.TypedUnion;
    }

    private static TypeMeta BuildCompatibleTypeMeta(
        RegisteredTypeInfo info,
        TypeId wireTypeId,
        bool trackRef)
    {
        IReadOnlyList<TypeMetaFieldInfo> fields = info.Serializer.CompatibleTypeMetaFields(trackRef);
        bool hasFieldsMeta = fields.Count > 0;
        if (info.RegisterByName)
        {
            if (info.NamespaceName is null)
            {
                throw new InvalidDataException("missing namespace metadata for name-registered type");
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
            throw new InvalidDataException("missing user type id metadata for id-registered type");
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
        HashSet<TypeId> expectedWireTypes,
        TypeId actualWireTypeId)
    {
        if (remoteTypeMeta.RegisterByName)
        {
            if (!localInfo.RegisterByName || localInfo.NamespaceName is null)
            {
                throw new InvalidDataException(
                    "received name-registered compatible metadata for id-registered local type");
            }

            if (remoteTypeMeta.NamespaceName.Value != localInfo.NamespaceName.Value.Value)
            {
                throw new InvalidDataException(
                    $"namespace mismatch: expected {localInfo.NamespaceName.Value.Value}, got {remoteTypeMeta.NamespaceName.Value}");
            }

            if (remoteTypeMeta.TypeName.Value != localInfo.TypeName.Value)
            {
                throw new InvalidDataException(
                    $"type name mismatch: expected {localInfo.TypeName.Value}, got {remoteTypeMeta.TypeName.Value}");
            }
        }
        else
        {
            if (localInfo.RegisterByName)
            {
                throw new InvalidDataException(
                    "received id-registered compatible metadata for name-registered local type");
            }

            if (!remoteTypeMeta.UserTypeId.HasValue)
            {
                throw new InvalidDataException("missing user type id in compatible type metadata");
            }

            if (!localInfo.UserTypeId.HasValue)
            {
                throw new InvalidDataException("missing local user type id metadata for id-registered type");
            }

            if (remoteTypeMeta.UserTypeId.Value != localInfo.UserTypeId.Value)
            {
                throw new TypeMismatchException(localInfo.UserTypeId.Value, remoteTypeMeta.UserTypeId.Value);
            }
        }

        if (remoteTypeMeta.TypeId.HasValue &&
            Enum.IsDefined(typeof(TypeId), remoteTypeMeta.TypeId.Value))
        {
            TypeId remoteWireTypeId = (TypeId)remoteTypeMeta.TypeId.Value;
            if (!expectedWireTypes.Contains(remoteWireTypeId))
            {
                throw new TypeMismatchException((uint)actualWireTypeId, remoteTypeMeta.TypeId.Value);
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
            throw new EncodingException("failed to normalize meta string encoding");
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
                throw new InvalidDataException($"unknown meta string ref index {index}");
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
                throw new InvalidDataException($"meta string encoding {encoding} not allowed in this context");
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
