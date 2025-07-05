using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Fury.Context;
using Fury.Helpers;
using Fury.Serialization.Meta;

namespace Fury.Serialization;

[Flags]
internal enum CollectionHeaderFlags : byte
{
    TrackingRef = 0b1,
    HasNull = 0b10,
    NotDeclElementType = 0b100,
    NotSameType = 0b1000,
}

// IReadOnlyCollection<TElement> is not inherited from ICollection<TElement>, so we use IEnumerable<TElement> instead.

public abstract class CollectionSerializer<TElement, TCollection>(TypeRegistration? elementRegistration = null) : AbstractSerializer<TCollection>
    where TCollection : notnull
{
    private bool _hasWrittenHeader;
    private bool _hasInitializedTypeMetaSerializer;
    private CollectionHeaderFlags _writtenCollectionFlags;

    /// <summary>
    /// Only used when elements are same type but not declared type.
    /// </summary>
    private TypeRegistration? _elementRegistration = elementRegistration;
    private readonly bool _shouldResetElementRegistration = elementRegistration is null;
    private TypeMetaSerializer _elementTypeMetaSerializer = new();
    private bool _hasWrittenCount;

    public override void Reset()
    {
        _hasWrittenHeader = false;
        _writtenCollectionFlags = default;
        _hasWrittenCount = false;

        if (_shouldResetElementRegistration)
        {
            _elementRegistration = null;
        }
        _hasInitializedTypeMetaSerializer = false;
    }

    public sealed override bool Serialize(SerializationWriter writer, in TCollection value)
    {
        if (_elementRegistration is null && typeof(TElement).IsSealed)
        {
            _elementRegistration = writer.TypeRegistry.GetTypeRegistration(typeof(TElement));
        }

        var writerRef = writer.ByrefWriter;
        WriteCount(ref writerRef, in value);
        if (!_hasWrittenCount)
        {
            return false;
        }
        WriteElementsHeader(ref writerRef, in value, out var needWriteTypeMeta);
        if (!_hasWrittenHeader)
        {
            return false;
        }

        if (needWriteTypeMeta)
        {
            if (!WriteTypeMeta(ref writerRef))
            {
                return false;
            }
        }

        return WriteElements(ref writerRef, in value);
    }

    private void WriteElementsHeader(ref SerializationWriterRef writerRef, in TCollection collection, out bool needWriteTypeMeta)
    {
        needWriteTypeMeta = false;
        if (typeof(TElement).IsValueType)
        {
            // For value types, all elements are the same as the declared type.
            if (TypeHelper.IsNullable(typeof(TElement)))
            {
                // If the element type is nullable, we need to check if there are any null elements.
                WriteNullabilityHeader(ref writerRef, in collection);
            }
            else
            {
                // If the element type is not nullable, we can write a header without any flags.
                WriteHeaderFlags(ref writerRef, default);
            }

            _elementRegistration = writerRef.TypeRegistry.GetTypeRegistration(typeof(TElement));
        }
        var config = writerRef.Config;
        if (typeof(TElement).IsSealed)
        {
            // For sealed reference types, all elements are the same as the declared type.
            if (config.ReferenceTracking)
            {
                // RefFlag contains the nullability information, so we don't need to write HasNull flag here.
                WriteHeaderFlags(ref writerRef, CollectionHeaderFlags.TrackingRef);
            }
            else
            {
                // If ReferenceTracking is disabled, we need to check if there are any null elements to determine if we need to write the HasNull flag.
                WriteNullabilityHeader(ref writerRef, in collection);
            }
            _elementRegistration = writerRef.TypeRegistry.GetTypeRegistration(typeof(TElement));
        }
        else
        {
            if (config.ReferenceTracking)
            {
                var flags = CollectionHeaderFlags.TrackingRef;
                var checkResult = CheckElementsState(in collection, CollectionCheckOptions.TypeConsistency);
                if (checkResult.ElementType is { } elementType)
                {
                    _elementRegistration = writerRef.TypeRegistry.GetTypeRegistration(elementType);
                    if (elementType != typeof(TElement))
                    {
                        flags |= CollectionHeaderFlags.NotDeclElementType;
                        needWriteTypeMeta = true;
                    }
                }
                else
                {
                    // ElementType is null, which means elements are not the same type or all null.
                    flags |= CollectionHeaderFlags.NotSameType | CollectionHeaderFlags.NotDeclElementType;
                }

                WriteHeaderFlags(ref writerRef, flags);
            }
        }
    }

    private void WriteNullabilityHeader(ref SerializationWriterRef writerRef, in TCollection collection)
    {
        var checkResult = CheckElementsState(in collection, CollectionCheckOptions.Nullability);
        var flags = checkResult.HasNull ? CollectionHeaderFlags.HasNull : default;
        WriteHeaderFlags(ref writerRef, flags);
    }

    /// <summary>
    /// Depending on the <paramref name="options"/>, this method will check if the elements in the collection
    /// contain null values or if they are of the same type.
    /// </summary>
    /// <param name="collection">
    /// The collection to check.
    /// </param>
    /// <param name="options">
    /// Which checks to perform.
    /// </param>
    /// <returns>
    /// A <see cref="CollectionCheckResult"/> indicating the result of the checks.
    /// </returns>
    /// <seealso cref="CheckElementsState{T}"/>
    protected virtual CollectionCheckResult CheckElementsState(in TCollection collection, CollectionCheckOptions options)
    {
        if (collection is not IEnumerable<TElement> enumerable)
        {
            ThrowNotSupportedException_TCollectionNotSupported();
            return default;
        }

        return CheckElementsState(enumerable.GetEnumerator(), options);
    }

    /// <summary>
    /// A default implementation of <see cref="CheckElementsState"/>.
    /// </summary>
    /// <param name="enumerator">
    /// An enumerator for the collection to check.
    /// </param>
    /// <param name="options">
    /// Which checks to perform.
    /// </param>
    /// <returns>
    /// A <see cref="CollectionCheckResult"/> indicating the result of the checks.
    /// </returns>
    protected CollectionCheckResult CheckElementsState<TEnumerator>(in TEnumerator enumerator, CollectionCheckOptions options)
        where TEnumerator : IEnumerator<TElement>
    {
        // We create this separate method to avoid boxing the enumerator.

        if ((options & CollectionCheckOptions.Nullability) != 0)
        {
            if ((options & CollectionCheckOptions.TypeConsistency) != 0)
            {
                return CheckElementsNullabilityAndTypeConsistency(enumerator);
            }

            return CheckElementsNullability(enumerator);
        }

        if ((options & CollectionCheckOptions.TypeConsistency) != 0)
        {
            return CheckElementsTypeConsistency(enumerator);
        }

        Debug.Fail("Invalid options");
        return new CollectionCheckResult(true, null);
    }

    private static CollectionCheckResult CheckElementsNullability<TEnumerator>(TEnumerator enumerator)
        where TEnumerator : IEnumerator<TElement>
    {
        if (typeof(TElement).IsValueType && !TypeHelper.IsNullable(typeof(TElement)))
        {
            return CollectionCheckResult.FromNullability(false);
        }

        while (enumerator.MoveNext())
        {
            if (enumerator.Current is null)
            {
                return CollectionCheckResult.FromNullability(true);
            }
        }

        return CollectionCheckResult.FromNullability(false);
    }

    private static CollectionCheckResult CheckElementsTypeConsistency<TEnumerator>(TEnumerator enumerator)
        where TEnumerator : IEnumerator<TElement>
    {
        if (typeof(TElement).IsSealed)
        {
            return CollectionCheckResult.FromElementType(typeof(TElement));
        }
        Type? elementType = null;
        while (enumerator.MoveNext())
        {
            var element = enumerator.Current;
            if (element is null)
            {
                continue;
            }
            if (elementType is null)
            {
                elementType = element.GetType();
            }
            else if (elementType != element.GetType())
            {
                return CollectionCheckResult.FromElementType(null);
            }
        }

        return CollectionCheckResult.FromElementType(elementType ?? typeof(void));
    }

    private static CollectionCheckResult CheckElementsNullabilityAndTypeConsistency<TEnumerator>(TEnumerator enumerator)
        where TEnumerator : IEnumerator<TElement>
    {
        var hasNull = false;
        var hasDifferentType = false;
        if (typeof(TElement).IsSealed)
        {
            return CheckElementsNullability(enumerator);
        }

        Type? elementType = null;
        while (enumerator.MoveNext())
        {
            var element = enumerator.Current;
            if (element is null)
            {
                hasNull = true;
            }
            else
            {
                if (elementType is null)
                {
                    elementType = element.GetType();
                }
                else if (elementType != element.GetType())
                {
                    hasDifferentType = true;
                }
            }

            if (hasNull && hasDifferentType)
            {
                break;
            }
        }

        return new CollectionCheckResult(hasNull, hasDifferentType ? null : elementType);
    }

    private void WriteHeaderFlags(ref SerializationWriterRef writerRef, CollectionHeaderFlags flags)
    {
        if (_hasWrittenHeader)
        {
            Debug.Assert(_writtenCollectionFlags == flags);
            return;
        }

        _hasWrittenHeader = writerRef.WriteUInt8((byte)flags);
        _writtenCollectionFlags = flags;
    }

    private bool WriteTypeMeta(ref SerializationWriterRef writerRef)
    {
        if (!_hasInitializedTypeMetaSerializer)
        {
            _elementTypeMetaSerializer.Initialize(writerRef.InnerWriter.MetaStringContext);
            _hasInitializedTypeMetaSerializer = true;
        }
        return _elementTypeMetaSerializer.Write(ref writerRef, _elementRegistration!);
    }

    private void WriteCount(ref SerializationWriterRef writerRef, in TCollection collection)
    {
        if (_hasWrittenCount)
        {
            return;
        }

        var count = GetCount(in collection);
        _hasWrittenCount = writerRef.Write7BitEncodedUInt32((uint)count);
    }

    protected bool WriteElement(ref SerializationWriterRef writerRef, in TElement element)
    {
        ObjectMetaOption metaOption = default;
        if ((_writtenCollectionFlags & (CollectionHeaderFlags.TrackingRef | CollectionHeaderFlags.HasNull)) != 0)
        {
            metaOption |= ObjectMetaOption.ReferenceMeta;
        }
        if ((_writtenCollectionFlags & CollectionHeaderFlags.NotSameType) != 0)
        {
            metaOption |= ObjectMetaOption.TypeMeta;
        }
        return writerRef.Write(element, metaOption, _elementRegistration);
    }

    protected abstract bool WriteElements(ref SerializationWriterRef writerRef, in TCollection collection);

    protected abstract int GetCount(in TCollection collection);

    private void ThrowNotSupportedException_TCollectionNotSupported([CallerMemberName] string methodName = "")
    {
        throw new NotSupportedException($"The default implementation of {methodName} is not supported for {typeof(TCollection).Name}.");
    }

    [Flags]
    protected enum CollectionCheckOptions
    {
        Nullability = 0b1,
        TypeConsistency = 0b10,
    }

    protected readonly record struct CollectionCheckResult(bool HasNull, Type? ElementType)
    {
        public static CollectionCheckResult AllNullElements => new(true, null);

        public static CollectionCheckResult FromNullability(bool hasNull) => new(hasNull, null);

        public static CollectionCheckResult FromElementType(Type? elementType) => new(true, elementType);
    }
}

