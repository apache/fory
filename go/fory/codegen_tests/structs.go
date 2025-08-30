package codegen_tests

// 验证用的基本结构体 (只包含基本类型，因为PR1只支持基本类型)
type ValidationDemo struct {
	A int32  `json:"a"` // int32
	B string `json:"b"` // string
	C int64  `json:"c"` // int64 (代替数组，因为当前不支持数组)
}
