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

public abstract class Serializer
{
    public abstract Type Type { get; }

    public abstract TypeId StaticTypeId { get; }

    public abstract bool IsNullableType { get; }

    public abstract bool IsReferenceTrackableType { get; }

    public abstract object? DefaultObject { get; }

    public abstract bool IsNoneObject(object? value);

    public abstract void WriteDataObject(ref WriteContext context, object? value, bool hasGenerics);

    public abstract object? ReadDataObject(ref ReadContext context);

    public abstract void WriteObject(ref WriteContext context, object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics);

    public abstract object? ReadObject(ref ReadContext context, RefMode refMode, bool readTypeInfo);

    public abstract void WriteTypeInfo(ref WriteContext context);

    public abstract void ReadTypeInfo(ref ReadContext context);

    public abstract IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef);

    public abstract Serializer<T> RequireSerializer<T>();
}

public abstract class Serializer<T> : Serializer
{
    public override Type Type => typeof(T);

    public abstract override TypeId StaticTypeId { get; }

    public override bool IsNullableType => false;

    public override bool IsReferenceTrackableType => false;

    public virtual T DefaultValue => default!;

    public override object? DefaultObject => DefaultValue;

    public virtual bool IsNone(in T value)
    {
        _ = value;
        return false;
    }

    public abstract void WriteData(ref WriteContext context, in T value, bool hasGenerics);

    public abstract T ReadData(ref ReadContext context);

    public virtual void Write(ref WriteContext context, in T value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        if (refMode != RefMode.None)
        {
            bool wroteTrackingRefFlag = false;
            if (refMode == RefMode.Tracking &&
                IsReferenceTrackableType &&
                value is object obj)
            {
                if (context.RefWriter.TryWriteReference(context.Writer, obj))
                {
                    return;
                }

                wroteTrackingRefFlag = true;
            }

            if (!wroteTrackingRefFlag)
            {
                if (IsNullableType && IsNone(value))
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    return;
                }

                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
            }
        }

        if (writeTypeInfo)
        {
            WriteTypeInfo(ref context);
        }

        WriteData(ref context, value, hasGenerics);
    }

    public virtual T Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        if (refMode != RefMode.None)
        {
            sbyte rawFlag = context.Reader.ReadInt8();
            RefFlag flag = (RefFlag)rawFlag;
            switch (flag)
            {
                case RefFlag.Null:
                    return DefaultValue;
                case RefFlag.Ref:
                {
                    uint refId = context.Reader.ReadVarUInt32();
                    return context.RefReader.ReadRef<T>(refId);
                }
                case RefFlag.RefValue:
                {
                    uint reservedRefId = context.RefReader.ReserveRefId();
                    context.PushPendingReference(reservedRefId);
                    if (readTypeInfo)
                    {
                        ReadTypeInfo(ref context);
                    }

                    T value = ReadData(ref context);
                    context.FinishPendingReferenceIfNeeded(value);
                    context.PopPendingReference();
                    return value;
                }
                case RefFlag.NotNullValue:
                    break;
                default:
                    throw new RefException($"invalid ref flag {rawFlag}");
            }
        }

        if (readTypeInfo)
        {
            ReadTypeInfo(ref context);
        }

        return ReadData(ref context);
    }

    public override void WriteTypeInfo(ref WriteContext context)
    {
        context.TypeResolver.WriteTypeInfo(Type, this, ref context);
    }

    public override void ReadTypeInfo(ref ReadContext context)
    {
        context.TypeResolver.ReadTypeInfo(Type, this, ref context);
    }

    public override IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        _ = trackRef;
        return [];
    }

    public override bool IsNoneObject(object? value)
    {
        if (value is null)
        {
            return IsNullableType;
        }

        return value is T typed && IsNone(typed);
    }

    public override void WriteDataObject(ref WriteContext context, object? value, bool hasGenerics)
    {
        WriteData(ref context, CoerceValue(value), hasGenerics);
    }

    public override object? ReadDataObject(ref ReadContext context)
    {
        return ReadData(ref context);
    }

    public override void WriteObject(ref WriteContext context, object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        Write(ref context, CoerceValue(value), refMode, writeTypeInfo, hasGenerics);
    }

    public override object? ReadObject(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return Read(ref context, refMode, readTypeInfo);
    }

    public override Serializer<TCast> RequireSerializer<TCast>()
    {
        if (typeof(TCast) == typeof(T))
        {
            return (Serializer<TCast>)(object)this;
        }

        throw new InvalidDataException($"serializer type mismatch for {typeof(TCast)}");
    }

    protected virtual T CoerceValue(object? value)
    {
        if (value is T typed)
        {
            return typed;
        }

        if (value is null && IsNullableType)
        {
            return DefaultValue;
        }

        throw new InvalidDataException(
            $"serializer {GetType().Name} expected value of type {typeof(T)}, got {value?.GetType()}");
    }
}
