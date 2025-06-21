namespace Fury.Meta;

internal readonly record struct TypeMetadata(InternalTypeKind Kind, int Id)
{
    private const int TypeKindBits = 8;
    private const uint TypeKindMask = (1u << TypeKindBits) - 1;

    public static TypeMetadata FromUint(uint value)
    {
        var kind = (InternalTypeKind)(value & TypeKindMask);
        var id = (int)(value >>> TypeKindBits);
        return new TypeMetadata(kind, id);
    }

    public uint ToUint()
    {
        return (uint)Id << TypeKindBits | (uint)Kind;
    }
}

internal record struct RefMetadata(RefFlag RefFlag, int RefId = 0);

internal enum RefFlag : sbyte
{
    Null = -3,

    /// <summary>
    /// This flag indicates that object is a not-null value.
    /// We don't use another byte to indicate REF, so that we can save one byte.
    /// </summary>
    Ref = -2,

    /// <summary>
    /// this flag indicates that the object is a non-null value.
    /// </summary>
    NotNullValue = -1,

    /// <summary>
    /// this flag indicates that the object is a referencable and first write.
    /// </summary>
    RefValue = 0,
}
