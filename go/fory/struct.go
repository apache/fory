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

package fory

import (
    "encoding/binary"
    "errors"
    "fmt"
    "math"
    "reflect"
    "sort"
    "strings"
    "unicode"
    "unicode/utf8"
    "unsafe"

    "github.com/spaolacci/murmur3"
)

// FieldInfo stores field metadata computed ENTIRELY at init time.
// All flags and decisions are pre-computed to eliminate runtime checks.
type FieldInfo struct {
    Name         string
    Offset       uintptr
    Type         reflect.Type
    StaticId     StaticTypeId
    TypeId       TypeId // Fory type ID for the serializer
    Serializer   Serializer
    Referencable bool
    FieldIndex   int      // -1 if field doesn't exist in current struct (for compatible mode)
    FieldDef     FieldDef // original FieldDef from remote TypeDef (for compatible mode skip)

    // Pre-computed sizes and offsets (for fixed primitives)
    FixedSize   int // 0 if not fixed-size, else 1/2/4/8
    WriteOffset int // Offset within fixed-fields buffer region (sum of preceding field sizes)

    // Pre-computed flags for serialization (computed at init time)
    RefMode     RefMode // ref mode for serializer.Write/Read
    WriteType   bool    // whether to write type info (true for struct fields in compatible mode)
    HasGenerics bool    // whether element types are known from TypeDef (for container fields)

    // Tag-based configuration (from fory struct tags)
    TagID          int  // -1 = use field name, >=0 = use tag ID
    HasForyTag     bool // Whether field has explicit fory tag
    TagRefSet      bool // Whether ref was explicitly set via fory tag
    TagRef         bool // The ref value from fory tag (only valid if TagRefSet is true)
    TagNullableSet bool // Whether nullable was explicitly set via fory tag
    TagNullable    bool // The nullable value from fory tag (only valid if TagNullableSet is true)
}

// fieldHasNonPrimitiveSerializer returns true if the field has a serializer with a non-primitive type ID.
// This is used to skip the fast path for fields like enums where StaticId is int32 but the serializer
// writes a different format (e.g., unsigned varint for enum ordinals vs signed zigzag for int32).
func fieldHasNonPrimitiveSerializer(field *FieldInfo) bool {
    if field.Serializer == nil {
        return false
    }
    // ENUM (numeric ID), NAMED_ENUM (namespace/typename), NAMED_STRUCT, NAMED_COMPATIBLE_STRUCT, NAMED_EXT
    // all require special serialization and should not use the primitive fast path
    // Note: ENUM uses unsigned Varuint32Small7 for ordinals, not signed zigzag varint
    // Use internal type ID (low 8 bits) since registered types have composite TypeIds like (userID << 8) | internalID
    internalTypeId := TypeId(field.TypeId & 0xFF)
    switch internalTypeId {
    case ENUM, NAMED_ENUM, NAMED_STRUCT, NAMED_COMPATIBLE_STRUCT, NAMED_EXT:
        return true
    default:
        return false
    }
}

// isEnumField checks if a field is an enum type based on its TypeId
func isEnumField(field *FieldInfo) bool {
    if field.Serializer == nil {
        return false
    }
    internalTypeId := field.TypeId & 0xFF
    return internalTypeId == ENUM || internalTypeId == NAMED_ENUM
}

// writeEnumField writes an enum field respecting the field's RefMode.
// Java writes enum ordinals as unsigned Varuint32Small7, not signed zigzag.
// RefMode determines whether null flag is written, regardless of whether the local type is a pointer.
// This is important for compatible mode where remote TypeDef's nullable flag controls the wire format.
func writeEnumField(ctx *WriteContext, field *FieldInfo, fieldValue reflect.Value) {
    buf := ctx.Buffer()
    isPointer := fieldValue.Kind() == reflect.Ptr

    // Write null flag based on RefMode only (not based on whether local type is pointer)
    if field.RefMode != RefModeNone {
        if isPointer && fieldValue.IsNil() {
            buf.WriteInt8(NullFlag)
            return
        }
        buf.WriteInt8(NotNullValueFlag)
    }

    // Get the actual value to serialize
    targetValue := fieldValue
    if isPointer {
        if fieldValue.IsNil() {
            // RefModeNone but nil pointer - this is a protocol error in schema-consistent mode
            // Write zero value as fallback
            targetValue = reflect.Zero(field.Type.Elem())
        } else {
            targetValue = fieldValue.Elem()
        }
    }

    // For pointer enum fields, the serializer is ptrToValueSerializer wrapping enumSerializer.
    // We need to call the inner enumSerializer directly with the dereferenced value.
    if ptrSer, ok := field.Serializer.(*ptrToValueSerializer); ok {
        ptrSer.valueSerializer.WriteData(ctx, targetValue)
    } else {
        field.Serializer.WriteData(ctx, targetValue)
    }
}

// readEnumField reads an enum field respecting the field's RefMode.
// RefMode determines whether null flag is read, regardless of whether the local type is a pointer.
// This is important for compatible mode where remote TypeDef's nullable flag controls the wire format.
// Uses context error state for deferred error checking.
func readEnumField(ctx *ReadContext, field *FieldInfo, fieldValue reflect.Value) {
    buf := ctx.Buffer()
    isPointer := fieldValue.Kind() == reflect.Ptr

    // Read null flag based on RefMode only (not based on whether local type is pointer)
    if field.RefMode != RefModeNone {
        nullFlag := buf.ReadInt8(ctx.Err())
        if nullFlag == NullFlag {
            // For pointer enum fields, leave as nil; for non-pointer, set to zero
            if !isPointer {
                fieldValue.SetInt(0)
            }
            return
        }
    }

    // For pointer enum fields, allocate a new value
    targetValue := fieldValue
    if isPointer {
        newVal := reflect.New(field.Type.Elem())
        fieldValue.Set(newVal)
        targetValue = newVal.Elem()
    }

    // For pointer enum fields, the serializer is ptrToValueSerializer wrapping enumSerializer.
    // We need to call the inner enumSerializer directly with the dereferenced value.
    if ptrSer, ok := field.Serializer.(*ptrToValueSerializer); ok {
        ptrSer.valueSerializer.ReadData(ctx, field.Type.Elem(), targetValue)
    } else {
        field.Serializer.ReadData(ctx, field.Type, targetValue)
    }
}

type structSerializer struct {
    // Identity
    typeTag    string
    type_      reflect.Type
    structHash int32

    // Pre-sorted field lists by category (computed at init)
    fixedFields     []*FieldInfo // fixed-size primitives (bool, int8, int16, float32, float64)
    varintFields    []*FieldInfo // varint primitives (int32, int64, int)
    remainingFields []*FieldInfo // all other fields (string, slice, map, struct, etc.)

    // All fields in protocol order (for compatible mode)
    fields    []*FieldInfo          // all fields in sorted order
    fieldMap  map[string]*FieldInfo // for compatible reading
    fieldDefs []FieldDef            // for type_def compatibility

    // Pre-computed buffer sizes
    fixedSize     int // Total bytes for fixed-size primitives
    maxVarintSize int // Max bytes for varints (5 per int32, 10 per int64)

    // Mode flags (set at init)
    isCompatibleMode bool // true when compatible=true
    typeDefDiffers   bool // true when compatible=true AND remote TypeDef != local (requires ordered read)

    // Initialization state
    initialized bool
}

// newStructSerializer creates a new structSerializer with the given parameters.
// typeTag can be empty and will be derived from type_.Name() if not provided.
// fieldDefs can be nil for local structs without remote schema.
func newStructSerializer(type_ reflect.Type, typeTag string, fieldDefs []FieldDef) *structSerializer {
    if typeTag == "" && type_ != nil {
        typeTag = type_.Name()
    }
    return &structSerializer{
        type_:     type_,
        typeTag:   typeTag,
        fieldDefs: fieldDefs,
    }
}

// initialize performs eager initialization of the struct serializer.
// This should be called at registration time to pre-compute all field metadata.
func (s *structSerializer) initialize(typeResolver *TypeResolver) error {
    if s.initialized {
        return nil
    }

    // Ensure type is set
    if s.type_ == nil {
        return errors.New("struct type not set")
    }

    // Normalize pointer types
    for s.type_.Kind() == reflect.Ptr {
        s.type_ = s.type_.Elem()
    }

    // Build fields from type or fieldDefs
    if s.fieldDefs != nil {
        if err := s.initFieldsFromDefsWithResolver(typeResolver); err != nil {
            return err
        }
    } else {
        if err := s.initFieldsFromTypeResolver(typeResolver); err != nil {
            return err
        }
    }

    // Compute struct hash
    s.structHash = s.computeHash()

    // Set compatible mode flag
    s.isCompatibleMode = typeResolver.Compatible()

    s.initialized = true
    return nil
}

func (s *structSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
    switch refMode {
    case RefModeTracking:
        if value.Kind() == reflect.Ptr && value.IsNil() {
            ctx.buffer.WriteInt8(NullFlag)
            return
        }
        refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.buffer, value)
        if err != nil {
            ctx.SetError(FromError(err))
            return
        }
        if refWritten {
            return
        }
    case RefModeNullOnly:
        if value.Kind() == reflect.Ptr && value.IsNil() {
            ctx.buffer.WriteInt8(NullFlag)
            return
        }
        ctx.buffer.WriteInt8(NotNullValueFlag)
    }
    if writeType {
        // Structs have dynamic type IDs, need to look up from TypeResolver
        typeInfo, err := ctx.TypeResolver().getTypeInfo(value, true)
        if err != nil {
            ctx.SetError(FromError(err))
            return
        }
        ctx.TypeResolver().WriteTypeInfo(ctx.buffer, typeInfo, ctx.Err())
    }
    s.WriteData(ctx, value)
}

