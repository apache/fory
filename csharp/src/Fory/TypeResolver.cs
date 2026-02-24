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

using System.Collections.Concurrent;
using System.Collections.Immutable;

namespace Apache.Fory;

public sealed class TypeResolver
{
    private static readonly ConcurrentDictionary<Type, Func<Serializer>> GeneratedFactories = new();
    private static readonly ConcurrentDictionary<TypeId, HashSet<TypeId>> SingleAllowedWireTypes = new();
    private static readonly HashSet<TypeId> CompatibleStructAllowedWireTypes =
    [
        TypeId.Struct,
        TypeId.NamedStruct,
        TypeId.CompatibleStruct,
        TypeId.NamedCompatibleStruct,
    ];
    private static readonly Dictionary<Type, Type> PrimitiveStringKeyDictionaryCodecs = new()
    {
        [typeof(string)] = typeof(StringPrimitiveDictionaryCodec),
        [typeof(int)] = typeof(Int32PrimitiveDictionaryCodec),
        [typeof(long)] = typeof(Int64PrimitiveDictionaryCodec),
        [typeof(bool)] = typeof(BoolPrimitiveDictionaryCodec),
        [typeof(double)] = typeof(Float64PrimitiveDictionaryCodec),
        [typeof(float)] = typeof(Float32PrimitiveDictionaryCodec),
        [typeof(uint)] = typeof(UInt32PrimitiveDictionaryCodec),
        [typeof(ulong)] = typeof(UInt64PrimitiveDictionaryCodec),
        [typeof(sbyte)] = typeof(Int8PrimitiveDictionaryCodec),
        [typeof(short)] = typeof(Int16PrimitiveDictionaryCodec),
        [typeof(ushort)] = typeof(UInt16PrimitiveDictionaryCodec),
    };

    private static readonly Dictionary<Type, Type> PrimitiveSameTypeDictionaryCodecs = new()
    {
        [typeof(int)] = typeof(Int32PrimitiveDictionaryCodec),
        [typeof(long)] = typeof(Int64PrimitiveDictionaryCodec),
        [typeof(uint)] = typeof(UInt32PrimitiveDictionaryCodec),
        [typeof(ulong)] = typeof(UInt64PrimitiveDictionaryCodec),
    };

    private static readonly Dictionary<Type, Type> PrimitiveListLikeCollectionCodecs = new()
    {
        [typeof(bool)] = typeof(BoolPrimitiveDictionaryCodec),
        [typeof(sbyte)] = typeof(Int8PrimitiveDictionaryCodec),
        [typeof(short)] = typeof(Int16PrimitiveDictionaryCodec),
        [typeof(int)] = typeof(Int32PrimitiveDictionaryCodec),
        [typeof(long)] = typeof(Int64PrimitiveDictionaryCodec),
        [typeof(ushort)] = typeof(UInt16PrimitiveDictionaryCodec),
        [typeof(uint)] = typeof(UInt32PrimitiveDictionaryCodec),
        [typeof(ulong)] = typeof(UInt64PrimitiveDictionaryCodec),
        [typeof(float)] = typeof(Float32PrimitiveDictionaryCodec),
        [typeof(double)] = typeof(Float64PrimitiveDictionaryCodec),
    };

    private static readonly Dictionary<Type, Type> PrimitiveSetCollectionCodecs = new()
    {
        [typeof(sbyte)] = typeof(Int8PrimitiveDictionaryCodec),
        [typeof(short)] = typeof(Int16PrimitiveDictionaryCodec),
        [typeof(int)] = typeof(Int32PrimitiveDictionaryCodec),
        [typeof(long)] = typeof(Int64PrimitiveDictionaryCodec),
        [typeof(ushort)] = typeof(UInt16PrimitiveDictionaryCodec),
        [typeof(uint)] = typeof(UInt32PrimitiveDictionaryCodec),
        [typeof(ulong)] = typeof(UInt64PrimitiveDictionaryCodec),
        [typeof(float)] = typeof(Float32PrimitiveDictionaryCodec),
        [typeof(double)] = typeof(Float64PrimitiveDictionaryCodec),
    };

    private readonly Dictionary<uint, TypeInfo> _byUserTypeId = [];
    private readonly Dictionary<(string NamespaceName, string TypeName), TypeInfo> _byTypeName = [];

    private readonly Dictionary<Type, TypeInfo> _typeInfos = [];

