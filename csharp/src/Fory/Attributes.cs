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

using System.ComponentModel;

namespace Apache.Fory;

/// <summary>
/// Marks a class or struct directly for generated structural serialization, or
/// declares an external structural serializer.
/// This attribute is not inherited by derived classes.
/// </summary>
[AttributeUsage(
    AttributeTargets.Class | AttributeTargets.Struct,
    AllowMultiple = false,
    Inherited = false)]
public sealed class ForyStructAttribute : Attribute
{
    /// <summary>
    /// Gets or sets the external class or struct whose schema is declared by the annotated
    /// serializer declaration. External class declarations can also map exact storage fields.
    /// When null, the annotated class or struct is the serialized target.
    /// </summary>
    public Type? Target { get; set; }

    /// <summary>
    /// Gets or sets whether the generated structural serializer uses schema evolution metadata
    /// in compatible mode. Abstract ordinary classes and external base-only declarations cannot
    /// set this option explicitly; each concrete descendant owns its serializer setting.
    /// </summary>
    public bool Evolving { get; set; } = true;

    /// <summary>
    /// Gets or sets whether an external class declaration supplies only the generated
    /// hierarchy provider consumed by directly annotated derived classes.
    /// </summary>
    /// <remarks>
    /// A base-only declaration does not generate a standalone serializer factory or
    /// registration for <see cref="Target"/>.
    /// </remarks>
    public bool BaseOnly { get; set; }
}

/// <summary>
/// Marks an enum as a generated Fory enum type, or declares an external enum serializer.
/// Enum numeric values are the wire tags and must be in the range
/// <c>0</c> through <see cref="uint.MaxValue"/>.
/// </summary>
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Enum)]
public sealed class ForyEnumAttribute : Attribute
{
    /// <summary>
    /// Gets or sets the external enum handled by the annotated serializer declaration.
    /// When null, the annotated enum is the serialized target.
    /// </summary>
    public Type? Target { get; set; }
}

/// <summary>
/// Marks a generated Fory union type.
/// </summary>
[AttributeUsage(AttributeTargets.Class)]
public sealed class ForyUnionAttribute : Attribute
{
}

/// <summary>
/// Marks a nested case type within a generated Fory union.
/// </summary>
[AttributeUsage(AttributeTargets.Class)]
public sealed class ForyCaseAttribute : Attribute
{
    public ForyCaseAttribute(int id)
    {
        if (id < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(id));
        }

        Id = id;
    }

    /// <summary>
    /// Stable union case id written on the wire.
    /// </summary>
    public int Id { get; }

    /// <summary>
    /// Optional Fory schema descriptor type from <c>Apache.Fory.Schema.Types</c>.
    /// </summary>
    public Type? Type { get; set; }
}

/// <summary>
/// Marks the runtime-owned unknown-case carrier inside a generated Fory union.
/// </summary>
[AttributeUsage(AttributeTargets.Class)]
public sealed class ForyUnknownCaseAttribute : Attribute
{
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

    /// <summary>
    /// Gets or sets whether an external class serializer declaration excludes an exact
    /// target field from the wire schema while retaining its storage in the graph-memory
    /// estimate.
    /// </summary>
    public bool Ignore { get; set; }

    /// <summary>
    /// Gets or sets the exact external class target or non-<see cref="object"/> ancestor
    /// type that declares a mapped field.
    /// </summary>
    /// <remarks>
    /// This option is valid only on an external structural serializer declaration.
    /// </remarks>
    public Type? TargetDeclaringType { get; set; }

    /// <summary>
    /// Gets or sets the case-sensitive name of an externally mapped target member.
    /// For an external class mapping with <see cref="TargetDeclaringType"/>, this is an
    /// exact field metadata name.
    /// </summary>
    /// <remarks>
    /// This option is valid only on an external structural serializer declaration.
    /// </remarks>
    public string? TargetMemberName { get; set; }

    private static void ValidateId(short id)
    {
        if (id < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(id));
        }
    }
}

/// <summary>
/// Identifies the target of a generated hierarchy provider.
/// </summary>
[AttributeUsage(AttributeTargets.Class, AllowMultiple = false, Inherited = false)]
[EditorBrowsable(EditorBrowsableState.Never)]
public sealed class ForyGeneratedHierarchyProviderAttribute : Attribute
{
    /// <summary>
    /// Initializes a generated hierarchy provider for <paramref name="targetType"/>.
    /// </summary>
    /// <param name="targetType">The exact class hierarchy target supplied by the provider.</param>
    /// <param name="wireMemberCount">The number of declaration-owned wire members.</param>
    public ForyGeneratedHierarchyProviderAttribute(
        Type targetType,
        int wireMemberCount)
    {
        if (wireMemberCount < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(wireMemberCount));
        }

        TargetType = targetType;
        WireMemberCount = wireMemberCount;
    }

    /// <summary>
    /// Gets the exact class hierarchy target supplied by the provider.
    /// </summary>
    public Type TargetType { get; }

    /// <summary>
    /// Gets the number of declaration-owned wire members.
    /// </summary>
    public int WireMemberCount { get; }
}

/// <summary>
/// Records one declaration-owned wire member on its generated read accessor.
/// </summary>
[AttributeUsage(AttributeTargets.Method, AllowMultiple = false, Inherited = false)]
[EditorBrowsable(EditorBrowsableState.Never)]
public sealed class ForyGeneratedWireMemberAttribute : Attribute
{
    /// <summary>
    /// Initializes a generated wire-member contract.
    /// </summary>
    /// <param name="ordinal">Stable ordinal within the declaring structural type.</param>
    /// <param name="declaringType">The exact type that declares the target member.</param>
    /// <param name="logicalName">The logical CLR member name used by the schema.</param>
    /// <param name="targetMemberName">The exact target metadata member name.</param>
    public ForyGeneratedWireMemberAttribute(
        int ordinal,
        Type declaringType,
        string logicalName,
        string targetMemberName)
    {
        Ordinal = ordinal;
        DeclaringType = declaringType;
        LogicalName = logicalName;
        TargetMemberName = targetMemberName;
    }

    /// <summary>
    /// Gets the stable ordinal within the declaring structural type.
    /// </summary>
    public int Ordinal { get; }

    /// <summary>
    /// Gets the exact type that declares the target member.
    /// </summary>
    public Type DeclaringType { get; }

    /// <summary>
    /// Gets the logical CLR member name used by the schema.
    /// </summary>
    public string LogicalName { get; }

    /// <summary>
    /// Gets the exact target metadata member name.
    /// </summary>
    public string TargetMemberName { get; }

    /// <summary>
    /// Gets or sets the explicit schema field ID, or <c>-1</c> for name-based identity.
    /// </summary>
    public int FieldId { get; set; } = -1;

    /// <summary>
    /// Gets or sets the optional Fory schema descriptor type.
    /// </summary>
    public Type? SchemaType { get; set; }

    /// <summary>
    /// Gets or sets the stable override-slot identity for a logical property.
    /// </summary>
    public string? Slot { get; set; }
}