func (s *structSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
    // Early error check - skip all intermediate checks for normal path performance
    if ctx.HasError() {
        return
    }

    // Lazy initialization
    if !s.initialized {
        if err := s.initialize(ctx.TypeResolver()); err != nil {
            ctx.SetError(FromError(err))
            return
        }
    }

    buf := ctx.Buffer()

    // Dereference pointer if needed
    if value.Kind() == reflect.Ptr {
        if value.IsNil() {
            ctx.SetError(SerializationError("cannot write nil pointer"))
            return
        }
        value = value.Elem()
    }

    // In compatible mode with meta share, struct hash is not written
    if !ctx.Compatible() {
        buf.WriteInt32(s.structHash)
    }

    // Check if value is addressable for unsafe access
    canUseUnsafe := value.CanAddr()
    var ptr unsafe.Pointer
    if canUseUnsafe {
        ptr = unsafe.Pointer(value.UnsafeAddr())
    }

    // ==========================================================================
    // Phase 1: Fixed-size primitives (bool, int8, int16, float32, float64)
    // - Reserve once, inline unsafe writes with endian handling, update index once
    // - field.WriteOffset computed at init time
    // ==========================================================================
    if canUseUnsafe && s.fixedSize > 0 {
        buf.Reserve(s.fixedSize)
        baseOffset := buf.WriterIndex()
        data := buf.GetData()

        for _, field := range s.fixedFields {
            fieldPtr := unsafe.Add(ptr, field.Offset)
            bufOffset := baseOffset + field.WriteOffset
            switch field.StaticId {
            case ConcreteTypeBool:
                if *(*bool)(fieldPtr) {
                    data[bufOffset] = 1
                } else {
                    data[bufOffset] = 0
                }
            case ConcreteTypeInt8:
                data[bufOffset] = *(*byte)(fieldPtr)
            case ConcreteTypeInt16:
                if isLittleEndian {
                    *(*int16)(unsafe.Pointer(&data[bufOffset])) = *(*int16)(fieldPtr)
                } else {
                    binary.LittleEndian.PutUint16(data[bufOffset:], uint16(*(*int16)(fieldPtr)))
                }
            case ConcreteTypeFloat32:
                if isLittleEndian {
                    *(*float32)(unsafe.Pointer(&data[bufOffset])) = *(*float32)(fieldPtr)
                } else {
                    binary.LittleEndian.PutUint32(data[bufOffset:], math.Float32bits(*(*float32)(fieldPtr)))
                }
            case ConcreteTypeFloat64:
                if isLittleEndian {
                    *(*float64)(unsafe.Pointer(&data[bufOffset])) = *(*float64)(fieldPtr)
                } else {
                    binary.LittleEndian.PutUint64(data[bufOffset:], math.Float64bits(*(*float64)(fieldPtr)))
                }
            }
        }
        // Update writer index ONCE after all fixed fields
        buf.SetWriterIndex(baseOffset + s.fixedSize)
    } else if len(s.fixedFields) > 0 {
        // Fallback to reflect-based access for unaddressable values
        for _, field := range s.fixedFields {
            fieldValue := value.Field(field.FieldIndex)
            switch field.StaticId {
            case ConcreteTypeBool:
                buf.WriteBool(fieldValue.Bool())
            case ConcreteTypeInt8:
                buf.WriteByte_(byte(fieldValue.Int()))
            case ConcreteTypeInt16:
                buf.WriteInt16(int16(fieldValue.Int()))
            case ConcreteTypeFloat32:
                buf.WriteFloat32(float32(fieldValue.Float()))
            case ConcreteTypeFloat64:
                buf.WriteFloat64(fieldValue.Float())
            }
        }
    }

    // ==========================================================================
    // Phase 2: Varint primitives (int32, int64, int)
    // - Reserve max size, track offset locally, update index once at end
    // ==========================================================================
    if canUseUnsafe && s.maxVarintSize > 0 {
        buf.Reserve(s.maxVarintSize)
        offset := buf.WriterIndex()

        for _, field := range s.varintFields {
            fieldPtr := unsafe.Add(ptr, field.Offset)
            switch field.StaticId {
            case ConcreteTypeInt32:
                offset += buf.UnsafePutVarInt32(offset, *(*int32)(fieldPtr))
            case ConcreteTypeInt64:
                offset += buf.UnsafePutVarInt64(offset, *(*int64)(fieldPtr))
            case ConcreteTypeInt:
                offset += buf.UnsafePutVarInt64(offset, int64(*(*int)(fieldPtr)))
            }
        }
        // Update writer index ONCE after all varint fields
        buf.SetWriterIndex(offset)
    } else if len(s.varintFields) > 0 {
        // Fallback to reflect-based access for unaddressable values
        for _, field := range s.varintFields {
            fieldValue := value.Field(field.FieldIndex)
            switch field.StaticId {
            case ConcreteTypeInt32:
                buf.WriteVarint32(int32(fieldValue.Int()))
            case ConcreteTypeInt64, ConcreteTypeInt:
                buf.WriteVarint64(fieldValue.Int())
            }
        }
    }

    // ==========================================================================
    // Phase 3: Remaining fields (strings, slices, maps, structs, enums)
    // - These require per-field handling (ref flags, type info, serializers)
    // - No intermediate error checks - trade error path performance for normal path
    // ==========================================================================
    for _, field := range s.remainingFields {
        s.writeRemainingField(ctx, ptr, field, value)
    }
}