    public static void RegisterGenerated<T, TSerializer>()
        where TSerializer : Serializer<T>, new()
    {
        Type type = typeof(T);
        GeneratedFactories[type] = CreateSerializer<TSerializer>;
    }

    public Serializer<T> GetSerializer<T>()
    {
        return GetTypeInfo<T>().Serializer.RequireSerializer<T>();
    }

    public TypeInfo GetTypeInfo(Type type)
    {
        return GetOrCreateTypeInfo(type, null);
    }

    public TypeInfo GetTypeInfo<T>()
    {
        return GetTypeInfo(typeof(T));
    }

    private TypeInfo GetOrCreateTypeInfo(Type type, Serializer? explicitSerializer)
    {
        if (_typeInfos.TryGetValue(type, out TypeInfo? existing))
        {
            if (explicitSerializer is null || ReferenceEquals(existing.Serializer, explicitSerializer))
            {
                return existing;
            }

            if (existing.IsRegistered)
            {
                throw new InvalidDataException($"cannot override serializer for registered type {type}");
            }
        }

        Serializer serializer = explicitSerializer ?? CreateBindingCore(type);
        TypeInfo typeInfo = new(type, serializer);
        if (_typeInfos.TryGetValue(type, out TypeInfo? previous))
        {
            typeInfo.CopyRegistrationFrom(previous);
        }

        _typeInfos[type] = typeInfo;
        return typeInfo;
    }

    internal Serializer GetSerializer(Type type)
    {
        return GetOrCreateTypeInfo(type, null).Serializer;
    }

    internal Serializer RegisterSerializer<T, TSerializer>()
        where TSerializer : Serializer<T>, new()
    {
        Serializer serializer = CreateSerializer<TSerializer>();
        RegisterSerializer(typeof(T), serializer);
        return serializer;
    }

    internal void RegisterSerializer(Type type, Serializer serializer)
    {
        GetOrCreateTypeInfo(type, serializer);
    }

    internal void Register(Type type, uint id, Serializer? explicitSerializer = null)
    {
        TypeInfo typeInfo = GetOrCreateTypeInfo(type, explicitSerializer);
        typeInfo.RegisterByTypeId(id);
        _byUserTypeId[id] = typeInfo;
    }

