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

package main

import (
	"context"
	"fmt"
	"net"
	"testing"

	grpc_fdl "github.com/apache/fory/integration_tests/grpc_tests/go/generated/grpc_fdl"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

func startTestServer(t testing.TB) (string, func()) {
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to listen: %v", err)
	}

	s := grpc.NewServer(grpc.ForceServerCodecV2(grpc_fdl.CodecV2{}))
	grpc_fdl.RegisterFdlGrpcServiceServer(s, &fdlService{})

	go func() {
		_ = s.Serve(lis)
	}()

	addr := fmt.Sprintf("127.0.0.1:%d", lis.Addr().(*net.TCPAddr).Port)
	cleanup := func() {
		s.Stop()
		_ = lis.Close()
	}
	return addr, cleanup
}

func TestForyGrpcEndToEnd(t *testing.T) {
	target, cleanup := startTestServer(t)
	defer cleanup()

	if err := runClient(target); err != nil {
		t.Fatalf("runClient failed: %v", err)
	}
}

func BenchmarkForyGrpcUnary(b *testing.B) {
	target, cleanup := startTestServer(b)
	defer cleanup()

	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		b.Fatalf("dial %s: %v", target, err)
	}
	defer conn.Close()

	stub := grpc_fdl.NewFdlGrpcServiceClient(conn)
	req := &grpc_fdl.GrpcFdlRequest{Id: "bench", Count: 42, Payload: "hello-fory-grpc"}
	ctx := context.Background()

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_, err := stub.UnaryMessage(ctx, req)
		if err != nil {
			b.Fatalf("UnaryMessage error: %v", err)
		}
	}
}
