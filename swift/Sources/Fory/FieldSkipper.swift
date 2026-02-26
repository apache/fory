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

import Foundation

public enum FieldSkipper {
    public static func skipFieldValue(
        context: ReadContext,
        fieldType: TypeMetaFieldType
    ) throws {
        _ = try readFieldValue(context: context, fieldType: fieldType)
    }

    private static func readEnumOrdinal(
        _ context: ReadContext,
        refMode: RefMode
    ) throws -> UInt32? {
        switch refMode {
        case .none:
            return try context.buffer.readVarUInt32()
        case .nullOnly:
            let flag = try context.buffer.readInt8()
            if flag == RefFlag.null.rawValue {
                return nil
            }
            if flag != RefFlag.notNullValue.rawValue {
                throw ForyError.invalidData("unexpected enum nullOnly flag \(flag)")
            }
            return try context.buffer.readVarUInt32()
        case .tracking:
            throw ForyError.invalidData("enum tracking ref mode is not supported")
        }
    }

    private static func readFieldValue(
        context: ReadContext,
        fieldType: TypeMetaFieldType
    ) throws -> Any? {
        let refMode = RefMode.from(nullable: fieldType.nullable, trackRef: fieldType.trackRef)
        switch fieldType.typeID {
        case TypeId.bool.rawValue:
            return fieldType.nullable
                ? try Bool?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try Bool.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.int8.rawValue:
            return fieldType.nullable
                ? try Int8?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try Int8.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.int16.rawValue:
            return fieldType.nullable
                ? try Int16?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try Int16.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.varint32.rawValue:
            return fieldType.nullable
                ? try Int32?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try Int32.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.varint64.rawValue:
            return fieldType.nullable
                ? try Int64?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try Int64.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.float32.rawValue:
            return fieldType.nullable
                ? try Float?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try Float.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.float64.rawValue:
            return fieldType.nullable
                ? try Double?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try Double.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.string.rawValue:
            return fieldType.nullable
                ? try String?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try String.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.timestamp.rawValue:
            return fieldType.nullable
                ? try Date?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try Date.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.date.rawValue:
            return fieldType.nullable
                ? try ForyDate?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try ForyDate.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.list.rawValue:
            guard fieldType.generics.count == 1,
                  fieldType.generics[0].typeID == TypeId.string.rawValue else {
                throw ForyError.invalidData("unsupported compatible list element type")
            }
            return fieldType.nullable
                ? try [String]?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try [String].foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.set.rawValue:
            guard fieldType.generics.count == 1,
                  fieldType.generics[0].typeID == TypeId.string.rawValue else {
                throw ForyError.invalidData("unsupported compatible set element type")
            }
            return fieldType.nullable
                ? try Set<String>?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try Set<String>.foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.map.rawValue:
            guard fieldType.generics.count == 2,
                  fieldType.generics[0].typeID == TypeId.string.rawValue,
                  fieldType.generics[1].typeID == TypeId.string.rawValue else {
                throw ForyError.invalidData("unsupported compatible map key/value type")
            }
            return fieldType.nullable
                ? try [String: String]?.foryRead(context, refMode: refMode, readTypeInfo: false)
                : try [String: String].foryRead(context, refMode: refMode, readTypeInfo: false)
        case TypeId.enumType.rawValue:
            return try readEnumOrdinal(context, refMode: refMode)
        default:
            throw ForyError.invalidData("unsupported compatible field type id \(fieldType.typeID)")
        }
    }
}
