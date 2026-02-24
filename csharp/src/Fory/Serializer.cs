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

public abstract class Serializer<T>
{
    public Type Type => typeof(T);

    public abstract TypeId StaticTypeId { get; }

    public virtual bool IsNullableType => false;

    public virtual bool IsReferenceTrackableType => false;

    public virtual T DefaultValue => default!;

    internal object? DefaultObject => DefaultValue;

    public virtual bool IsNone(in T value)
    {
        _ = value;
        return false;
    }

    public abstract void WriteData(WriteContext context, in T value, bool hasGenerics);

    public abstract T ReadData(ReadContext context);

    public virtual void Write(WriteContext context, in T value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
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
            WriteTypeInfo(context);
        }

        WriteData(context, value, hasGenerics);
    }

    public virtual T Read(ReadContext context, RefMode refMode, bool readTypeInfo)
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
                    context.RefReader.PushPendingReference(reservedRefId);
                    if (readTypeInfo)
                    {
                        ReadTypeInfo(context);
                    }

                    T value = ReadData(context);
                    context.RefReader.FinishPendingReferenceIfNeeded(value);
                    context.RefReader.PopPendingReference();
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
            ReadTypeInfo(context);
        }

        return ReadData(context);
    }

    public virtual void WriteTypeInfo(WriteContext context)
    {
        context.TypeResolver.WriteTypeInfo(this, context);
    }

    public virtual void ReadTypeInfo(ReadContext context)
    {
        context.TypeResolver.ReadTypeInfo(this, context);
    }

    public virtual IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        _ = trackRef;
        return [];
    }

    internal bool IsNoneObject(object? value)
    {
        if (value is null)
        {
            return IsNullableType;
        }

        return value is T typed && IsNone(typed);
    }

    internal void WriteDataObject(WriteContext context, object? value, bool hasGenerics)
    {
        WriteData(context, CoerceValue(value), hasGenerics);
    }

    internal object? ReadDataObject(ReadContext context)
    {
        return ReadData(context);
    }

    internal void WriteObject(WriteContext context, object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        Write(context, CoerceValue(value), refMode, writeTypeInfo, hasGenerics);
    }

    internal object? ReadObject(ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return Read(context, refMode, readTypeInfo);
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
