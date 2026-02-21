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

internal sealed record RegisteredTypeInfo(
    uint? UserTypeId,
    ForyTypeId Kind,
    bool RegisterByName,
    MetaString? NamespaceName,
    MetaString TypeName,
    SerializerBinding Serializer);

internal enum DynamicRegistrationMode
{
    IdOnly,
    NameOnly,
    Mixed,
}

internal readonly record struct TypeNameKey(string NamespaceName, string TypeName);

internal sealed class TypeReader
{
    public required ForyTypeId Kind { get; init; }
    public required Func<ReadContext, object?> Reader { get; init; }
    public required Func<ReadContext, TypeMeta, object?> CompatibleReader { get; init; }
}

public sealed class TypeResolver
{
    private readonly Dictionary<Type, RegisteredTypeInfo> _byType = [];
    private readonly Dictionary<uint, TypeReader> _byUserTypeId = [];
    private readonly Dictionary<TypeNameKey, TypeReader> _byTypeName = [];
    private readonly Dictionary<ForyTypeId, DynamicRegistrationMode> _registrationModeByKind = [];

    internal void Register(Type type, uint id, SerializerBinding? explicitSerializer = null)
    {
        SerializerBinding serializer = explicitSerializer ?? SerializerRegistry.GetBinding(type);
        RegisteredTypeInfo info = new(
            id,
            serializer.StaticTypeId,
            false,
            null,
            MetaString.Empty('$', '_'),
            serializer);
        _byType[type] = info;
        MarkRegistrationMode(info.Kind, false);
        _byUserTypeId[id] = new TypeReader
        {
            Kind = serializer.StaticTypeId,
            Reader = context => serializer.Read(ref context, RefMode.None, false),
            CompatibleReader = (context, typeMeta) =>
            {
                context.PushCompatibleTypeMeta(type, typeMeta);
                return serializer.Read(ref context, RefMode.None, false);
            },
        };
    }

