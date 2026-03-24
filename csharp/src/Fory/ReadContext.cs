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

internal readonly record struct CanonicalRefSignature(
    Type Type,
    ulong HashLo,
    ulong HashHi,
    int Length);

internal sealed class CanonicalRefEntry
{
    public required byte[] Bytes { get; init; }
    public required object Object { get; init; }
}

public sealed class ReadContext
{
    private const int MaxParsedTypeMetaEntries = 8192;

    private readonly record struct CachedTypeMetaEntry(TypeMeta TypeMeta, int SkipBytesAfterHeader);

    private readonly ReusableArray<TypeMeta> _readTypeMetas = new();
    private readonly Dictionary<ulong, CachedTypeMetaEntry> _cachedTypeMetasByHeader = [];
    private TypeMeta? _firstReadTypeMeta;
    private bool _hasFirstReadTypeMeta;
    private ulong _lastMetaHeader;
    private CachedTypeMetaEntry _lastTypeMeta;
    private bool _hasLastMetaHeader;

    private readonly List<MetaString> _readMetaStrings = [];

    internal readonly UInt64Map<TypeInfo> _readTypeInfoByType = new();
    internal readonly Dictionary<CanonicalRefSignature, List<CanonicalRefEntry>> _canonicalRefCache = [];
    internal readonly List<uint> _reservedRefIds = [];
    private readonly int _maxDynamicReadDepth;
    internal Type? _typeMetaType;
    internal TypeMeta? _typeMeta;
    internal UInt64Map<TypeMeta>? _typeMetaByType;
    internal Type? _cachedTypeMetaType;
    internal TypeMeta? _cachedTypeMeta;
    internal int _currentDynamicReadDepth;

    public ReadContext(
        ByteReader reader,
        TypeResolver typeResolver,
        bool trackRef,
        bool compatible = false,
        bool checkStructVersion = false,
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
        _maxDynamicReadDepth = maxDynamicReadDepth;
    }

    public ByteReader Reader { get; private set; }

    public TypeResolver TypeResolver { get; }

    public bool TrackRef { get; }

    public bool Compatible { get; }

    public bool CheckStructVersion { get; }

    internal RefReader RefReader { get; }

    internal void ResetFor(ByteReader reader)
    {
        Reader = reader;
        Reset();
    }

    internal TypeMeta? GetReadTypeMeta(int index)
    {
        if (index < 0)
        {
            return null;
        }

        if (index == 0)
        {
            return _hasFirstReadTypeMeta ? _firstReadTypeMeta : null;
        }

        return _readTypeMetas.Get(index - 1);
    }

    internal void StoreReadTypeMeta(TypeMeta typeMeta, int index)
    {
        if (index < 0)
        {
            throw new InvalidDataException("negative type meta index");
        }

        if (index == 0)
        {
            _firstReadTypeMeta = typeMeta;
            _hasFirstReadTypeMeta = true;
            return;
        }

        if (!_hasFirstReadTypeMeta)
        {
            throw new InvalidDataException(
                $"type meta index gap: index={index}, missing index 0");
        }

        int listIndex = index - 1;
        if (listIndex == _readTypeMetas.Count)
        {
            _readTypeMetas.Add(typeMeta);
            return;
        }

        if (listIndex < _readTypeMetas.Count)
        {
            _readTypeMetas.Set(listIndex, typeMeta);
            return;
        }

        throw new InvalidDataException(
            $"type meta index gap: index={index}, count={_readTypeMetas.Count + 1}");
    }

    internal bool TryGetCachedReadTypeMeta(ulong header, out TypeMeta typeMeta, out int skipBytesAfterHeader)
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

    internal void CacheReadTypeMeta(ulong header, TypeMeta typeMeta, int skipBytesAfterHeader)
    {
        CachedTypeMetaEntry cached = new(typeMeta, skipBytesAfterHeader);
        _lastMetaHeader = header;
        _lastTypeMeta = cached;
        _hasLastMetaHeader = true;
        if (_cachedTypeMetasByHeader.Count < MaxParsedTypeMetaEntries)
        {
            _cachedTypeMetasByHeader.TryAdd(header, cached);
        }
    }

    internal MetaString? GetReadMetaString(int index)
    {
        return index >= 0 && index < _readMetaStrings.Count ? _readMetaStrings[index] : null;
    }

    internal void AppendReadMetaString(MetaString value)
    {
        _readMetaStrings.Add(value);
    }

    internal TypeMeta ReadTypeMeta()
    {
        uint indexMarker = Reader.ReadVarUInt32();
        bool isRef = (indexMarker & 1) == 1;
        int index = checked((int)(indexMarker >> 1));
        if (isRef)
        {
            TypeMeta? cached = GetReadTypeMeta(index);
            if (cached is null)
            {
                throw new InvalidDataException($"unknown type meta ref index {index}");
            }

            return cached;
        }

        ulong header = Reader.ReadUInt64();
        if (TryGetCachedReadTypeMeta(header, out TypeMeta cachedTypeMeta, out int skipBytesAfterHeader))
        {
            Reader.Skip(skipBytesAfterHeader);
            StoreReadTypeMeta(cachedTypeMeta, index);
            return cachedTypeMeta;
        }

        int headerStartCursor = Reader.Cursor - sizeof(ulong);
        Reader.MoveBack(sizeof(ulong));
        TypeMeta typeMeta = TypeMeta.Decode(Reader);
        int consumedTypeMetaBytes = Reader.Cursor - headerStartCursor;
        int parsedSkipBytesAfterHeader = consumedTypeMetaBytes - sizeof(ulong);
        StoreReadTypeMeta(typeMeta, index);
        CacheReadTypeMeta(header, typeMeta, parsedSkipBytesAfterHeader);
        return typeMeta;
    }

