package main

import (
	"bytes"
	"crypto/md5"
	"encoding/binary"
	"flag"
	"fmt"
	"go/format"
	"go/types"
	"io/ioutil"
	"log"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode"

	"golang.org/x/tools/go/packages"
)

var (
	typeFlag = flag.String("type", "", "comma-separated list of types to generate code for")
	pkgFlag  = flag.String("pkg", ".", "package directory to search for types")
)

// StructInfo contains metadata about a struct to generate code for
type StructInfo struct {
	Name   string
	Fields []*FieldInfo
}

// FieldInfo contains metadata about a struct field
type FieldInfo struct {
	GoName        string     // Original Go field name
	SnakeName     string     // snake_case field name for sorting
	Type          types.Type // Go type information
	Index         int        // Original field index in struct
	IsPrimitive   bool       // Whether it's a Fory primitive type
	IsPointer     bool       // Whether it's a pointer type
	TypeID        string     // Fory TypeID for sorting
	PrimitiveSize int        // Size for primitive type sorting
}

func main() {
	flag.Parse()

	if err := run(); err != nil {
		log.Fatalf("forygen failed: %v", err)
	}
}

func run() error {
	// Load packages
	cfg := &packages.Config{
		Mode: packages.NeedTypes | packages.NeedSyntax | packages.NeedName | packages.NeedFiles | packages.NeedTypesInfo,
	}

	pkgs, err := packages.Load(cfg, *pkgFlag)
	if err != nil {
		return fmt.Errorf("loading packages: %w", err)
	}

	if len(pkgs) == 0 {
		return fmt.Errorf("no packages found")
	}

	if packages.PrintErrors(pkgs) > 0 {
		return fmt.Errorf("errors in packages")
	}

	// Process each package
	for _, pkg := range pkgs {
		if err := processPackage(pkg); err != nil {
			return fmt.Errorf("processing package %s: %w", pkg.PkgPath, err)
		}
	}

	return nil
}

func processPackage(pkg *packages.Package) error {
	// Find structs to generate code for
	var targetTypes []string
	if *typeFlag != "" {
		targetTypes = strings.Split(*typeFlag, ",")
	}

	var structs []*StructInfo

	// Check if package has types
	if pkg.Types == nil {
		return fmt.Errorf("package %s has no type information", pkg.PkgPath)
	}

	// Iterate through all types in the package
	scope := pkg.Types.Scope()
	allNames := scope.Names()

	// Also check if there are any compilation errors
	if len(pkg.Errors) > 0 {
		for _, err := range pkg.Errors {
			log.Printf("package error: %s", err)
		}
	}

	for _, name := range allNames {
		obj := scope.Lookup(name)
		if obj == nil {
			continue
		}

		// Check if it's a named type
		named, ok := obj.Type().(*types.Named)
		if !ok {
			continue
		}

		// Check if underlying type is struct
		structType, ok := named.Underlying().(*types.Struct)
		if !ok {
			continue
		}

		// Check if we should generate code for this type
		shouldGenerate := false
		if len(targetTypes) > 0 {
			for _, t := range targetTypes {
				if strings.TrimSpace(t) == name {
					shouldGenerate = true
					break
				}
			}
		} else {
			// TODO: Check for fory:gen comment
			shouldGenerate = false
		}

		if !shouldGenerate {
			continue
		}

		// Extract struct information
		structInfo, err := extractStructInfo(name, structType)
		if err != nil {
			return fmt.Errorf("extracting struct info for %s: %w", name, err)
		}

		structs = append(structs, structInfo)
	}

	if len(structs) == 0 {
		return nil // No structs to generate
	}

	// Generate code
	return generateCode(pkg, structs)
}