// writeRemainingField writes a non-primitive field (string, slice, map, struct, enum)
func (s *structSerializer) writeRemainingField(ctx *WriteContext, ptr unsafe.Pointer, field *FieldInfo, value reflect.Value) {
    buf := ctx.Buffer()

    // Fast path dispatch using pre-computed StaticId
    // ptr must be valid (addressable value)
    if ptr != nil {
        fieldPtr := unsafe.Add(ptr, field.Offset)
        switch field.StaticId {
        case ConcreteTypeString:
            if field.RefMode == RefModeTracking {
                break // Fall through to slow path
            }
            // Only write null flag if RefMode requires it (nullable field)
            if field.RefMode == RefModeNullOnly {
                buf.WriteInt8(NotNullValueFlag)
            }
            ctx.WriteString(*(*string)(fieldPtr))
            return
        case ConcreteTypeEnum:
            // Enums don't track refs - always use fast path
            writeEnumField(ctx, field, value.Field(field.FieldIndex))
            return
        case ConcreteTypeStringSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteStringSlice(*(*[]string)(fieldPtr), field.RefMode, false, true)
            return
        case ConcreteTypeBoolSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteBoolSlice(*(*[]bool)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeInt8Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteInt8Slice(*(*[]int8)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeByteSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteByteSlice(*(*[]byte)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeInt16Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteInt16Slice(*(*[]int16)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeInt32Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteInt32Slice(*(*[]int32)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeInt64Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteInt64Slice(*(*[]int64)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeIntSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteIntSlice(*(*[]int)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeUintSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteUintSlice(*(*[]uint)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeFloat32Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteFloat32Slice(*(*[]float32)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeFloat64Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteFloat64Slice(*(*[]float64)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeStringStringMap:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteStringStringMap(*(*map[string]string)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeStringInt64Map:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteStringInt64Map(*(*map[string]int64)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeStringInt32Map:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteStringInt32Map(*(*map[string]int32)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeStringIntMap:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteStringIntMap(*(*map[string]int)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeStringFloat64Map:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteStringFloat64Map(*(*map[string]float64)(fieldPtr), field.RefMode, false)
            return
        case ConcreteTypeStringBoolMap:
            // NOTE: map[string]bool is used to represent SETs in Go xlang mode.
            // We CANNOT use the fast path here because it writes MAP format,
            // but the data should be written in SET format. Fall through to slow path
            // which uses setSerializer to correctly write the SET format.
            break
        case ConcreteTypeIntIntMap:
            if field.RefMode == RefModeTracking {
                break
            }
            ctx.WriteIntIntMap(*(*map[int]int)(fieldPtr), field.RefMode, false)
            return
        }
    }

    // Slow path: use full serializer
    fieldValue := value.Field(field.FieldIndex)
    if field.Serializer != nil {
        field.Serializer.Write(ctx, field.RefMode, field.WriteType, field.HasGenerics, fieldValue)
    } else {
        ctx.WriteValue(fieldValue, RefModeTracking, true)
    }
}

func (s *structSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
    buf := ctx.Buffer()
    ctxErr := ctx.Err()
    switch refMode {
    case RefModeTracking:
        refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
        if refErr != nil {
            ctx.SetError(FromError(refErr))
            return
        }
        if refID < int32(NotNullValueFlag) {
            // Reference found
            obj := ctx.RefResolver().GetReadObject(refID)
            if obj.IsValid() {
                value.Set(obj)
            }
            return
        }
    case RefModeNullOnly:
        flag := buf.ReadInt8(ctxErr)
        if flag == NullFlag {
            return
        }
    }
    if readType {
        // Read type info - in compatible mode this returns the serializer with remote fieldDefs
        typeID := buf.ReadVaruint32Small7(ctxErr)
        internalTypeID := TypeId(typeID & 0xFF)
        // Check if this is a struct type that needs type meta reading
        if IsNamespacedType(TypeId(typeID)) || internalTypeID == COMPATIBLE_STRUCT || internalTypeID == STRUCT {
            // For struct types in compatible mode, use the serializer from TypeInfo
            typeInfo := ctx.TypeResolver().readTypeInfoWithTypeID(buf, typeID, ctxErr)
            // Use the serializer from TypeInfo which has the remote field definitions
            if structSer, ok := typeInfo.Serializer.(*structSerializer); ok && len(structSer.fieldDefs) > 0 {
                structSer.ReadData(ctx, value.Type(), value)
                return
            }
        }
    }
    s.ReadData(ctx, value.Type(), value)
}

func (s *structSerializer) ReadData(ctx *ReadContext, type_ reflect.Type, value reflect.Value) {
    // Early error check - skip all intermediate checks for normal path performance
    if ctx.HasError() {
        return
    }

    // Lazy initialization
    if !s.initialized {
        if err := s.initialize(ctx.TypeResolver()); err != nil {
            ctx.SetError(FromError(err))
            return
        }
    }

    buf := ctx.Buffer()
    if value.Kind() == reflect.Ptr {
        if value.IsNil() {
            value.Set(reflect.New(type_.Elem()))
        }
        value = value.Elem()
        type_ = type_.Elem()
    }

    // In compatible mode with meta share, struct hash is not written
    if !ctx.Compatible() {
        err := ctx.Err()
        structHash := buf.ReadInt32(err)
        if structHash != s.structHash {
            ctx.SetError(HashMismatchError(structHash, s.structHash, s.type_.String()))
            return
        }
    }

    // Use ordered reading only when TypeDef differs from local type (schema evolution)
    // When types match (typeDefDiffers=false), use grouped reading for better performance
    if s.typeDefDiffers {
        s.readFieldsInOrder(ctx, value)
        return
    }

    // Check if value is addressable for unsafe access
    if !value.CanAddr() {
        s.readFieldsInOrder(ctx, value)
        return
    }

    // ==========================================================================
    // Grouped reading for matching types (optimized path)
    // - Types match, so all fields exist locally (no FieldIndex < 0 checks)
    // - Use UnsafeGet at pre-computed offsets, update reader index once per phase
    // ==========================================================================
    ptr := unsafe.Pointer(value.UnsafeAddr())

    // Phase 1: Fixed-size primitives (inline unsafe reads with endian handling)
    if s.fixedSize > 0 {
        baseOffset := buf.ReaderIndex()
        data := buf.GetData()

        for _, field := range s.fixedFields {
            fieldPtr := unsafe.Add(ptr, field.Offset)
            bufOffset := baseOffset + field.WriteOffset
            switch field.StaticId {
            case ConcreteTypeBool:
                *(*bool)(fieldPtr) = data[bufOffset] != 0
            case ConcreteTypeInt8:
                *(*int8)(fieldPtr) = int8(data[bufOffset])
            case ConcreteTypeInt16:
                if isLittleEndian {
                    *(*int16)(fieldPtr) = *(*int16)(unsafe.Pointer(&data[bufOffset]))
                } else {
                    *(*int16)(fieldPtr) = int16(binary.LittleEndian.Uint16(data[bufOffset:]))
                }
            case ConcreteTypeFloat32:
                if isLittleEndian {
                    *(*float32)(fieldPtr) = *(*float32)(unsafe.Pointer(&data[bufOffset]))
                } else {
                    *(*float32)(fieldPtr) = math.Float32frombits(binary.LittleEndian.Uint32(data[bufOffset:]))
                }
            case ConcreteTypeFloat64:
                if isLittleEndian {
                    *(*float64)(fieldPtr) = *(*float64)(unsafe.Pointer(&data[bufOffset]))
                } else {
                    *(*float64)(fieldPtr) = math.Float64frombits(binary.LittleEndian.Uint64(data[bufOffset:]))
                }
            }
        }
        // Update reader index ONCE after all fixed fields
        buf.SetReaderIndex(baseOffset + s.fixedSize)
    }

    // Phase 2: Varint primitives (must read sequentially - variable length)
    // Use unsafe reads when we have enough buffer remaining
    if s.maxVarintSize > 0 && buf.remaining() >= s.maxVarintSize {
        for _, field := range s.varintFields {
            fieldPtr := unsafe.Add(ptr, field.Offset)
            switch field.StaticId {
            case ConcreteTypeInt32:
                *(*int32)(fieldPtr) = buf.UnsafeReadVarint32()
            case ConcreteTypeInt64:
                *(*int64)(fieldPtr) = buf.UnsafeReadVarint64()
            case ConcreteTypeInt:
                *(*int)(fieldPtr) = int(buf.UnsafeReadVarint64())
            }
        }
    } else if len(s.varintFields) > 0 {
        // Slow path with bounds checking
        err := ctx.Err()
        for _, field := range s.varintFields {
            fieldPtr := unsafe.Add(ptr, field.Offset)
            switch field.StaticId {
            case ConcreteTypeInt32:
                *(*int32)(fieldPtr) = buf.ReadVarint32(err)
            case ConcreteTypeInt64:
                *(*int64)(fieldPtr) = buf.ReadVarint64(err)
            case ConcreteTypeInt:
                *(*int)(fieldPtr) = int(buf.ReadVarint64(err))
            }
        }
    }

    // Phase 3: Remaining fields (strings, slices, maps, structs, enums)
    // No intermediate error checks - trade error path performance for normal path
    for _, field := range s.remainingFields {
        s.readRemainingField(ctx, ptr, field, value)
    }
}

// readRemainingField reads a non-primitive field (string, slice, map, struct, enum)
func (s *structSerializer) readRemainingField(ctx *ReadContext, ptr unsafe.Pointer, field *FieldInfo, value reflect.Value) {
    buf := ctx.Buffer()
    ctxErr := ctx.Err()

    // Fast path dispatch using pre-computed StaticId
    // ptr must be valid (addressable value)
    if ptr != nil {
        fieldPtr := unsafe.Add(ptr, field.Offset)
        switch field.StaticId {
        case ConcreteTypeString:
            if field.RefMode == RefModeTracking {
                break // Fall through to slow path for ref tracking
            }
            // Only read null flag if RefMode requires it (nullable field)
            if field.RefMode == RefModeNullOnly {
                refFlag := buf.ReadInt8(ctxErr)
                if refFlag == NullFlag {
                    *(*string)(fieldPtr) = ""
                    return
                }
            }
            *(*string)(fieldPtr) = ctx.ReadString()
            return
        case ConcreteTypeEnum:
            // Enums don't track refs - always use fast path
            fieldValue := value.Field(field.FieldIndex)
            readEnumField(ctx, field, fieldValue)
            return
        case ConcreteTypeStringSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]string)(fieldPtr) = ctx.ReadStringSlice(field.RefMode, false)
            return
        case ConcreteTypeBoolSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]bool)(fieldPtr) = ctx.ReadBoolSlice(field.RefMode, false)
            return
        case ConcreteTypeInt8Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]int8)(fieldPtr) = ctx.ReadInt8Slice(field.RefMode, false)
            return
        case ConcreteTypeByteSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]byte)(fieldPtr) = ctx.ReadByteSlice(field.RefMode, false)
            return
        case ConcreteTypeInt16Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]int16)(fieldPtr) = ctx.ReadInt16Slice(field.RefMode, false)
            return
        case ConcreteTypeInt32Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]int32)(fieldPtr) = ctx.ReadInt32Slice(field.RefMode, false)
            return
        case ConcreteTypeInt64Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]int64)(fieldPtr) = ctx.ReadInt64Slice(field.RefMode, false)
            return
        case ConcreteTypeIntSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]int)(fieldPtr) = ctx.ReadIntSlice(field.RefMode, false)
            return
        case ConcreteTypeUintSlice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]uint)(fieldPtr) = ctx.ReadUintSlice(field.RefMode, false)
            return
        case ConcreteTypeFloat32Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]float32)(fieldPtr) = ctx.ReadFloat32Slice(field.RefMode, false)
            return
        case ConcreteTypeFloat64Slice:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*[]float64)(fieldPtr) = ctx.ReadFloat64Slice(field.RefMode, false)
            return
        case ConcreteTypeStringStringMap:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*map[string]string)(fieldPtr) = ctx.ReadStringStringMap(field.RefMode, false)
            return
        case ConcreteTypeStringInt64Map:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*map[string]int64)(fieldPtr) = ctx.ReadStringInt64Map(field.RefMode, false)
            return
        case ConcreteTypeStringInt32Map:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*map[string]int32)(fieldPtr) = ctx.ReadStringInt32Map(field.RefMode, false)
            return
        case ConcreteTypeStringIntMap:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*map[string]int)(fieldPtr) = ctx.ReadStringIntMap(field.RefMode, false)
            return
        case ConcreteTypeStringFloat64Map:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*map[string]float64)(fieldPtr) = ctx.ReadStringFloat64Map(field.RefMode, false)
            return
        case ConcreteTypeStringBoolMap:
            // NOTE: map[string]bool is used to represent SETs in Go xlang mode.
            // We CANNOT use the fast path here because it reads MAP format,
            // but the data is actually in SET format. Fall through to slow path
            // which uses setSerializer to correctly read the SET format.
            break
        case ConcreteTypeIntIntMap:
            if field.RefMode == RefModeTracking {
                break
            }
            *(*map[int]int)(fieldPtr) = ctx.ReadIntIntMap(field.RefMode, false)
            return
        }
    }

    // Slow path: use full serializer
    fieldValue := value.Field(field.FieldIndex)

    if field.Serializer != nil {
        field.Serializer.Read(ctx, field.RefMode, field.WriteType, field.HasGenerics, fieldValue)
    } else {
        ctx.ReadValue(fieldValue, RefModeTracking, true)
    }
}

