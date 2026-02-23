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

/**
 * Code generator for converting proto IDL to TypeScript/JavaScript type definitions.
 * Generates gRPC-compatible message, enum, and union type definitions.
 */

import {
  ProtoFile,
  ProtoMessage,
  ProtoEnum,
  ProtoField,
  ProtoService,
  ProtoMethod,
  IDLParser,
} from "./idlParser";

export interface CodeGenOptions {
  /** Target language: 'typescript' or 'javascript' */
  language?: "typescript" | "javascript";
  /** Whether to generate gRPC service definitions */
  generateServices?: boolean;
  /** Custom package name override */
  packageOverride?: string;
  /** Whether to include JSDoc comments */
  includeJSDoc?: boolean;
  /** Output file extension */
  fileExtension?: string;
}

const DEFAULT_OPTIONS: Required<CodeGenOptions> = {
  language: "typescript",
  generateServices: true,
  packageOverride: "",
  includeJSDoc: true,
  fileExtension: ".ts",
};

export class CodeGenerator {
  private protoFile: ProtoFile;
  private options: Required<CodeGenOptions>;
  private typeMapping: Map<string, string> = new Map();

  constructor(protoContent: string, options: CodeGenOptions = {}) {
    const parser = new IDLParser(protoContent);
    this.protoFile = parser.parse();
    this.options = { ...DEFAULT_OPTIONS, ...options };
    this.setupTypeMapping();
  }

  /**
   * Generate TypeScript/JavaScript code for all types in the proto file
   */
  generate(): string {
    const lines: string[] = [];

    // Add license header
    lines.push(this.generateLicenseHeader());

    // Add imports
    lines.push(this.generateImports());

    // Add package comment
    if (this.protoFile.package) {
      lines.push(`// Package: ${this.protoFile.package}`);
      lines.push("");
    }

    // Generate enums first (they're used by messages)
    if (this.protoFile.enums.length > 0) {
      lines.push("// Enums");
      lines.push("");
      this.protoFile.enums.forEach((enumDef) => {
        lines.push(this.generateEnum(enumDef));
        lines.push("");
      });
    }

    // Generate messages
    if (this.protoFile.messages.length > 0) {
      lines.push("// Messages");
      lines.push("");
      this.protoFile.messages.forEach((message) => {
        lines.push(this.generateMessage(message));
        lines.push("");
      });
    }

    // Generate service definitions if requested
    if (this.options.generateServices && this.protoFile.services.length > 0) {
      lines.push("// Services");
      lines.push("");
      this.protoFile.services.forEach((service) => {
        lines.push(this.generateService(service));
        lines.push("");
      });
    }

    // Add exports
    lines.push(this.generateExports());

    return lines.join("\n");
  }

  /**
   * Generate TypeScript/JavaScript code for a specific message
   */
  private generateMessage(message: ProtoMessage, indent = 0): string {
    const lines: string[] = [];
    const spaces = " ".repeat(indent);
    const interfaceName = this.toPascalCase(message.name);

    if (this.options.includeJSDoc) {
      lines.push(`${spaces}/**`);
      lines.push(`${spaces} * ${message.name} message`);
      lines.push(`${spaces} */`);
    }

    if (this.options.language === "typescript") {
      lines.push(`${spaces}export interface ${interfaceName} {`);
    } else {
      lines.push(`${spaces}const ${interfaceName} = {`);
    }

    // Generate fields
    message.fields.forEach((field) => {
      lines.push(this.generateField(field, indent + 2));
    });

    // Generate nested enums
    if (message.nestedEnums.length > 0) {
      lines.push("");
      message.nestedEnums.forEach((enumDef) => {
        lines.push(this.generateEnum(enumDef, indent + 2));
        lines.push("");
      });
    }

    // Generate nested messages
    if (message.nestedMessages.length > 0) {
      lines.push("");
      message.nestedMessages.forEach((nestedMsg) => {
        lines.push(this.generateMessage(nestedMsg, indent + 2));
        lines.push("");
      });
    }

    lines.push(`${spaces}}`);

    return lines.join("\n");
  }

  /**
   * Generate field definition
   */
  private generateField(field: ProtoField, indent = 0): string {
    const spaces = " ".repeat(indent);
    let fieldType = this.mapProtoType(field.type);

    // Handle repeated fields (arrays)
    if (field.repeated && !field.map) {
      fieldType = `${fieldType}[]`;
    }

    // Handle optional fields (nullable)
    if (field.optional) {
      if (this.options.language === "typescript") {
        fieldType = `${fieldType} | undefined`;
      } else {
        fieldType = `${fieldType} | null`;
      }
    }

    // Handle map types
    if (field.map) {
      const keyType = this.mapProtoType(field.map.key);
      const valueType = this.mapProtoType(field.map.value);
      fieldType = `Record<${keyType}, ${valueType}>`;
    }

    if (this.options.language === "typescript") {
      return `${spaces}${field.name}: ${fieldType};`;
    } else {
      return `${spaces}${field.name}: '${fieldType}',`;
    }
  }

  /**
   * Generate enum definition
   */
  private generateEnum(enumDef: ProtoEnum, indent = 0): string {
    const lines: string[] = [];
    const spaces = " ".repeat(indent);
    const enumName = this.toPascalCase(enumDef.name);

    if (this.options.includeJSDoc) {
      lines.push(`${spaces}/**`);
      lines.push(`${spaces} * ${enumDef.name} enum`);
      lines.push(`${spaces} */`);
    }

    if (this.options.language === "typescript") {
      lines.push(`${spaces}export enum ${enumName} {`);
      enumDef.values.forEach((value) => {
        lines.push(`${spaces}  ${value.name} = ${value.number},`);
      });
      lines.push(`${spaces}}`);
    } else {
      lines.push(`${spaces}const ${enumName} = {`);
      enumDef.values.forEach((value) => {
        lines.push(`${spaces}  ${value.name}: ${value.number},`);
      });
      lines.push(`${spaces}};`);
    }

    return lines.join("\n");
  }

