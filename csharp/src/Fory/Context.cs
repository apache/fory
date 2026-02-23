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
    private readonly Dictionary<Type, uint> _typeIndexByType = [];
    private uint _nextIndex;

    public uint? LookupIndex(Type type)
    {
        return _typeIndexByType.TryGetValue(type, out uint idx) ? idx : null;
    }

    public (uint Index, bool IsNew) AssignIndexIfAbsent(Type type)
    {
        if (_typeIndexByType.TryGetValue(type, out uint existing))
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
        _typeIndexByType.Clear();
        _nextIndex = 0;
    }
}

public sealed class CompatibleTypeDefReadState
{
    private readonly List<TypeMeta> _typeMetas = [];

    public TypeMeta? TypeMetaAt(int index)
    {
        return index >= 0 && index < _typeMetas.Count ? _typeMetas[index] : null;
    }

    public void StoreTypeMeta(TypeMeta typeMeta, int index)
    {
        if (index < 0)
        {
            throw new InvalidDataException("negative compatible type definition index");
        }

        if (index == _typeMetas.Count)
        {
            _typeMetas.Add(typeMeta);
            return;
        }

        if (index < _typeMetas.Count)
        {
            _typeMetas[index] = typeMeta;
            return;
        }

        throw new InvalidDataException(
            $"compatible type definition index gap: index={index}, count={_typeMetas.Count}");
    }

    public void Reset()
    {
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
        CompatibleTypeDefWriteState? compatibleTypeDefState = null,
        MetaStringWriteState? metaStringWriteState = null)
    {
        Writer = writer;
        TypeResolver = typeResolver;
        TrackRef = trackRef;
        Compatible = compatible;
        RefWriter = new RefWriter();
        CompatibleTypeDefState = compatibleTypeDefState ?? new CompatibleTypeDefWriteState();
        MetaStringWriteState = metaStringWriteState ?? new MetaStringWriteState();
    }

    public ByteWriter Writer { get; private set; }

    public TypeResolver TypeResolver { get; }

    public bool TrackRef { get; }

    public bool Compatible { get; }

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
        (uint index, bool isNew) = CompatibleTypeDefState.AssignIndexIfAbsent(type);
        if (isNew)
        {
            Writer.WriteVarUInt32(index << 1);
            Writer.WriteBytes(typeMeta.Encode());
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

internal readonly record struct PendingRefSlot(uint RefId, bool Bound);

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
    private readonly List<PendingRefSlot> _pendingRefStack = [];
    private readonly Dictionary<Type, List<TypeMeta>> _pendingCompatibleTypeMeta = [];
    private readonly Dictionary<Type, DynamicTypeInfo> _pendingDynamicTypeInfo = [];
    private readonly Dictionary<CanonicalReferenceSignature, List<CanonicalReferenceEntry>> _canonicalReferenceCache = [];

    public ReadContext(
        ByteReader reader,
        TypeResolver typeResolver,
        bool trackRef,
        bool compatible = false,
        CompatibleTypeDefReadState? compatibleTypeDefState = null,
        MetaStringReadState? metaStringReadState = null)
    {
        Reader = reader;
        TypeResolver = typeResolver;
        TrackRef = trackRef;
        Compatible = compatible;
        RefReader = new RefReader();
        CompatibleTypeDefState = compatibleTypeDefState ?? new CompatibleTypeDefReadState();
        MetaStringReadState = metaStringReadState ?? new MetaStringReadState();
    }

    public ByteReader Reader { get; private set; }

    public TypeResolver TypeResolver { get; }

    public bool TrackRef { get; }

    public bool Compatible { get; }

    public RefReader RefReader { get; }

    public CompatibleTypeDefReadState CompatibleTypeDefState { get; }

    public MetaStringReadState MetaStringReadState { get; }

    public void ResetFor(ByteReader reader)
    {
        Reader = reader;
        Reset();
    }

    public void PushPendingReference(uint refId)
    {
        _pendingRefStack.Add(new PendingRefSlot(refId, false));
    }

    public void BindPendingReference(object? value)
    {
        if (_pendingRefStack.Count == 0)
        {
            return;
        }

        PendingRefSlot last = _pendingRefStack[^1];
        _pendingRefStack.RemoveAt(_pendingRefStack.Count - 1);
        _pendingRefStack.Add(last with { Bound = true });
        RefReader.StoreRef(value, last.RefId);
    }

    public void FinishPendingReferenceIfNeeded(object? value)
    {
        if (_pendingRefStack.Count == 0)
        {
            return;
        }

        PendingRefSlot last = _pendingRefStack[^1];
        if (!last.Bound)
        {
            RefReader.StoreRef(value, last.RefId);
        }
    }

    public void PopPendingReference()
    {
        if (_pendingRefStack.Count > 0)
        {
            _pendingRefStack.RemoveAt(_pendingRefStack.Count - 1);
        }
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

        TypeMeta typeMeta = TypeMeta.Decode(Reader);
        CompatibleTypeDefState.StoreTypeMeta(typeMeta, index);
        return typeMeta;
    }

    public void PushCompatibleTypeMeta(Type type, TypeMeta typeMeta)
    {
        _pendingCompatibleTypeMeta[type] = [typeMeta];
    }

    public TypeMeta ConsumeCompatibleTypeMeta(Type type)
    {
        if (!_pendingCompatibleTypeMeta.TryGetValue(type, out List<TypeMeta>? stack) || stack.Count == 0)
        {
            throw new InvalidDataException($"missing compatible type metadata for {type}");
        }

        return stack[^1];
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
        _pendingRefStack.Clear();
        _pendingCompatibleTypeMeta.Clear();
        _pendingDynamicTypeInfo.Clear();
        _canonicalReferenceCache.Clear();
    }

    public void Reset()
    {
        ResetObjectState();
        CompatibleTypeDefState.Reset();
        MetaStringReadState.Reset();
    }
}
