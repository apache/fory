package fory

import (
    "fmt"
    "reflect"
)

type boolArraySerializer struct {
}

func (s boolArraySerializer) TypeId() TypeId {
    return BOOL_ARRAY
}

func (s boolArraySerializer) NeedWriteRef() int8 {
    return 1
}

func (s boolArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
    if value.Kind() == reflect.Interface {
        value = value.Elem()
    }
    size := value.Len()
    if size >= MaxInt32 {
        return fmt.Errorf("too long slice: %d", size)
    }
    buf.WriteLength(size)
    for i := 0; i < size; i++ {
        buf.WriteBool(value.Index(i).Bool())
    }
    return nil
}

func (s boolArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
    length := buf.ReadLength()
    var r reflect.Value
    switch type_.Kind() {
    case reflect.Slice:
        if type_.Elem().Kind() != reflect.Bool {
            return fmt.Errorf("element kind must be bool, got %v", type_.Elem())
        }
        r = reflect.MakeSlice(type_, length, length)

    case reflect.Array:
        if type_.Elem().Kind() != reflect.Bool {
            return fmt.Errorf("element kind must be bool, got %v", type_.Elem())
        }
        if length != type_.Len() {
            return fmt.Errorf("length %d does not match array type %v", length, type_)
        }
        r = reflect.New(type_).Elem()

    case reflect.Interface:
        arrT := reflect.ArrayOf(length, reflect.TypeOf(true)) // [length]bool
        r = reflect.New(arrT).Elem()

    default:
        return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
    }

    for i := 0; i < length; i++ {
        b := buf.ReadBool()
        r.Index(i).SetBool(b)
    }

    value.Set(r)
    return nil
}

type int8ArraySerializer struct {
}

func (s int8ArraySerializer) TypeId() TypeId {
    return INT8_ARRAY
}

func (s int8ArraySerializer) NeedWriteRef() int8 {
    return 1
}

func (s int8ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
    v := value.Interface().([]int8)
    size := len(v)
    if size >= MaxInt32 {
        return fmt.Errorf("too long slice: %d", len(v))
    }
    buf.WriteLength(size)
    for _, elem := range v {
        buf.WriteByte_(byte(elem))
    }
    return nil
}

func (s int8ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
    length := buf.ReadLength()
    var r reflect.Value
    switch type_.Kind() {
    case reflect.Slice:
        if type_.Elem().Kind() != reflect.Bool {
            return fmt.Errorf("element kind must be bool, got %v", type_.Elem())
        }
        r = reflect.MakeSlice(type_, length, length)

    case reflect.Array:
        if type_.Elem().Kind() != reflect.Bool {
            return fmt.Errorf("element kind must be bool, got %v", type_.Elem())
        }
        if length != type_.Len() {
            return fmt.Errorf("length %d does not match array type %v", length, type_)
        }
        r = reflect.New(type_).Elem()

    case reflect.Interface:
        arrT := reflect.ArrayOf(length, reflect.TypeOf(true)) // [length]bool
        r = reflect.New(arrT).Elem()

    default:
        return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
    }

    for i := 0; i < length; i++ {
        b, _ := buf.ReadByte()
        r.Index(i).SetInt(int64(b))
    }

    value.Set(r)
    return nil
}

type int16ArraySerializer struct {
}

func (s int16ArraySerializer) TypeId() TypeId {
    return INT16_ARRAY
}

func (s int16ArraySerializer) NeedWriteRef() int8 {
    return 1
}

func (s int16ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
    if value.Kind() == reflect.Interface {
        value = value.Elem()
    }
    size := value.Len() * 2
    if size >= MaxInt32 {
        return fmt.Errorf("too long slice: %d", value.Len())
    }
    buf.WriteLength(size)
    for i := 0; i < value.Len(); i++ {
        buf.WriteInt16(int16(value.Index(i).Int()))
    }
    return nil
}