func extractStructInfo(name string, structType *types.Struct) (*StructInfo, error) {
	var fields []*FieldInfo

	for i := 0; i < structType.NumFields(); i++ {
		field := structType.Field(i)
		if !field.Exported() {
			continue // Skip unexported fields
		}

		fieldInfo, err := analyzeField(field, i)
		if err != nil {
			return nil, fmt.Errorf("analyzing field %s: %w", field.Name(), err)
		}

		if fieldInfo == nil {
			continue // Skip unsupported fields
		}

		fields = append(fields, fieldInfo)
	}

	// Sort fields according to Fory protocol
	sortFields(fields)

	return &StructInfo{
		Name:   name,
		Fields: fields,
	}, nil
}

func analyzeField(field *types.Var, index int) (*FieldInfo, error) {
	fieldType := field.Type()
	goName := field.Name()
	snakeName := toSnakeCase(goName)

	// Check if field type is supported in PR1
	if !isSupportedFieldType(fieldType) {
		return nil, nil // Skip unsupported types
	}

	// Analyze type information
	isPrimitive := isPrimitiveType(fieldType)
	isPointer := false
	typeID := getTypeID(fieldType)
	primitiveSize := getPrimitiveSize(fieldType)

	// Handle pointer types
	if ptr, ok := fieldType.(*types.Pointer); ok {
		isPointer = true
		fieldType = ptr.Elem()
		isPrimitive = isPrimitiveType(fieldType)
		typeID = getTypeID(fieldType)
		primitiveSize = getPrimitiveSize(fieldType)
	}

	return &FieldInfo{
		GoName:        goName,
		SnakeName:     snakeName,
		Type:          field.Type(),
		Index:         index,
		IsPrimitive:   isPrimitive,
		IsPointer:     isPointer,
		TypeID:        typeID,
		PrimitiveSize: primitiveSize,
	}, nil
}

func isSupportedFieldType(t types.Type) bool {
	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	// Check named types
	if named, ok := t.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time", "github.com/apache/fory/go/fory.Date":
			return true
		}
		// Check if it's another struct
		if _, ok := named.Underlying().(*types.Struct); ok {
			return true
		}
	}

	// Check basic types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Int16, types.Int32, types.Int, types.Int64,
			types.Uint8, types.Uint16, types.Uint32, types.Uint, types.Uint64,
			types.Float32, types.Float64, types.String:
			return true
		}
	}

	return false
}

func isPrimitiveType(t types.Type) bool {
	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	// Check basic types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Int16, types.Int32, types.Int, types.Int64,
			types.Uint8, types.Uint16, types.Uint32, types.Uint, types.Uint64,
			types.Float32, types.Float64:
			return true
		}
	}

	// String is also considered primitive in Fory context but nullable
	if basic, ok := t.Underlying().(*types.Basic); ok && basic.Kind() == types.String {
		return true
	}

	return false
}

func getTypeID(t types.Type) string {
	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	// Check named types first
	if named, ok := t.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			return "TIMESTAMP"
		case "github.com/apache/fory/go/fory.Date":
			return "LOCAL_DATE"
		}
		// Struct types
		if _, ok := named.Underlying().(*types.Struct); ok {
			return "NAMED_STRUCT"
		}
	}

	// Check basic types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			return "BOOL"
		case types.Int8:
			return "INT8"
		case types.Int16:
			return "INT16"
		case types.Int32:
			return "INT32"
		case types.Int, types.Int64:
			return "INT64"
		case types.Uint8:
			return "UINT8"
		case types.Uint16:
			return "UINT16"
		case types.Uint32:
			return "UINT32"
		case types.Uint, types.Uint64:
			return "UINT64"
		case types.Float32:
			return "FLOAT32"
		case types.Float64:
			return "FLOAT64"
		case types.String:
			return "STRING"
		}
	}

	return "UNKNOWN"
}

func getPrimitiveSize(t types.Type) int {
	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Uint8:
			return 1
		case types.Int16, types.Uint16:
			return 2
		case types.Int32, types.Uint32, types.Float32:
			return 4
		case types.Int, types.Int64, types.Uint, types.Uint64, types.Float64:
			return 8
		case types.String:
			return 999 // Variable size, sort last among primitives
		}
	}

	return 0
}

