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

    public virtual T DefaultValue => default!;

    internal object? DefaultObject => DefaultValue;

    public abstract void WriteData(WriteContext context, in T value, bool hasGenerics);

    public abstract T ReadData(ReadContext context);

    public virtual void Write(WriteContext context, in T value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        if (refMode != RefMode.None)
        {
            bool wroteTrackingRefFlag = false;
            if (refMode == RefMode.Tracking &&
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
                if (value is null)
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    return;
                }

                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
            }
        }

        if (writeTypeInfo)
        {
            this.WriteTypeInfo(context);
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
                        this.ReadTypeInfo(context);
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
            this.ReadTypeInfo(context);
        }

        return ReadData(context);
    }
}