// readFieldsInOrder reads fields in the order they appear in s.fields (TypeDef order)
// This is used in compatible mode where Java writes fields in TypeDef order
func (s *structSerializer) readFieldsInOrder(ctx *ReadContext, value reflect.Value) {
    buf := ctx.Buffer()
    canUseUnsafe := value.CanAddr()
    var ptr unsafe.Pointer
    if canUseUnsafe {
        ptr = unsafe.Pointer(value.UnsafeAddr())
    }
    err := ctx.Err()

    for _, field := range s.fields {
        if field.FieldIndex < 0 {
            s.skipField(ctx, field)
            if ctx.HasError() {
                return
            }
            continue
        }

        // Fast path for fixed-size primitive types (no ref flag)
        // Use error-aware methods with deferred checking
        if canUseUnsafe && isFixedSizePrimitive(field.StaticId, field.Referencable) {
            fieldPtr := unsafe.Add(ptr, field.Offset)
            switch field.StaticId {
            case ConcreteTypeBool:
                *(*bool)(fieldPtr) = buf.ReadBool(err)
            case ConcreteTypeInt8:
                *(*int8)(fieldPtr) = buf.ReadInt8(err)
            case ConcreteTypeInt16:
                *(*int16)(fieldPtr) = buf.ReadInt16(err)
            case ConcreteTypeFloat32:
                *(*float32)(fieldPtr) = buf.ReadFloat32(err)
            case ConcreteTypeFloat64:
                *(*float64)(fieldPtr) = buf.ReadFloat64(err)
            }
            continue
        }

        // Fast path for varint primitive types (no ref flag)
        // Skip fast path if field has a serializer with a non-primitive type (e.g., NAMED_ENUM)
        if canUseUnsafe && isVarintPrimitive(field.StaticId, field.Referencable) && !fieldHasNonPrimitiveSerializer(field) {
            fieldPtr := unsafe.Add(ptr, field.Offset)
            switch field.StaticId {
            case ConcreteTypeInt32:
                *(*int32)(fieldPtr) = buf.ReadVarint32(err)
            case ConcreteTypeInt64:
                *(*int64)(fieldPtr) = buf.ReadVarint64(err)
            case ConcreteTypeInt:
                *(*int)(fieldPtr) = int(buf.ReadVarint64(err))
            }
            continue
        }

        // Get field value for slow paths
        fieldValue := value.Field(field.FieldIndex)

        // Slow path for primitives when not addressable
        if !canUseUnsafe && isFixedSizePrimitive(field.StaticId, field.Referencable) {
            switch field.StaticId {
            case ConcreteTypeBool:
                fieldValue.SetBool(buf.ReadBool(err))
            case ConcreteTypeInt8:
                fieldValue.SetInt(int64(buf.ReadInt8(err)))
            case ConcreteTypeInt16:
                fieldValue.SetInt(int64(buf.ReadInt16(err)))
            case ConcreteTypeFloat32:
                fieldValue.SetFloat(float64(buf.ReadFloat32(err)))
            case ConcreteTypeFloat64:
                fieldValue.SetFloat(buf.ReadFloat64(err))
            }
            continue
        }

        if !canUseUnsafe && isVarintPrimitive(field.StaticId, field.Referencable) && !fieldHasNonPrimitiveSerializer(field) {
            switch field.StaticId {
            case ConcreteTypeInt32:
                fieldValue.SetInt(int64(buf.ReadVarint32(err)))
            case ConcreteTypeInt64, ConcreteTypeInt:
                fieldValue.SetInt(buf.ReadVarint64(err))
            }
            continue
        }

        if isEnumField(field) {
            readEnumField(ctx, field, fieldValue)
            continue
        }

        // Slow path for non-primitives (all need ref flag per xlang spec)
        if field.Serializer != nil {
            // Use pre-computed RefMode and WriteType from field initialization
            field.Serializer.Read(ctx, field.RefMode, field.WriteType, field.HasGenerics, fieldValue)
        } else {
            ctx.ReadValue(fieldValue, RefModeTracking, true)
        }
    }
}

// skipField skips a field that doesn't exist or is incompatible
// Uses context error state for deferred error checking.
func (s *structSerializer) skipField(ctx *ReadContext, field *FieldInfo) {
    if field.FieldDef.name != "" {
        fieldDefIsStructType := isStructFieldType(field.FieldDef.fieldType)
        // Use FieldDef's trackingRef and nullable to determine if ref flag was written by Java
        // Java writes ref flag based on its FieldDef, not Go's field type
        readRefFlag := field.FieldDef.trackingRef || field.FieldDef.nullable
        SkipFieldValueWithTypeFlag(ctx, field.FieldDef, readRefFlag, ctx.Compatible() && fieldDefIsStructType)
        return
    }
    // No FieldDef available, read into temp value
    tempValue := reflect.New(field.Type).Elem()
    if field.Serializer != nil {
        readType := ctx.Compatible() && isStructField(field.Type)
        refMode := RefModeNone
        if field.Referencable {
            refMode = RefModeTracking
        }
        field.Serializer.Read(ctx, refMode, readType, false, tempValue)
    } else {
        ctx.ReadValue(tempValue, RefModeTracking, true)
    }
}

func (s *structSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
    // typeInfo is already read, don't read it again
    s.Read(ctx, refMode, false, false, value)
}

// initFieldsFromContext initializes fields using context's type resolver (for WriteContext)
// initFieldsFromTypeResolver initializes fields from local struct type using TypeResolver
func (s *structSerializer) initFieldsFromTypeResolver(typeResolver *TypeResolver) error {
    // If we have fieldDefs from type_def (remote meta), use them
    if len(s.fieldDefs) > 0 {
        return s.initFieldsFromDefsWithResolver(typeResolver)
    }

    // Otherwise initialize from local struct type
    type_ := s.type_
    var fields []*FieldInfo
    var fieldNames []string
    var serializers []Serializer
    var typeIds []TypeId
    var nullables []bool
    var tagIDs []int

    for i := 0; i < type_.NumField(); i++ {
        field := type_.Field(i)
        firstRune, _ := utf8.DecodeRuneInString(field.Name)
        if unicode.IsLower(firstRune) {
            continue // skip unexported fields
        }

        // Parse fory struct tag and check for ignore
        foryTag := ParseForyTag(field)
        if foryTag.Ignore {
            continue // skip ignored fields
        }

        fieldType := field.Type

        var fieldSerializer Serializer
        // For interface{} fields, don't get a serializer - use WriteValue/ReadValue instead
        // which will handle polymorphic types dynamically
        if fieldType.Kind() != reflect.Interface {
            // Get serializer for all non-interface field types
            fieldSerializer, _ = typeResolver.getSerializerByType(fieldType, true)
        }

        // Use TypeResolver helper methods for arrays and slices
        if fieldType.Kind() == reflect.Array && fieldType.Elem().Kind() != reflect.Interface {
            fieldSerializer, _ = typeResolver.GetArraySerializer(fieldType)
        } else if fieldType.Kind() == reflect.Slice && fieldType.Elem().Kind() != reflect.Interface {
            fieldSerializer, _ = typeResolver.GetSliceSerializer(fieldType)
        } else if fieldType.Kind() == reflect.Slice && fieldType.Elem().Kind() == reflect.Interface {
            // For struct fields with interface element types, use sliceDynSerializer
            fieldSerializer = mustNewSliceDynSerializer(fieldType.Elem())
        }

        // Get TypeId for the serializer, fallback to deriving from kind
        fieldTypeId := typeResolver.getTypeIdByType(fieldType)
        if fieldTypeId == 0 {
            fieldTypeId = typeIdFromKind(fieldType)
        }
        // Calculate nullable flag for serialization (wire format):
        // - In xlang mode: Per xlang spec, fields are NON-NULLABLE by default.
        //   Only pointer types are nullable by default.
        // - In native mode: Go's natural semantics apply - slice/map/interface can be nil,
        //   so they are nullable by default.
        // Can be overridden by explicit fory tag `fory:"nullable"`.
        internalId := TypeId(fieldTypeId & 0xFF)
        isEnum := internalId == ENUM || internalId == NAMED_ENUM

        // Determine nullable based on mode
        // In xlang mode: only pointer types are nullable by default (per xlang spec)
        // In native mode: Go's natural semantics - all nil-able types are nullable
        // This ensures proper interoperability with Java/other languages in xlang mode.
        var nullableFlag bool
        if typeResolver.fory.config.IsXlang {
            // xlang mode: only pointer types are nullable by default per xlang spec
            // Slices and maps are NOT nullable - they serialize as empty when nil
            nullableFlag = fieldType.Kind() == reflect.Ptr
        } else {
            // Native mode: Go's natural semantics - all nil-able types are nullable
            nullableFlag = fieldType.Kind() == reflect.Ptr ||
                fieldType.Kind() == reflect.Slice ||
                fieldType.Kind() == reflect.Map ||
                fieldType.Kind() == reflect.Interface
        }
        if foryTag.NullableSet {
            // Override nullable flag if explicitly set in fory tag
            nullableFlag = foryTag.Nullable
        }
        // Primitives are never nullable, regardless of tag
        if isNonNullablePrimitiveKind(fieldType.Kind()) && !isEnum {
            nullableFlag = false
        }

        // Calculate ref tracking - use tag override if explicitly set
        trackRef := typeResolver.TrackRef()
        if foryTag.RefSet {
            trackRef = foryTag.Ref
        }

        // Pre-compute RefMode based on (possibly overridden) trackRef and nullable
        // For pointer-to-struct fields, enable ref tracking when trackRef is enabled,
        // regardless of nullable flag. This is necessary to detect circular references.
        refMode := RefModeNone
        isStructPointer := fieldType.Kind() == reflect.Ptr && fieldType.Elem().Kind() == reflect.Struct
        if trackRef && (nullableFlag || isStructPointer) {
            refMode = RefModeTracking
        } else if nullableFlag {
            refMode = RefModeNullOnly
        }
        // Pre-compute WriteType: true for struct fields in compatible mode
        writeType := typeResolver.Compatible() && isStructField(fieldType)

        // Pre-compute StaticId, with special handling for enum fields
        staticId := GetStaticTypeId(fieldType)
        if fieldSerializer != nil {
            if _, ok := fieldSerializer.(*enumSerializer); ok {
                staticId = ConcreteTypeEnum
            } else if ptrSer, ok := fieldSerializer.(*ptrToValueSerializer); ok {
                if _, ok := ptrSer.valueSerializer.(*enumSerializer); ok {
                    staticId = ConcreteTypeEnum
                }
            }
        }
        if DebugOutputEnabled() {
            fmt.Printf("[fory-debug] initFieldsFromTypeResolver: field=%s type=%v staticId=%d refMode=%v nullableFlag=%v serializer=%T\n",
                SnakeCase(field.Name), fieldType, staticId, refMode, nullableFlag, fieldSerializer)
        }

        fieldInfo := &FieldInfo{
            Name:           SnakeCase(field.Name),
            Offset:         field.Offset,
            Type:           fieldType,
            StaticId:       staticId,
            TypeId:         fieldTypeId,
            Serializer:     fieldSerializer,
            Referencable:   nullableFlag, // Use same logic as TypeDef's nullable flag for consistent ref handling
            FieldIndex:     i,
            RefMode:        refMode,
            WriteType:      writeType,
            HasGenerics:    isCollectionType(fieldTypeId), // Container fields have declared element types
            TagID:          foryTag.ID,
            HasForyTag:     foryTag.HasTag,
            TagRefSet:      foryTag.RefSet,
            TagRef:         foryTag.Ref,
            TagNullableSet: foryTag.NullableSet,
            TagNullable:    foryTag.Nullable,
        }
        fields = append(fields, fieldInfo)
        fieldNames = append(fieldNames, fieldInfo.Name)
        serializers = append(serializers, fieldSerializer)
        typeIds = append(typeIds, fieldTypeId)
        nullables = append(nullables, nullableFlag)
        tagIDs = append(tagIDs, foryTag.ID)
    }

    // Sort fields according to specification using nullable info and tag IDs for consistent ordering
    serializers, fieldNames = sortFields(typeResolver, fieldNames, serializers, typeIds, nullables, tagIDs)
    order := make(map[string]int, len(fieldNames))
    for idx, name := range fieldNames {
        order[name] = idx
    }

    sort.SliceStable(fields, func(i, j int) bool {
        oi, okI := order[fields[i].Name]
        oj, okJ := order[fields[j].Name]
        switch {
        case okI && okJ:
            return oi < oj
        case okI:
            return true
        case okJ:
            return false
        default:
            return false
        }
    })

    s.fields = fields
    s.groupFields()
    return nil
}