func getTypeIDValue(typeID string) int {
	// Map Fory TypeIDs to numeric values for sorting
	typeIDMap := map[string]int{
		"BOOL":         1,
		"INT8":         2,
		"INT16":        3,
		"INT32":        4,
		"INT64":        5,
		"UINT8":        6,
		"UINT16":       7,
		"UINT32":       8,
		"UINT64":       9,
		"FLOAT32":      10,
		"FLOAT64":      11,
		"STRING":       12,
		"TIMESTAMP":    20,
		"LOCAL_DATE":   21,
		"NAMED_STRUCT": 30,
	}

	if val, ok := typeIDMap[typeID]; ok {
		return val
	}
	return 999
}

func toSnakeCase(s string) string {
	var result []rune
	for i, r := range s {
		if i > 0 && unicode.IsUpper(r) {
			result = append(result, '_')
		}
		result = append(result, unicode.ToLower(r))
	}
	return string(result)
}

func sortFields(fields []*FieldInfo) {
	sort.Slice(fields, func(i, j int) bool {
		f1, f2 := fields[i], fields[j]

		// Group primitives first
		if f1.IsPrimitive && !f2.IsPrimitive {
			return true
		}
		if !f1.IsPrimitive && f2.IsPrimitive {
			return false
		}

		if f1.IsPrimitive && f2.IsPrimitive {
			// Sort primitives by size (descending), then by type ID, then by name
			if f1.PrimitiveSize != f2.PrimitiveSize {
				return f1.PrimitiveSize > f2.PrimitiveSize
			}
			if f1.TypeID != f2.TypeID {
				return getTypeIDValue(f1.TypeID) < getTypeIDValue(f2.TypeID)
			}
			return f1.SnakeName < f2.SnakeName
		}

		// Sort non-primitives by type ID, then by name
		if f1.TypeID != f2.TypeID {
			return getTypeIDValue(f1.TypeID) < getTypeIDValue(f2.TypeID)
		}
		return f1.SnakeName < f2.SnakeName
	})
}

func computeStructHash(s *StructInfo) int32 {
	h := md5.New()

	// Write struct name
	h.Write([]byte(s.Name))

	// Write sorted field information
	for _, field := range s.Fields {
		h.Write([]byte(field.SnakeName))
		h.Write([]byte(field.TypeID))
		// Add primitive size for better differentiation
		if field.IsPrimitive {
			sizeBytes := make([]byte, 4)
			binary.LittleEndian.PutUint32(sizeBytes, uint32(field.PrimitiveSize))
			h.Write(sizeBytes)
		}
	}

	hashBytes := h.Sum(nil)
	// Take first 4 bytes as int32
	return int32(binary.LittleEndian.Uint32(hashBytes[:4]))
}

func generateCode(pkg *packages.Package, structs []*StructInfo) error {
	var buf bytes.Buffer

	// Generate file header
	fmt.Fprintf(&buf, "// Code generated by forygen. DO NOT EDIT.\n")
	fmt.Fprintf(&buf, "// source: %s\n", pkg.PkgPath)
	fmt.Fprintf(&buf, "// generated at: %s\n\n", time.Now().Format(time.RFC3339))
	fmt.Fprintf(&buf, "package %s\n\n", pkg.Name)

	// Determine which imports are needed
	needsTime := false
	needsReflect := false

	for _, s := range structs {
		for _, field := range s.Fields {
			typeStr := field.Type.String()
			if typeStr == "time.Time" || typeStr == "github.com/apache/fory/go/fory.Date" {
				needsTime = true
			}
			// We need reflect for the interface compatibility methods
			needsReflect = true
		}
	}

	// Generate imports
	fmt.Fprintf(&buf, "import (\n")
	fmt.Fprintf(&buf, "\t\"fmt\"\n")
	if needsReflect {
		fmt.Fprintf(&buf, "\t\"reflect\"\n")
	}
	if needsTime {
		fmt.Fprintf(&buf, "\t\"time\"\n")
	}
	fmt.Fprintf(&buf, "\t\"github.com/apache/fory/go/fory\"\n")
	fmt.Fprintf(&buf, ")\n\n")

	// Generate init function to register serializers
	fmt.Fprintf(&buf, "func init() {\n")
	for _, s := range structs {
		fmt.Fprintf(&buf, "\tfory.RegisterGeneratedSerializer((*%s)(nil), %s_ForyGenSerializer{})\n", s.Name, s.Name)
	}
	fmt.Fprintf(&buf, "}\n\n")

	// Generate serializers for each struct
	for _, s := range structs {
		if err := generateStructSerializer(&buf, s); err != nil {
			return fmt.Errorf("generating serializer for %s: %w", s.Name, err)
		}
	}

	// Format the generated code
	formatted, err := format.Source(buf.Bytes())
	if err != nil {
		return fmt.Errorf("formatting generated code: %w", err)
	}

	// Write to output file
	outputFile := filepath.Join(filepath.Dir(pkg.GoFiles[0]), fmt.Sprintf("%s_fory_gen.go", pkg.Name))
	return ioutil.WriteFile(outputFile, formatted, 0644)
}