    internal void Register(Type type, string namespaceName, string typeName, SerializerBinding? explicitSerializer = null)
    {
        SerializerBinding serializer = explicitSerializer ?? SerializerRegistry.GetBinding(type);
        MetaString namespaceMeta = MetaStringEncoder.Namespace.Encode(namespaceName, TypeMetaEncodings.NamespaceMetaStringEncodings);
        MetaString typeNameMeta = MetaStringEncoder.TypeName.Encode(typeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
        RegisteredTypeInfo info = new(
            null,
            serializer.StaticTypeId,
            true,
            namespaceMeta,
            typeNameMeta,
            serializer);
        _byType[type] = info;
        MarkRegistrationMode(info.Kind, true);
        _byTypeName[new TypeNameKey(namespaceName, typeName)] = new TypeReader
        {
            Kind = serializer.StaticTypeId,
            Reader = context => serializer.Read(ref context, RefMode.None, false),
            CompatibleReader = (context, typeMeta) =>
            {
                context.PushCompatibleTypeMeta(type, typeMeta);
                return serializer.Read(ref context, RefMode.None, false);
            },
        };
    }

    internal RegisteredTypeInfo? RegisteredTypeInfo(Type type)
    {
        return _byType.TryGetValue(type, out RegisteredTypeInfo? info) ? info : null;
    }

    internal RegisteredTypeInfo RequireRegisteredTypeInfo(Type type)
    {
        if (_byType.TryGetValue(type, out RegisteredTypeInfo? info))
        {
            return info;
        }

        throw new ForyTypeNotRegisteredException($"{type} is not registered");
    }

    public object? ReadByUserTypeId(uint userTypeId, ref ReadContext context, TypeMeta? compatibleTypeMeta = null)
    {
        if (!_byUserTypeId.TryGetValue(userTypeId, out TypeReader? entry))
        {
            throw new ForyTypeNotRegisteredException($"user_type_id={userTypeId}");
        }

        return compatibleTypeMeta is null
            ? entry.Reader(context)
            : entry.CompatibleReader(context, compatibleTypeMeta);
    }

    public object? ReadByTypeName(string namespaceName, string typeName, ref ReadContext context, TypeMeta? compatibleTypeMeta = null)
    {
        if (!_byTypeName.TryGetValue(new TypeNameKey(namespaceName, typeName), out TypeReader? entry))
        {
            throw new ForyTypeNotRegisteredException($"namespace={namespaceName}, type={typeName}");
        }

        return compatibleTypeMeta is null
            ? entry.Reader(context)
            : entry.CompatibleReader(context, compatibleTypeMeta);
    }

    public DynamicTypeInfo ReadDynamicTypeInfo(ref ReadContext context)
    {
        uint rawTypeId = context.Reader.ReadVarUInt32();
        if (!Enum.IsDefined(typeof(ForyTypeId), rawTypeId))
        {
            throw new ForyInvalidDataException($"unknown dynamic type id {rawTypeId}");
        }

        ForyTypeId wireTypeId = (ForyTypeId)rawTypeId;
        switch (wireTypeId)
        {
            case ForyTypeId.CompatibleStruct:
            case ForyTypeId.NamedCompatibleStruct:
            {
                TypeMeta typeMeta = context.ReadCompatibleTypeMeta();
                if (typeMeta.RegisterByName)
                {
                    return new DynamicTypeInfo(wireTypeId, null, typeMeta.NamespaceName, typeMeta.TypeName, typeMeta);
                }

                return new DynamicTypeInfo(wireTypeId, typeMeta.UserTypeId, null, null, typeMeta);
            }
            case ForyTypeId.NamedStruct:
            case ForyTypeId.NamedEnum:
            case ForyTypeId.NamedExt:
            case ForyTypeId.NamedUnion:
            {
                MetaString namespaceName = ReadMetaString(context.Reader, MetaStringDecoder.Namespace, TypeMetaEncodings.NamespaceMetaStringEncodings);
                MetaString typeName = ReadMetaString(context.Reader, MetaStringDecoder.TypeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
                return new DynamicTypeInfo(wireTypeId, null, namespaceName, typeName, null);
            }
            case ForyTypeId.Struct:
            case ForyTypeId.Enum:
            case ForyTypeId.Ext:
            case ForyTypeId.TypedUnion:
            {
                DynamicRegistrationMode mode = DynamicRegistrationModeFor(wireTypeId);
                if (mode == DynamicRegistrationMode.IdOnly)
                {
                    return new DynamicTypeInfo(wireTypeId, context.Reader.ReadVarUInt32(), null, null, null);
                }

                if (mode == DynamicRegistrationMode.NameOnly)
                {
                    MetaString namespaceName = ReadMetaString(context.Reader, MetaStringDecoder.Namespace, TypeMetaEncodings.NamespaceMetaStringEncodings);
                    MetaString typeName = ReadMetaString(context.Reader, MetaStringDecoder.TypeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
                    return new DynamicTypeInfo(wireTypeId, null, namespaceName, typeName, null);
                }

                throw new ForyInvalidDataException($"ambiguous dynamic type registration mode for {wireTypeId}");
            }
            default:
                return new DynamicTypeInfo(wireTypeId, null, null, null, null);
        }
    }

    public object? ReadDynamicValue(DynamicTypeInfo typeInfo, ref ReadContext context)
    {
        switch (typeInfo.WireTypeId)
        {
            case ForyTypeId.Bool:
                return SerializerRegistry.Get<bool>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Int8:
                return SerializerRegistry.Get<sbyte>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Int16:
                return SerializerRegistry.Get<short>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Int32:
                return SerializerRegistry.Get<ForyInt32Fixed>().Read(ref context, RefMode.None, false);
            case ForyTypeId.VarInt32:
                return SerializerRegistry.Get<int>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Int64:
                return SerializerRegistry.Get<ForyInt64Fixed>().Read(ref context, RefMode.None, false);
            case ForyTypeId.VarInt64:
                return SerializerRegistry.Get<long>().Read(ref context, RefMode.None, false);
            case ForyTypeId.TaggedInt64:
                return SerializerRegistry.Get<ForyInt64Tagged>().Read(ref context, RefMode.None, false);
            case ForyTypeId.UInt8:
                return SerializerRegistry.Get<byte>().Read(ref context, RefMode.None, false);
            case ForyTypeId.UInt16:
                return SerializerRegistry.Get<ushort>().Read(ref context, RefMode.None, false);
            case ForyTypeId.UInt32:
                return SerializerRegistry.Get<ForyUInt32Fixed>().Read(ref context, RefMode.None, false);
            case ForyTypeId.VarUInt32:
                return SerializerRegistry.Get<uint>().Read(ref context, RefMode.None, false);
            case ForyTypeId.UInt64:
                return SerializerRegistry.Get<ForyUInt64Fixed>().Read(ref context, RefMode.None, false);
            case ForyTypeId.VarUInt64:
                return SerializerRegistry.Get<ulong>().Read(ref context, RefMode.None, false);
            case ForyTypeId.TaggedUInt64:
                return SerializerRegistry.Get<ForyUInt64Tagged>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Float32:
                return SerializerRegistry.Get<float>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Float64:
                return SerializerRegistry.Get<double>().Read(ref context, RefMode.None, false);
            case ForyTypeId.String:
                return SerializerRegistry.Get<string>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Binary:
            case ForyTypeId.UInt8Array:
                return SerializerRegistry.Get<byte[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.BoolArray:
                return SerializerRegistry.Get<bool[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Int8Array:
                return SerializerRegistry.Get<sbyte[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Int16Array:
                return SerializerRegistry.Get<short[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Int32Array:
                return SerializerRegistry.Get<int[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Int64Array:
                return SerializerRegistry.Get<long[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.UInt16Array:
                return SerializerRegistry.Get<ushort[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.UInt32Array:
                return SerializerRegistry.Get<uint[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.UInt64Array:
                return SerializerRegistry.Get<ulong[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Float32Array:
                return SerializerRegistry.Get<float[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Float64Array:
                return SerializerRegistry.Get<double[]>().Read(ref context, RefMode.None, false);
            case ForyTypeId.List:
                return DynamicAnyCodec.ReadAnyList(ref context, RefMode.None, false) ?? [];
            case ForyTypeId.Map:
                return DynamicAnyCodec.ReadDynamicAnyMapValue(ref context);
            case ForyTypeId.Union:
                return SerializerRegistry.Get<Union>().Read(ref context, RefMode.None, false);
            case ForyTypeId.Struct:
            case ForyTypeId.Enum:
            case ForyTypeId.Ext:
            case ForyTypeId.TypedUnion:
            {
                if (typeInfo.UserTypeId.HasValue)
                {
                    return ReadByUserTypeId(typeInfo.UserTypeId.Value, ref context);
                }

                if (typeInfo.NamespaceName.HasValue && typeInfo.TypeName.HasValue)
                {
                    return ReadByTypeName(typeInfo.NamespaceName.Value.Value, typeInfo.TypeName.Value.Value, ref context);
                }

                throw new ForyInvalidDataException($"missing dynamic registration info for {typeInfo.WireTypeId}");
            }
            case ForyTypeId.NamedStruct:
            case ForyTypeId.NamedEnum:
            case ForyTypeId.NamedExt:
            case ForyTypeId.NamedUnion:
            {
                if (!typeInfo.NamespaceName.HasValue || !typeInfo.TypeName.HasValue)
                {
                    throw new ForyInvalidDataException($"missing dynamic type name for {typeInfo.WireTypeId}");
                }

                return ReadByTypeName(typeInfo.NamespaceName.Value.Value, typeInfo.TypeName.Value.Value, ref context);
            }
            case ForyTypeId.CompatibleStruct:
            case ForyTypeId.NamedCompatibleStruct:
            {
                if (typeInfo.CompatibleTypeMeta is null)
                {
                    throw new ForyInvalidDataException($"missing compatible type meta for {typeInfo.WireTypeId}");
                }

                TypeMeta compatibleTypeMeta = typeInfo.CompatibleTypeMeta;
                if (compatibleTypeMeta.RegisterByName)
                {
                    return ReadByTypeName(
                        compatibleTypeMeta.NamespaceName.Value,
                        compatibleTypeMeta.TypeName.Value,
                        ref context,
                        compatibleTypeMeta);
                }

                if (!compatibleTypeMeta.UserTypeId.HasValue)
                {
                    throw new ForyInvalidDataException("missing user type id in compatible dynamic type meta");
                }

                return ReadByUserTypeId(compatibleTypeMeta.UserTypeId.Value, ref context, compatibleTypeMeta);
            }
            case ForyTypeId.None:
                return new ForyAnyNullValue();
            default:
                throw new ForyInvalidDataException($"unsupported dynamic type id {typeInfo.WireTypeId}");
        }
    }

    private void MarkRegistrationMode(ForyTypeId kind, bool registerByName)
    {
        DynamicRegistrationMode mode = registerByName ? DynamicRegistrationMode.NameOnly : DynamicRegistrationMode.IdOnly;
        if (!_registrationModeByKind.TryGetValue(kind, out DynamicRegistrationMode existing))
        {
            _registrationModeByKind[kind] = mode;
            return;
        }

        if (existing != mode)
        {
            _registrationModeByKind[kind] = DynamicRegistrationMode.Mixed;
        }
    }

    private DynamicRegistrationMode DynamicRegistrationModeFor(ForyTypeId kind)
    {
        if (_registrationModeByKind.TryGetValue(kind, out DynamicRegistrationMode mode))
        {
            return mode;
        }

        throw new ForyTypeNotRegisteredException($"no dynamic registration mode for kind {kind}");
    }

    private static MetaString ReadMetaString(ByteReader reader, MetaStringDecoder decoder, IReadOnlyList<MetaStringEncoding> encodings)
    {
        byte header = reader.ReadUInt8();
        int encodingIndex = header & 0b11;
        if (encodingIndex >= encodings.Count)
        {
            throw new ForyInvalidDataException("invalid meta string encoding index");
        }

        int length = header >> 2;
        if (length >= 0b11_1111)
        {
            length = 0b11_1111 + (int)reader.ReadVarUInt32();
        }

        byte[] bytes = reader.ReadBytes(length);
        return decoder.Decode(bytes, encodings[encodingIndex]);
    }
}
