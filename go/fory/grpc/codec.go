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

// CodecV2 implements grpc/encoding.CodecV2, replacing the default protobuf
// codec with Fory binary serialization on both client and server sides.
// Pass a configured *fory.Fory instance with all message types registered.
type CodecV2 struct {
	Fory *fory.Fory
}

// Marshal serializes the message using Fory and wraps the resulting bytes
// in a single-buffer BufferSlice for gRPC transport.
func (c CodecV2) Marshal(v any) (mem.BufferSlice, error) {
	b, err := c.Fory.Marshal(v)
	if err != nil {
		return nil, err
	}
	return mem.BufferSlice{mem.NewBuffer(&b, nil)}, nil
}

// Unmarshal materializes the incoming buffer slice into a contiguous byte
// slice and deserializes it into v using Fory.
func (c CodecV2) Unmarshal(data mem.BufferSlice, v any) error {
	buf := data.MaterializeToBuffer(mem.DefaultBufferPool())
	defer buf.Free()
	return c.Fory.Unmarshal(buf.ReadOnlyData(), v)
}

// Name returns the codec identifier registered with gRPC. Using "fory"
// ensures this codec does not conflict with the default "proto" codec.
func (CodecV2) Name() string {
	return "fory"
}
