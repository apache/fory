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

using System.Runtime.CompilerServices;

namespace Apache.Fory;

public sealed class ReadContext
{
    private const int MinRemoteTypeMetaLimit = 8192;
    internal const long KnownContainerBudgetSlackBytes = 64 * 1024;

    private readonly ReusableArray<TypeMeta> _typeMetaRefs = new();
    private readonly UInt64Map<TypeMeta> _typeMetasByHeader = new();
    private TypeMeta? _firstTypeMetaRef;
    private bool _hasFirstTypeMetaRef;

    private readonly List<MetaString> _readMetaStrings = [];

    internal readonly UInt64Map<TypeInfo> _readTypeInfoByType = new();
    internal readonly List<uint> _reservedRefIds = [];
    private readonly int _maxDynamicReadDepth;
    internal Type? _typeMetaType;
    internal TypeMeta? _typeMeta;
    internal UInt64Map<TypeMeta>? _typeMetaByType;
    internal Type? _cachedTypeMetaType;
    internal TypeMeta? _cachedTypeMeta;
    internal int _currentDynamicReadDepth;
    private readonly Dictionary<object, int> _remoteSchemaVersionsByType = [];
    private readonly Config _config;
    private int _totalAcceptedSchemaVersions;
    private long _containerMemoryLimitBytes = long.MaxValue;
    private long _remainingContainerMemoryBytes = long.MaxValue;

    public ReadContext(
        ByteReader reader,
        TypeResolver typeResolver,
        Config config)
    {
        ArgumentNullException.ThrowIfNull(config);

        Reader = reader;
        TypeResolver = typeResolver;
        TrackRef = config.TrackRef;
        Compatible = config.Compatible;
        CheckStructVersion = config.CheckStructVersion;
        RefReader = new RefReader();
        _maxDynamicReadDepth = config.MaxDepth;
        _config = config;
    }

    public ByteReader Reader { get; private set; }

    public TypeResolver TypeResolver { get; }

    public bool TrackRef { get; }

    public bool Compatible { get; }

    public bool CheckStructVersion { get; }

