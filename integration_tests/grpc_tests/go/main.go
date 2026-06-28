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
// It is invoked as a subprocess by Java gRPC integration tests and supports two modes:
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

	grpc_fdl "github.com/apache/fory/integration_tests/grpc_tests/go/generated/grpc_fdl"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// --- helpers ----------------------------------------------------------------

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

func fdlRequestUnion(req *grpc_fdl.GrpcFdlRequest) *grpc_fdl.GrpcFdlUnion {
	union := grpc_fdl.RequestGrpcFdlUnion(req)
	return &union
}

func fdlResponseUnion(response *grpc_fdl.GrpcFdlResponse) *grpc_fdl.GrpcFdlUnion {
	union := grpc_fdl.ResponseGrpcFdlUnion(response)
	return &union
}

func fdlUnionResponse(req *grpc_fdl.GrpcFdlRequest, tag string, offset int) *grpc_fdl.GrpcFdlUnion {
	return fdlResponseUnion(fdlResponse(req, tag, offset))
}

func fdlUnionAggregate(unions []*grpc_fdl.GrpcFdlUnion) (*grpc_fdl.GrpcFdlUnion, error) {
	requests := make([]*grpc_fdl.GrpcFdlRequest, len(unions))
	for i, union := range unions {
		req, err := fdlRequestFromUnion(union)
		if err != nil {
			return nil, err
		}
		requests[i] = req
	}
	return fdlResponseUnion(fdlAggregate(requests)), nil
}

func fdlRequestFromUnion(union *grpc_fdl.GrpcFdlUnion) (*grpc_fdl.GrpcFdlRequest, error) {
	if union == nil {
		return nil, fmt.Errorf("expected request union, got nil")
	}
	req, ok := union.AsRequest()
	if !ok {
		return nil, fmt.Errorf("expected request union, got case %d", union.Case())
	}
	return req, nil
}

