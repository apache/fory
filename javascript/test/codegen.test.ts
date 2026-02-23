/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import { describe, it, expect } from "@jest/globals";
import {
  IDLParser,
  CodeGenerator,
  generateFromProto,
} from "../packages/fory/lib/codegen";

describe("IDL Code Generation", () => {
  describe("IDLParser", () => {
    it("should parse proto3 simple message definition", () => {
      const proto = `
        syntax = "proto3";
        package example.v1;

        message Person {
          string name = 1;
          int32 id = 2;
          string email = 3;
        }
      `;

      const parser = new IDLParser(proto);
      const result = parser.parse();

      expect(result.syntax).toBe("proto3");
      expect(result.package).toBe("example.v1");
      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].name).toBe("Person");
      expect(result.messages[0].fields).toHaveLength(3);
      expect(result.messages[0].fields[0].name).toBe("name");
      expect(result.messages[0].fields[0].type).toBe("string");
    });

    it("should parse messages with repeated fields", () => {
      const proto = `
        message AddressBook {
          repeated Person people = 1;
        }

        message Person {
          string name = 1;
        }
      `;

      const parser = new IDLParser(proto);
      const result = parser.parse();

      const addressBook = result.messages.find((m) => m.name === "AddressBook");
      expect(addressBook).toBeDefined();
      expect(addressBook?.fields[0].repeated).toBe(true);
    });

    it("should parse enum definitions", () => {
      const proto = `
        enum Color {
          RED = 0;
          GREEN = 1;
          BLUE = 2;
        }
      `;

      const parser = new IDLParser(proto);
      const result = parser.parse();

      expect(result.enums).toHaveLength(1);
      expect(result.enums[0].name).toBe("Color");
      expect(result.enums[0].values).toHaveLength(3);
      expect(result.enums[0].values[0].name).toBe("RED");
      expect(result.enums[0].values[0].number).toBe(0);
    });

    it("should parse nested messages and enums", () => {
      const proto = `
        message Outer {
          message Inner {
            string value = 1;
          }

          enum Status {
            UNKNOWN = 0;
            ACTIVE = 1;
          }

          Inner inner = 1;
          Status status = 2;
        }
      `;

      const parser = new IDLParser(proto);
      const result = parser.parse();

      const outer = result.messages[0];
      expect(outer.nestedMessages).toHaveLength(1);
      expect(outer.nestedMessages[0].name).toBe("Inner");
      expect(outer.nestedEnums).toHaveLength(1);
      expect(outer.nestedEnums[0].name).toBe("Status");
    });

    it("should parse map fields", () => {
      const proto = `
        message Config {
          map<string, string> settings = 1;
          map<string, int32> values = 2;
        }
      `;

      const parser = new IDLParser(proto);
      const result = parser.parse();

      const config = result.messages[0];
      expect(config.fields[0].map).toBeDefined();
      expect(config.fields[0].map?.key).toBe("string");
      expect(config.fields[0].map?.value).toBe("string");
    });

    it("should parse service definitions", () => {
      const proto = `
        service UserService {
          rpc GetUser(GetUserRequest) returns (User);
          rpc ListUsers(Empty) returns (stream User);
          rpc CreateUser(CreateUserRequest) returns (User);
        }
      `;

      const parser = new IDLParser(proto);
      const result = parser.parse();

      expect(result.services).toHaveLength(1);
      expect(result.services[0].name).toBe("UserService");
      expect(result.services[0].methods).toHaveLength(3);
      expect(result.services[0].methods[1].serverStreaming).toBe(true);
    });

    it("should handle optional fields", () => {
      const proto = `
        message Request {
          string required_field = 1;
          optional string optional_field = 2;
        }
      `;

      const parser = new IDLParser(proto);
      const result = parser.parse();

      const msg = result.messages[0];
      expect(msg.fields[0].optional).toBe(false);
      expect(msg.fields[1].optional).toBe(true);
    });
  });

  describe("CodeGenerator - TypeScript", () => {
    it("should generate TypeScript interface for message", () => {
      const proto = `
        syntax = "proto3";
        package example.v1;

        message Person {
          string name = 1;
          int32 id = 2;
          string email = 3;
        }
      `;

      const generator = new CodeGenerator(proto, { language: "typescript" });
      const code = generator.generate();

      expect(code).toContain("export interface Person");
      expect(code).toContain("name: string;");
      expect(code).toContain("id: number;");
      expect(code).toContain("email: string;");
    });

    it("should generate TypeScript enum", () => {
      const proto = `
        enum Status {
          UNKNOWN = 0;
          ACTIVE = 1;
          INACTIVE = 2;
        }
      `;

      const generator = new CodeGenerator(proto, { language: "typescript" });
      const code = generator.generate();

      expect(code).toContain("export enum Status");
      expect(code).toContain("UNKNOWN = 0");
      expect(code).toContain("ACTIVE = 1");
    });

    it("should generate TypeScript for repeated fields as arrays", () => {
      const proto = `
        message List {
          repeated string items = 1;
          repeated int32 numbers = 2;
        }
      `;

      const generator = new CodeGenerator(proto, { language: "typescript" });
      const code = generator.generate();

      expect(code).toContain("items: string[];");
      expect(code).toContain("numbers: number[];");
    });

    it("should generate TypeScript for optional fields", () => {
      const proto = `
        message Request {
          string required = 1;
          optional string optional = 2;
        }
      `;

      const generator = new CodeGenerator(proto, { language: "typescript" });
      const code = generator.generate();

      expect(code).toContain("required: string;");
      expect(code).toContain("optional: string | undefined;");
    });

    it("should generate TypeScript service interface", () => {
      const proto = `
        service UserService {
          rpc GetUser(GetUserRequest) returns (User);
          rpc ListUsers(Empty) returns (stream User);
        }
      `;

      const generator = new CodeGenerator(proto, { language: "typescript" });
      const code = generator.generate();

      expect(code).toContain("export interface UserService");
      expect(code).toContain("getUser(input: GetUserRequest): Promise<User>;");
      expect(code).toContain(
        "listUsers(input: Empty): AsyncIterable<User>;"
      );
    });

    it("should generate proper type mappings", () => {
      const proto = `
        message AllTypes {
          double f_double = 1;
          float f_float = 2;
          int32 f_int32 = 3;
          int64 f_int64 = 4;
          uint32 f_uint32 = 5;
          bool f_bool = 6;
          string f_string = 7;
          bytes f_bytes = 8;
        }
      `;

      const generator = new CodeGenerator(proto, { language: "typescript" });
      const code = generator.generate();

      expect(code).toContain("f_double: number;");
      expect(code).toContain("f_float: number;");
      expect(code).toContain("f_int32: number;");
      expect(code).toContain("f_int64: bigint | string;");
      expect(code).toContain("f_uint32: number;");
      expect(code).toContain("f_bool: boolean;");
      expect(code).toContain("f_string: string;");
      expect(code).toContain("f_bytes: Uint8Array;");
    });

    it("should generate map fields as Record types", () => {
      const proto = `
        message Config {
          map<string, string> settings = 1;
        }
      `;

      const generator = new CodeGenerator(proto, { language: "typescript" });
      const code = generator.generate();

      expect(code).toContain("settings: Record<string, string>;");
    });
  });

  describe("CodeGenerator - JavaScript", () => {
    it("should generate JavaScript object for message", () => {
      const proto = `
        message Person {
          string name = 1;
          int32 id = 2;
        }
      `;

      const generator = new CodeGenerator(proto, { language: "javascript" });
      const code = generator.generate();

      expect(code).toContain("const Person = {");
    });
  });

  describe("Golden file signatures", () => {
    it("should maintain consistent file structure", () => {
      const proto = `
        syntax = "proto3";
        package example.v1;

        message Request {
          string query = 1;
        }

        message Response {
          string result = 2;
        }

        service ExampleService {
          rpc Search(Request) returns (Response);
        }
      `;

      const generator = new CodeGenerator(proto, { language: "typescript" });
      const code = generator.generate();

      // Verify structure
      expect(code).toContain("Licensed to the Apache");
      expect(code).toContain("Package: example.v1");
      expect(code).toContain("// Messages");
      expect(code).toContain("// Services");
      expect(code).toMatch(/export (interface|enum|type)/);
    });

    it("should include license header", () => {
      const proto = `message Test {}`;
      const generator = new CodeGenerator(proto);
      const code = generator.generate();

      expect(code).toContain("Licensed to the Apache Software Foundation");
      expect(code).toContain("under the License.\n */");
    });

    it("should include JSDoc when enabled", () => {
      const proto = `
        message Person {
          string name = 1;
        }
      `;

      const generator = new CodeGenerator(proto, { includeJSDoc: true });
      const code = generator.generate();

      expect(code).toContain("/**");
      expect(code).toContain("* Person message");
      expect(code).toContain("*/");
    });

    it("should not include JSDoc when disabled", () => {
      const proto = `
        message Person {
          string name = 1;
        }
      `;

      const generator = new CodeGenerator(proto, { includeJSDoc: false });
      const code = generator.generate();

      // Should not have message JSDoc (might have file header)
      const personSection = code.substring(
        code.indexOf("export interface Person")
      );
      expect(personSection).not.toContain("* Person message");
    });
  });

  describe("Helper function", () => {
    it("generateFromProto should work with default options", () => {
      const proto = `
        message User {
          string name = 1;
          int32 age = 2;
        }
      `;

      const code = generateFromProto(proto);

      expect(code).toContain("export interface User");
      expect(code).toContain("name: string;");
      expect(code).toContain("age: number;");
    });

    it("generateFromProto should work with custom options", () => {
      const proto = `
        message User {
          string name = 1;
        }
      `;

      const code = generateFromProto(proto, {
        language: "javascript",
        includeJSDoc: false,
      });

      expect(code).toContain("const User = {");
    });
  });

  describe("Namespace handling", () => {
    it("should handle package namespaces", () => {
      const proto = `
        syntax = "proto3";
        package com.example.service.v1;

        message Request {}
      `;

      const generator = new CodeGenerator(proto);
      const code = generator.generate();

      expect(code).toContain("// Package: com.example.service.v1");
    });

    it("should handle qualified type names", () => {
      const proto = `
        message Wrapper {
          com.example.Inner inner = 1;
        }

        message com.example.Inner {
          string value = 1;
        }
      `;

      const generator = new CodeGenerator(proto);
      const code = generator.generate();

      // Should reference the qualified name properly
      expect(code).toContain("Inner");
    });
  });

  describe("No runtime dependencies", () => {
    it("should not reference gRPC modules", () => {
      const proto = `
        service TestService {
          rpc Test(Request) returns (Response);
        }

        message Request {}
        message Response {}
      `;

      const generator = new CodeGenerator(proto);
      const code = generator.generate();

      expect(code).not.toContain("@grpc");
      expect(code).not.toContain("grpc-js");
      expect(code).not.toContain("require('grpc");
      expect(code).not.toContain("import.*grpc");
    });

    it("should only include type definitions", () => {
      const proto = `
        enum Status { UNKNOWN = 0; }
        message Data { string value = 1; }
      `;

      const code = generateFromProto(proto);

      // Should be pure type definitions
      expect(code).toMatch(/export (interface|enum|type)/);
      expect(code).not.toContain("class");
      expect(code).not.toContain("constructor");
      expect(code).not.toContain("method");
    });
  });
});
