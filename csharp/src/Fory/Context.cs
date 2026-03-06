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

public sealed class CompatibleTypeDefWriteState
{
    private Dictionary<Type, uint>? _typeIndexByType;
    private Type? _firstType;
    private uint _nextIndex;

    public uint? LookupIndex(Type type)
    {
        if (_nextIndex == 0)
        {
            return null;
        }

        if (ReferenceEquals(_firstType, type))
        {
            return 0;
        }

        if (_typeIndexByType is not null && _typeIndexByType.TryGetValue(type, out uint idx))
        {
            return idx;
        }

        return null;
    }

    public (uint Index, bool IsNew) AssignIndexIfAbsent(Type type)
    {
        if (_nextIndex == 0)
        {
            _firstType = type;
            _nextIndex = 1;
            return (0, true);
        }

        if (ReferenceEquals(_firstType, type))
        {
            return (0, false);
        }

        if (_typeIndexByType is null)
        {
            _typeIndexByType = new Dictionary<Type, uint>();
        }
        else if (_typeIndexByType.TryGetValue(type, out uint existing))
        {
            return (existing, false);
        }

        uint index = _nextIndex;
        _nextIndex += 1;
        _typeIndexByType[type] = index;
        return (index, true);
    }

    public void Reset()
    {
        _firstType = null;
        _typeIndexByType?.Clear();
        _nextIndex = 0;
    }
}

public sealed class CompatibleTypeDefReadState
{
    private const int MaxParsedTypeDefEntries = 8192;
    private readonly List<TypeMeta> _typeMetas = [];
    private readonly Dictionary<ulong, CachedTypeMetaEntry> _cachedTypeMetasByHeader = [];
    private TypeMeta? _firstTypeMeta;
    private bool _hasFirstTypeMeta;
    private ulong _lastMetaHeader;
    private CachedTypeMetaEntry _lastTypeMeta;
    private bool _hasLastMetaHeader;

    private readonly record struct CachedTypeMetaEntry(TypeMeta TypeMeta, int SkipBytesAfterHeader);

    public TypeMeta? TypeMetaAt(int index)
    {
        if (index < 0)
        {
            return null;
        }

        if (index == 0)
        {
            return _hasFirstTypeMeta ? _firstTypeMeta : null;
        }

        int listIndex = index - 1;
        return listIndex >= 0 && listIndex < _typeMetas.Count ? _typeMetas[listIndex] : null;
    }

    public void StoreTypeMeta(TypeMeta typeMeta, int index)
    {
        if (index < 0)
        {
            throw new InvalidDataException("negative compatible type definition index");
        }

        if (index == 0)
        {
            _firstTypeMeta = typeMeta;
            _hasFirstTypeMeta = true;
            return;
        }

        if (!_hasFirstTypeMeta)
        {
            throw new InvalidDataException(
                $"compatible type definition index gap: index={index}, missing index 0");
        }

        int listIndex = index - 1;
        if (listIndex == _typeMetas.Count)
        {
            _typeMetas.Add(typeMeta);
            return;
        }

        if (listIndex < _typeMetas.Count)
        {
            _typeMetas[listIndex] = typeMeta;
            return;
        }

        throw new InvalidDataException(
            $"compatible type definition index gap: index={index}, count={_typeMetas.Count + 1}");
    }

    public bool TryGetCachedTypeMeta(ulong header, out TypeMeta typeMeta, out int skipBytesAfterHeader)
    {
        if (_hasLastMetaHeader && _lastMetaHeader == header)
        {
            typeMeta = _lastTypeMeta.TypeMeta;
            skipBytesAfterHeader = _lastTypeMeta.SkipBytesAfterHeader;
            return true;
        }

        if (_cachedTypeMetasByHeader.TryGetValue(header, out CachedTypeMetaEntry cached))
        {
            _lastMetaHeader = header;
            _lastTypeMeta = cached;
            _hasLastMetaHeader = true;
            typeMeta = cached.TypeMeta;
            skipBytesAfterHeader = cached.SkipBytesAfterHeader;
            return true;
        }

        typeMeta = null!;
        skipBytesAfterHeader = 0;
        return false;
    }

    public void CacheTypeMeta(ulong header, TypeMeta typeMeta, int skipBytesAfterHeader)
    {
        CachedTypeMetaEntry cached = new(typeMeta, skipBytesAfterHeader);
        _lastMetaHeader = header;
        _lastTypeMeta = cached;
        _hasLastMetaHeader = true;
        if (_cachedTypeMetasByHeader.Count < MaxParsedTypeDefEntries)
        {
            _cachedTypeMetasByHeader.TryAdd(header, cached);
        }
    }