func expectUnionResponse(name string, got *grpc_fdl.GrpcFdlUnion, want *grpc_fdl.GrpcFdlUnion) error {
	if got == nil || want == nil {
		return fmt.Errorf("%s: got %+v, want %+v", name, got, want)
	}
	gotResponse, ok := got.AsResponse()
	if !ok {
		return fmt.Errorf("%s: got non-response union case %d", name, got.Case())
	}
	wantResponse, ok := want.AsResponse()
	if !ok {
		return fmt.Errorf("%s: want non-response union case %d", name, want.Case())
	}
	if *gotResponse != *wantResponse {
		return fmt.Errorf("%s: got %+v, want %+v", name, gotResponse, wantResponse)
	}
	return nil
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

func (s *fdlService) UnaryUnion(_ context.Context, req *grpc_fdl.GrpcFdlUnion) (*grpc_fdl.GrpcFdlUnion, error) {
	message, err := fdlRequestFromUnion(req)
	if err != nil {
		return nil, err
	}
	return fdlUnionResponse(message, "unary", 10), nil
}

func (s *fdlService) ServerStreamUnion(req *grpc_fdl.GrpcFdlUnion, stream grpc_fdl.FdlGrpcService_ServerStreamUnionServer) error {
	message, err := fdlRequestFromUnion(req)
	if err != nil {
		return err
	}
	for i := 0; i < 3; i++ {
		if err := stream.Send(fdlUnionResponse(message, fmt.Sprintf("server-%d", i), i)); err != nil {
			return err
		}
	}
	return nil
}

func (s *fdlService) ClientStreamUnion(stream grpc_fdl.FdlGrpcService_ClientStreamUnionServer) error {
	var requests []*grpc_fdl.GrpcFdlUnion
	for {
		req, err := stream.Recv()
		if err == io.EOF {
			response, err := fdlUnionAggregate(requests)
			if err != nil {
				return err
			}
			return stream.SendAndClose(response)
		}
		if err != nil {
			return err
		}
		requests = append(requests, req)
	}
}

func (s *fdlService) BidiStreamUnion(stream grpc_fdl.FdlGrpcService_BidiStreamUnionServer) error {
	index := 0
	for {
		req, err := stream.Recv()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		message, err := fdlRequestFromUnion(req)
		if err != nil {
			return err
		}
		if err := stream.Send(fdlUnionResponse(message, fmt.Sprintf("bidi-%d", index), index)); err != nil {
			return err
		}
		index++
	}
}

func runServer(portFile string) error {
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("listen: %w", err)
	}
	s := grpc.NewServer(grpc.ForceServerCodecV2(grpc_fdl.CodecV2{}))
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

func exerciseUnionStub(stub grpc_fdl.FdlGrpcServiceClient, requests []*grpc_fdl.GrpcFdlUnion) error {
	ctx := context.Background()
	first := requests[0]
	firstMessage, err := fdlRequestFromUnion(first)
	if err != nil {
		return err
	}

	// unary
	got, err := stub.UnaryUnion(ctx, first)
	if err != nil {
		return fmt.Errorf("UnaryUnion: %w", err)
	}
	if err := expectUnionResponse("UnaryUnion", got, fdlUnionResponse(firstMessage, "unary", 10)); err != nil {
		return err
	}

	// server streaming
	ss, err := stub.ServerStreamUnion(ctx, first)
	if err != nil {
		return fmt.Errorf("ServerStreamUnion: %w", err)
	}
	for i := 0; i < 3; i++ {
		got, err := ss.Recv()
		if err != nil {
			return fmt.Errorf("ServerStreamUnion Recv[%d]: %w", i, err)
		}
		if err := expectUnionResponse(
			fmt.Sprintf("ServerStreamUnion[%d]", i),
			got,
			fdlUnionResponse(firstMessage, fmt.Sprintf("server-%d", i), i),
		); err != nil {
			return err
		}
	}
	if _, err := ss.Recv(); err != io.EOF {
		return fmt.Errorf("ServerStreamUnion: expected EOF, got %v", err)
	}

	// client streaming
	cs, err := stub.ClientStreamUnion(ctx)
	if err != nil {
		return fmt.Errorf("ClientStreamUnion: %w", err)
	}
	for _, req := range requests {
		if err := cs.Send(req); err != nil {
			return fmt.Errorf("ClientStreamUnion Send: %w", err)
		}
	}
	csResp, err := cs.CloseAndRecv()
	if err != nil {
		return fmt.Errorf("ClientStreamUnion CloseAndRecv: %w", err)
	}
	wantAggregate, err := fdlUnionAggregate(requests)
	if err != nil {
		return err
	}
	if err := expectUnionResponse("ClientStreamUnion", csResp, wantAggregate); err != nil {
		return err
	}

	// bidirectional streaming
	bidi, err := stub.BidiStreamUnion(ctx)
	if err != nil {
		return fmt.Errorf("BidiStreamUnion: %w", err)
	}
	for i, req := range requests {
		message, err := fdlRequestFromUnion(req)
		if err != nil {
			return err
		}
		if err := bidi.Send(req); err != nil {
			return fmt.Errorf("BidiStreamUnion Send[%d]: %w", i, err)
		}
		got, err := bidi.Recv()
		if err != nil {
			return fmt.Errorf("BidiStreamUnion Recv[%d]: %w", i, err)
		}
		if err := expectUnionResponse(
			fmt.Sprintf("BidiStreamUnion[%d]", i),
			got,
			fdlUnionResponse(message, fmt.Sprintf("bidi-%d", i), i),
		); err != nil {
			return err
		}
	}
	if err := bidi.CloseSend(); err != nil {
		return fmt.Errorf("BidiStreamUnion CloseSend: %w", err)
	}
	if _, err := bidi.Recv(); err != io.EOF {
		return fmt.Errorf("BidiStreamUnion: expected EOF after CloseSend, got %v", err)
	}

	return nil
}

func runClient(target string) error {
	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return fmt.Errorf("dial %s: %w", target, err)
	}
	defer conn.Close()

	stub := grpc_fdl.NewFdlGrpcServiceClient(conn)
	requests := []*grpc_fdl.GrpcFdlRequest{
		{Id: "fdl-a", Count: 1, Payload: "alpha"},
		{Id: "fdl-b", Count: 2, Payload: "beta"},
	}
	if err := exerciseMessageStub(stub, requests); err != nil {
		return err
	}

	unionRequests := []*grpc_fdl.GrpcFdlUnion{
		fdlRequestUnion(&grpc_fdl.GrpcFdlRequest{Id: "fdl-u-a", Count: 3, Payload: "union-alpha"}),
		fdlRequestUnion(&grpc_fdl.GrpcFdlRequest{Id: "fdl-u-b", Count: 4, Payload: "union-beta"}),
	}
	return exerciseUnionStub(stub, unionRequests)
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

	switch os.Args[1] {
	case "server":
		serverCmd.Parse(os.Args[2:])
		if *serverPortFile == "" {
			log.Fatal("--port-file is required")
		}
		if err := runServer(*serverPortFile); err != nil {
			log.Fatalf("server error: %v", err)
		}
	case "client":
		clientCmd.Parse(os.Args[2:])
		if *clientTarget == "" {
			log.Fatal("--target is required")
		}
		if err := runClient(*clientTarget); err != nil {
			log.Fatalf("client error: %v", err)
		}
	default:
		log.Fatalf("unknown command %q", os.Args[1])
	}
}
