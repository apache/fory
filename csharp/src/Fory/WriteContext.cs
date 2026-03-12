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

public sealed class WriteContext
{
    private UInt64Map<uint>? _typeMetaIndexByType;
    private ulong _firstTypeMetaKey;
    private bool _hasFirstTypeMeta;
    private uint _nextTypeMetaIndex;

    private readonly Dictionary<MetaString, uint> _metaStringIndexByKey = [];
    private uint _nextMetaStringIndex;

    public WriteContext(
        ByteWriter writer,
        TypeResolver typeResolver,
        bool trackRef,
        bool compatible = false,
        bool checkStructVersion = false)
    {
        Writer = writer;
        TypeResolver = typeResolver;
        TrackRef = trackRef;
        Compatible = compatible;
        CheckStructVersion = checkStructVersion;
        RefWriter = new RefWriter();
    }

    public ByteWriter Writer { get; private set; }

    public TypeResolver TypeResolver { get; }

    public bool TrackRef { get; }

    public bool Compatible { get; }

    public bool CheckStructVersion { get; }

    internal RefWriter RefWriter { get; }

    internal void ResetFor(ByteWriter writer)
    {
        Writer = writer;
        Reset();
    }

    internal (uint Index, bool IsNew) AssignTypeMetaIndexIfAbsent(Type type)
    {
        ulong typeKey = TypeMapKey.Get(type);
        if (_nextTypeMetaIndex == 0)
        {
            _firstTypeMetaKey = typeKey;
            _hasFirstTypeMeta = true;
            _nextTypeMetaIndex = 1;
            return (0, true);
        }

        if (_hasFirstTypeMeta && _firstTypeMetaKey == typeKey)
        {
            return (0, false);
        }

        if (_typeMetaIndexByType is null)
        {
            _typeMetaIndexByType = new UInt64Map<uint>();
        }
        else if (_typeMetaIndexByType.TryGetValue(typeKey, out uint existing))
        {
            return (existing, false);
        }

        uint index = _nextTypeMetaIndex;
        _nextTypeMetaIndex += 1;
        _typeMetaIndexByType.Set(typeKey, index);
        return (index, true);
    }

    internal (uint Index, bool IsNew) AssignMetaStringIndexIfAbsent(MetaString value)
    {
        if (_metaStringIndexByKey.TryGetValue(value, out uint existing))
        {
            return (existing, false);
        }

        uint index = _nextMetaStringIndex;
        _nextMetaStringIndex += 1;
        _metaStringIndexByKey[value] = index;
        return (index, true);
    }

    internal void WriteTypeMeta(Type type, TypeMeta typeMeta)
    {
        WriteTypeMeta(type, typeMeta.Encode());
    }

    internal void WriteTypeMeta(Type type, ReadOnlySpan<byte> encodedTypeMeta)
    {
        (uint index, bool isNew) = AssignTypeMetaIndexIfAbsent(type);
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

    internal void Reset()
    {
        RefWriter.Reset();
        _firstTypeMetaKey = 0;
        _hasFirstTypeMeta = false;
        _typeMetaIndexByType?.ClearKeys();
        _nextTypeMetaIndex = 0;
        _metaStringIndexByKey.Clear();
        _nextMetaStringIndex = 0;
    }
}