// groupFields categorizes fields into fixedFields, varintFields, and remainingFields.
// Also computes pre-computed sizes and WriteOffset for batch buffer reservation.
func (s *structSerializer) groupFields() {
    s.fixedFields = nil
    s.varintFields = nil
    s.remainingFields = nil
    s.fixedSize = 0
    s.maxVarintSize = 0

    for _, field := range s.fields {
        // Fields with non-primitive serializers (NAMED_ENUM, NAMED_STRUCT, etc.)
        // must go to remainingFields to use their serializer's type info writing
        hasNonPrimitive := fieldHasNonPrimitiveSerializer(field)
        if DebugOutputEnabled() {
            fmt.Printf("[fory-debug] groupFields: field=%s TypeId=%d internalId=%d hasNonPrimitive=%v\n",
                field.Name, field.TypeId, field.TypeId&0xFF, hasNonPrimitive)
        }
        if hasNonPrimitive {
            s.remainingFields = append(s.remainingFields, field)
        } else if isFixedSizePrimitive(field.StaticId, field.Referencable) {
            // Compute FixedSize and WriteOffset for this field
            field.FixedSize = getFixedSizeByStaticId(field.StaticId)
            field.WriteOffset = s.fixedSize
            s.fixedSize += field.FixedSize
            s.fixedFields = append(s.fixedFields, field)
        } else if isVarintPrimitive(field.StaticId, field.Referencable) {
            s.maxVarintSize += getVarintMaxSizeByStaticId(field.StaticId)
            s.varintFields = append(s.varintFields, field)
        } else {
            s.remainingFields = append(s.remainingFields, field)
        }
    }
}