func (s int16ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
    length := buf.ReadLength() / 2
    var r reflect.Value
    switch type_.Kind() {
    case reflect.Slice:
        if type_.Elem().Kind() != reflect.Int16 {
            return fmt.Errorf("element kind must be Int16, got %v", type_.Elem())
        }
        r = reflect.MakeSlice(type_, length, length)

    case reflect.Array:
        if type_.Elem().Kind() != reflect.Int16 {
            return fmt.Errorf("element kind must be Int16, got %v", type_.Elem())
        }
        if length != type_.Len() {
            return fmt.Errorf("length %d does not match array type %v", length, type_)
        }
        r = reflect.New(type_).Elem()

    case reflect.Interface:
        arrT := reflect.ArrayOf(length, reflect.TypeOf(int16(0))) // [length]int16
        r = reflect.New(arrT).Elem()

    default:
        return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
    }

    for i := 0; i < length; i++ {
        fmt.Print("有进来读吗兄弟")
        b := buf.ReadInt16()
        r.Index(i).SetInt(int64(b))
    }
    value.Set(r)
    return nil
}

type int32ArraySerializer struct {
}

func (s int32ArraySerializer) TypeId() TypeId {
    return INT32_ARRAY
}

func (s int32ArraySerializer) NeedWriteRef() int8 {
    return 1
}

func (s int32ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
    if value.Kind() == reflect.Interface {
        value = value.Elem()
    }
    size := value.Len() * 4
    if size >= MaxInt32 {
        return fmt.Errorf("too long slice: %d", value.Len())
    }
    buf.WriteLength(size)
    for i := 0; i < value.Len(); i++ {
        buf.WriteInt32(int32(value.Index(i).Int()))
    }
    return nil
}

func (s int32ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
    length := buf.ReadLength() / 4
    var r reflect.Value
    switch type_.Kind() {
    case reflect.Slice:
        if type_.Elem().Kind() != reflect.Int32 {
            return fmt.Errorf("element kind must be int32, got %v", type_.Elem())
        }
        r = reflect.MakeSlice(type_, length, length)

    case reflect.Array:
        if type_.Elem().Kind() != reflect.Int32 {
            return fmt.Errorf("element kind must be int32, got %v", type_.Elem())
        }
        if length != type_.Len() {
            return fmt.Errorf("length %d does not match array type %v", length, type_)
        }
        r = reflect.New(type_).Elem()

    case reflect.Interface:
        arrT := reflect.ArrayOf(length, reflect.TypeOf(int32(0))) // [length]int32
        r = reflect.New(arrT).Elem()

    default:
        return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
    }

    for i := 0; i < length; i++ {
        b := buf.ReadInt32()
        r.Index(i).SetInt(int64(b))
    }
    value.Set(r)
    return nil
}

type int64ArraySerializer struct {
}

func (s int64ArraySerializer) TypeId() TypeId {
    return INT64_ARRAY
}

func (s int64ArraySerializer) NeedWriteRef() int8 {
    return 1
}

func (s int64ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
    if value.Kind() == reflect.Interface {
        value = value.Elem()
    }
    size := value.Len() * 8
    if size >= MaxInt32 {
        return fmt.Errorf("too long slice: %d", value.Len())
    }
    buf.WriteLength(size)
    for i := 0; i < value.Len(); i++ {
        buf.WriteInt64(value.Index(i).Int())
    }
    return nil
}