func generateStructSerializer(buf *bytes.Buffer, s *StructInfo) error {
	// Generate struct serializer type
	fmt.Fprintf(buf, "type %s_ForyGenSerializer struct {}\n\n", s.Name)

	// Generate TypeId method
	fmt.Fprintf(buf, "func (%s_ForyGenSerializer) TypeId() fory.TypeId {\n", s.Name)
	fmt.Fprintf(buf, "\treturn fory.NAMED_STRUCT\n")
	fmt.Fprintf(buf, "}\n\n")

	// Generate NeedWriteRef method
	fmt.Fprintf(buf, "func (%s_ForyGenSerializer) NeedWriteRef() bool {\n", s.Name)
	fmt.Fprintf(buf, "\treturn true\n")
	fmt.Fprintf(buf, "}\n\n")

	// Generate strongly-typed Write method (new signature!)
	if err := generateWriteTyped(buf, s); err != nil {
		return err
	}

	// Generate strongly-typed Read method (new signature!)
	if err := generateReadTyped(buf, s); err != nil {
		return err
	}

	// Generate interface compatibility methods
	if err := generateWriteInterface(buf, s); err != nil {
		return err
	}

	if err := generateReadInterface(buf, s); err != nil {
		return err
	}

	return nil
}

// Generate the strongly-typed Write method according to the doc
func generateWriteTyped(buf *bytes.Buffer, s *StructInfo) error {
	hash := computeStructHash(s)

	fmt.Fprintf(buf, "// WriteTyped provides strongly-typed serialization with no reflection overhead\n")
	fmt.Fprintf(buf, "func (g %s_ForyGenSerializer) WriteTyped(f *fory.Fory, buf *fory.ByteBuffer, v *%s) error {\n", s.Name, s.Name)

	// Write struct hash
	fmt.Fprintf(buf, "\t// Write precomputed struct hash for compatibility checking\n")
	fmt.Fprintf(buf, "\tbuf.WriteInt32(%d) // hash of %s structure\n\n", hash, s.Name)

	// Write fields in sorted order
	fmt.Fprintf(buf, "\t// Write fields in sorted order\n")
	for _, field := range s.Fields {
		if err := generateFieldWriteTyped(buf, field); err != nil {
			return err
		}
	}

	fmt.Fprintf(buf, "\treturn nil\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// Generate the strongly-typed Read method according to the doc
func generateReadTyped(buf *bytes.Buffer, s *StructInfo) error {
	hash := computeStructHash(s)

	fmt.Fprintf(buf, "// ReadTyped provides strongly-typed deserialization with no reflection overhead\n")
	fmt.Fprintf(buf, "func (g %s_ForyGenSerializer) ReadTyped(f *fory.Fory, buf *fory.ByteBuffer, v *%s) error {\n", s.Name, s.Name)

	// Read and verify struct hash
	fmt.Fprintf(buf, "\t// Read and verify struct hash\n")
	fmt.Fprintf(buf, "\tif got := buf.ReadInt32(); got != %d {\n", hash)
	fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"struct hash mismatch for %s: expected %d, got %%d\", got)\n", s.Name, hash)
	fmt.Fprintf(buf, "\t}\n\n")

	// Read fields in sorted order
	fmt.Fprintf(buf, "\t// Read fields in same order as write\n")
	for _, field := range s.Fields {
		if err := generateFieldReadTyped(buf, field); err != nil {
			return err
		}
	}

	fmt.Fprintf(buf, "\treturn nil\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// Generate interface compatibility Write method
func generateWriteInterface(buf *bytes.Buffer, s *StructInfo) error {
	fmt.Fprintf(buf, "// Write provides reflect.Value interface compatibility\n")
	fmt.Fprintf(buf, "func (g %s_ForyGenSerializer) Write(f *fory.Fory, buf *fory.ByteBuffer, value reflect.Value) error {\n", s.Name)
	fmt.Fprintf(buf, "\t// Convert reflect.Value to concrete type and delegate to typed method\n")
	fmt.Fprintf(buf, "\tvar v *%s\n", s.Name)
	fmt.Fprintf(buf, "\tif value.Kind() == reflect.Ptr {\n")
	fmt.Fprintf(buf, "\t\tv = value.Interface().(*%s)\n", s.Name)
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\t// Create a copy to get a pointer\n")
	fmt.Fprintf(buf, "\t\ttemp := value.Interface().(%s)\n", s.Name)
	fmt.Fprintf(buf, "\t\tv = &temp\n")
	fmt.Fprintf(buf, "\t}\n")
	fmt.Fprintf(buf, "\t// Delegate to strongly-typed method for maximum performance\n")
	fmt.Fprintf(buf, "\treturn g.WriteTyped(f, buf, v)\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// Generate interface compatibility Read method
func generateReadInterface(buf *bytes.Buffer, s *StructInfo) error {
	fmt.Fprintf(buf, "// Read provides reflect.Value interface compatibility\n")
	fmt.Fprintf(buf, "func (g %s_ForyGenSerializer) Read(f *fory.Fory, buf *fory.ByteBuffer, type_ reflect.Type, value reflect.Value) error {\n", s.Name)
	fmt.Fprintf(buf, "\t// Convert reflect.Value to concrete type and delegate to typed method\n")
	fmt.Fprintf(buf, "\tvar v *%s\n", s.Name)
	fmt.Fprintf(buf, "\tif value.Kind() == reflect.Ptr {\n")
	fmt.Fprintf(buf, "\t\tif value.IsNil() {\n")
	fmt.Fprintf(buf, "\t\t\t// For pointer types, allocate using type_.Elem()\n")
	fmt.Fprintf(buf, "\t\t\tvalue.Set(reflect.New(type_.Elem()))\n")
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t\tv = value.Interface().(*%s)\n", s.Name)
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\t// value must be addressable for read\n")
	fmt.Fprintf(buf, "\t\tv = value.Addr().Interface().(*%s)\n", s.Name)
	fmt.Fprintf(buf, "\t}\n")
	fmt.Fprintf(buf, "\t// Delegate to strongly-typed method for maximum performance\n")
	fmt.Fprintf(buf, "\treturn g.ReadTyped(f, buf, v)\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

func generateFieldWriteTyped(buf *bytes.Buffer, field *FieldInfo) error {
	fmt.Fprintf(buf, "\t// Field: %s (%s)\n", field.GoName, field.Type.String())

	fieldAccess := fmt.Sprintf("v.%s", field.GoName)

	// Handle special named types first
	if named, ok := field.Type.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			fmt.Fprintf(buf, "\tbuf.WriteInt64(fory.GetUnixMicro(%s))\n", fieldAccess)
			return nil
		case "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\t// Handle zero date specially\n")
			fmt.Fprintf(buf, "\tif %s.Year == 0 && %s.Month == 0 && %s.Day == 0 {\n", fieldAccess, fieldAccess, fieldAccess)
			fmt.Fprintf(buf, "\t\tbuf.WriteInt32(int32(-2147483648)) // Special marker for zero date\n")
			fmt.Fprintf(buf, "\t} else {\n")
			fmt.Fprintf(buf, "\t\tdiff := time.Date(%s.Year, %s.Month, %s.Day, 0, 0, 0, 0, time.Local).Sub(time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local))\n", fieldAccess, fieldAccess, fieldAccess)
			fmt.Fprintf(buf, "\t\tbuf.WriteInt32(int32(diff.Hours() / 24))\n")
			fmt.Fprintf(buf, "\t}\n")
			return nil
		}
	}

	// Handle pointer types
	if _, ok := field.Type.(*types.Pointer); ok {
		// For all pointer types, use WriteReferencable
		fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		return nil
	}

	// Handle basic types
	if basic, ok := field.Type.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\tbuf.WriteBool(%s)\n", fieldAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(%s)\n", fieldAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\tbuf.WriteInt16(%s)\n", fieldAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\tbuf.WriteInt32(%s)\n", fieldAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\tbuf.WriteInt64(%s)\n", fieldAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\tbuf.WriteByte_(%s)\n", fieldAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\tbuf.WriteInt16(int16(%s))\n", fieldAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\tbuf.WriteInt32(int32(%s))\n", fieldAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\tbuf.WriteInt64(int64(%s))\n", fieldAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\tbuf.WriteFloat32(%s)\n", fieldAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\tbuf.WriteFloat64(%s)\n", fieldAccess)
		case types.String:
			fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		default:
			fmt.Fprintf(buf, "\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	// Handle struct types
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t// TODO: unsupported type %s\n", field.Type.String())
	return nil
}

func generateFieldReadTyped(buf *bytes.Buffer, field *FieldInfo) error {
	fmt.Fprintf(buf, "\t// Field: %s (%s)\n", field.GoName, field.Type.String())

	fieldAccess := fmt.Sprintf("v.%s", field.GoName)

	// Handle special named types first
	if named, ok := field.Type.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			fmt.Fprintf(buf, "\tusec := buf.ReadInt64()\n")
			fmt.Fprintf(buf, "\t%s = fory.CreateTimeFromUnixMicro(usec)\n", fieldAccess)
			return nil
		case "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\tdays := buf.ReadInt32()\n")
			fmt.Fprintf(buf, "\t// Handle zero date marker\n")
			fmt.Fprintf(buf, "\tif days == int32(-2147483648) {\n")
			fmt.Fprintf(buf, "\t\t%s = fory.Date{Year: 0, Month: 0, Day: 0}\n", fieldAccess)
			fmt.Fprintf(buf, "\t} else {\n")
			fmt.Fprintf(buf, "\t\tdiff := time.Duration(days) * 24 * time.Hour\n")
			fmt.Fprintf(buf, "\t\tt := time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local).Add(diff)\n")
			fmt.Fprintf(buf, "\t\t%s = fory.Date{Year: t.Year(), Month: t.Month(), Day: t.Day()}\n", fieldAccess)
			fmt.Fprintf(buf, "\t}\n")
			return nil
		}
	}

	// Handle pointer types
	if _, ok := field.Type.(*types.Pointer); ok {
		// For pointer types, use ReadReferencable
		fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", fieldAccess)
		return nil
	}

	// Handle basic types
	if basic, ok := field.Type.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t%s = buf.ReadBool()\n", fieldAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt8()\n", fieldAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt16()\n", fieldAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt32()\n", fieldAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt64()\n", fieldAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\t%s = buf.ReadByte_()\n", fieldAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\t%s = uint16(buf.ReadInt16())\n", fieldAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\t%s = uint32(buf.ReadInt32())\n", fieldAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t%s = uint64(buf.ReadInt64())\n", fieldAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\t%s = buf.ReadFloat32()\n", fieldAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\t%s = buf.ReadFloat64()\n", fieldAccess)
		case types.String:
			fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", fieldAccess)
		default:
			fmt.Fprintf(buf, "\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	// Handle struct types
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", fieldAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t// TODO: unsupported type %s\n", field.Type.String())
	return nil
}