// initFieldsFromDefsWithResolver initializes fields from remote fieldDefs using typeResolver
func (s *structSerializer) initFieldsFromDefsWithResolver(typeResolver *TypeResolver) error {
    type_ := s.type_
    if type_ == nil {
        // Type is not known - we'll create an interface{} placeholder
        // This happens when deserializing unknown types in compatible mode
        // For now, we'll create fields that discard all data
        var fields []*FieldInfo
        for _, def := range s.fieldDefs {
            fieldSerializer, _ := getFieldTypeSerializerWithResolver(typeResolver, def.fieldType)
            remoteTypeInfo, _ := def.fieldType.getTypeInfoWithResolver(typeResolver)
            remoteType := remoteTypeInfo.Type
            if remoteType == nil {
                remoteType = reflect.TypeOf((*interface{})(nil)).Elem()
            }
            // Get TypeId from FieldType's TypeId method
            fieldTypeId := def.fieldType.TypeId()
            // Pre-compute RefMode based on trackRef and FieldDef flags
            refMode := RefModeNone
            if def.trackingRef {
                refMode = RefModeTracking
            } else if def.nullable {
                refMode = RefModeNullOnly
            }
            // Pre-compute WriteType: true for struct fields in compatible mode
            writeType := typeResolver.Compatible() && isStructField(remoteType)

            // Pre-compute StaticId, with special handling for enum fields
            staticId := GetStaticTypeId(remoteType)
            if fieldSerializer != nil {
                if _, ok := fieldSerializer.(*enumSerializer); ok {
                    staticId = ConcreteTypeEnum
                } else if ptrSer, ok := fieldSerializer.(*ptrToValueSerializer); ok {
                    if _, ok := ptrSer.valueSerializer.(*enumSerializer); ok {
                        staticId = ConcreteTypeEnum
                    }
                }
            }

            fieldInfo := &FieldInfo{
                Name:         def.name,
                Offset:       0,
                Type:         remoteType,
                StaticId:     staticId,
                TypeId:       fieldTypeId,
                Serializer:   fieldSerializer,
                Referencable: def.nullable, // Use remote nullable flag
                FieldIndex:   -1,           // Mark as non-existent field to discard data
                FieldDef:     def,          // Save original FieldDef for skipping
                RefMode:      refMode,
                WriteType:    writeType,
                HasGenerics:  isCollectionType(fieldTypeId), // Container fields have declared element types
            }
            fields = append(fields, fieldInfo)
        }
        s.fields = fields
        s.groupFields()
        s.typeDefDiffers = true // Unknown type, must use ordered reading
        return nil
    }

    // Build maps from field names and tag IDs to struct field indices
    fieldNameToIndex := make(map[string]int)
    fieldNameToOffset := make(map[string]uintptr)
    fieldNameToType := make(map[string]reflect.Type)
    fieldTagIDToIndex := make(map[int]int)         // tag ID -> struct field index
    fieldTagIDToOffset := make(map[int]uintptr)    // tag ID -> field offset
    fieldTagIDToType := make(map[int]reflect.Type) // tag ID -> field type
    fieldTagIDToName := make(map[int]string)       // tag ID -> snake_case field name
    for i := 0; i < type_.NumField(); i++ {
        field := type_.Field(i)

        // Parse fory tag and skip ignored fields
        foryTag := ParseForyTag(field)
        if foryTag.Ignore {
            continue
        }

        name := SnakeCase(field.Name)
        fieldNameToIndex[name] = i
        fieldNameToOffset[name] = field.Offset
        fieldNameToType[name] = field.Type

        // Also index by tag ID if present
        if foryTag.ID >= 0 {
            fieldTagIDToIndex[foryTag.ID] = i
            fieldTagIDToOffset[foryTag.ID] = field.Offset
            fieldTagIDToType[foryTag.ID] = field.Type
            fieldTagIDToName[foryTag.ID] = name
        }
    }

    var fields []*FieldInfo

    for _, def := range s.fieldDefs {
        fieldSerializer, err := getFieldTypeSerializerWithResolver(typeResolver, def.fieldType)
        if err != nil || fieldSerializer == nil {
            // If we can't get serializer from typeID, try to get it from the Go type
            // This can happen when the type isn't registered in typeIDToTypeInfo
            remoteTypeInfo, _ := def.fieldType.getTypeInfoWithResolver(typeResolver)
            if remoteTypeInfo.Type != nil {
                fieldSerializer, _ = typeResolver.getSerializerByType(remoteTypeInfo.Type, true)
            }
        }

        // Get the remote type from fieldDef
        remoteTypeInfo, _ := def.fieldType.getTypeInfoWithResolver(typeResolver)
        remoteType := remoteTypeInfo.Type
        // Track if type lookup failed - we'll need to skip such fields
        // Note: DynamicFieldType.getTypeInfoWithResolver returns interface{} (not nil) when lookup fails
        emptyInterfaceType := reflect.TypeOf((*interface{})(nil)).Elem()
        typeLookupFailed := remoteType == nil || remoteType == emptyInterfaceType
        if remoteType == nil {
            remoteType = emptyInterfaceType
        }

        // For struct-like fields, even if TypeDef lookup fails, we can try to read
        // the field because type resolution happens at read time from the buffer.
        // The type name might map to a different local type.
        isStructLikeField := isStructFieldType(def.fieldType)

        // Try to find corresponding local field
        // First try to match by tag ID (if remote def uses tag ID)
        // Then fall back to matching by field name
        fieldIndex := -1
        var offset uintptr
        var fieldType reflect.Type
        var localFieldName string
        var localType reflect.Type
        var exists bool

        if def.tagID >= 0 {
            // Try to match by tag ID
            if idx, ok := fieldTagIDToIndex[def.tagID]; ok {
                exists = true
                fieldIndex = idx // Will be overwritten if types are compatible
                localType = fieldTagIDToType[def.tagID]
                offset = fieldTagIDToOffset[def.tagID]
                localFieldName = fieldTagIDToName[def.tagID]
                _ = fieldIndex // Use to avoid compiler warning, will be set properly below
            }
        }

        // Fall back to name-based matching if tag ID match failed
        if !exists && def.name != "" {
            if idx, ok := fieldNameToIndex[def.name]; ok {
                exists = true
                localType = fieldNameToType[def.name]
                offset = fieldNameToOffset[def.name]
                localFieldName = def.name
                _ = idx // Will be set properly below
            }
        }

        if exists {
            idx := fieldNameToIndex[localFieldName]
            if def.tagID >= 0 {
                idx = fieldTagIDToIndex[def.tagID]
            }
            // Check if types are compatible
            // For primitive types: skip if types don't match
            // For struct-like types: allow read even if TypeDef lookup failed,
            // because runtime type resolution by name might work
            shouldRead := false
            isPolymorphicField := def.fieldType.TypeId() == UNKNOWN
            defTypeId := def.fieldType.TypeId()
            // Check if field is an enum - either by type ID or by serializer type
            // The type ID may be a composite value with namespace bits, so check the low 8 bits
            internalDefTypeId := defTypeId & 0xFF
            isEnumField := internalDefTypeId == NAMED_ENUM || internalDefTypeId == ENUM
            if !isEnumField && fieldSerializer != nil {
                _, isEnumField = fieldSerializer.(*enumSerializer)
            }
            if isPolymorphicField && localType.Kind() == reflect.Interface {
                // For polymorphic (UNKNOWN) fields with interface{} local type,
                // allow reading - the actual type will be determined at runtime
                shouldRead = true
                fieldType = localType
            } else if typeLookupFailed && isEnumField {
                // For enum fields with failed TypeDef lookup (NAMED_ENUM stores by namespace/typename, not typeId),
                // check if local field is a numeric type (Go enums are int-based)
                // Also handle pointer enum fields (*EnumType)
                localKind := localType.Kind()
                elemKind := localKind
                if localKind == reflect.Ptr {
                    elemKind = localType.Elem().Kind()
                }
                if isNumericKind(elemKind) {
                    shouldRead = true
                    fieldType = localType
                    // Get the serializer for the base type (the enum type, not the pointer)
                    baseType := localType
                    if localKind == reflect.Ptr {
                        baseType = localType.Elem()
                    }
                    fieldSerializer, _ = typeResolver.getSerializerByType(baseType, true)
                }
            } else if typeLookupFailed && isStructLikeField {
                // For struct fields with failed TypeDef lookup, check if local field can hold a struct
                localKind := localType.Kind()
                if localKind == reflect.Ptr {
                    localKind = localType.Elem().Kind()
                }
                if localKind == reflect.Struct || localKind == reflect.Interface {
                    shouldRead = true
                    fieldType = localType // Use local type for struct fields
                }
            } else if typeLookupFailed && (defTypeId == LIST || defTypeId == SET) {
                // For collection fields with failed type lookup (e.g., List<Animal> with interface element type),
                // check if local type is a slice with interface element type (e.g., []Animal)
                // The type lookup fails because sliceSerializer doesn't support interface elements
                if localType.Kind() == reflect.Slice && localType.Elem().Kind() == reflect.Interface {
                    shouldRead = true
                    fieldType = localType
                }
            } else if !typeLookupFailed && typesCompatible(localType, remoteType) {
                shouldRead = true
                fieldType = localType
            }

            if shouldRead {
                fieldIndex = idx
                // offset was already set above when matching by tag ID or field name
                // For struct-like fields with failed type lookup, get the serializer for the local type
                if typeLookupFailed && isStructLikeField && fieldSerializer == nil {
                    fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
                }
                // For collection fields with interface element types, use sliceDynSerializer
                if typeLookupFailed && (defTypeId == LIST || defTypeId == SET) && fieldSerializer == nil {
                    if localType.Kind() == reflect.Slice && localType.Elem().Kind() == reflect.Interface {
                        fieldSerializer = mustNewSliceDynSerializer(localType.Elem())
                    }
                }
                // If local type is *T and remote type is T, we need the serializer for *T
                // This handles Java's Integer/Long (nullable boxed types) mapping to Go's *int32/*int64
                if localType.Kind() == reflect.Ptr && localType.Elem() == remoteType {
                    fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
                }
                // For pointer enum fields (*EnumType), get the serializer for the base enum type
                // The struct read/write code will handle pointer dereferencing
                if isEnumField && localType.Kind() == reflect.Ptr {
                    baseType := localType.Elem()
                    fieldSerializer, _ = typeResolver.getSerializerByType(baseType, true)
                    if DebugOutputEnabled() {
                        fmt.Printf("[fory-debug] pointer enum field %s: localType=%v baseType=%v serializer=%T\n",
                            def.name, localType, baseType, fieldSerializer)
                    }
                }
                // For array fields, use array serializers (not slice serializers) even if typeID maps to slice serializer
                // The typeID (INT16_ARRAY, etc.) is shared between arrays and slices, but we need the correct
                // serializer based on the actual Go type
                if localType.Kind() == reflect.Array {
                    elemType := localType.Elem()
                    switch elemType.Kind() {
                    case reflect.Bool:
                        fieldSerializer = boolArraySerializer{arrayType: localType}
                    case reflect.Int8:
                        fieldSerializer = int8ArraySerializer{arrayType: localType}
                    case reflect.Int16:
                        fieldSerializer = int16ArraySerializer{arrayType: localType}
                    case reflect.Int32:
                        fieldSerializer = int32ArraySerializer{arrayType: localType}
                    case reflect.Int64:
                        fieldSerializer = int64ArraySerializer{arrayType: localType}
                    case reflect.Uint8:
                        fieldSerializer = uint8ArraySerializer{arrayType: localType}
                    case reflect.Float32:
                        fieldSerializer = float32ArraySerializer{arrayType: localType}
                    case reflect.Float64:
                        fieldSerializer = float64ArraySerializer{arrayType: localType}
                    case reflect.Int:
                        if reflect.TypeOf(int(0)).Size() == 8 {
                            fieldSerializer = int64ArraySerializer{arrayType: localType}
                        } else {
                            fieldSerializer = int32ArraySerializer{arrayType: localType}
                        }
                    }
                }
            } else {
                // Types are incompatible or unknown - use remote type but mark field as not settable
                fieldType = remoteType
                fieldIndex = -1
                offset = 0 // Don't set offset for incompatible fields
            }
        } else {
            // Field doesn't exist locally, use type from fieldDef
            fieldType = remoteType
        }

        // Get TypeId from FieldType's TypeId method
        fieldTypeId := def.fieldType.TypeId()
        // Pre-compute RefMode based on FieldDef flags (trackingRef and nullable)
        refMode := RefModeNone
        if def.trackingRef {
            refMode = RefModeTracking
        } else if def.nullable {
            refMode = RefModeNullOnly
        }
        // Pre-compute WriteType: true for struct fields in compatible mode
        writeType := typeResolver.Compatible() && isStructField(fieldType)

        // Pre-compute StaticId, with special handling for enum fields
        staticId := GetStaticTypeId(fieldType)
        if fieldSerializer != nil {
            if _, ok := fieldSerializer.(*enumSerializer); ok {
                staticId = ConcreteTypeEnum
            } else if ptrSer, ok := fieldSerializer.(*ptrToValueSerializer); ok {
                if _, ok := ptrSer.valueSerializer.(*enumSerializer); ok {
                    staticId = ConcreteTypeEnum
                }
            }
        }

        // Determine field name: use local field name if matched, otherwise use def.name
        fieldName := def.name
        if localFieldName != "" {
            fieldName = localFieldName
        }

        fieldInfo := &FieldInfo{
            Name:         fieldName,
            Offset:       offset,
            Type:         fieldType,
            StaticId:     staticId,
            TypeId:       fieldTypeId,
            Serializer:   fieldSerializer,
            Referencable: def.nullable, // Use remote nullable flag
            FieldIndex:   fieldIndex,
            FieldDef:     def, // Save original FieldDef for skipping
            RefMode:      refMode,
            WriteType:    writeType,
            HasGenerics:  isCollectionType(fieldTypeId), // Container fields have declared element types
            TagID:        def.tagID,
            HasForyTag:   def.tagID >= 0,
        }
        fields = append(fields, fieldInfo)
    }

    s.fields = fields
    s.groupFields()

    // Compute typeDefDiffers: true if any field doesn't exist locally or has type mismatch
    // When typeDefDiffers is false, we can use grouped reading for better performance
    s.typeDefDiffers = false
    for _, field := range fields {
        if field.FieldIndex < 0 {
            // Field exists in remote TypeDef but not locally
            s.typeDefDiffers = true
            break
        }
    }

    return nil
}

// isNonNullablePrimitiveKind returns true for Go kinds that map to Java primitive types
// These are the types that cannot be null in Java and should have nullable=0 in hash computation
func isNonNullablePrimitiveKind(kind reflect.Kind) bool {
    switch kind {
    case reflect.Bool, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
        reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64,
        reflect.Float32, reflect.Float64, reflect.Int, reflect.Uint:
        return true
    default:
        return false
    }
}

