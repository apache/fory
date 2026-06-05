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

// Binary grpc-interop is the Go peer for Java-driven gRPC integration tests.
// It is invoked as a subprocess by GrpcInteropTest.java and supports two modes:
//
//	server --port-file <path>  start a gRPC server and write the bound port to the file
//	client --target <addr>     connect to addr and exercise all four streaming modes
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"

	"github.com/apache/fory/go/fory"
	forygrpc "github.com/apache/fory/go/fory/grpc"
	grpc_fdl "github.com/apache/fory/integration_tests/grpc_tests/go/generated/grpc_fdl"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/encoding"
)

// --- helpers ----------------------------------------------------------------

func newFory() *fory.Fory {
	f := fory.New(fory.WithXlang(true), fory.WithRefTracking(true), fory.WithCompatible(true))
	if err := grpc_fdl.RegisterTypes(f); err != nil {
		log.Fatalf("RegisterTypes: %v", err)
	}
	return f
}

func fdlResponse(req *grpc_fdl.GrpcFdlRequest, tag string, offset int) *grpc_fdl.GrpcFdlResponse {
	return &grpc_fdl.GrpcFdlResponse{
		Id:      fmt.Sprintf("%s:%s", tag, req.Id),
		Count:   req.Count + int32(offset),
		Payload: fmt.Sprintf("%s:%s", tag, req.Payload),
	}
}

func fdlAggregate(requests []*grpc_fdl.GrpcFdlRequest) *grpc_fdl.GrpcFdlResponse {
	ids := make([]string, len(requests))
	payloads := make([]string, len(requests))
	var count int32
	for i, req := range requests {
		ids[i] = req.Id
		payloads[i] = req.Payload
		count += req.Count
	}
	return &grpc_fdl.GrpcFdlResponse{
		Id:      "client:" + strings.Join(ids, "+"),
		Count:   count,
		Payload: "client:" + strings.Join(payloads, "+"),
	}
}

// protoFallbackCodec overrides the built-in "proto" codec (v1 registry) with
// Fory so the server can decode requests from Java clients, which send with the
// default content-type (application/grpc) rather than application/grpc+fory.
type protoFallbackCodec struct{ forygrpc.CodecV2 }

func (protoFallbackCodec) Name() string { return "proto" }

func (c protoFallbackCodec) Marshal(v interface{}) ([]byte, error) {
	b, err := c.Fory.Marshal(v)
	if err != nil {
		return nil, err
	}
	out := make([]byte, len(b))
	copy(out, b)
	return out, nil
}

func (c protoFallbackCodec) Unmarshal(data []byte, v interface{}) error {
	return c.Fory.Unmarshal(data, v)
}

// --- server -----------------------------------------------------------------

type fdlService struct {
	grpc_fdl.UnimplementedFdlGrpcServiceServer
}

func (s *fdlService) UnaryMessage(_ context.Context, req *grpc_fdl.GrpcFdlRequest) (*grpc_fdl.GrpcFdlResponse, error) {
	return fdlResponse(req, "unary", 10), nil
}

func (s *fdlService) ServerStreamMessage(req *grpc_fdl.GrpcFdlRequest, stream grpc_fdl.FdlGrpcService_ServerStreamMessageServer) error {
	for i := 0; i < 3; i++ {
		if err := stream.Send(fdlResponse(req, fmt.Sprintf("server-%d", i), i)); err != nil {
			return err
		}
	}
	return nil
}

func (s *fdlService) ClientStreamMessage(stream grpc_fdl.FdlGrpcService_ClientStreamMessageServer) error {
	var requests []*grpc_fdl.GrpcFdlRequest
	for {
		req, err := stream.Recv()
		if err == io.EOF {
			return stream.SendAndClose(fdlAggregate(requests))
		}
		if err != nil {
			return err
		}
		requests = append(requests, req)
	}
}

func (s *fdlService) BidiStreamMessage(stream grpc_fdl.FdlGrpcService_BidiStreamMessageServer) error {
	index := 0
	for {
		req, err := stream.Recv()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		if err := stream.Send(fdlResponse(req, fmt.Sprintf("bidi-%d", index), index)); err != nil {
			return err
		}
		index++
	}
}

func runServer(portFile string, f *fory.Fory) error {
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("listen: %w", err)
	}
	codec := forygrpc.CodecV2{Fory: f}
	// "fory" (v2): used by Go clients via grpc.ForceCodecV2.
	encoding.RegisterCodecV2(codec)
	// "proto" (v1): overrides the built-in proto codec so Java clients are
	// also handled by Fory. RegisterCodecV2 writes to a separate registry and
	// does not replace the v1 "proto" entry; RegisterCodec must be used.
	encoding.RegisterCodec(protoFallbackCodec{codec})
	s := grpc.NewServer()
	grpc_fdl.RegisterFdlGrpcServiceServer(s, &fdlService{})

	// Write the bound port so the Java test harness knows where to connect.
	port := lis.Addr().(*net.TCPAddr).Port
	if err := os.WriteFile(portFile, []byte(fmt.Sprintf("%d", port)), 0600); err != nil {
		return fmt.Errorf("write port file: %w", err)
	}

	return s.Serve(lis)
}

