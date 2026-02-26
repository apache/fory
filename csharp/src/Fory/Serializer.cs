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
/// Base class for custom serializers.
/// </summary>
/// <typeparam name="T">Runtime value type handled by this serializer.</typeparam>
public abstract class Serializer<T>
{
    /// <summary>
    /// Gets the default value returned when a null marker is read for this serializer.
    /// </summary>
    public virtual T DefaultValue => default!;

    internal object? DefaultObject => DefaultValue;

    /// <summary>
    /// Writes the serializer-specific payload body.
    /// </summary>
    /// <param name="context">Write context.</param>
    /// <param name="value">Value to encode.</param>
    /// <param name="hasGenerics">Whether generic type metadata is present for the current field path.</param>
    public abstract void WriteData(WriteContext context, in T value, bool hasGenerics);

    /// <summary>
    /// Reads the serializer-specific payload body.
    /// </summary>
    /// <param name="context">Read context.</param>
    /// <returns>Decoded value.</returns>
    public abstract T ReadData(ReadContext context);

    /// <summary>
    /// Writes reference metadata and optional type metadata, then delegates to <see cref="WriteData"/>.
    /// </summary>
    /// <param name="context">Write context.</param>
    /// <param name="value">Value to write.</param>
    /// <param name="refMode">Reference handling mode.</param>
    /// <param name="writeTypeInfo">Whether type metadata should be written.</param>
    /// <param name="hasGenerics">Whether generic type metadata is present for the current field path.</param>
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
            context.TypeResolver.WriteTypeInfo(this, context);
        }

        WriteData(context, value, hasGenerics);
    }

    /// <summary>
    /// Reads reference metadata and optional type metadata, then delegates to <see cref="ReadData"/>.
    /// </summary>
    /// <param name="context">Read context.</param>
    /// <param name="refMode">Reference handling mode.</param>
    /// <param name="readTypeInfo">Whether type metadata should be read.</param>
    /// <returns>Decoded value.</returns>
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
                            context.TypeResolver.ReadTypeInfo(this, context);
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
            context.TypeResolver.ReadTypeInfo(this, context);
        }

        return ReadData(context);
    }
}
