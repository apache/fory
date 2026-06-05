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

package forygrpc

import (
	"github.com/apache/fory/go/fory"
	"google.golang.org/grpc/mem"
)

// CodecV2 implements grpc/encoding.CodecV2 using Fory serialization.
// Pass a configured *fory.Fory instance with all message types registered.
type CodecV2 struct {
	Fory *fory.Fory
}

// Marshal serializes v with Fory. The result is copied before being handed to
// gRPC because Fory reuses its internal write buffer across calls — streaming
// handlers may buffer multiple frames before sending, and without a copy all
// frames would alias the last serialized value.
func (c CodecV2) Marshal(v any) (mem.BufferSlice, error) {
	b, err := c.Fory.Marshal(v)
	if err != nil {
		return nil, err
	}
	out := make([]byte, len(b))
	copy(out, b)
	return mem.BufferSlice{mem.NewBuffer(&out, nil)}, nil
}

// Unmarshal deserializes the gRPC frame into v. Each buffer segment is copied
// into a fresh slice because the transport may reclaim the underlying memory
// before Fory finishes reading it.
func (c CodecV2) Unmarshal(data mem.BufferSlice, v any) error {
	b := make([]byte, data.Len())
	n := 0
	for _, buf := range data {
		n += copy(b[n:], buf.ReadOnlyData())
	}
	return c.Fory.Unmarshal(b, v)
}

// Name returns "fory", the codec identifier used with grpc.ForceCodecV2.
func (CodecV2) Name() string {
	return "fory"
}