    public void Reset()
    {
        _firstTypeMeta = null;
        _hasFirstTypeMeta = false;
        _typeMetas.Clear();
    }
}

public sealed class MetaStringWriteState
{
    private readonly Dictionary<MetaString, uint> _stringIndexByKey = [];
    private uint _nextIndex;

    public uint? Index(MetaString value)
    {
        return _stringIndexByKey.TryGetValue(value, out uint index) ? index : null;
    }

    public (uint Index, bool IsNew) AssignIndexIfAbsent(MetaString value)
    {
        if (_stringIndexByKey.TryGetValue(value, out uint existing))
        {
            return (existing, false);
        }

        uint index = _nextIndex;
        _nextIndex += 1;
        _stringIndexByKey[value] = index;
        return (index, true);
    }

    public void Reset()
    {
        _stringIndexByKey.Clear();
        _nextIndex = 0;
    }
}

public sealed class MetaStringReadState
{
    private readonly List<MetaString> _values = [];

    public MetaString? ValueAt(int index)
    {
        return index >= 0 && index < _values.Count ? _values[index] : null;
    }

    public void Append(MetaString value)
    {
        _values.Add(value);
    }

    public void Reset()
    {
        _values.Clear();
    }
}

public sealed record DynamicTypeInfo(
    TypeId WireTypeId,
    uint? UserTypeId,
    MetaString? NamespaceName,
    MetaString? TypeName,
    TypeMeta? CompatibleTypeMeta);

public sealed class WriteContext
{
    public WriteContext(
        ByteWriter writer,
        TypeResolver typeResolver,
        bool trackRef,
        bool compatible = false,
        bool checkStructVersion = false,
        CompatibleTypeDefWriteState? compatibleTypeDefState = null,
        MetaStringWriteState? metaStringWriteState = null)
    {
        Writer = writer;
        TypeResolver = typeResolver;
        TrackRef = trackRef;
        Compatible = compatible;
        CheckStructVersion = checkStructVersion;
        RefWriter = new RefWriter();
        CompatibleTypeDefState = compatibleTypeDefState ?? new CompatibleTypeDefWriteState();
        MetaStringWriteState = metaStringWriteState ?? new MetaStringWriteState();
    }

    public ByteWriter Writer { get; private set; }

    public TypeResolver TypeResolver { get; }

    public bool TrackRef { get; }

    public bool Compatible { get; }

    public bool CheckStructVersion { get; }

    public RefWriter RefWriter { get; }

    public CompatibleTypeDefWriteState CompatibleTypeDefState { get; }

    public MetaStringWriteState MetaStringWriteState { get; }

    public void ResetFor(ByteWriter writer)
    {
        Writer = writer;
        Reset();
    }

    public void WriteCompatibleTypeMeta(Type type, TypeMeta typeMeta)
    {
        WriteCompatibleTypeMeta(type, typeMeta.Encode());
    }

    public void WriteCompatibleTypeMeta(Type type, ReadOnlySpan<byte> encodedTypeMeta)
    {
        (uint index, bool isNew) = CompatibleTypeDefState.AssignIndexIfAbsent(type);
        if (isNew)
        {
            Writer.WriteVarUInt32(index << 1);
            Writer.WriteBytes(encodedTypeMeta);
        }
        else
        {
            Writer.WriteVarUInt32((index << 1) | 1);
        }
    }

    public void ResetObjectState()
    {
        RefWriter.Reset();
    }

    public void Reset()
    {
        ResetObjectState();
        CompatibleTypeDefState.Reset();
        MetaStringWriteState.Reset();
    }
}

internal readonly record struct CanonicalReferenceSignature(
    Type Type,
    ulong HashLo,
    ulong HashHi,
    int Length);

internal sealed class CanonicalReferenceEntry
{
    public required byte[] Bytes { get; init; }
    public required object Object { get; init; }
}

public sealed class ReadContext
{
    private readonly Dictionary<Type, DynamicTypeInfo> _pendingDynamicTypeInfo = [];
    private readonly Dictionary<CanonicalReferenceSignature, List<CanonicalReferenceEntry>> _canonicalReferenceCache = [];
    private readonly int _maxDynamicReadDepth;
    private Type? _pendingCompatibleType;
    private TypeMeta? _pendingCompatibleTypeMeta;
    private Dictionary<Type, TypeMeta>? _pendingCompatibleTypeMetaMap;
    private Type? _lastCompatibleType;
    private TypeMeta? _lastCompatibleTypeMeta;
    private int _currentDynamicReadDepth;

