using System;
using System.Diagnostics;
using System.IO.Pipelines;
using Fury.Collections;
using Fury.Meta;
using Fury.Serialization;
using Fury.Serialization.Meta;
using JetBrains.Annotations;

namespace Fury.Context;

public sealed class SerializationWriter : IDisposable
{
    private sealed class Frame
    {
        public bool NeedNotifyWriteValueCompleted;
        public TypeRegistration? Registration;
        public ISerializer? Serializer;

        public void Reset()
        {
            Debug.Assert(Registration is not null || Serializer is null);
            if (Registration is not null && Serializer is not null)
            {
                Registration.ReturnSerializer(Serializer);
            }
            NeedNotifyWriteValueCompleted = false;
            Registration = null;
            Serializer = null;
        }
    }

    public SerializationConfig Config { get; private set; } = SerializationConfig.Default;
    private readonly BatchWriter _innerWriter = new();

    public TypeRegistry TypeRegistry { get; }

    private readonly HeaderSerializer _headerSerializer = new();
    private readonly ReferenceMetaSerializer _referenceMetaSerializer = new();
    private readonly TypeMetaSerializer _typeMetaSerializer;

    internal AutoIncrementIdDictionary<MetaString> MetaStringContext { get; } = new();
    private readonly FrameStack<Frame> _frameStack = new();

    public SerializationWriterRef ByrefWriter => new(this, _innerWriter);

    internal SerializationWriter(TypeRegistry registry)
    {
        TypeRegistry = registry;
        _typeMetaSerializer = new TypeMetaSerializer();
        _typeMetaSerializer.Initialize(MetaStringContext);
    }

    internal void Reset()
    {
        _innerWriter.Reset();
        _headerSerializer.Reset();
        ResetCurrent();
        foreach (var frame in _frameStack.Frames)
        {
            frame.Reset();
        }
        _frameStack.Reset();
    }

    private void ResetCurrent()
    {
        _referenceMetaSerializer.ResetCurrent();
        _typeMetaSerializer.Reset();
    }

    internal void Initialize(PipeWriter pipeWriter, SerializationConfig config)
    {
        Config = config;
        _innerWriter.Initialize(pipeWriter);
        _referenceMetaSerializer.Initialize(config.ReferenceTracking);
    }

    public void Dispose()
    {
        _innerWriter.Dispose();
        TypeRegistry.Dispose();
    }

    private void OnCurrentSerializationCompleted(bool isSuccess)
    {
        if (isSuccess)
        {
            ResetCurrent();
            _frameStack.PopFrame().Reset();
        }
        else
        {
            _frameStack.MoveLast();
        }
    }

    internal bool WriteHeader(bool rootObjectIsNull)
    {
        var writerRef = ByrefWriter;
        return _headerSerializer.Write(ref writerRef, rootObjectIsNull);
    }

    [MustUseReturnValue]
    public bool Write<TTarget>(in TTarget? value, TypeRegistration? registrationHint = null)
    {
        return Write(in value, ObjectMetaOption.ReferenceMeta | ObjectMetaOption.TypeMeta, registrationHint);
    }

    [MustUseReturnValue]
    internal bool Write<TTarget>(in TTarget? value, ObjectMetaOption metaOption, TypeRegistration? registrationHint = null)
    {
        _frameStack.MoveNext();
        var currentFrame = _frameStack.CurrentFrame;
        var needWriteMeta = _frameStack.IsCurrentTheLastFrame;
        var isSuccess = false;
        try
        {
            var writer = ByrefWriter;
            if (needWriteMeta)
            {
                isSuccess = WriteMeta(currentFrame, ref writer, in value, metaOption, registrationHint, out var needWriteValue);
                if (!isSuccess)
                {
                    return false;
                }
                if (!needWriteValue)
                {
                    return true;
                }
            }
            isSuccess = WriteValue(currentFrame, in value);
        }
        finally
        {
            OnCurrentSerializationCompleted(isSuccess);
        }
        return isSuccess;
    }

    [MustUseReturnValue]
    public bool WriteNullable<TTarget>(in TTarget? value, TypeRegistration? registrationHint = null)
        where TTarget : struct
    {
        return WriteNullable(in value, ObjectMetaOption.ReferenceMeta | ObjectMetaOption.TypeMeta, registrationHint);
    }

    [MustUseReturnValue]
    internal bool WriteNullable<TTarget>(in TTarget? value, ObjectMetaOption metaOption, TypeRegistration? registrationHint = null)
        where TTarget : struct
    {
        _frameStack.MoveNext();
        var currentFrame = _frameStack.CurrentFrame;
        var needWriteMeta = _frameStack.IsCurrentTheLastFrame;
        var isSuccess = false;
        try
        {
            var writerRef = ByrefWriter;
            if (needWriteMeta)
            {
                isSuccess = WriteMeta(currentFrame, ref writerRef, in value, metaOption, registrationHint, out var needWriteValue);
                if (!isSuccess)
                {
                    return false;
                }
                if (!needWriteValue)
                {
                    return true;
                }
            }
#if NET7_0_OR_GREATER
            ref readonly var valueRef = ref Nullable.GetValueRefOrDefaultRef(in value);
            isSuccess = WriteValue(currentFrame, in valueRef);
#else
            isSuccess = WriteValue(currentFrame, value!.Value);
#endif
        }
        finally
        {
            OnCurrentSerializationCompleted(isSuccess);
        }
        return isSuccess;
    }