    internal RefReader RefReader { get; }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal void InitContainerBudgetKnown(int rootBytes)
    {
        long limit = _config.MaxContainerMemoryBytes;
        if (limit < 0)
        {
            limit = (long)rootBytes * 8 + KnownContainerBudgetSlackBytes;
        }

        _containerMemoryLimitBytes = limit;
        _remainingContainerMemoryBytes = limit;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal void ReserveContainerMemory(long bytes)
    {
        long remaining = _remainingContainerMemoryBytes;
        if ((ulong)bytes > (ulong)remaining)
        {
            ThrowContainerBudgetExceeded(bytes, remaining, _containerMemoryLimitBytes);
        }

        _remainingContainerMemoryBytes = remaining - bytes;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal void ReserveCountedContainerMemory(int count, long elementBytes)
    {
        if (count < 0 || elementBytes < 0)
        {
            ThrowContainerBudgetOverflow();
        }

        uint length = (uint)count;
        if (elementBytes != 0 && length > long.MaxValue / elementBytes)
        {
            ThrowContainerBudgetOverflow();
        }

        ReserveContainerMemory((long)length * elementBytes);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void ThrowContainerBudgetOverflow()
    {
        throw new InvalidDataException("container memory estimate overflows");
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void ThrowContainerBudgetExceeded(long bytes, long remaining, long limit)
    {
        throw new InvalidDataException(
            $"estimated container memory request {bytes} bytes exceeds MaxContainerMemoryBytes remaining budget {remaining} bytes out of effective limit {limit} bytes");
    }

    internal void ResetFor(ByteReader reader)
    {
        Reader = reader;
        Reset();
    }

    internal TypeMeta? GetTypeMetaRef(int index)
    {
        if (index < 0)
        {
            return null;
        }

        if (index == 0)
        {
            return _hasFirstTypeMetaRef ? _firstTypeMetaRef : null;
        }

        return _typeMetaRefs.Get(index - 1);
    }

    internal void StoreTypeMetaRef(TypeMeta typeMeta, int index)
    {
        if (index < 0)
        {
            throw new InvalidDataException("negative type meta index");
        }

        if (index == 0)
        {
            _firstTypeMetaRef = typeMeta;
            _hasFirstTypeMetaRef = true;
            return;
        }

        if (!_hasFirstTypeMetaRef)
        {
            throw new InvalidDataException(
                $"type meta index gap: index={index}, missing index 0");
        }

        int listIndex = index - 1;
        if (listIndex == _typeMetaRefs.Count)
        {
            _typeMetaRefs.Add(typeMeta);
            return;
        }

        if (listIndex < _typeMetaRefs.Count)
        {
            _typeMetaRefs.Set(listIndex, typeMeta);
            return;
        }

        throw new InvalidDataException(
            $"type meta index gap: index={index}, count={_typeMetaRefs.Count + 1}");
    }

    internal bool TryGetTypeMetaByHeader(ulong header, out TypeMeta typeMeta)
    {
        // UInt64Map reserves ulong.MaxValue as its empty-slot marker. A valid
        // cached TypeMeta header cannot use reserved global-header bits, but an
        // attacker-controlled cache lookup can happen before cold-path header
        // validation, so this value must be forced to the miss path.
        if (header != ulong.MaxValue &&
            _typeMetasByHeader.TryGetValue(header, out TypeMeta? cached) &&
            cached is not null)
        {
            typeMeta = cached;
            return true;
        }

        typeMeta = null!;
        return false;
    }

    internal void StoreRemoteTypeMeta(ulong header, TypeMeta typeMeta)
    {
        if (_typeMetasByHeader.TryGetValue(header, out _))
        {
            return;
        }

        object typeKey = CheckRemoteTypeMetaLimits(typeMeta);
        _typeMetasByHeader.Set(header, typeMeta);
        RecordRemoteTypeMetaVersion(typeKey);
    }

    internal void StoreExactLocalTypeMeta(ulong header, TypeMeta typeMeta)
    {
        _typeMetasByHeader.Set(header, typeMeta);
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private object CheckRemoteTypeMetaLimits(TypeMeta typeMeta)
    {
        object typeKey;
        if (typeMeta.RegisterByName)
        {
            typeKey = $"{typeMeta.NamespaceName.Value}\0{typeMeta.TypeName.Value}";
        }
        else if (typeMeta.UserTypeId.HasValue)
        {
            typeKey = typeMeta.UserTypeId.Value;
        }
        else
        {
            throw new InvalidDataException("remote metadata is missing type identity");
        }
        _remoteSchemaVersionsByType.TryGetValue(typeKey, out int versionsForType);
        int maxSchemaVersionsPerType = _config.MaxSchemaVersionsPerType;
        if (versionsForType >= maxSchemaVersionsPerType)
        {
            throw new InvalidDataException(
                $"Remote schema version limit exceeded for type {typeKey}: {versionsForType} >= {maxSchemaVersionsPerType}. " +
                "The data may be malicious. If the data is not malicious, please increase MaxSchemaVersionsPerType.");
        }

        int acceptedTypeCount = versionsForType == 0
            ? _remoteSchemaVersionsByType.Count + 1
            : _remoteSchemaVersionsByType.Count;
        int maxAverageSchemaVersionsPerType = _config.MaxAverageSchemaVersionsPerType;
        long globalLimit = Math.Max(
            MinRemoteTypeMetaLimit,
            (long)acceptedTypeCount * maxAverageSchemaVersionsPerType);
        if (_totalAcceptedSchemaVersions >= globalLimit)
        {
            throw new InvalidDataException(
                $"Remote schema version limit exceeded: {_totalAcceptedSchemaVersions} metadata versions for " +
                $"{acceptedTypeCount} accepted remote types exceeds the average limit {maxAverageSchemaVersionsPerType}. " +
                "The data may be malicious. If the data is not malicious, please increase MaxAverageSchemaVersionsPerType.");
        }

        return typeKey;
    }

    private void RecordRemoteTypeMetaVersion(object typeKey)
    {
        _remoteSchemaVersionsByType.TryGetValue(typeKey, out int versionsForType);
        _remoteSchemaVersionsByType[typeKey] = versionsForType + 1;
        _totalAcceptedSchemaVersions++;
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
        if (TryReadTypeMetaRef(out int index, out TypeMeta typeMeta))
        {
            return typeMeta;
        }

        ulong header = Reader.ReadUInt64();
        if (TryGetTypeMetaByHeader(header, out TypeMeta cachedTypeMeta))
        {
            // Header-cache hits intentionally skip without rehashing. Entries reach this cache only
            // after successful TypeMeta body validation. Do not add body/hash/schema-limit/exact-local
            // checks here; the miss path owns them before cache publish.
            TypeMeta.SkipBody(Reader, header);
            StoreTypeMetaRef(cachedTypeMeta, index);
            return cachedTypeMeta;
        }

        Reader.MoveBack(sizeof(ulong));
        int typeMetaStart = Reader.Cursor;
        typeMeta = DecodeTypeMeta();
        int typeMetaEnd = Reader.Cursor;
        if (MatchesExactLocalTypeMeta(typeMeta, typeMetaStart, typeMetaEnd))
        {
            StoreExactLocalTypeMeta(header, typeMeta);
        }
        else
        {
            StoreRemoteTypeMeta(header, typeMeta);
        }
        StoreTypeMetaRef(typeMeta, index);
        return typeMeta;
    }

    internal bool TryReadTypeMetaRef(out int index, out TypeMeta typeMeta)
    {
        uint indexMarker = Reader.ReadVarUInt32();
        bool isRef = (indexMarker & 1) == 1;
        index = checked((int)(indexMarker >> 1));
        if (isRef)
        {
            TypeMeta? cached = GetTypeMetaRef(index);
            if (cached is null)
            {
                throw new InvalidDataException($"unknown type meta ref index {index}");
            }

            typeMeta = cached;
            return true;
        }

        typeMeta = null!;
        return false;
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    internal bool MatchesExactLocalTypeMeta(TypeMeta typeMeta, int start, int end)
    {
        if (!TypeResolver.TryGetLocalTypeInfo(typeMeta, out TypeInfo exactLocal))
        {
            return false;
        }

        TypeInfo.TypeMetaCacheEntry local = exactLocal.GetTypeMetaCacheEntry(TrackRef);
        byte[] encoded = local.EncodedBytes;
        if (end - start != encoded.Length ||
            !Reader.Storage.AsSpan(start, encoded.Length).SequenceEqual(encoded))
        {
            return false;
        }

        return true;
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    internal TypeMeta DecodeTypeMeta()
    {
        return TypeMeta.Decode(Reader, _config.MaxTypeFields, _config.MaxTypeMetaBytes);
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

    internal void Reset()
    {
        RefReader.Reset();
        _typeMetaType = null;
        _typeMeta = null;
        _typeMetaByType?.ClearKeys();
        _readTypeInfoByType.ClearKeys();
        _reservedRefIds.Clear();
        _cachedTypeMetaType = null;
        _cachedTypeMeta = null;
        _currentDynamicReadDepth = 0;
        _firstTypeMetaRef = null;
        _hasFirstTypeMetaRef = false;
        _typeMetaRefs.Clear();
        _readMetaStrings.Clear();
    }
}