// isInternalTypeWithoutTypeMeta checks if a type is serialized without type meta per xlang spec.
// Per the spec (struct field serialization), these types use format: | ref/null flag | value data | (NO type meta)
// - Nullable primitives (*int32, *float64, etc.): | null flag | field value |
// - Strings (string): | null flag | value data |
// - Binary ([]byte): | null flag | value data |
// - List/Slice: | ref meta | value data |
// - Set: | ref meta | value data |
// - Map: | ref meta | value data |
// Only struct/enum/ext types need type meta: | ref flag | type meta | value data |
func isInternalTypeWithoutTypeMeta(t reflect.Type) bool {
    kind := t.Kind()
    // String type - no type meta needed
    if kind == reflect.String {
        return true
    }
    // Slice (list or byte slice) - no type meta needed
    if kind == reflect.Slice {
        return true
    }
    // Map type - no type meta needed
    if kind == reflect.Map {
        return true
    }
    // Pointer to primitive - no type meta needed
    if kind == reflect.Ptr {
        elemKind := t.Elem().Kind()
        switch elemKind {
        case reflect.Bool, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
            reflect.Int, reflect.Float32, reflect.Float64, reflect.String:
            return true
        }
    }
    return false
}

// isStructField checks if a type is a struct type (directly or via pointer)
func isStructField(t reflect.Type) bool {
    if t.Kind() == reflect.Struct {
        return true
    }
    if t.Kind() == reflect.Ptr && t.Elem().Kind() == reflect.Struct {
        return true
    }
    return false
}

// isStructFieldType checks if a FieldType represents a type that needs type info written
// This is used to determine if type info was written for the field in compatible mode
// In compatible mode, Java writes type info for struct and ext types, but NOT for enum types
// Enum fields only have null flag + ordinal, no type ID
func isStructFieldType(ft FieldType) bool {
    if ft == nil {
        return false
    }
    typeId := ft.TypeId()
    // Check base type IDs that need type info (struct and ext, NOT enum)
    // Always check the internal type ID (low byte) to handle composite type IDs
    // which may be negative when stored as int32 (e.g., -2288 = (short)128784)
    internalTypeId := TypeId(typeId & 0xFF)
    switch internalTypeId {
    case STRUCT, NAMED_STRUCT, COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT,
        EXT, NAMED_EXT:
        return true
    }
    return false
}

// FieldFingerprintInfo contains the information needed to compute a field's fingerprint.
type FieldFingerprintInfo struct {
    // FieldID is the tag ID if configured (>= 0), or -1 to use field name
    FieldID int
    // FieldName is the snake_case field name (used when FieldID < 0)
    FieldName string
    // TypeID is the Fory type ID for the field
    TypeID TypeId
    // Ref is true if reference tracking is enabled for this field
    Ref bool
    // Nullable is true if null flag is written for this field
    Nullable bool
}

// ComputeStructFingerprint computes the fingerprint string for a struct type.
//
// Fingerprint Format:
//
//	Each field contributes: "<field_id_or_name>,<type_id>,<ref>,<nullable>;"
//	Fields are sorted by field_id_or_name (lexicographically as strings)
//
// Field Components:
//   - field_id_or_name: Tag ID as string if configured (e.g., "0", "1"), otherwise snake_case field name
//   - type_id: Fory TypeId as decimal string (e.g., "4" for INT32)
//   - ref: "1" if reference tracking enabled, "0" otherwise
//   - nullable: "1" if null flag is written, "0" otherwise
//
// Example fingerprints:
//   - With tag IDs: "0,4,0,0;1,4,0,1;2,9,0,1;"
//   - With field names: "age,4,0,0;name,9,0,1;"
//
// The fingerprint is used to compute a hash for struct schema versioning.
// Different nullable/ref settings will produce different fingerprints,
// ensuring schema compatibility is properly validated.
func ComputeStructFingerprint(fields []FieldFingerprintInfo) string {
    // Sort fields by their identifier (field ID or name)
    type fieldWithKey struct {
        field   FieldFingerprintInfo
        sortKey string
    }
    fieldsWithKeys := make([]fieldWithKey, 0, len(fields))
    for _, field := range fields {
        var sortKey string
        if field.FieldID >= 0 {
            sortKey = fmt.Sprintf("%d", field.FieldID)
        } else {
            sortKey = field.FieldName
        }
        fieldsWithKeys = append(fieldsWithKeys, fieldWithKey{field: field, sortKey: sortKey})
    }

    sort.Slice(fieldsWithKeys, func(i, j int) bool {
        return fieldsWithKeys[i].sortKey < fieldsWithKeys[j].sortKey
    })

    var sb strings.Builder
    for _, fw := range fieldsWithKeys {
        // Field identifier
        sb.WriteString(fw.sortKey)
        sb.WriteString(",")
        // Type ID
        sb.WriteString(fmt.Sprintf("%d", fw.field.TypeID))
        sb.WriteString(",")
        // Ref flag
        if fw.field.Ref {
            sb.WriteString("1")
        } else {
            sb.WriteString("0")
        }
        sb.WriteString(",")
        // Nullable flag
        if fw.field.Nullable {
            sb.WriteString("1")
        } else {
            sb.WriteString("0")
        }
        sb.WriteString(";")
    }
    return sb.String()
}

func (s *structSerializer) computeHash() int32 {
    // Build FieldFingerprintInfo for each field
    fields := make([]FieldFingerprintInfo, 0, len(s.fields))
    for _, field := range s.fields {
        var typeId TypeId
        isEnumField := false
        if field.Serializer == nil {
            typeId = UNKNOWN
        } else {
            typeId = field.TypeId
            // Check if this is an enum serializer (directly or wrapped in ptrToValueSerializer)
            if _, ok := field.Serializer.(*enumSerializer); ok {
                isEnumField = true
                typeId = UNKNOWN
            } else if ptrSer, ok := field.Serializer.(*ptrToValueSerializer); ok {
                if _, ok := ptrSer.valueSerializer.(*enumSerializer); ok {
                    isEnumField = true
                    typeId = UNKNOWN
                }
            }
            // For user-defined types (struct, ext types), use UNKNOWN in fingerprint
            // This matches Java's behavior where user-defined types return UNKNOWN
            // to ensure consistent fingerprint computation across languages
            if isUserDefinedType(int16(typeId)) {
                typeId = UNKNOWN
            }
            // For fixed-size arrays with primitive elements, use primitive array type IDs
            if field.Type.Kind() == reflect.Array {
                elemKind := field.Type.Elem().Kind()
                switch elemKind {
                case reflect.Int8:
                    typeId = INT8_ARRAY
                case reflect.Int16:
                    typeId = INT16_ARRAY
                case reflect.Int32:
                    typeId = INT32_ARRAY
                case reflect.Int64:
                    typeId = INT64_ARRAY
                case reflect.Float32:
                    typeId = FLOAT32_ARRAY
                case reflect.Float64:
                    typeId = FLOAT64_ARRAY
                default:
                    typeId = LIST
                }
            } else if field.Type.Kind() == reflect.Slice {
                typeId = LIST
            } else if field.Type.Kind() == reflect.Map {
                // map[T]bool is used to represent a Set in Go
                if field.Type.Elem().Kind() == reflect.Bool {
                    typeId = SET
                } else {
                    typeId = MAP
                }
            }
        }

        // Determine nullable flag for xlang compatibility:
        // - Default: false for ALL fields (xlang default - aligned with all languages)
        // - Primitives are always non-nullable
        // - Can be overridden by explicit fory tag
        nullable := false // Default to nullable=false for xlang mode
        if field.TagNullableSet {
            // Use explicit tag value if set
            nullable = field.TagNullable
        }
        // Primitives are never nullable, regardless of tag
        if isNonNullablePrimitiveKind(field.Type.Kind()) && !isEnumField {
            nullable = false
        }

        fields = append(fields, FieldFingerprintInfo{
            FieldID:   field.TagID,
            FieldName: SnakeCase(field.Name),
            TypeID:    typeId,
            // Ref is based on explicit tag annotation only, NOT runtime ref_tracking config
            // This allows fingerprint to be computed at compile time for C++/Rust
            Ref:      field.TagRefSet && field.TagRef,
            Nullable: nullable,
        })
    }

    hashString := ComputeStructFingerprint(fields)
    data := []byte(hashString)
    h1, _ := murmur3.Sum128WithSeed(data, 47)
    hash := int32(h1 & 0xFFFFFFFF)

    if DebugOutputEnabled() {
        fmt.Printf("[Go][fory-debug] struct %v version fingerprint=\"%s\" version hash=%d\n", s.type_, hashString, hash)
    }

    if hash == 0 {
        panic(fmt.Errorf("hash for type %v is 0", s.type_))
    }
    return hash
}

// GetStructHash returns the struct hash for a given type using the provided TypeResolver.
// This is used by codegen serializers to get the hash at runtime.
func GetStructHash(type_ reflect.Type, resolver *TypeResolver) int32 {
    ser := newStructSerializer(type_, "", nil)
    if err := ser.initialize(resolver); err != nil {
        panic(fmt.Errorf("failed to initialize struct serializer for hash computation: %v", err))
    }
    return ser.structHash
}

// Field sorting helpers

type triple struct {
    typeID     int16
    serializer Serializer
    name       string
    nullable   bool
    tagID      int // -1 = use field name, >=0 = use tag ID for sorting
}

// getFieldSortKey returns the sort key for a field.
// If tagID >= 0, returns the tag ID as string (for tag-based sorting).
// Otherwise returns the snake_case field name.
func (t triple) getSortKey() string {
    if t.tagID >= 0 {
        return fmt.Sprintf("%d", t.tagID)
    }
    return SnakeCase(t.name)
}

