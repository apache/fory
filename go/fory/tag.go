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
	"reflect"
	"strings"
)

const (
	// TagIDUseFieldName indicates field name should be used instead of tag ID.
	TagIDUseFieldName int32 = -1
	maxTypeDefTagID   int32 = 1<<29 - 1
)

func parseBoolStrict(s string) (bool, bool) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "true", "1", "yes":
		return true, true
	case "false", "0", "no":
		return false, true
	default:
		return false, false
	}
}

// validateForyTags validates all fory tags in a struct type.
// Validation is strict and rejects unknown keys, duplicates, malformed nested type DSL,
// impossible IDs, and duplicate tag IDs among included fields.
func validateForyTags(t reflect.Type) error {
	if t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	if t.Kind() != reflect.Struct {
		return nil
	}

	tagIDs := make(map[int32]string)
	for i := 0; i < t.NumField(); i++ {
		field := t.Field(i)
		if field.PkgPath != "" {
			continue
		}
		parsed, err := parseFieldTag(field)
		if err != nil {
			return err
		}
		if parsed.ignore {
			continue
		}
		if parsed.idSet {
			if parsed.tagID < 0 {
				return InvalidTagErrorf("invalid fory tag id=%d on field %s: id must be non-negative", parsed.tagID, field.Name)
			}
			if parsed.tagID > maxTypeDefTagID {
				return InvalidTagErrorf("invalid fory tag id=%d on field %s: id exceeds TypeDef range", parsed.tagID, field.Name)
			}
			if existing, ok := tagIDs[parsed.tagID]; ok {
				return InvalidTagErrorf("duplicate fory tag id=%d on fields %s and %s", parsed.tagID, existing, field.Name)
			}
			tagIDs[parsed.tagID] = field.Name
		}
	}
	return nil
}

// shouldIncludeField returns true if the field should be serialized.
func shouldIncludeField(field reflect.StructField) bool {
	if field.PkgPath != "" {
		return false
	}
	parsed, err := parseFieldTag(field)
	if err != nil {
		return false
	}
	return !parsed.ignore
}