    internal void StoreTypeMeta(Type type, TypeMeta typeMeta)
    {
        ulong typeKey = TypeMapKey.Get(type);
        if (_cachedTypeMetaType == type && ReferenceEquals(_cachedTypeMeta, typeMeta))
        {
            return;
        }

        if (ReferenceEquals(_typeMetaType, type))
        {
            if (ReferenceEquals(_typeMeta, typeMeta))
            {
                _cachedTypeMetaType = type;
                _cachedTypeMeta = typeMeta;
                return;
            }

            _typeMeta = typeMeta;
            _cachedTypeMetaType = type;
            _cachedTypeMeta = typeMeta;
            return;
        }

        if (_typeMetaType is null)
        {
            _typeMetaType = type;
            _typeMeta = typeMeta;
            _cachedTypeMetaType = type;
            _cachedTypeMeta = typeMeta;
            return;
        }

        if (_typeMetaByType is null)
        {
            _typeMetaByType = new UInt64Map<TypeMeta>();
            if (_typeMeta is not null)
            {
                _typeMetaByType.Set(TypeMapKey.Get(_typeMetaType!), _typeMeta);
            }
        }
        else if (_typeMetaByType.TryGetValue(typeKey, out TypeMeta? existing) &&
                 ReferenceEquals(existing, typeMeta))
        {
            _cachedTypeMetaType = type;
            _cachedTypeMeta = typeMeta;
            return;
        }

        _typeMetaByType.Set(typeKey, typeMeta);
        _cachedTypeMetaType = type;
        _cachedTypeMeta = typeMeta;
    }

    public TypeMeta? GetTypeMeta<T>()
    {
        return GetTypeMeta(typeof(T));
    }

    private TypeMeta? GetTypeMeta(Type type)
    {
        ulong typeKey = TypeMapKey.Get(type);
        if (_cachedTypeMetaType == type && _cachedTypeMeta is not null)
        {
            return _cachedTypeMeta;
        }

        if (ReferenceEquals(_typeMetaType, type) &&
            _typeMeta is not null)
        {
            _cachedTypeMetaType = type;
            _cachedTypeMeta = _typeMeta;
            return _typeMeta;
        }

        if (_typeMetaByType is null ||
            !_typeMetaByType.TryGetValue(typeKey, out TypeMeta? typeMeta) ||
            typeMeta is null)
        {
            return null;
        }

        _cachedTypeMetaType = type;
        _cachedTypeMeta = typeMeta;
        return typeMeta;
    }

    internal void SetReadTypeInfo(Type type, TypeInfo typeInfo)
    {
        _readTypeInfoByType.Set(TypeMapKey.Get(type), typeInfo);
    }

    internal TypeInfo? GetReadTypeInfo(Type type)
    {
        return _readTypeInfoByType.TryGetValue(TypeMapKey.Get(type), out TypeInfo? typeInfo) ? typeInfo : null;
    }

    internal void ClearReadTypeInfo(Type type)
    {
        _readTypeInfoByType.Remove(TypeMapKey.Get(type));
    }

    public void StoreRef(object? value)
    {
        if (_reservedRefIds.Count == 0)
        {
            return;
        }

        RefReader.StoreRefAt(_reservedRefIds[^1], value);
    }

    internal void SetReservedRefId(uint refId)
    {
        _reservedRefIds.Add(refId);
    }

    internal void ClearReservedRefId()
    {
        if (_reservedRefIds.Count > 0)
        {
            _reservedRefIds.RemoveAt(_reservedRefIds.Count - 1);
        }
    }

    internal void IncreaseReadDepth()
    {
        _currentDynamicReadDepth += 1;
        if (_currentDynamicReadDepth > _maxDynamicReadDepth)
        {
            throw new InvalidDataException(
                $"maximum dynamic object nesting depth ({_maxDynamicReadDepth}) exceeded. current depth: {_currentDynamicReadDepth}");
        }
    }

    internal void DecreaseReadDepth()
    {
        if (_currentDynamicReadDepth > 0)
        {
            _currentDynamicReadDepth -= 1;
        }
    }

    internal T CanonicalizeNonTrackingRef<T>(T value, int start, int end)
    {
        if (!TrackRef || end <= start || value is null || value is not object obj)
        {
            return value;
        }

        byte[] bytes = new byte[end - start];
        Array.Copy(Reader.Storage, start, bytes, 0, bytes.Length);
        (ulong hashLo, ulong hashHi) = MurmurHash3.X64_128(bytes, 47);
        CanonicalRefSignature signature = new(obj.GetType(), hashLo, hashHi, bytes.Length);

        if (_canonicalRefCache.TryGetValue(signature, out List<CanonicalRefEntry>? bucket))
        {
            foreach (CanonicalRefEntry entry in bucket)
            {
                if (entry.Bytes.AsSpan().SequenceEqual(bytes))
                {
                    return (T)entry.Object;
                }
            }

            bucket.Add(new CanonicalRefEntry { Bytes = bytes, Object = obj });
            return value;
        }

        _canonicalRefCache[signature] =
        [
            new CanonicalRefEntry { Bytes = bytes, Object = obj },
        ];
        return value;
    }

    internal void Reset()
    {
        RefReader.Reset();
        _typeMetaType = null;
        _typeMeta = null;
        _typeMetaByType?.ClearKeys();
        _readTypeInfoByType.ClearKeys();
        _canonicalRefCache.Clear();
        _reservedRefIds.Clear();
        _cachedTypeMetaType = null;
        _cachedTypeMeta = null;
        _currentDynamicReadDepth = 0;
        _firstReadTypeMeta = null;
        _hasFirstReadTypeMeta = false;
        _readTypeMetas.Clear();
        _readMetaStrings.Clear();
    }
}
