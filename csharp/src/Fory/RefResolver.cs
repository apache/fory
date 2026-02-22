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

public sealed class RefWriter
{
    private readonly Dictionary<object, uint> _refs = new(ReferenceEqualityComparer.Instance);
    private uint _nextRefId;

    public bool TryWriteReference(ByteWriter writer, object obj)
    {
        if (_refs.TryGetValue(obj, out uint refId))
        {
            writer.WriteInt8((sbyte)RefFlag.Ref);
            writer.WriteVarUInt32(refId);
            return true;
        }

        _refs[obj] = _nextRefId;
        _nextRefId += 1;
        writer.WriteInt8((sbyte)RefFlag.RefValue);
        return false;
    }

    public uint ReserveRefId()
    {
        uint id = _nextRefId;
        _nextRefId += 1;
        return id;
    }

    public void Reset()
    {
        _refs.Clear();
        _nextRefId = 0;
    }
}

public sealed class RefReader
{
    private readonly List<object?> _refs = [];

    public uint ReserveRefId()
    {
        uint id = (uint)_refs.Count;
        _refs.Add(null);
        return id;
    }

    public void StoreRef(object? value, uint refId)
    {
        int index = checked((int)refId);
        _refs[index] = value;
    }

    public T ReadRef<T>(uint refId)
    {
        int index = checked((int)refId);
        if (index < 0 || index >= _refs.Count)
        {
            throw new RefException($"ref_id out of range: {refId}");
        }

        if (_refs[index] is T typed)
        {
            return typed;
        }

        throw new RefException($"ref_id {refId} has unexpected runtime type");
    }

    public object? ReadRefValue(uint refId)
    {
        int index = checked((int)refId);
        if (index < 0 || index >= _refs.Count)
        {
            throw new RefException($"ref_id out of range: {refId}");
        }

        return _refs[index];
    }

    public void Reset()
    {
        _refs.Clear();
    }
}