func (s int64ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
    length := buf.ReadLength() / 8
    var r reflect.Value
    switch type_.Kind() {
    case reflect.Slice:
        if type_.Elem().Kind() != reflect.Int64 {
            return fmt.Errorf("element kind must be int64, got %v", type_.Elem())
        }
        r = reflect.MakeSlice(type_, length, length)

    case reflect.Array:
        if type_.Elem().Kind() != reflect.Int64 {
            return fmt.Errorf("element kind must be int64, got %v", type_.Elem())
        }
        if length != type_.Len() {
            return fmt.Errorf("length %d does not match array type %v", length, type_)
        }
        r = reflect.New(type_).Elem()

    case reflect.Interface:
        arrT := reflect.ArrayOf(length, reflect.TypeOf(int64(0))) // [length]int32
        r = reflect.New(arrT).Elem()

    default:
        return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
    }

    for i := 0; i < length; i++ {
        b := buf.ReadInt64()
        r.Index(i).SetInt(int64(b))
    }
    value.Set(r)
    return nil
}

type float32ArraySerializer struct {
}

func (s float32ArraySerializer) TypeId() TypeId {
    return FLOAT32_ARRAY
}

func (s float32ArraySerializer) NeedWriteRef() int8 {
    return 1
}

func (s float32ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
    if value.Kind() == reflect.Interface {
        value = value.Elem()
    }
    size := value.Len() * 4
    if size >= MaxInt32 {
        return fmt.Errorf("too long slice: %d", value.Len())
    }
    buf.WriteLength(size)
    for i := 0; i < value.Len(); i++ {
        buf.WriteFloat32(float32(value.Index(i).Float()))
    }
    return nil
}

func (s float32ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
    length := buf.ReadLength() / 4
    var r reflect.Value
    switch type_.Kind() {
    case reflect.Slice:
        if type_.Elem().Kind() != reflect.Float32 {
            return fmt.Errorf("element kind must be float32, got %v", type_.Elem())
        }
        r = reflect.MakeSlice(type_, length, length)

    case reflect.Array:
        if type_.Elem().Kind() != reflect.Float32 {
            return fmt.Errorf("element kind must be float32, got %v", type_.Elem())
        }
        if length != type_.Len() {
            return fmt.Errorf("length %d does not match array type %v", length, type_)
        }
        r = reflect.New(type_).Elem()

    case reflect.Interface:
        arrT := reflect.ArrayOf(length, reflect.TypeOf(float32(0))) // [length]int32
        r = reflect.New(arrT).Elem()

    default:
        return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
    }

    for i := 0; i < length; i++ {
        r.Index(i).SetFloat(float64(buf.ReadFloat32()))
    }
    value.Set(r)
    return nil
}

type float64ArraySerializer struct {
}

func (s float64ArraySerializer) TypeId() TypeId {
    return FLOAT64_ARRAY
}

func (s float64ArraySerializer) NeedWriteRef() int8 {
    return 1
}

func (s float64ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
    if value.Kind() == reflect.Interface {
        value = value.Elem()
    }
    size := value.Len() * 8
    if size >= MaxInt32 {
        return fmt.Errorf("too long slice: %d", value.Len())
    }
    buf.WriteLength(size)
    for i := 0; i < value.Len(); i++ {
        buf.WriteFloat64(value.Index(i).Float())
    }
    return nil
}

func (s float64ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
    length := buf.ReadLength() / 8
    var r reflect.Value
    switch type_.Kind() {
    case reflect.Slice:
        if type_.Elem().Kind() != reflect.Float64 {
            return fmt.Errorf("element kind must be float64, got %v", type_.Elem())
        }
        r = reflect.MakeSlice(type_, length, length)

    case reflect.Array:
        if type_.Elem().Kind() != reflect.Float64 {
            return fmt.Errorf("element kind must be float64, got %v", type_.Elem())
        }
        if length != type_.Len() {
            return fmt.Errorf("length %d does not match array type %v", length, type_)
        }
        r = reflect.New(type_).Elem()

    case reflect.Interface:
        arrT := reflect.ArrayOf(length, reflect.TypeOf(float64(0))) // [length]int32
        r = reflect.New(arrT).Elem()

    default:
        return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
    }

    for i := 0; i < length; i++ {
        r.Index(i).SetFloat(buf.ReadFloat64())
    }
    value.Set(r)
    return nil
}