    private bool WriteMeta<TTarget>(
        Frame currentFrame,
        ref SerializationWriterRef writerRef,
        in TTarget? value,
        ObjectMetaOption metaOption,
        TypeRegistration? registrationHint,
        out bool needWriteValue
    )
    {
        if ((metaOption & ObjectMetaOption.ReferenceMeta) != 0)
        {
            if (!WriteRefMeta(currentFrame, ref writerRef, in value, out needWriteValue))
            {
                return false;
            }
        }
        else
        {
            needWriteValue = true;
        }
        PopulateTypeRegistrationToCurrentFrame(in value, registrationHint);
        if ((metaOption & ObjectMetaOption.TypeMeta) != 0)
        {
            if (!WriteTypeMeta(ref writerRef))
            {
                return false;
            }
        }

        return true;
    }

    private bool WriteRefMeta<TTarget>(Frame currentFrame, ref SerializationWriterRef writerRef, in TTarget? value, out bool needWriteValue)
    {
        var writeMetaSuccess = _referenceMetaSerializer.Write(ref writerRef, in value, out var writtenFlag);
        if (!writeMetaSuccess)
        {
            needWriteValue = false;
            return false;
        }

        needWriteValue = writtenFlag is RefFlag.RefValue or RefFlag.NotNullValue;
        currentFrame.NeedNotifyWriteValueCompleted = writtenFlag is RefFlag.RefValue;
        return true;
    }

    private void PopulateTypeRegistrationToCurrentFrame<TTarget>(in TTarget value, TypeRegistration? registrationHint)
    {
        var currentFrame = _frameStack.CurrentFrame;
        if (currentFrame.Registration is null)
        {
            var desiredType = value!.GetType();
            if (registrationHint?.TargetType != desiredType)
            {
                Debug.WriteLine("Type registration hint does not match the actual type.");
                registrationHint = null;
            }
            currentFrame.Registration = registrationHint ?? TypeRegistry.GetTypeRegistration(desiredType);
        }
        Debug.Assert(currentFrame.Registration.TargetType == value!.GetType());
    }

    private bool WriteTypeMeta(ref SerializationWriterRef writerRef)
    {
        var currentFrame = _frameStack.CurrentFrame;
        Debug.Assert(currentFrame is { Registration: not null });
        return _typeMetaSerializer.Write(ref writerRef, currentFrame.Registration!);
    }

    [MustUseReturnValue]
    private bool WriteValue<TTarget>(Frame currentFrame, in TTarget value)
    {
        Debug.Assert(currentFrame.Registration is not null);
        currentFrame.Serializer ??= currentFrame.Registration!.RentSerializer();

        bool success;

        var serializer = currentFrame.Serializer;
        if (serializer is ISerializer<TTarget> typedSerializer)
        {
            success = typedSerializer.Serialize(this, in value);
        }
        else
        {
            success = serializer.Serialize(this, value!);
        }

        if (success && currentFrame.NeedNotifyWriteValueCompleted)
        {
            _referenceMetaSerializer.HandleWriteValueCompleted(in value);
        }

        return success;
    }

    #region Write Methods

    [MustUseReturnValue]
    internal bool WriteUnmanaged<T>(T value)
        where T : unmanaged => ByrefWriter.WriteUnmanaged(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteBytes"/>
    [MustUseReturnValue]
    public int WriteBytes(scoped ReadOnlySpan<byte> bytes) => ByrefWriter.WriteBytes(bytes);

    /// <inheritdoc cref="SerializationWriterRef.WriteUInt8"/>
    [MustUseReturnValue]
    public bool WriteUInt8(byte value) => ByrefWriter.WriteUInt8(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteInt8"/>
    [MustUseReturnValue]
    public bool WriteInt8(sbyte value) => ByrefWriter.WriteInt8(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteUInt16"/>
    [MustUseReturnValue]
    public bool WriteUInt16(ushort value) => ByrefWriter.WriteUInt16(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteInt16"/>
    [MustUseReturnValue]
    public bool WriteInt16(short value) => ByrefWriter.WriteInt16(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteUInt32"/>
    [MustUseReturnValue]
    public bool WriteUInt32(uint value) => ByrefWriter.WriteUInt32(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteInt32"/>
    [MustUseReturnValue]
    public bool WriteInt32(int value) => ByrefWriter.WriteInt32(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteInt64"/>
    [MustUseReturnValue]
    public bool WriteInt64(ulong value) => ByrefWriter.WriteInt64(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteUInt64"/>
    [MustUseReturnValue]
    public bool WriteUInt64(long value) => ByrefWriter.WriteUInt64(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteFloat32"/>
    [MustUseReturnValue]
    public bool WriteFloat32(float value) => ByrefWriter.WriteFloat32(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteFloat64"/>
    [MustUseReturnValue]
    public bool WriteFloat64(double value) => ByrefWriter.WriteFloat64(value);

    /// <inheritdoc cref="SerializationWriterRef.WriteBool"/>
    [MustUseReturnValue]
    public bool WriteBool(bool value) => ByrefWriter.WriteBool(value);

    /// <inheritdoc cref="SerializationWriterRef.Write7BitEncodedInt32"/>
    [MustUseReturnValue]
    public bool Write7BitEncodedInt32(int value) => ByrefWriter.Write7BitEncodedInt32(value);

    /// <inheritdoc cref="SerializationWriterRef.Write7BitEncodedUInt32"/>
    [MustUseReturnValue]
    public bool Write7BitEncodedUInt32(uint value) => ByrefWriter.Write7BitEncodedUInt32(value);

    /// <inheritdoc cref="SerializationWriterRef.Write7BitEncodedInt64"/>
    [MustUseReturnValue]
    public bool Write7BitEncodedInt64(long value) => ByrefWriter.Write7BitEncodedInt64(value);

    /// <inheritdoc cref="SerializationWriterRef.Write7BitEncodedUInt64"/>
    [MustUseReturnValue]
    public bool Write7BitEncodedUInt64(ulong value) => ByrefWriter.Write7BitEncodedUInt64(value);
    #endregion
}
