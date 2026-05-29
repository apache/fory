# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Go gRPC service code generator."""

from typing import List
from fory_compiler.generators.services.base import ImportTracker, StreamingMode, streaming_mode
from fory_compiler.generators.base import GeneratedFile
from fory_compiler.ir.ast import RpcMethod, Service, NamedType


class GoServiceGeneratorMixin:
    """Generates Go gRPC service stubs."""

    def generate_services(self) -> List[GeneratedFile]:
        local_services = [s for s in self.schema.services if not self.is_imported_type(s)]
        if not local_services:
            return []
        return [self._generate_grpc_file(s) for s in local_services]

    def _generate_grpc_file(self, service: Service) -> GeneratedFile:
        lines: List[str] = []
        tracker = ImportTracker()

        # License header
        lines.append(self.get_license_header("//"))
        lines.append("")

        # Package declaration
        lines.append(f"package {self.get_package_name()}")
        lines.append("")

        # Imports
        # save the placeholder index for now
        import_placeholder_index = len(lines)

        # Client interface
        



        # after all the service code gets generated, insert the import block at the placeholder index
        import_lines = self._build_import_block(tracker)
        for i, line in enumerate(import_lines):
            lines.insert(import_placeholder_index + i, line)

        return GeneratedFile(
            path=f"{self.get_file_name()}_grpc.go",
            content="\n".join(lines)
        )

    def _build_import_block(self, tracker: ImportTracker) -> List[str]:
        imports = [
            '"context"',
            '"google.golang.org/grpc"',
            '"google.golang.org/grpc/codes"',
            '"google.golang.org/grpc/status"',
        ]
        
        for path in tracker.go_imports():
            imports.append(f'"{path}"')
        
        sorted_imports = sorted(set(imports))

        lines = ["import ("]
        for imp in sorted_imports:
            lines.append(f"\t{imp}")
        lines.append(")")
        lines.append("")

        return lines

    def _resolve_go_type(self, named_type: NamedType, tracker: ImportTracker) -> str:
        type_ref = self.schema.resolve_type_name(named_type.name)
        type_def = self.schema.get_type(type_ref)
        if type_def is not None and self.is_imported_type(type_def):
            info = self._import_info_for_type(type_def)
            if info:
                alias, import_path, _ = info
                tracker.add(alias, import_path)
                return f"*{alias}.{type_ref}"
        return f"*{type_ref}"

    def _generate_client_interface(self, service: Service, tracker: ImportTracker) -> List[str]:
        lines: List[str] = []
        lines.append(f"// {service.name}Client is the client API for {service.name} service.")
        lines.append(f"type {service.name}Client interface {{")
        for method in service.methods:
            req_type = self._resolve_go_type(method.request_type, tracker)
            res_type = self._resolve_go_type(method.response_type, tracker)
            mode = streaming_mode(method)
            if mode is StreamingMode.UNARY:
                signature = f"(ctx context.Context, in {req_type}, opts ...grpc.CallOption) ({res_type}, error)"
                lines.append(f"\t{self.to_pascal_case(method.name)}{signature}")
            elif mode is StreamingMode.SERVER_STREAMING:
                signature = f"(ctx context.Context, in {req_type}, opts ...grpc.CallOption) ({service.name}_{self.to_pascal_case(method.name)}Client, error)"
                lines.append(f"\t{self.to_pascal_case(method.name)}{signature}")
            else:
                signature = f"(ctx context.Context, opts ...grpc.CallOption) ({service.name}_{self.to_pascal_case(method.name)}Client, error)"
                lines.append(f"\t{self.to_pascal_case(method.name)}{signature}")

        lines.append("}")
        lines.append("")
        return lines

    def _generate_client_struct(self, service: Service) -> List[str]:
        lines: List[str] = []
        lines.append(f"type {self.to_camel_case(service.name)}Client struct {{")
        lines.append("\tcc grpc.ClientConnInterface")
        lines.append("}")
        lines.append("")
        return lines

    def _generate_new_client(self, service: Service) -> List[str]:
        lines: List[str] = []
        lines.append(f"func New{service.name}Client(cc grpc.ClientConnInterface) {service.name}Client {{")
        lines.append(f"\treturn &{self.to_camel_case(service.name)}Client{{cc}}")
        lines.append("}")
        lines.append("")
        return lines

    def _generate_client_methods(self, service: Service, tracker: ImportTracker) -> List[str]:
        lines: List[str] = []
        tracker.add("forygrpc", "github.com/apache/fory/go/fory/grpc")
        stream_index = 0
        for method in service.methods:
            req_type = self._resolve_go_type(method.request_type, tracker)
            res_type = self._resolve_go_type(method.response_type, tracker)
            mode = streaming_mode(method)
            if mode is StreamingMode.UNARY:
                lines.append(f"func (c *{self.to_camel_case(service.name)}Client) {self.to_pascal_case(method.name)}(ctx context.Context, in {req_type}, opts ...grpc.CallOption) ({res_type}, error) {{")
                lines.append(f"\tout := new({res_type[1:]})")
                lines.append(f'\terr := c.cc.Invoke(ctx, "{self.get_grpc_method_path(service, method)}", in, out, grpc.ForceCodecV2(forygrpc.CodecV2{{}}), opts...)')
                lines.append("\tif err != nil {")
                lines.append("\t\treturn nil, err")
                lines.append("\t}")
                lines.append("\treturn out, nil")
                lines.append("}")
                lines.append("")
            elif mode is StreamingMode.SERVER_STREAMING:
                lines.append(f'func (c *{self.to_camel_case(service.name)}Client) {self.to_pascal_case(method.name)}(ctx context.Context, in {req_type}, opts ...grpc.CallOption) ({service.name}_{self.to_pascal_case(method.name)}Client, error) {{')
                lines.append(f'\tstream, err := c.cc.NewStream(ctx, &_{service.name}_serviceDesc.Streams[{stream_index}], "{self.get_grpc_method_path(service, method)}", grpc.ForceCodecV2(forygrpc.CodecV2{{}}), opts...)')
                lines.append("\tif err != nil {")
                lines.append("\t\treturn nil, err")
                lines.append("\t}")
                lines.append(f"\tx := &{self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client{{stream}}")
                lines.append("\tif err := x.SendMsg(in); err != nil {")
                lines.append("\t\treturn nil, err")
                lines.append("\t}")
                lines.append("\tif err := x.CloseSend(); err != nil {")
                lines.append("\t\treturn nil, err")
                lines.append("\t}")
                lines.append("\treturn x, nil")
                lines.append("}")
                lines.append("")
                stream_index += 1
            else:
                lines.append(f"func (c *{self.to_camel_case(service.name)}Client) {self.to_pascal_case(method.name)}(ctx context.Context, opts ...grpc.CallOption) ({service.name}_{self.to_pascal_case(method.name)}Client, error) {{")
                lines.append(f'\tstream, err := c.cc.NewStream(ctx, &_{service.name}_serviceDesc.Streams[{stream_index}], "{self.get_grpc_method_path(service, method)}", grpc.ForceCodecV2(forygrpc.CodecV2{{}}), opts...)')
                lines.append("\tif err != nil {")
                lines.append("\t\treturn nil, err")
                lines.append("\t}")
                lines.append(f"\treturn &{self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client{{stream}}, nil")
                lines.append("}")
                lines.append("")
                stream_index += 1
        return lines
    
    def _generate_stream_types(self, service: Service, tracker: ImportTracker) -> List[str]:
        lines: List[str] = []
        for method in service.methods:
            req_type = self._resolve_go_type(method.request_type, tracker)
            res_type = self._resolve_go_type(method.response_type, tracker)
            mode = streaming_mode(method)
            if mode is StreamingMode.UNARY:
                continue
            if mode is StreamingMode.CLIENT_STREAMING:
                # type interface
                lines.append(f"type {service.name}_{self.to_pascal_case(method.name)}Client interface {{")
                lines.append(f"\tSend({req_type}) error")
                lines.append(f"\tCloseAndRecv() ({res_type}, error)")
                lines.append("\tgrpc.ClientStream")
                lines.append("}")
                lines.append("")

                # struct
                lines.append(f"type {self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client struct {{")
                lines.append("\tgrpc.ClientStream")
                lines.append("}")
                lines.append("")

                # methods
                lines.append(f"func (x *{self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client) Send(m {req_type}) error {{")
                lines.append("\treturn x.ClientStream.SendMsg(m)")
                lines.append("}")
                lines.append("")
                lines.append(f"func (x *{self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client) CloseAndRecv() ({res_type}, error) {{")
                lines.append("\tif err := x.ClientStream.CloseSend(); err != nil {")
                lines.append("\t\treturn nil, err")
                lines.append("\t}")
                lines.append(f"\tm := new({res_type[1:]})")
                lines.append("\tif err := x.ClientStream.RecvMsg(m); err != nil {")
                lines.append("\t\treturn nil, err")
                lines.append("\t}")
                lines.append("\treturn m, nil")
                lines.append("}")
                lines.append("")
            elif mode is StreamingMode.SERVER_STREAMING:
                # type interface
                lines.append(f"type {service.name}_{self.to_pascal_case(method.name)}Client interface {{")
                lines.append(f"\tRecv() ({res_type}, error)")
                lines.append("\tgrpc.ClientStream")
                lines.append("}")
                lines.append("")
                
                # struct
                lines.append(f"type {self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client struct {{")
                lines.append("\tgrpc.ClientStream")
                lines.append("}")
                lines.append("")

                # methods
                lines.append(f"func (x *{self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client) Recv() ({res_type}, error) {{")
                lines.append(f"\tm := new({res_type[1:]})")
                lines.append("\tif err := x.ClientStream.RecvMsg(m); err != nil {")
                lines.append("\t\treturn nil, err")
                lines.append("\t}")
                lines.append("\treturn m, nil")
                lines.append("}")
                lines.append("")
            else:
                # interface
                lines.append(f"type {service.name}_{self.to_pascal_case(method.name)}Client interface {{")
                lines.append(f"\tSend({req_type}) error")
                lines.append(f"\tRecv() ({res_type}, error)")
                lines.append("\tgrpc.ClientStream")
                lines.append("}")
                lines.append("")

                # struct
                lines.append(f"type {self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client struct {{")
                lines.append("\tgrpc.ClientStream")
                lines.append("}")
                lines.append("")

                # methods
                lines.append(f"func (x *{self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client) Send(m {req_type}) error {{")
                lines.append("\treturn x.ClientStream.SendMsg(m)")
                lines.append("}")
                lines.append("")
                lines.append(f"func (x *{self.to_camel_case(service.name)}{self.to_pascal_case(method.name)}Client) Recv() ({res_type}, error) {{")
                lines.append(f"\tm := new({res_type[1:]})")
                lines.append("\tif err := x.ClientStream.RecvMsg(m); err != nil {")
                lines.append("\t\treturn nil, err")
                lines.append("\t}")
                lines.append("\treturn m, nil")
                lines.append("}")
                lines.append("")
        return lines

    def _generate_server_interface(self, service: Service, tracker: ImportTracker) -> List[str]:
        lines: List[str] = []
        lines.append(f"// {service.name}Server is the server API for {service.name} service.")
        lines.append(f"// All implementations must embed Unimplemented{service.name}Server")
        lines.append("// for forward compatibility.")
        lines.append(f"type {service.name}Server interface {{")
        for method in service.methods:
            req_type = self._resolve_go_type(method.request_type, tracker)
            res_type = self._resolve_go_type(method.response_type, tracker)
            mode = streaming_mode(method)
            if mode is StreamingMode.UNARY:
                lines.append(f"\t{self.to_pascal_case(method.name)}(context.Context, {req_type}) ({res_type}, error)")
            elif mode is StreamingMode.SERVER_STREAMING:
                lines.append(f"\t{self.to_pascal_case(method.name)}({req_type}, {service.name}_{self.to_pascal_case(method.name)}Server) error")
            else:
                lines.append(f"\t{self.to_pascal_case(method.name)}({service.name}_{self.to_pascal_case(method.name)}Server) error")
        lines.append(f"\tmustEmbedUnimplemented{service.name}Server()")
        lines.append("}")
        lines.append("")
        return lines