    public ReadContext(
        ByteReader reader,
        TypeResolver typeResolver,
        bool trackRef,
        bool compatible = false,
        bool checkStructVersion = false,
        CompatibleTypeDefReadState? compatibleTypeDefState = null,
        MetaStringReadState? metaStringReadState = null,
        int maxDynamicReadDepth = 20)
    {
        if (maxDynamicReadDepth <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maxDynamicReadDepth), "MaxDepth must be greater than 0.");
        }

        Reader = reader;
        TypeResolver = typeResolver;
        TrackRef = trackRef;
        Compatible = compatible;
        CheckStructVersion = checkStructVersion;
        RefReader = new RefReader();
        CompatibleTypeDefState = compatibleTypeDefState ?? new CompatibleTypeDefReadState();
        MetaStringReadState = metaStringReadState ?? new MetaStringReadState();
        _maxDynamicReadDepth = maxDynamicReadDepth;
    }

    public ByteReader Reader { get; private set; }

    public TypeResolver TypeResolver { get; }

    public bool TrackRef { get; }

    public bool Compatible { get; }

    public bool CheckStructVersion { get; }

    public RefReader RefReader { get; }

    public CompatibleTypeDefReadState CompatibleTypeDefState { get; }

    public MetaStringReadState MetaStringReadState { get; }

    public void ResetFor(ByteReader reader)
    {
        Reader = reader;
        Reset();
    }

    public TypeMeta ReadCompatibleTypeMeta()
    {
        uint indexMarker = Reader.ReadVarUInt32();
        bool isRef = (indexMarker & 1) == 1;
        int index = checked((int)(indexMarker >> 1));
        if (isRef)
        {
            TypeMeta? cached = CompatibleTypeDefState.TypeMetaAt(index);
            if (cached is null)
            {
                throw new InvalidDataException($"unknown compatible type definition ref index {index}");
            }

            return cached;
        }

        ulong header = Reader.ReadUInt64();
        if (CompatibleTypeDefState.TryGetCachedTypeMeta(
                header,
                out TypeMeta cachedTypeMeta,
                out int skipBytesAfterHeader))
        {
            Reader.Skip(skipBytesAfterHeader);
            CompatibleTypeDefState.StoreTypeMeta(cachedTypeMeta, index);
            return cachedTypeMeta;
        }

        int headerStartCursor = Reader.Cursor - sizeof(ulong);
        Reader.MoveBack(sizeof(ulong));
        TypeMeta typeMeta = TypeMeta.Decode(Reader);
        int consumedTypeMetaBytes = Reader.Cursor - headerStartCursor;
        int parsedSkipBytesAfterHeader = consumedTypeMetaBytes - sizeof(ulong);
        CompatibleTypeDefState.StoreTypeMeta(typeMeta, index);
        CompatibleTypeDefState.CacheTypeMeta(header, typeMeta, parsedSkipBytesAfterHeader);
        return typeMeta;
    }

    public void PushCompatibleTypeMeta(Type type, TypeMeta typeMeta)
    {
        if (_lastCompatibleType == type && ReferenceEquals(_lastCompatibleTypeMeta, typeMeta))
        {
            return;
        }

        if (ReferenceEquals(_pendingCompatibleType, type))
        {
            if (ReferenceEquals(_pendingCompatibleTypeMeta, typeMeta))
            {
                _lastCompatibleType = type;
                _lastCompatibleTypeMeta = typeMeta;
                return;
            }

            _pendingCompatibleTypeMeta = typeMeta;
            _lastCompatibleType = type;
            _lastCompatibleTypeMeta = typeMeta;
            return;
        }

        if (_pendingCompatibleType is null)
        {
            _pendingCompatibleType = type;
            _pendingCompatibleTypeMeta = typeMeta;
            _lastCompatibleType = type;
            _lastCompatibleTypeMeta = typeMeta;
            return;
        }

        if (_pendingCompatibleTypeMetaMap is null)
        {
            _pendingCompatibleTypeMetaMap = new Dictionary<Type, TypeMeta>();
            if (_pendingCompatibleTypeMeta is not null)
            {
                _pendingCompatibleTypeMetaMap[_pendingCompatibleType] = _pendingCompatibleTypeMeta;
            }
        }
        else if (_pendingCompatibleTypeMetaMap.TryGetValue(type, out TypeMeta? existing) &&
                 ReferenceEquals(existing, typeMeta))
        {
            _lastCompatibleType = type;
            _lastCompatibleTypeMeta = typeMeta;
            return;
        }

        _pendingCompatibleTypeMetaMap[type] = typeMeta;
        _lastCompatibleType = type;
        _lastCompatibleTypeMeta = typeMeta;
    }

    public bool TryConsumeCompatibleTypeMeta(Type type, out TypeMeta? typeMeta)
    {
        if (_lastCompatibleType == type && _lastCompatibleTypeMeta is not null)
        {
            typeMeta = _lastCompatibleTypeMeta;
            return true;
        }

        if (ReferenceEquals(_pendingCompatibleType, type) &&
            _pendingCompatibleTypeMeta is not null)
        {
            _lastCompatibleType = type;
            _lastCompatibleTypeMeta = _pendingCompatibleTypeMeta;
            typeMeta = _pendingCompatibleTypeMeta;
            return true;
        }

        if (_pendingCompatibleTypeMetaMap is null ||
            !_pendingCompatibleTypeMetaMap.TryGetValue(type, out TypeMeta? pendingTypeMeta) ||
            pendingTypeMeta is null)
        {
            typeMeta = null;
            return false;
        }

        _lastCompatibleType = type;
        _lastCompatibleTypeMeta = pendingTypeMeta;
        typeMeta = pendingTypeMeta;
        return true;
    }

    public TypeMeta ConsumeCompatibleTypeMeta(Type type)
    {
        if (TryConsumeCompatibleTypeMeta(type, out TypeMeta? typeMeta) && typeMeta is not null)
        {
            return typeMeta;
        }

        throw new InvalidDataException($"missing compatible type metadata for {type}");
    }

    public void SetDynamicTypeInfo(Type type, DynamicTypeInfo typeInfo)
    {
        _pendingDynamicTypeInfo[type] = typeInfo;
    }

    public DynamicTypeInfo? DynamicTypeInfo(Type type)
    {
        return _pendingDynamicTypeInfo.TryGetValue(type, out DynamicTypeInfo? typeInfo) ? typeInfo : null;
    }

    public void ClearDynamicTypeInfo(Type type)
    {
        _pendingDynamicTypeInfo.Remove(type);
    }

    public void IncreaseDynamicReadDepth()
    {
        _currentDynamicReadDepth += 1;
        if (_currentDynamicReadDepth > _maxDynamicReadDepth)
        {
            throw new InvalidDataException(
                $"maximum dynamic object nesting depth ({_maxDynamicReadDepth}) exceeded. current depth: {_currentDynamicReadDepth}");
        }
    }

    public void DecreaseDynamicReadDepth()
    {
        if (_currentDynamicReadDepth > 0)
        {
            _currentDynamicReadDepth -= 1;
        }
    }

    public T CanonicalizeNonTrackingReference<T>(T value, int start, int end)
    {
        if (!TrackRef || end <= start || value is null || value is not object obj)
        {
            return value;
        }

        byte[] bytes = new byte[end - start];
        Array.Copy(Reader.Storage, start, bytes, 0, bytes.Length);
        (ulong hashLo, ulong hashHi) = MurmurHash3.X64_128(bytes, 47);
        CanonicalReferenceSignature signature = new(obj.GetType(), hashLo, hashHi, bytes.Length);

        if (_canonicalReferenceCache.TryGetValue(signature, out List<CanonicalReferenceEntry>? bucket))
        {
            foreach (CanonicalReferenceEntry entry in bucket)
            {
                if (entry.Bytes.AsSpan().SequenceEqual(bytes))
                {
                    return (T)entry.Object;
                }
            }

            bucket.Add(new CanonicalReferenceEntry { Bytes = bytes, Object = obj });
            return value;
        }

        _canonicalReferenceCache[signature] =
        [
            new CanonicalReferenceEntry { Bytes = bytes, Object = obj },
        ];
        return value;
    }

    public void ResetObjectState()
    {
        RefReader.Reset();
        _pendingCompatibleType = null;
        _pendingCompatibleTypeMeta = null;
        _pendingCompatibleTypeMetaMap?.Clear();
        _pendingDynamicTypeInfo.Clear();
        _canonicalReferenceCache.Clear();
        _lastCompatibleType = null;
        _lastCompatibleTypeMeta = null;
        _currentDynamicReadDepth = 0;
    }

    public void Reset()
    {
        ResetObjectState();
        CompatibleTypeDefState.Reset();
        MetaStringReadState.Reset();
    }
}