    internal void Register(Type type, string namespaceName, string typeName, Serializer? explicitSerializer = null)
    {
        TypeInfo typeInfo = GetOrCreateTypeInfo(type, explicitSerializer);
        MetaString namespaceMeta = MetaStringEncoder.Namespace.Encode(namespaceName, TypeMetaEncodings.NamespaceMetaStringEncodings);
        MetaString typeNameMeta = MetaStringEncoder.TypeName.Encode(typeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
        typeInfo.RegisterByTypeName(namespaceMeta, typeNameMeta);
        _byTypeName[(namespaceName, typeName)] = typeInfo;
    }

    internal TypeInfo? GetRegisteredTypeInfo(Type type)
    {
        if (_typeInfos.TryGetValue(type, out TypeInfo? typeInfo) && typeInfo.IsRegistered)
        {
            return typeInfo;
        }

        return null;
    }

    internal TypeInfo RequireRegisteredTypeInfo(Type type)
    {
        TypeInfo? info = GetRegisteredTypeInfo(type);
        if (info is not null)
        {
            return info;
        }

        throw new TypeNotRegisteredException($"{type} is not registered");
    }

    internal void WriteTypeInfo(Type type, Serializer serializer, WriteContext context)
    {
        TypeId staticTypeId = serializer.StaticTypeId;
        if (!staticTypeId.IsUserTypeKind())
        {
            context.Writer.WriteUInt8((byte)staticTypeId);
            return;
        }

        TypeInfo info = RequireRegisteredTypeInfo(type);
        TypeId wireTypeId = ResolveWireTypeId(info.StaticTypeId, info.RegisterByName, context.Compatible);
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
                    if (!info.NamespaceName.HasValue || !info.TypeName.HasValue)
                    {
                        throw new InvalidDataException("missing type name metadata for name-registered type");
                    }

                    WriteMetaString(
                        context,
                        info.NamespaceName.Value,
                        TypeMetaEncodings.NamespaceMetaStringEncodings,
                        MetaStringEncoder.Namespace);
                    WriteMetaString(
                        context,
                        info.TypeName.Value,
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

    internal void ReadTypeInfo(Type type, Serializer serializer, ReadContext context)
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

        TypeInfo info = RequireRegisteredTypeInfo(type);
        HashSet<TypeId> allowed = AllowedWireTypeIds(info.StaticTypeId, info.RegisterByName, context.Compatible);
        if (!allowed.Contains(typeId))
        {
            uint expected = 0;
            foreach (TypeId allowedType in allowed)
            {
                expected = (uint)allowedType;
                break;
            }

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
                        context,
                        MetaStringDecoder.Namespace,
                        TypeMetaEncodings.NamespaceMetaStringEncodings);
                    MetaString typeName = ReadMetaString(
                        context,
                        MetaStringDecoder.TypeName,
                        TypeMetaEncodings.TypeNameMetaStringEncodings);
                    if (!info.RegisterByName || !info.NamespaceName.HasValue || !info.TypeName.HasValue)
                    {
                        throw new InvalidDataException("received name-registered type info for id-registered local type");
                    }

                    if (namespaceName.Value != info.NamespaceName.Value.Value || typeName.Value != info.TypeName.Value.Value)
                    {
                        throw new InvalidDataException(
                            $"type name mismatch: expected {info.NamespaceName.Value.Value}::{info.TypeName.Value.Value}, got {namespaceName.Value}::{typeName.Value}");
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

    internal static TypeId ResolveWireTypeId(TypeId declaredKind, bool registerByName, bool compatible)
    {
        TypeId baseKind = NormalizeBaseKind(declaredKind);
        if (registerByName)
        {
            return NamedKind(baseKind, compatible);
        }

        return IdKind(baseKind, compatible);
    }

    internal static HashSet<TypeId> AllowedWireTypeIds(TypeId declaredKind, bool registerByName, bool compatible)
    {
        TypeId baseKind = NormalizeBaseKind(declaredKind);
        if (baseKind == TypeId.Struct && compatible)
        {
            return CompatibleStructAllowedWireTypes;
        }

        TypeId expected = ResolveWireTypeId(declaredKind, registerByName, compatible);
        return SingleAllowedWireTypes.GetOrAdd(expected, static typeId => [typeId]);
    }

    public object? ReadByUserTypeId(uint userTypeId, ReadContext context, TypeMeta? compatibleTypeMeta = null)
    {
        if (!_byUserTypeId.TryGetValue(userTypeId, out TypeInfo? typeInfo))
        {
            throw new TypeNotRegisteredException($"user_type_id={userTypeId}");
        }

        return ReadRegisteredValue(typeInfo, context, compatibleTypeMeta);
    }

    public object? ReadByTypeName(string namespaceName, string typeName, ReadContext context, TypeMeta? compatibleTypeMeta = null)
    {
        if (!_byTypeName.TryGetValue((namespaceName, typeName), out TypeInfo? typeInfo))
        {
            throw new TypeNotRegisteredException($"namespace={namespaceName}, type={typeName}");
        }

        return ReadRegisteredValue(typeInfo, context, compatibleTypeMeta);
    }

    private static object? ReadRegisteredValue(TypeInfo typeInfo, ReadContext context, TypeMeta? compatibleTypeMeta)
    {
        if (compatibleTypeMeta is not null)
        {
            context.PushCompatibleTypeMeta(typeInfo.Type, compatibleTypeMeta);
        }

        return typeInfo.Serializer.ReadObject(context, RefMode.None, false);
    }

    public DynamicTypeInfo ReadDynamicTypeInfo(ReadContext context)
    {
        uint rawTypeId = context.Reader.ReadVarUInt32();
        if (!Enum.IsDefined(typeof(TypeId), rawTypeId))
        {
            throw new InvalidDataException($"unknown dynamic type id {rawTypeId}");
        }

        TypeId wireTypeId = (TypeId)rawTypeId;
        switch (wireTypeId)
        {
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
            {
                TypeMeta typeMeta = context.ReadCompatibleTypeMeta();
                if (typeMeta.RegisterByName)
                {
                    return new DynamicTypeInfo(wireTypeId, null, typeMeta.NamespaceName, typeMeta.TypeName, typeMeta);
                }

                return new DynamicTypeInfo(wireTypeId, typeMeta.UserTypeId, null, null, typeMeta);
            }
            case TypeId.NamedStruct:
            case TypeId.NamedEnum:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
            {
                MetaString namespaceName = ReadMetaString(context.Reader, MetaStringDecoder.Namespace, TypeMetaEncodings.NamespaceMetaStringEncodings);
                MetaString typeName = ReadMetaString(context.Reader, MetaStringDecoder.TypeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
                return new DynamicTypeInfo(wireTypeId, null, namespaceName, typeName, null);
            }
            case TypeId.Struct:
            case TypeId.Enum:
            case TypeId.Ext:
            case TypeId.TypedUnion:
            {
                return new DynamicTypeInfo(wireTypeId, context.Reader.ReadVarUInt32(), null, null, null);
            }
            default:
                return new DynamicTypeInfo(wireTypeId, null, null, null, null);
        }
    }

    public object? ReadDynamicValue(DynamicTypeInfo typeInfo, ReadContext context)
    {
        switch (typeInfo.WireTypeId)
        {
            case TypeId.Bool:
                return context.Reader.ReadUInt8() != 0;
            case TypeId.Int8:
                return context.Reader.ReadInt8();
            case TypeId.Int16:
                return context.Reader.ReadInt16();
            case TypeId.Int32:
                return context.Reader.ReadInt32();
            case TypeId.VarInt32:
                return context.Reader.ReadVarInt32();
            case TypeId.Int64:
                return context.Reader.ReadInt64();
            case TypeId.VarInt64:
                return context.Reader.ReadVarInt64();
            case TypeId.TaggedInt64:
                return context.Reader.ReadTaggedInt64();
            case TypeId.UInt8:
                return context.Reader.ReadUInt8();
            case TypeId.UInt16:
                return context.Reader.ReadUInt16();
            case TypeId.UInt32:
                return context.Reader.ReadUInt32();
            case TypeId.VarUInt32:
                return context.Reader.ReadVarUInt32();
            case TypeId.UInt64:
                return context.Reader.ReadUInt64();
            case TypeId.VarUInt64:
                return context.Reader.ReadVarUInt64();
            case TypeId.TaggedUInt64:
                return context.Reader.ReadTaggedUInt64();
            case TypeId.Float32:
                return context.Reader.ReadFloat32();
            case TypeId.Float64:
                return context.Reader.ReadFloat64();
            case TypeId.String:
                return StringSerializer.ReadString(context);
            case TypeId.Date:
                return TimeCodec.ReadDate(context);
            case TypeId.Timestamp:
                return TimeCodec.ReadTimestamp(context);
            case TypeId.Duration:
                return TimeCodec.ReadDuration(context);
            case TypeId.Binary:
            case TypeId.UInt8Array:
                return ReadBinary(context);
            case TypeId.BoolArray:
                return ReadBoolArray(context);
            case TypeId.Int8Array:
                return ReadInt8Array(context);
            case TypeId.Int16Array:
                return ReadInt16Array(context);
            case TypeId.Int32Array:
                return ReadInt32Array(context);
            case TypeId.Int64Array:
                return ReadInt64Array(context);
            case TypeId.UInt16Array:
                return ReadUInt16Array(context);
            case TypeId.UInt32Array:
                return ReadUInt32Array(context);
            case TypeId.UInt64Array:
                return ReadUInt64Array(context);
            case TypeId.Float32Array:
                return ReadFloat32Array(context);
            case TypeId.Float64Array:
                return ReadFloat64Array(context);
            case TypeId.List:
                return DynamicContainerCodec.ReadListPayload(context);
            case TypeId.Set:
                return DynamicContainerCodec.ReadSetPayload(context);
            case TypeId.Map:
                return DynamicContainerCodec.ReadMapPayload(context);
            case TypeId.Union:
                return GetSerializer<Union>().Read(context, RefMode.None, false);
            case TypeId.Struct:
            case TypeId.Enum:
            case TypeId.Ext:
            case TypeId.TypedUnion:
            {
                if (!typeInfo.UserTypeId.HasValue)
                {
                    throw new InvalidDataException($"missing dynamic user type id for {typeInfo.WireTypeId}");
                }

                return ReadByUserTypeId(typeInfo.UserTypeId.Value, context);
            }
            case TypeId.NamedStruct:
            case TypeId.NamedEnum:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
            {
                if (!typeInfo.NamespaceName.HasValue || !typeInfo.TypeName.HasValue)
                {
                    throw new InvalidDataException($"missing dynamic type name for {typeInfo.WireTypeId}");
                }

                return ReadByTypeName(typeInfo.NamespaceName.Value.Value, typeInfo.TypeName.Value.Value, context);
            }
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
            {
                if (typeInfo.CompatibleTypeMeta is null)
                {
                    throw new InvalidDataException($"missing compatible type meta for {typeInfo.WireTypeId}");
                }

                TypeMeta compatibleTypeMeta = typeInfo.CompatibleTypeMeta;
                if (compatibleTypeMeta.RegisterByName)
                {
                    return ReadByTypeName(
                        compatibleTypeMeta.NamespaceName.Value,
                        compatibleTypeMeta.TypeName.Value,
                        context,
                        compatibleTypeMeta);
                }

                if (!compatibleTypeMeta.UserTypeId.HasValue)
                {
                    throw new InvalidDataException("missing user type id in compatible dynamic type meta");
                }

                return ReadByUserTypeId(compatibleTypeMeta.UserTypeId.Value, context, compatibleTypeMeta);
            }
            case TypeId.None:
                return null;
            default:
                throw new InvalidDataException($"unsupported dynamic type id {typeInfo.WireTypeId}");
        }
    }

    private static byte[] ReadBinary(ReadContext context)
    {
        uint length = context.Reader.ReadVarUInt32();
        return context.Reader.ReadBytes(checked((int)length));
    }

    private static bool[] ReadBoolArray(ReadContext context)
    {
        int count = checked((int)context.Reader.ReadVarUInt32());
        bool[] values = new bool[count];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt8() != 0;
        }

        return values;
    }

    private static sbyte[] ReadInt8Array(ReadContext context)
    {
        int count = checked((int)context.Reader.ReadVarUInt32());
        sbyte[] values = new sbyte[count];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt8();
        }

        return values;
    }

    private static short[] ReadInt16Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 1) != 0)
        {
            throw new InvalidDataException("int16 array payload size mismatch");
        }

        short[] values = new short[payloadSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt16();
        }

        return values;
    }