public abstract class CollectionDeserializer<TElement, TCollection>(TypeRegistration? elementRegistration = null) : AbstractDeserializer<TCollection>
    where TCollection : notnull
{
    private bool _hasReadCount;
    private CollectionHeaderFlags? _headerFlags;
    private bool _hasInitializedTypeMetaDeserializer;
    private readonly TypeMetaDeserializer _elementTypeMetaDeserializer = new();

    protected TCollection? Collection;
    private TypeRegistration? _elementRegistration = elementRegistration;
    private readonly bool _shouldResetElementRegistration = elementRegistration is null;

    public override object ReferenceableObject
    {
        get
        {
            if (typeof(TCollection).IsValueType)
            {
                ThrowNotSupportedException_ValueTypeNotSupported();
            }

            return Collection!;
        }
    }

    public override void Reset()
    {
        _hasReadCount = false;
        _headerFlags = null;
        _hasInitializedTypeMetaDeserializer = false;
        Collection = default;
        if (_shouldResetElementRegistration)
        {
            _elementRegistration = null;
        }
    }

    public sealed override ReadValueResult<TCollection> Deserialize(DeserializationReader reader)
    {
        var task = Deserialize(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public sealed override ValueTask<ReadValueResult<TCollection>> DeserializeAsync(DeserializationReader reader, CancellationToken cancellationToken = default)
    {
        return Deserialize(reader, true, cancellationToken);
    }

    private async ValueTask<ReadValueResult<TCollection>> Deserialize(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken = default)
    {
        if (_elementRegistration is null && typeof(TElement).IsSealed)
        {
            _elementRegistration = reader.TypeRegistry.GetTypeRegistration(typeof(TElement));
        }

        if (!_hasReadCount)
        {
            var countResult = await reader.Read7BitEncodedUint(isAsync, cancellationToken);
            if (!countResult.IsSuccess)
            {
                return ReadValueResult<TCollection>.Failed;
            }

            _hasReadCount = true;
            var count = (int)countResult.Value;
            CreateCollection(count);
        }
        Debug.Assert(Collection is not null);

        if (!await ReadHeader(reader, isAsync, cancellationToken))
        {
            return ReadValueResult<TCollection>.Failed;
        }

        bool fillSuccess;
        if (isAsync)
        {
            fillSuccess = await ReadElementsAsync(reader, cancellationToken);
        }
        else
        {
            // ReSharper disable once MethodHasAsyncOverloadWithCancellation
            fillSuccess = ReadElements(reader);
        }

        if (!fillSuccess)
        {
            return ReadValueResult<TCollection>.Failed;
        }
        return ReadValueResult<TCollection>.FromValue(Collection!);
    }

    private async ValueTask<bool> ReadHeader(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (!await ReadHeaderFlags(reader, isAsync, cancellationToken))
        {
            return false;
        }

        if ((_headerFlags!.Value & CollectionHeaderFlags.NotSameType) == 0)
        {
            if (!await ReadTypeMeta(reader, isAsync, cancellationToken))
            {
                return false;
            }
        }

        return true;
    }

    private async ValueTask<bool> ReadHeaderFlags(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_headerFlags is not null)
        {
            return true;
        }

        var readFlagsResult = await reader.ReadUInt8(isAsync, cancellationToken);
        if (!readFlagsResult.IsSuccess)
        {
            return false;
        }

        _headerFlags = (CollectionHeaderFlags)readFlagsResult.Value;
        return true;
    }

    private async ValueTask<bool> ReadTypeMeta(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (!_hasInitializedTypeMetaDeserializer)
        {
            _elementTypeMetaDeserializer.Initialize(reader.TypeRegistry, reader.MetaStringStorage, reader.MetaStringContext);
            _hasInitializedTypeMetaDeserializer = true;
        }

        var typeMetaResult = await _elementTypeMetaDeserializer.Read(reader, typeof(TElement), _elementRegistration, isAsync, cancellationToken);
        if (!typeMetaResult.IsSuccess)
        {
            return false;
        }

        _elementRegistration = typeMetaResult.Value;
        return true;
    }

    protected ReadValueResult<TElement?> ReadElement(DeserializationReader reader)
    {
        var task = ReadElement(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    protected ValueTask<ReadValueResult<TElement?>> ReadElementAsync(DeserializationReader reader, CancellationToken cancellationToken = default)
    {
        return ReadElement(reader, true, cancellationToken);
    }

    private protected async ValueTask<ReadValueResult<TElement?>> ReadElement(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_headerFlags is not { } headerFlags)
        {
            ThrowInvalidOperationException_HeaderNotRead();
            return ReadValueResult<TElement>.Failed;
        }

        ObjectMetaOption metaOption = default;
        if ((headerFlags & (CollectionHeaderFlags.TrackingRef | CollectionHeaderFlags.HasNull)) != 0)
        {
            metaOption |= ObjectMetaOption.ReferenceMeta;
        }
        if ((headerFlags & CollectionHeaderFlags.NotSameType) != 0)
        {
            metaOption |= ObjectMetaOption.TypeMeta;
        }
        return await reader.Read<TElement>(_elementRegistration, metaOption, isAsync, cancellationToken);
    }

    protected abstract bool ReadElements(DeserializationReader reader);

    protected abstract ValueTask<bool> ReadElementsAsync(DeserializationReader reader, CancellationToken cancellationToken);

    [MemberNotNull(nameof(Collection))]
    protected abstract void CreateCollection(int count);

    [DoesNotReturn]
    private static void ThrowInvalidOperationException_HeaderNotRead()
    {
        throw new InvalidOperationException(
            $"Header not read yet. Call {nameof(ReadElement)} in {nameof(ReadElements)} " + $"or {nameof(ReadElementAsync)} in {nameof(ReadElementsAsync)}."
        );
    }

    [DoesNotReturn]
    private static void ThrowNotSupportedException_ValueTypeNotSupported([CallerMemberName] string memberName = "")
    {
        throw new NotSupportedException(
            $"{memberName}'s default implementation is not supported when {nameof(TCollection)} is a value type: {typeof(TCollection).Name}."
        );
    }
}

#region Built-in

internal sealed class ListSerializer<TElement>(TypeRegistration? elementRegistration) : CollectionSerializer<TElement, List<TElement>>(elementRegistration)
{
    private int _currentIndex;

    public override void Reset()
    {
        base.Reset();
        _currentIndex = 0;
    }

    protected override int GetCount(in List<TElement> list) => list.Count;

    protected override bool WriteElements(ref SerializationWriterRef writerRef, in List<TElement> collection)
    {
#if NET5_0_OR_GREATER
        var elements = CollectionsMarshal.AsSpan(collection);
        for (; _currentIndex < elements.Length; _currentIndex++)
        {
            if (!WriteElement(ref writerRef, in elements[_currentIndex]))
            {
                return false;
            }
        }
#else
        for (; _currentIndex < collection.Count; _currentIndex++)
        {
            if (!WriteElement(ref writerRef, collection[_currentIndex]))
            {
                return false;
            }
        }
#endif
        return true;
    }

    protected override CollectionCheckResult CheckElementsState(in List<TElement> collection, CollectionCheckOptions options)
    {
        return base.CheckElementsState(collection.GetEnumerator(), options);
    }
}

internal sealed class ListDeserializer<TElement>(TypeRegistration? elementRegistration) : CollectionDeserializer<TElement, List<TElement?>>(elementRegistration)
{
    private int _count;
    private int _currentIndex;
    public override object ReferenceableObject => Collection!;

    public override void Reset()
    {
        base.Reset();
        _count = 0;
        _currentIndex = 0;
    }

    protected override void CreateCollection(int count)
    {
        _count = count;
        Collection = new List<TElement?>(count);
    }

    protected override bool ReadElements(DeserializationReader reader)
    {
#if NET8_0_OR_GREATER
        CollectionsMarshal.SetCount(Collection!, _count);
        var elements = CollectionsMarshal.AsSpan(Collection);
#else
        var elements = Collection!;
#endif
        for (; _currentIndex < _count; _currentIndex++)
        {
            var readResult = ReadElement(reader);
            if (!readResult.IsSuccess)
            {
                return false;
            }
#if NET8_0_OR_GREATER
            elements[_currentIndex] = readResult.Value;
#else
            elements.Add(readResult.Value);
#endif
        }

        return true;
    }

    protected override async ValueTask<bool> ReadElementsAsync(DeserializationReader reader, CancellationToken cancellationToken)
    {
        for (; _currentIndex < _count; _currentIndex++)
        {
            var readResult = await ReadElementAsync(reader, cancellationToken);
            if (!readResult.IsSuccess)
            {
                return false;
            }

            Collection!.Add(readResult.Value);
        }

        return true;
    }
}

internal sealed class ArraySerializer<TElement>(TypeRegistration? elementRegistration) : CollectionSerializer<TElement, TElement[]>(elementRegistration)
{
    private int _currentIndex;

    public override void Reset()
    {
        base.Reset();
        _currentIndex = 0;
    }

    protected override int GetCount(in TElement[] list) => list.Length;

    protected override bool WriteElements(ref SerializationWriterRef writerRef, in TElement[] collection)
    {
        for (; _currentIndex < collection.Length; _currentIndex++)
        {
            if (!WriteElement(ref writerRef, in collection[_currentIndex]))
            {
                return false;
            }
        }

        return true;
    }
}

internal sealed class ArrayDeserializer<TElement>(TypeRegistration? elementRegistration) : CollectionDeserializer<TElement, TElement?[]>(elementRegistration)
{
    private int _currentIndex;

    public override object ReferenceableObject => Collection!;

    public override void Reset()
    {
        base.Reset();
        _currentIndex = 0;
    }

    protected override void CreateCollection(int count) => Collection = new TElement[count];

    protected override bool ReadElements(DeserializationReader reader)
    {
        var task = ReadElements(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    protected override ValueTask<bool> ReadElementsAsync(DeserializationReader reader, CancellationToken cancellationToken)
    {
        return ReadElements(reader, true, cancellationToken);
    }

    private async ValueTask<bool> ReadElements(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        for (; _currentIndex < Collection!.Length; _currentIndex++)
        {
            var readResult = await ReadElement(reader, isAsync, cancellationToken);
            if (!readResult.IsSuccess)
            {
                return false;
            }

            Collection[_currentIndex] = readResult.Value;
        }
        return true;
    }
}

internal sealed class HashSetSerializer<TElement>(TypeRegistration? elementRegistration)
    : CollectionSerializer<TElement, HashSet<TElement>>(elementRegistration)
{
    private bool _hasGottenEnumerator;
    private HashSet<TElement>.Enumerator _enumerator;

    public override void Reset()
    {
        base.Reset();
        _hasGottenEnumerator = false;
    }

    protected override int GetCount(in HashSet<TElement> set) => set.Count;

    protected override bool WriteElements(ref SerializationWriterRef writerRef, in HashSet<TElement> collection)
    {
        var moveNextSuccess = true;
        if (!_hasGottenEnumerator)
        {
            _enumerator = collection.GetEnumerator();
            _hasGottenEnumerator = true;
            moveNextSuccess = _enumerator.MoveNext();
        }

        while (moveNextSuccess)
        {
            if (!WriteElement(ref writerRef, _enumerator.Current))
            {
                return false;
            }
            moveNextSuccess = _enumerator.MoveNext();
        }

        return true;
    }

    protected override CollectionCheckResult CheckElementsState(in HashSet<TElement> collection, CollectionCheckOptions options)
    {
        return base.CheckElementsState(collection.GetEnumerator(), options);
    }
}

internal sealed class HashSetDeserializer<TElement>(TypeRegistration? elementRegistration)
    : CollectionDeserializer<TElement, HashSet<TElement?>>(elementRegistration)
{
    private int _count;

    public override object ReferenceableObject => Collection!;

    public override void Reset()
    {
        base.Reset();
        _count = 0;
    }

    protected override void CreateCollection(int count)
    {
        _count = count;
#if NETSTANDARD2_0
        Collection = [];
#else
        Collection = new HashSet<TElement?>(count);
#endif
    }

    protected override bool ReadElements(DeserializationReader reader)
    {
        var task = ReadElements(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    protected override ValueTask<bool> ReadElementsAsync(DeserializationReader reader, CancellationToken cancellationToken)
    {
        return ReadElements(reader, true, cancellationToken);
    }

    private async ValueTask<bool> ReadElements(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        while (Collection!.Count < _count)
        {
            var readResult = await ReadElement(reader, isAsync, cancellationToken);
            if (!readResult.IsSuccess)
            {
                return false;
            }

            Collection.Add(readResult.Value);
        }

        return true;
    }
}

#endregion