  /**
   * Generate service definitions for gRPC compatibility
   */
  private generateService(service: ProtoService, indent = 0): string {
    const lines: string[] = [];
    const spaces = " ".repeat(indent);
    const serviceName = this.toPascalCase(service.name);

    if (this.options.includeJSDoc) {
      lines.push(`${spaces}/**`);
      lines.push(`${spaces} * ${service.name} service definition`);
      lines.push(`${spaces} */`);
    }

    lines.push(`${spaces}export interface ${serviceName} {`);

    service.methods.forEach((method) => {
      lines.push(this.generateServiceMethod(method, indent + 2));
    });

    lines.push(`${spaces}}`);

    return lines.join("\n");
  }

  /**
   * Generate a single service method signature
   */
  private generateServiceMethod(method: ProtoMethod, indent = 0): string {
    const spaces = " ".repeat(indent);
    const methodName = this.toCamelCase(method.name);
    const inputType = this.toPascalCase(method.inputType);
    const outputType = this.toPascalCase(method.outputType);

    let signature = `${spaces}${methodName}`;

    if (this.options.language === "typescript") {
      const inputParam = method.clientStreaming
        ? `input: AsyncIterable<${inputType}>`
        : `input: ${inputType}`;
      const returnType = method.serverStreaming
        ? `AsyncIterable<${outputType}>`
        : `Promise<${outputType}>`;

      signature += `(${inputParam}): ${returnType};`;
    } else {
      signature += `(input): Promise<any>;`;
    }

    return signature;
  }

  /**
   * Generate union type definitions (oneofs)
   */
  private generateUnionType(
    name: string,
    members: string[],
    indent = 0
  ): string {
    const spaces = " ".repeat(indent);
    const unionName = this.toPascalCase(name);

    if (this.options.language === "typescript") {
      const unionDef = members.map(m => `{ type: '${m}'; value: ${m} }`).join(" | ");
      return `${spaces}export type ${unionName} = ${unionDef};`;
    } else {
      return `${spaces}const ${unionName} = Symbol('${name}');`;
    }
  }

  /**
   * Map proto types to TypeScript/JavaScript types
   */
  private mapProtoType(protoType: string): string {
    // Check custom type mapping first
    if (this.typeMapping.has(protoType)) {
      return this.typeMapping.get(protoType)!;
    }

    // Handle qualified type names
    if (protoType.includes(".")) {
      return this.toPascalCase(protoType.split(".").pop() || protoType);
    }

    // Default to PascalCase (for custom types)
    return this.toPascalCase(protoType);
  }

  /**
   * Setup built-in type mappings
   */
  private setupTypeMapping(): void {
    // Scalar types
    this.typeMapping.set("double", "number");
    this.typeMapping.set("float", "number");
    this.typeMapping.set("int32", "number");
    this.typeMapping.set("int64", "bigint | string");
    this.typeMapping.set("uint32", "number");
    this.typeMapping.set("uint64", "bigint | string");
    this.typeMapping.set("sint32", "number");
    this.typeMapping.set("sint64", "bigint | string");
    this.typeMapping.set("fixed32", "number");
    this.typeMapping.set("fixed64", "bigint | string");
    this.typeMapping.set("sfixed32", "number");
    this.typeMapping.set("sfixed64", "bigint | string");
    this.typeMapping.set("bool", "boolean");
    this.typeMapping.set("string", "string");
    this.typeMapping.set("bytes", "Uint8Array");

    // Well-known types
    this.typeMapping.set("google.protobuf.Timestamp", "Date");
    this.typeMapping.set("google.protobuf.Duration", "number");
    this.typeMapping.set("google.protobuf.StringValue", "string");
    this.typeMapping.set("google.protobuf.Int32Value", "number");
    this.typeMapping.set("google.protobuf.Int64Value", "bigint | string");
    this.typeMapping.set("google.protobuf.Any", "any");
  }

  /**
   * Convert string to PascalCase
   */
  private toPascalCase(str: string): string {
    if (!str) return "";

    // If string has delimiters, split and capitalize each part
    if (/[._-]/.test(str)) {
      return str
        .split(/[._-]/)
        .map((part) => {
          if (!part) return "";
          return part.charAt(0).toUpperCase() + part.slice(1).toLowerCase();
        })
        .join("");
    }

    // If already appears to be PascalCase, return as-is
    if (/^[A-Z]/.test(str)) {
      return str;
    }

    // Otherwise capitalize first letter
    return str.charAt(0).toUpperCase() + str.slice(1);
  }

  /**
   * Convert string to camelCase
   */
  private toCamelCase(str: string): string {
    if (!str) return "";
    const pascal = this.toPascalCase(str);
    return pascal.charAt(0).toLowerCase() + pascal.slice(1);
  }

  /**
   * Generate license header
   */
  private generateLicenseHeader(): string {
    return `/*
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
 */`;
  }

  /**
   * Generate import statements
   */
  private generateImports(): string {
    // No external imports needed - only type definitions
    return "// Auto-generated from proto IDL\n";
  }

  /**
   * Generate export statements
   */
  private generateExports(): string {
    const lines: string[] = [];
    lines.push("// Export all types");
    lines.push("export {};");
    return lines.join("\n");
  }
}

/**
 * Entry point for generating code from proto files
 */
export function generateFromProto(
  protoContent: string,
  options?: CodeGenOptions
): string {
  const generator = new CodeGenerator(protoContent, options);
  return generator.generate();
}
