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

using System.Linq.Expressions;
using System.Reflection;

namespace Apache.Fory;

public readonly struct UnionSerializer<TUnion> : IStaticSerializer<UnionSerializer<TUnion>, TUnion>
    where TUnion : Union
{
    private static readonly Func<int, object?, TUnion> Factory = BuildFactory();

    public static TypeId StaticTypeId => TypeId.TypedUnion;

    public static bool IsNullableType => true;

    public static bool IsReferenceTrackableType => true;

    public static TUnion DefaultValue => null!;

    public static bool IsNone(in TUnion value)
    {
        return value is null;
    }

    public static void WriteData(ref WriteContext context, in TUnion value, bool hasGenerics)
    {
        _ = hasGenerics;
        if (value is null)
        {
            throw new ForyInvalidDataException("union value is null");
        }

        context.Writer.WriteVarUInt32((uint)value.Index);
        DynamicAnyCodec.WriteAny(ref context, value.Value, RefMode.Tracking, true, false);
    }

    public static TUnion ReadData(ref ReadContext context)
    {
        uint rawCaseId = context.Reader.ReadVarUInt32();
        if (rawCaseId > int.MaxValue)
        {
            throw new ForyInvalidDataException($"union case id out of range: {rawCaseId}");
        }

        object? caseValue = DynamicAnyCodec.ReadAny(ref context, RefMode.Tracking, true);
        return Factory((int)rawCaseId, caseValue);
    }

    private static Func<int, object?, TUnion> BuildFactory()
    {
        if (typeof(TUnion) == typeof(Union))
        {
            return (index, value) => (TUnion)(object)new Union(index, value);
        }

        ConstructorInfo? ctor = typeof(TUnion).GetConstructor(
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
            binder: null,
            [typeof(int), typeof(object)],
            modifiers: null);
        if (ctor is not null)
        {
            ParameterExpression indexParam = Expression.Parameter(typeof(int), "index");
            ParameterExpression valueParam = Expression.Parameter(typeof(object), "value");
            NewExpression created = Expression.New(ctor, indexParam, valueParam);
            return Expression.Lambda<Func<int, object?, TUnion>>(created, indexParam, valueParam).Compile();
        }

        MethodInfo? ofFactory = typeof(TUnion).GetMethod(
            "Of",
            BindingFlags.Public | BindingFlags.Static,
            binder: null,
            [typeof(int), typeof(object)],
            modifiers: null);
        if (ofFactory is not null && typeof(TUnion).IsAssignableFrom(ofFactory.ReturnType))
        {
            return (index, value) => (TUnion)ofFactory.Invoke(null, [index, value])!;
        }

        throw new ForyInvalidDataException(
            $"union type {typeof(TUnion)} must define (int, object) constructor or static Of(int, object)");
    }
}