// sortFields sorts fields with nullable information to match Java's field ordering.
// Java separates primitive types (int, long) from boxed types (Integer, Long).
// In Go, this corresponds to non-pointer primitives vs pointer-to-primitive.
// When tagIDs are provided (>= 0), fields are sorted by tag ID instead of field name.
func sortFields(
    typeResolver *TypeResolver,
    fieldNames []string,
    serializers []Serializer,
    typeIds []TypeId,
    nullables []bool,
    tagIDs []int,
) ([]Serializer, []string) {
    var (
        typeTriples []triple
        others      []triple
        userDefined []triple
    )

    for i, name := range fieldNames {
        ser := serializers[i]
        tagID := TagIDUseFieldName // default: use field name
        if tagIDs != nil && i < len(tagIDs) {
            tagID = tagIDs[i]
        }
        if ser == nil {
            others = append(others, triple{UNKNOWN, nil, name, nullables[i], tagID})
            continue
        }
        typeTriples = append(typeTriples, triple{typeIds[i], ser, name, nullables[i], tagID})
    }
    // Java orders: primitives, boxed, finals, others, collections, maps
    // primitives = non-nullable primitive types (int, long, etc.)
    // boxed = nullable boxed types (Integer, Long, etc. which are pointers in Go)
    var primitives, boxed, collection, setFields, maps, otherInternalTypeFields []triple

    for _, t := range typeTriples {
        switch {
        case isPrimitiveType(t.typeID):
            // Separate non-nullable primitives from nullable (boxed) primitives
            if t.nullable {
                boxed = append(boxed, t)
            } else {
                primitives = append(primitives, t)
            }
        case isListType(t.typeID), isPrimitiveArrayType(t.typeID):
            collection = append(collection, t)
        case isSetType(t.typeID):
            setFields = append(setFields, t)
        case isMapType(t.typeID):
            maps = append(maps, t)
        case isUserDefinedType(t.typeID):
            userDefined = append(userDefined, t)
        case t.typeID == UNKNOWN:
            others = append(others, t)
        default:
            otherInternalTypeFields = append(otherInternalTypeFields, t)
        }
    }
    // Sort primitives (non-nullable) - same logic as boxed
    // Java sorts by: compressed types last, then by size (largest first), then by type ID (descending)
    sortPrimitiveSlice := func(s []triple) {
        sort.Slice(s, func(i, j int) bool {
            ai, aj := s[i], s[j]
            compressI := ai.typeID == INT32 || ai.typeID == INT64 ||
                ai.typeID == VAR_INT32 || ai.typeID == VAR_INT64
            compressJ := aj.typeID == INT32 || aj.typeID == INT64 ||
                aj.typeID == VAR_INT32 || aj.typeID == VAR_INT64
            if compressI != compressJ {
                return !compressI && compressJ
            }
            szI, szJ := getPrimitiveTypeSize(ai.typeID), getPrimitiveTypeSize(aj.typeID)
            if szI != szJ {
                return szI > szJ
            }
            // Tie-breaker: type ID descending (higher type ID first), then field name
            if ai.typeID != aj.typeID {
                return ai.typeID > aj.typeID
            }
            return ai.getSortKey() < aj.getSortKey()
        })
    }
    sortPrimitiveSlice(primitives)
    sortPrimitiveSlice(boxed)
    sortByTypeIDThenName := func(s []triple) {
        sort.Slice(s, func(i, j int) bool {
            if s[i].typeID != s[j].typeID {
                return s[i].typeID < s[j].typeID
            }
            return s[i].getSortKey() < s[j].getSortKey()
        })
    }
    sortTuple := func(s []triple) {
        sort.Slice(s, func(i, j int) bool {
            return s[i].getSortKey() < s[j].getSortKey()
        })
    }
    sortByTypeIDThenName(otherInternalTypeFields)
    sortTuple(others)
    sortTuple(collection)
    sortTuple(setFields)
    sortTuple(maps)
    sortTuple(userDefined)

    // Java order: primitives, boxed, finals, collections, maps, others
    // finals = String and other monomorphic types (otherInternalTypeFields)
    // others = userDefined types (structs, enums) and unknown types
    all := make([]triple, 0, len(fieldNames))
    all = append(all, primitives...)
    all = append(all, boxed...)
    all = append(all, otherInternalTypeFields...) // finals (String, etc.)
    all = append(all, collection...)
    all = append(all, setFields...)
    all = append(all, maps...)
    all = append(all, userDefined...) // others (structs, enums)
    all = append(all, others...)      // unknown types

    outSer := make([]Serializer, len(all))
    outNam := make([]string, len(all))
    for i, t := range all {
        outSer[i] = t.serializer
        outNam[i] = t.name
    }
    return outSer, outNam
}

func typesCompatible(actual, expected reflect.Type) bool {
    if actual == nil || expected == nil {
        return false
    }
    if actual == expected {
        return true
    }
    // interface{} can accept any value
    if actual.Kind() == reflect.Interface && actual.NumMethod() == 0 {
        return true
    }
    if actual.AssignableTo(expected) || expected.AssignableTo(actual) {
        return true
    }
    if actual.Kind() == reflect.Ptr && actual.Elem() == expected {
        return true
    }
    if expected.Kind() == reflect.Ptr && expected.Elem() == actual {
        return true
    }
    if actual.Kind() == expected.Kind() {
        switch actual.Kind() {
        case reflect.Slice, reflect.Array:
            return elementTypesCompatible(actual.Elem(), expected.Elem())
        case reflect.Map:
            return elementTypesCompatible(actual.Key(), expected.Key()) && elementTypesCompatible(actual.Elem(), expected.Elem())
        }
    }
    if (actual.Kind() == reflect.Array && expected.Kind() == reflect.Slice) ||
        (actual.Kind() == reflect.Slice && expected.Kind() == reflect.Array) {
        return true
    }
    return false
}

func elementTypesCompatible(actual, expected reflect.Type) bool {
    if actual == nil || expected == nil {
        return false
    }
    if actual == expected || actual.AssignableTo(expected) || expected.AssignableTo(actual) {
        return true
    }
    if actual.Kind() == reflect.Ptr {
        return elementTypesCompatible(actual, expected.Elem())
    }
    return false
}

// typeIdFromKind derives a TypeId from a reflect.Type's kind
// This is used when the type is not registered in typesInfo
func typeIdFromKind(type_ reflect.Type) TypeId {
    switch type_.Kind() {
    case reflect.Bool:
        return BOOL
    case reflect.Int8:
        return INT8
    case reflect.Int16:
        return INT16
    case reflect.Int32:
        return INT32
    case reflect.Int64, reflect.Int:
        return INT64
    case reflect.Uint8:
        return UINT8
    case reflect.Uint16:
        return UINT16
    case reflect.Uint32:
        return UINT32
    case reflect.Uint64, reflect.Uint:
        return UINT64
    case reflect.Float32:
        return FLOAT
    case reflect.Float64:
        return DOUBLE
    case reflect.String:
        return STRING
    case reflect.Slice:
        // For slices, return the appropriate primitive array type ID based on element type
        elemKind := type_.Elem().Kind()
        switch elemKind {
        case reflect.Bool:
            return BOOL_ARRAY
        case reflect.Int8:
            return INT8_ARRAY
        case reflect.Int16:
            return INT16_ARRAY
        case reflect.Int32:
            return INT32_ARRAY
        case reflect.Int64, reflect.Int:
            return INT64_ARRAY
        case reflect.Float32:
            return FLOAT32_ARRAY
        case reflect.Float64:
            return FLOAT64_ARRAY
        default:
            // Non-primitive slices use LIST
            return LIST
        }
    case reflect.Array:
        // For arrays, return the appropriate primitive array type ID based on element type
        elemKind := type_.Elem().Kind()
        switch elemKind {
        case reflect.Bool:
            return BOOL_ARRAY
        case reflect.Int8:
            return INT8_ARRAY
        case reflect.Int16:
            return INT16_ARRAY
        case reflect.Int32:
            return INT32_ARRAY
        case reflect.Int64, reflect.Int:
            return INT64_ARRAY
        case reflect.Float32:
            return FLOAT32_ARRAY
        case reflect.Float64:
            return FLOAT64_ARRAY
        default:
            // Non-primitive arrays use LIST
            return LIST
        }
    case reflect.Map:
        // map[T]bool is used to represent a Set in Go
        if type_.Elem().Kind() == reflect.Bool {
            return SET
        }
        return MAP
    case reflect.Struct:
        return NAMED_STRUCT
    case reflect.Ptr:
        // For pointer types, get the type ID of the element type
        return typeIdFromKind(type_.Elem())
    default:
        return UNKNOWN
    }
}

// skipStructSerializer is a serializer that skips unknown struct data
// It reads and discards field data based on fieldDefs from remote TypeDef
type skipStructSerializer struct {
    fieldDefs []FieldDef
}

func (s *skipStructSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
    ctx.SetError(SerializationError("skipStructSerializer does not support WriteData - unknown struct type"))
}

func (s *skipStructSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
    ctx.SetError(SerializationError("skipStructSerializer does not support Write - unknown struct type"))
}

func (s *skipStructSerializer) ReadData(ctx *ReadContext, type_ reflect.Type, value reflect.Value) {
    // Skip all fields based on fieldDefs from remote TypeDef
    for _, fieldDef := range s.fieldDefs {
        isStructType := isStructFieldType(fieldDef.fieldType)
        // Use trackingRef from FieldDef for ref flag decision
        SkipFieldValueWithTypeFlag(ctx, fieldDef, fieldDef.trackingRef, ctx.Compatible() && isStructType)
        if ctx.HasError() {
            return
        }
    }
}

func (s *skipStructSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
    buf := ctx.Buffer()
    ctxErr := ctx.Err()
    switch refMode {
    case RefModeTracking:
        refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
        if refErr != nil {
            ctx.SetError(FromError(refErr))
            return
        }
        if refID < int32(NotNullValueFlag) {
            // Reference found, nothing to skip
            return
        }
    case RefModeNullOnly:
        flag := buf.ReadInt8(ctxErr)
        if flag == NullFlag {
            return
        }
    }
    if ctx.HasError() {
        return
    }
    s.ReadData(ctx, nil, value)
}

func (s *skipStructSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
    // typeInfo is already read, don't read it again - just skip data
    s.Read(ctx, refMode, false, false, value)
}