    private static int[] ReadInt32Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 3) != 0)
        {
            throw new InvalidDataException("int32 array payload size mismatch");
        }

        int[] values = new int[payloadSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt32();
        }

        return values;
    }

    private static long[] ReadInt64Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 7) != 0)
        {
            throw new InvalidDataException("int64 array payload size mismatch");
        }

        long[] values = new long[payloadSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt64();
        }

        return values;
    }

    private static ushort[] ReadUInt16Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 1) != 0)
        {
            throw new InvalidDataException("uint16 array payload size mismatch");
        }

        ushort[] values = new ushort[payloadSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt16();
        }

        return values;
    }

    private static uint[] ReadUInt32Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 3) != 0)
        {
            throw new InvalidDataException("uint32 array payload size mismatch");
        }

        uint[] values = new uint[payloadSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt32();
        }

        return values;
    }

    private static ulong[] ReadUInt64Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 7) != 0)
        {
            throw new InvalidDataException("uint64 array payload size mismatch");
        }

        ulong[] values = new ulong[payloadSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt64();
        }

        return values;
    }

    private static float[] ReadFloat32Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 3) != 0)
        {
            throw new InvalidDataException("float32 array payload size mismatch");
        }

        float[] values = new float[payloadSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadFloat32();
        }

        return values;
    }

    private static double[] ReadFloat64Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 7) != 0)
        {
            throw new InvalidDataException("float64 array payload size mismatch");
        }

        double[] values = new double[payloadSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadFloat64();
        }

        return values;
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
        TypeInfo info,
        TypeId wireTypeId,
        bool trackRef)
    {
        IReadOnlyList<TypeMetaFieldInfo> fields = info.Serializer.CompatibleTypeMetaFields(trackRef);
        bool hasFieldsMeta = fields.Count > 0;
        if (info.RegisterByName)
        {
            if (!info.NamespaceName.HasValue || !info.TypeName.HasValue)
            {
                throw new InvalidDataException("missing type name metadata for name-registered type");
            }

            return new TypeMeta(
                (uint)wireTypeId,
                null,
                info.NamespaceName.Value,
                info.TypeName.Value,
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
        TypeInfo localInfo,
        HashSet<TypeId> expectedWireTypes,
        TypeId actualWireTypeId)
    {
        if (remoteTypeMeta.RegisterByName)
        {
            if (!localInfo.RegisterByName || !localInfo.NamespaceName.HasValue || !localInfo.TypeName.HasValue)
            {
                throw new InvalidDataException(
                    "received name-registered compatible metadata for id-registered local type");
            }

            if (remoteTypeMeta.NamespaceName.Value != localInfo.NamespaceName.Value.Value)
            {
                throw new InvalidDataException(
                    $"namespace mismatch: expected {localInfo.NamespaceName.Value.Value}, got {remoteTypeMeta.NamespaceName.Value}");
            }

            if (remoteTypeMeta.TypeName.Value != localInfo.TypeName.Value.Value)
            {
                throw new InvalidDataException(
                    $"type name mismatch: expected {localInfo.TypeName.Value.Value}, got {remoteTypeMeta.TypeName.Value}");
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
        WriteContext context,
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
                context.Writer.WriteInt64(unchecked((long)MetaStringHash(normalized)));
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
        ReadContext context,
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

    private static ulong MetaStringHash(MetaString metaString)
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

    private Serializer CreateBindingCore(Type type)
    {
        if (GeneratedFactories.TryGetValue(type, out Func<Serializer>? generatedFactory))
        {
            return generatedFactory();
        }

        if (type == typeof(bool))
        {
            return new BoolSerializer();
        }

        if (type == typeof(sbyte))
        {
            return new Int8Serializer();
        }

        if (type == typeof(short))
        {
            return new Int16Serializer();
        }

        if (type == typeof(int))
        {
            return new Int32Serializer();
        }

        if (type == typeof(long))
        {
            return new Int64Serializer();
        }

        if (type == typeof(byte))
        {
            return new UInt8Serializer();
        }

        if (type == typeof(ushort))
        {
            return new UInt16Serializer();
        }

        if (type == typeof(uint))
        {
            return new UInt32Serializer();
        }

        if (type == typeof(ulong))
        {
            return new UInt64Serializer();
        }

        if (type == typeof(float))
        {
            return new Float32Serializer();
        }

        if (type == typeof(double))
        {
            return new Float64Serializer();
        }

        if (type == typeof(string))
        {
            return new StringSerializer();
        }

        if (type == typeof(byte[]))
        {
            return new BinarySerializer();
        }

        if (type == typeof(bool[]))
        {
            return new BoolArraySerializer();
        }

        if (type == typeof(sbyte[]))
        {
            return new Int8ArraySerializer();
        }

        if (type == typeof(short[]))
        {
            return new Int16ArraySerializer();
        }

        if (type == typeof(int[]))
        {
            return new Int32ArraySerializer();
        }

        if (type == typeof(long[]))
        {
            return new Int64ArraySerializer();
        }

        if (type == typeof(ushort[]))
        {
            return new UInt16ArraySerializer();
        }

        if (type == typeof(uint[]))
        {
            return new UInt32ArraySerializer();
        }

        if (type == typeof(ulong[]))
        {
            return new UInt64ArraySerializer();
        }

        if (type == typeof(float[]))
        {
            return new Float32ArraySerializer();
        }

        if (type == typeof(double[]))
        {
            return new Float64ArraySerializer();
        }

        if (type == typeof(DateOnly))
        {
            return new DateOnlySerializer();
        }

        if (type == typeof(DateTimeOffset))
        {
            return new DateTimeOffsetSerializer();
        }

        if (type == typeof(DateTime))
        {
            return new DateTimeSerializer();
        }

        if (type == typeof(TimeSpan))
        {
            return new TimeSpanSerializer();
        }

        if (type == typeof(List<bool>))
        {
            return new ListBoolSerializer();
        }

        if (type == typeof(List<sbyte>))
        {
            return new ListInt8Serializer();
        }

        if (type == typeof(List<short>))
        {
            return new ListInt16Serializer();
        }

        if (type == typeof(List<int>))
        {
            return new ListIntSerializer();
        }

        if (type == typeof(List<long>))
        {
            return new ListLongSerializer();
        }

        if (type == typeof(List<byte>))
        {
            return new ListUInt8Serializer();
        }

        if (type == typeof(List<ushort>))
        {
            return new ListUInt16Serializer();
        }

        if (type == typeof(List<uint>))
        {
            return new ListUIntSerializer();
        }

        if (type == typeof(List<ulong>))
        {
            return new ListULongSerializer();
        }

        if (type == typeof(List<float>))
        {
            return new ListFloatSerializer();
        }

        if (type == typeof(List<double>))
        {
            return new ListDoubleSerializer();
        }

        if (type == typeof(List<string>))
        {
            return new ListStringSerializer();
        }

        if (type == typeof(List<DateOnly>))
        {
            return new ListDateOnlySerializer();
        }

        if (type == typeof(List<DateTimeOffset>))
        {
            return new ListDateTimeOffsetSerializer();
        }

        if (type == typeof(List<DateTime>))
        {
            return new ListDateTimeSerializer();
        }

        if (type == typeof(List<TimeSpan>))
        {
            return new ListTimeSpanSerializer();
        }

        if (type == typeof(HashSet<sbyte>))
        {
            return new SetInt8Serializer();
        }

        if (type == typeof(HashSet<short>))
        {
            return new SetInt16Serializer();
        }

        if (type == typeof(HashSet<int>))
        {
            return new SetIntSerializer();
        }

        if (type == typeof(HashSet<long>))
        {
            return new SetLongSerializer();
        }

        if (type == typeof(HashSet<byte>))
        {
            return new SetUInt8Serializer();
        }

        if (type == typeof(HashSet<ushort>))
        {
            return new SetUInt16Serializer();
        }

        if (type == typeof(HashSet<uint>))
        {
            return new SetUIntSerializer();
        }

        if (type == typeof(HashSet<ulong>))
        {
            return new SetULongSerializer();
        }

        if (type == typeof(HashSet<float>))
        {
            return new SetFloatSerializer();
        }

        if (type == typeof(HashSet<double>))
        {
            return new SetDoubleSerializer();
        }

        Serializer? primitiveCollectionSerializer = TryCreatePrimitiveCollectionSerializer(type);
        if (primitiveCollectionSerializer is not null)
        {
            return primitiveCollectionSerializer;
        }

        Serializer? primitiveDictionarySerializer = TryCreatePrimitiveDictionarySerializer(type);
        if (primitiveDictionarySerializer is not null)
        {
            return primitiveDictionarySerializer;
        }

        if (type == typeof(object))
        {
            return new DynamicAnyObjectSerializer();
        }

        if (typeof(Union).IsAssignableFrom(type))
        {
            Type serializerType = typeof(UnionSerializer<>).MakeGenericType(type);
            return CreateSerializer(serializerType);
        }

        if (type.IsEnum)
        {
            Type serializerType = typeof(EnumSerializer<>).MakeGenericType(type);
            return CreateSerializer(serializerType);
        }

        if (type.IsArray)
        {
            Type elementType = type.GetElementType()!;
            Type serializerType = typeof(ArraySerializer<>).MakeGenericType(elementType);
            return CreateSerializer(serializerType);
        }

        if (type.IsGenericType)
        {
            Type genericType = type.GetGenericTypeDefinition();
            Type[] genericArgs = type.GetGenericArguments();
            if (genericType == typeof(Nullable<>))
            {
                Type serializerType = typeof(NullableSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(List<>))
            {
                Type serializerType = typeof(ListSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(HashSet<>))
            {
                Type serializerType = typeof(SetSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(SortedSet<>))
            {
                Type serializerType = typeof(SortedSetSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(ImmutableHashSet<>))
            {
                Type serializerType = typeof(ImmutableHashSetSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(LinkedList<>))
            {
                Type serializerType = typeof(LinkedListSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(Queue<>))
            {
                Type serializerType = typeof(QueueSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(Stack<>))
            {
                Type serializerType = typeof(StackSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(Dictionary<,>))
            {
                Type serializerType = typeof(DictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(SortedDictionary<,>))
            {
                Type serializerType = typeof(SortedDictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(SortedList<,>))
            {
                Type serializerType = typeof(SortedListSerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(ConcurrentDictionary<,>))
            {
                Type serializerType = typeof(ConcurrentDictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(NullableKeyDictionary<,>))
            {
                Type serializerType = typeof(NullableKeyDictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateSerializer(serializerType);
            }
        }

        throw new TypeNotRegisteredException($"No serializer available for {type}");
    }

    private Serializer? TryCreatePrimitiveCollectionSerializer(Type type)
    {
        if (!type.IsGenericType)
        {
            return null;
        }

        Type genericType = type.GetGenericTypeDefinition();
        Type elementType = type.GetGenericArguments()[0];
        if ((genericType == typeof(LinkedList<>) ||
             genericType == typeof(Queue<>) ||
             genericType == typeof(Stack<>)) &&
            PrimitiveListLikeCollectionCodecs.TryGetValue(elementType, out Type? listLikeCodec))
        {
            Type serializerType = genericType == typeof(LinkedList<>)
                ? typeof(PrimitiveLinkedListSerializer<,>).MakeGenericType(elementType, listLikeCodec)
                : genericType == typeof(Queue<>)
                    ? typeof(PrimitiveQueueSerializer<,>).MakeGenericType(elementType, listLikeCodec)
                    : typeof(PrimitiveStackSerializer<,>).MakeGenericType(elementType, listLikeCodec);
            return CreateSerializer(serializerType);
        }

        if ((genericType == typeof(SortedSet<>) ||
             genericType == typeof(ImmutableHashSet<>)) &&
            PrimitiveSetCollectionCodecs.TryGetValue(elementType, out Type? setCodec))
        {
            Type serializerType = genericType == typeof(SortedSet<>)
                ? typeof(PrimitiveSortedSetSerializer<,>).MakeGenericType(elementType, setCodec)
                : typeof(PrimitiveImmutableHashSetSerializer<,>).MakeGenericType(elementType, setCodec);
            return CreateSerializer(serializerType);
        }

        return null;
    }

    private Serializer? TryCreatePrimitiveDictionarySerializer(Type type)
    {
        if (!type.IsGenericType)
        {
            return null;
        }

        Type genericType = type.GetGenericTypeDefinition();
        if (genericType != typeof(Dictionary<,>) &&
            genericType != typeof(SortedDictionary<,>) &&
            genericType != typeof(SortedList<,>) &&
            genericType != typeof(ConcurrentDictionary<,>))
        {
            return null;
        }

        Type[] genericArgs = type.GetGenericArguments();
        Type keyType = genericArgs[0];
        Type valueType = genericArgs[1];

        if (keyType == typeof(string) &&
            PrimitiveStringKeyDictionaryCodecs.TryGetValue(valueType, out Type? valueCodecType))
        {
            Type serializerType = genericType == typeof(Dictionary<,>)
                ? typeof(PrimitiveStringKeyDictionarySerializer<,>).MakeGenericType(valueType, valueCodecType)
                : genericType == typeof(SortedDictionary<,>)
                    ? typeof(PrimitiveStringKeySortedDictionarySerializer<,>).MakeGenericType(valueType, valueCodecType)
                    : genericType == typeof(SortedList<,>)
                        ? typeof(PrimitiveStringKeySortedListSerializer<,>).MakeGenericType(valueType, valueCodecType)
                        : typeof(PrimitiveStringKeyConcurrentDictionarySerializer<,>).MakeGenericType(valueType, valueCodecType);
            return CreateSerializer(serializerType);
        }

        if (keyType == valueType &&
            PrimitiveSameTypeDictionaryCodecs.TryGetValue(valueType, out Type? sameTypeCodec))
        {
            Type serializerType = genericType == typeof(Dictionary<,>)
                ? typeof(PrimitiveSameTypeDictionarySerializer<,>).MakeGenericType(valueType, sameTypeCodec)
                : genericType == typeof(SortedDictionary<,>)
                    ? typeof(PrimitiveSameTypeSortedDictionarySerializer<,>).MakeGenericType(valueType, sameTypeCodec)
                    : genericType == typeof(SortedList<,>)
                        ? typeof(PrimitiveSameTypeSortedListSerializer<,>).MakeGenericType(valueType, sameTypeCodec)
                        : typeof(PrimitiveSameTypeConcurrentDictionarySerializer<,>).MakeGenericType(valueType, sameTypeCodec);
            return CreateSerializer(serializerType);
        }

        return null;
    }

    private static Serializer CreateSerializer<TSerializer>()
        where TSerializer : Serializer, new()
    {
        return new TSerializer();
    }

    private Serializer CreateSerializer(Type serializerType)
    {
        if (!typeof(Serializer).IsAssignableFrom(serializerType))
        {
            throw new InvalidDataException($"{serializerType} is not a serializer");
        }

        try
        {
            if (Activator.CreateInstance(serializerType) is Serializer serializer)
            {
                return serializer;
            }
        }
        catch (Exception ex)
        {
            throw new InvalidDataException($"failed to create serializer for {serializerType}: {ex.Message}");
        }

        throw new InvalidDataException($"{serializerType} is not a serializer");
    }

    private static MetaString ReadMetaString(ByteReader reader, MetaStringDecoder decoder, IReadOnlyList<MetaStringEncoding> encodings)
    {
        byte header = reader.ReadUInt8();
        int encodingIndex = header & 0b11;
        if (encodingIndex >= encodings.Count)
        {
            throw new InvalidDataException("invalid meta string encoding index");
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