// --- client -----------------------------------------------------------------

func exerciseMessageStub(stub grpc_fdl.FdlGrpcServiceClient, requests []*grpc_fdl.GrpcFdlRequest) error {
	ctx := context.Background()
	first := requests[0]

	// unary
	got, err := stub.UnaryMessage(ctx, first)
	if err != nil {
		return fmt.Errorf("UnaryMessage: %w", err)
	}
	if want := fdlResponse(first, "unary", 10); *got != *want {
		return fmt.Errorf("UnaryMessage: got %+v, want %+v", got, want)
	}

	// server streaming
	ss, err := stub.ServerStreamMessage(ctx, first)
	if err != nil {
		return fmt.Errorf("ServerStreamMessage: %w", err)
	}
	for i := 0; i < 3; i++ {
		got, err := ss.Recv()
		if err != nil {
			return fmt.Errorf("ServerStreamMessage Recv[%d]: %w", i, err)
		}
		if want := fdlResponse(first, fmt.Sprintf("server-%d", i), i); *got != *want {
			return fmt.Errorf("ServerStreamMessage[%d]: got %+v, want %+v", i, got, want)
		}
	}
	if _, err := ss.Recv(); err != io.EOF {
		return fmt.Errorf("ServerStreamMessage: expected EOF, got %v", err)
	}

	// client streaming
	cs, err := stub.ClientStreamMessage(ctx)
	if err != nil {
		return fmt.Errorf("ClientStreamMessage: %w", err)
	}
	for _, req := range requests {
		if err := cs.Send(req); err != nil {
			return fmt.Errorf("ClientStreamMessage Send: %w", err)
		}
	}
	csResp, err := cs.CloseAndRecv()
	if err != nil {
		return fmt.Errorf("ClientStreamMessage CloseAndRecv: %w", err)
	}
	if want := fdlAggregate(requests); *csResp != *want {
		return fmt.Errorf("ClientStreamMessage: got %+v, want %+v", csResp, want)
	}

	// bidirectional streaming
	bidi, err := stub.BidiStreamMessage(ctx)
	if err != nil {
		return fmt.Errorf("BidiStreamMessage: %w", err)
	}
	for i, req := range requests {
		if err := bidi.Send(req); err != nil {
			return fmt.Errorf("BidiStreamMessage Send[%d]: %w", i, err)
		}
		got, err := bidi.Recv()
		if err != nil {
			return fmt.Errorf("BidiStreamMessage Recv[%d]: %w", i, err)
		}
		if want := fdlResponse(req, fmt.Sprintf("bidi-%d", i), i); *got != *want {
			return fmt.Errorf("BidiStreamMessage[%d]: got %+v, want %+v", i, got, want)
		}
	}
	if err := bidi.CloseSend(); err != nil {
		return fmt.Errorf("BidiStreamMessage CloseSend: %w", err)
	}
	if _, err := bidi.Recv(); err != io.EOF {
		return fmt.Errorf("BidiStreamMessage: expected EOF after CloseSend, got %v", err)
	}

	return nil
}

func runClient(target string, f *fory.Fory) error {
	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return fmt.Errorf("dial %s: %w", target, err)
	}
	defer conn.Close()

	stub := grpc_fdl.NewFdlGrpcServiceClient(conn, f)
	requests := []*grpc_fdl.GrpcFdlRequest{
		{Id: "fdl-a", Count: 1, Payload: "alpha"},
		{Id: "fdl-b", Count: 2, Payload: "beta"},
	}
	return exerciseMessageStub(stub, requests)
}

// --- entry point ------------------------------------------------------------

func main() {
	serverCmd := flag.NewFlagSet("server", flag.ExitOnError)
	serverPortFile := serverCmd.String("port-file", "", "path to write bound port")

	clientCmd := flag.NewFlagSet("client", flag.ExitOnError)
	clientTarget := clientCmd.String("target", "", "host:port to connect to")

	if len(os.Args) < 2 {
		log.Fatal("usage: grpc-interop <server|client> [flags]")
	}

	f := newFory()

	switch os.Args[1] {
	case "server":
		serverCmd.Parse(os.Args[2:])
		if *serverPortFile == "" {
			log.Fatal("--port-file is required")
		}
		if err := runServer(*serverPortFile, f); err != nil {
			log.Fatalf("server error: %v", err)
		}
	case "client":
		clientCmd.Parse(os.Args[2:])
		if *clientTarget == "" {
			log.Fatal("--target is required")
		}
		if err := runClient(*clientTarget, f); err != nil {
			log.Fatalf("client error: %v", err)
		}
	default:
		log.Fatalf("unknown command %q", os.Args[1])
	}
}
