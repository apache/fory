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

"""Python gRPC service generator helpers."""

from typing import List

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.ir.ast import RpcMethod, Service


class PythonServiceGeneratorMixin:
    """Generates Python gRPC service companions."""

    def generate_services(self) -> List[GeneratedFile]:
        """Generate Python grpc service companion module."""
        local_services = [
            service
            for service in self.schema.services
            if not self.is_imported_type(service)
        ]
        if not local_services:
            return []
        self.check_python_grpc_service_collisions(local_services)
        self.check_python_grpc_method_collisions(local_services)
        return [self.generate_grpc_module(local_services)]

    def check_python_grpc_service_collisions(self, services: List[Service]) -> None:
        seen_registrations = {}
        for service in services:
            add_fn = self.python_grpc_add_servicer_name(service)
            if add_fn in seen_registrations:
                raise ValueError(
                    f"Python gRPC service registration collision: "
                    f"{seen_registrations[add_fn]} and {service.name} both generate {add_fn}"
                )
            seen_registrations[add_fn] = service.name

    def check_python_grpc_method_collisions(self, services: List[Service]) -> None:
        for service in services:
            seen_methods = {}
            for method in service.methods:
                python_method = self.python_grpc_method_name(method)
                if python_method in seen_methods:
                    raise ValueError(
                        f"Python gRPC method name collision in service {service.name}: "
                        f"{seen_methods[python_method]} and {method.name} both generate {python_method}"
                    )
                seen_methods[python_method] = method.name

    def generate_grpc_module(self, services: List[Service]) -> GeneratedFile:
        """Generate a grpcio-style companion module for schema services."""
        module_name = self.get_module_name()
        async_mode = self.python_grpc_async()
        lines = []
        lines.append(self.get_license_header("#"))
        lines.append("")
        lines.append("from __future__ import annotations")
        lines.append("")
        lines.append("import grpc")
        if async_mode:
            lines.append("import grpc.aio")
        lines.append(f"import {module_name} as _models")
        lines.append("")
        lines.append("")
        lines.append("def _serialize(value):")
        lines.append("    return _models._get_fory().serialize(value)")
        lines.append("")
        lines.append("")
        lines.append("def _deserialize(data: bytes):")
        lines.append("    return _models._get_fory().deserialize(data)")
        lines.append("")
        lines.append("")

        for service in services:
            lines.extend(self.generate_python_grpc_stub(service))
            lines.append("")
            if async_mode:
                lines.extend(self.generate_python_grpc_async_servicer(service))
            else:
                lines.extend(self.generate_python_grpc_servicer(service))
            lines.append("")
            lines.extend(self.generate_python_grpc_registration(service))
            lines.append("")
        lines.extend(self.generate_python_grpc_add_servicer(services))
        lines.append("")

        return GeneratedFile(
            path=f"{module_name}_grpc.py",
            content="\n".join(lines),
        )

    def generate_python_grpc_stub(self, service: Service) -> List[str]:
        lines = []
        lines.append(f"class {service.name}Stub(object):")
        lines.append(f'    """Client stub for {service.name}."""')
        lines.append("")
        lines.append("    def __init__(self, channel):")
        for method in service.methods:
            channel_call = self.python_grpc_channel_call(method)
            python_name = self.python_grpc_method_name(method)
            lines.append(f"        self.{python_name} = channel.{channel_call}(")
            lines.append(f'            "{self.get_grpc_method_path(service, method)}",')
            lines.append("            request_serializer=_serialize,")
            lines.append("            response_deserializer=_deserialize,")
            lines.append("        )")
        if not service.methods:
            lines.append("        pass")
        lines.append("")
        return lines

    def generate_python_grpc_servicer(self, service: Service) -> List[str]:
        lines = []
        lines.append(f"class {service.name}Servicer(object):")
        lines.append(f'    """Base servicer for {service.name}."""')
        if not service.methods:
            lines.append("    pass")
            return lines
        for method in service.methods:
            python_name = self.python_grpc_method_name(method)
            lines.append("")
            if method.client_streaming:
                lines.append(f"    def {python_name}(self, request_iterator, context):")
            else:
                lines.append(f"    def {python_name}(self, request, context):")
            lines.append("        context.set_code(grpc.StatusCode.UNIMPLEMENTED)")
            lines.append('        context.set_details("Method not implemented!")')
            lines.append('        raise NotImplementedError("Method not implemented!")')
        return lines

    def generate_python_grpc_async_servicer(self, service: Service) -> List[str]:
        lines = []
        lines.append(f"class {service.name}Servicer(object):")
        lines.append(f'    """AsyncIO base servicer for {service.name}."""')
        if not service.methods:
            lines.append("    pass")
            return lines
        for method in service.methods:
            python_name = self.python_grpc_method_name(method)
            lines.append("")
            if method.client_streaming:
                lines.append(
                    f"    async def {python_name}(self, request_iterator, context):"
                )
            else:
                lines.append(f"    async def {python_name}(self, request, context):")
            lines.append(
                '        await context.abort(grpc.StatusCode.UNIMPLEMENTED, "Method not implemented!")'
            )
        return lines

    def generate_python_grpc_registration(self, service: Service) -> List[str]:
        lines = []
        add_fn = self.python_grpc_add_servicer_name(service)
        lines.append(f"def {add_fn}(servicer, server):")
        lines.append("    rpc_method_handlers = {")
        for method in service.methods:
            handler = self.python_grpc_handler(method)
            python_name = self.python_grpc_method_name(method)
            lines.append(f'        "{method.name}": grpc.{handler}(')
            lines.append(f"            servicer.{python_name},")
            lines.append("            request_deserializer=_deserialize,")
            lines.append("            response_serializer=_serialize,")
            lines.append("        ),")
        lines.append("    }")
        lines.append(
            f'    generic_handler = grpc.method_handlers_generic_handler("{self.get_grpc_service_name(service)}", rpc_method_handlers)'
        )
        lines.append("    server.add_generic_rpc_handlers((generic_handler,))")
        return lines

    def generate_python_grpc_add_servicer(self, services: List[Service]) -> List[str]:
        lines = []
        lines.append("def add_servicer(servicer, server):")
        lines.append("    registered = False")
        for service in services:
            add_fn = self.python_grpc_add_servicer_name(service)
            lines.append(f"    if isinstance(servicer, {service.name}Servicer):")
            lines.append(f"        {add_fn}(servicer, server)")
            lines.append("        registered = True")
        lines.append("    if not registered:")
        lines.append(
            '        raise TypeError(f"Unsupported gRPC servicer type: {type(servicer).__name__}")'
        )
        return lines

    def python_grpc_channel_call(self, method: RpcMethod) -> str:
        if method.client_streaming and method.server_streaming:
            return "stream_stream"
        if method.client_streaming:
            return "stream_unary"
        if method.server_streaming:
            return "unary_stream"
        return "unary_unary"

    def python_grpc_handler(self, method: RpcMethod) -> str:
        if method.client_streaming and method.server_streaming:
            return "stream_stream_rpc_method_handler"
        if method.client_streaming:
            return "stream_unary_rpc_method_handler"
        if method.server_streaming:
            return "unary_stream_rpc_method_handler"
        return "unary_unary_rpc_method_handler"

    def python_grpc_method_name(self, method: RpcMethod) -> str:
        return self.safe_name(self.to_snake_case(method.name))

    def python_grpc_add_servicer_name(self, service: Service) -> str:
        return f"_add_{self.safe_name(self.to_snake_case(service.name))}_servicer"

    def python_grpc_async(self) -> bool:
        mode = self.options.grpc_python_mode
        if mode not in ("async", "sync"):
            raise ValueError(f"Unknown Python gRPC mode: {mode}")
        return mode == "async"
