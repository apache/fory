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

/// <summary>
/// Marks a class, struct, or enum as a generated Fory object type.
/// </summary>
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Struct | AttributeTargets.Enum)]
public sealed class ForyObjectAttribute : Attribute
{
    /// <summary>
    /// Whether the annotated struct should use schema evolution metadata in compatible mode.
    /// </summary>
    public bool Evolving { get; set; } = true;
}

/// <summary>
/// Overrides generated serializer behavior for a field or property.
/// </summary>
[AttributeUsage(AttributeTargets.Field | AttributeTargets.Property)]
public sealed class ForyFieldAttribute : Attribute
{
    private short id = -1;

    public ForyFieldAttribute()
    {
    }

    public ForyFieldAttribute(short id)
    {
        ValidateId(id);
        this.id = id;
    }

    public ForyFieldAttribute(int id)
    {
        if (id is < 0 or > short.MaxValue)
        {
            throw new ArgumentOutOfRangeException(nameof(id));
        }

        this.id = (short)id;
    }

    /// <summary>
    /// Optional stable field tag id used for compatible metadata dispatch.
    /// Use a non-negative value to emit numeric field ids instead of field names.
    /// </summary>
    public short Id
    {
        get => id;
        set
        {
            ValidateId(value);
            id = value;
        }
    }

    /// <summary>
    /// Optional Fory schema descriptor type from <c>Apache.Fory.Schema.Types</c>.
    /// </summary>
    public Type? Type { get; set; }

    private static void ValidateId(short id)
    {
        if (id < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(id));
        }
    }
}
