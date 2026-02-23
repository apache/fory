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
 * IDL (Interface Definition Language) parser for proto3 format.
 * Parses proto files into AST for code generation.
 */

export interface ProtoField {
  fieldNumber: number;
  name: string;
  type: string;
  repeated: boolean;
  optional: boolean;
  map: { key: string; value: string } | null;
  oneof: string | null;
}

export interface ProtoMessage {
  name: string;
  fields: ProtoField[];
  nestedMessages: ProtoMessage[];
  nestedEnums: ProtoEnum[];
  oneof: { [name: string]: string[] };
}

export interface ProtoEnumValue {
  name: string;
  number: number;
}

export interface ProtoEnum {
  name: string;
  values: ProtoEnumValue[];
}

export interface ProtoService {
  name: string;
  methods: ProtoMethod[];
}

export interface ProtoMethod {
  name: string;
  inputType: string;
  outputType: string;
  clientStreaming: boolean;
  serverStreaming: boolean;
}

export interface ProtoFile {
  syntax: string;
  package: string;
  imports: string[];
  messages: ProtoMessage[];
  enums: ProtoEnum[];
  services: ProtoService[];
}

export class IDLParser {
  private input: string;
  private pos = 0;

  constructor(input: string) {
    this.input = input;
  }

  /**
   * Parse a proto file content into ProtoFile AST
   */
  parse(): ProtoFile {
    const result: ProtoFile = {
      syntax: "proto3",
      package: "",
      imports: [],
      messages: [],
      enums: [],
      services: [],
    };

    while (!this.isAtEnd()) {
      this.skipWhitespaceAndComments();
      if (this.isAtEnd()) break;

      if (this.match("syntax")) {
        result.syntax = this.parseSyntax();
      } else if (this.match("package")) {
        result.package = this.parsePackage();
      } else if (this.match("import")) {
        result.imports.push(this.parseImport());
      } else if (this.match("message")) {
        result.messages.push(this.parseMessage());
      } else if (this.match("enum")) {
        result.enums.push(this.parseEnum());
      } else if (this.match("service")) {
        result.services.push(this.parseService());
      } else {
        this.advance();
      }
    }

    return result;
  }

  private parseSyntax(): string {
    // syntax = "proto3";
    this.consume("=", "Expected '=' after syntax");
    const quote = this.peek();
    if (quote === "\"" || quote === "'") {
      this.advance();
      const syntax = this.readUntil(quote);
      this.advance(); // consume closing quote
      this.consume(";", "Expected ';' after syntax");
      return syntax;
    }
    return "proto3";
  }

  private parsePackage(): string {
    // package com.example.foo;
    const pkg = this.readIdentifier();
    this.consume(";", "Expected ';' after package");
    return pkg;
  }

  private parseImport(): string {
    // import "google/protobuf/empty.proto";
    const quote = this.peek();
    if (quote === "\"" || quote === "'") {
      this.advance();
      const path = this.readUntil(quote);
      this.advance();
      this.consume(";", "Expected ';' after import");
      return path;
    }
    throw this.error("Expected quoted string in import");
  }

  private parseMessage(): ProtoMessage {
    const name = this.readIdentifier();
    const message: ProtoMessage = {
      name,
      fields: [],
      nestedMessages: [],
      nestedEnums: [],
      oneof: {},
    };

    this.consume("{", "Expected '{' after message name");

    while (!this.check("}") && !this.isAtEnd()) {
      this.skipWhitespaceAndComments();

      if (this.check("}")) {
        break;
      }

      if (this.match("message")) {
        message.nestedMessages.push(this.parseMessage());
      } else if (this.match("enum")) {
        message.nestedEnums.push(this.parseEnum());
      } else if (this.match("oneof")) {
        const oneofName = this.readIdentifier();
        this.consume("{", "Expected '{' after oneof name");
        const oneofFields: string[] = [];
        while (!this.check("}") && !this.isAtEnd()) {
          this.skipWhitespaceAndComments();
          if (!this.check("}")) {
            const field = this.parseField();
            if (field) {
              field.oneof = oneofName;
              message.fields.push(field);
              oneofFields.push(field.name);
            }
          }
        }
        this.consume("}", "Expected '}' to close oneof");
        message.oneof[oneofName] = oneofFields;
      } else {
        const field = this.parseField();
        if (field) {
          message.fields.push(field);
        }
      }
    }

    this.consume("}", "Expected '}' to close message");
    return message;
  }

  private parseField(): ProtoField | null {
    let repeated = false;
    let optional = false;

    if (this.match("repeated")) {
      repeated = true;
    } else if (this.match("optional")) {
      optional = true;
    }

    // Check for map
    if (this.match("map")) {
      return this.parseMapField(repeated, optional);
    }

    this.skipWhitespaceAndComments();
    const type = this.readIdentifier();
    const name = this.readIdentifier();
    this.consume("=", "Expected '=' after field name");
    const fieldNumber = parseInt(this.readNumber(), 10);

    // Skip options if present
    while (this.match("[")) {
      this.skipUntilMatchingBracket();
      this.consume("]", "Expected ']'");
      if (!this.match(",")) {
        break;
      }
    }

    this.consume(";", "Expected ';' after field");

    return {
      fieldNumber,
      name,
      type,
      repeated,
      optional,
      map: null,
      oneof: null,
    };
  }

  private parseMapField(repeated: boolean, optional: boolean): ProtoField {
    // map<key_type, value_type> map_field = 1;
    this.consume("<", "Expected '<' after map");
    const keyType = this.readIdentifier();
    this.consume(",", "Expected ',' after map key type");
    const valueType = this.readIdentifier();
    this.consume(">", "Expected '>' after map value type");

    const name = this.readIdentifier();
    this.consume("=", "Expected '=' after field name");
    const fieldNumber = parseInt(this.readNumber(), 10);

    // Skip options if present
    while (this.match("[")) {
      this.skipUntilMatchingBracket();
      this.consume("]", "Expected ']'");
      if (!this.match(",")) {
        break;
      }
    }

    this.consume(";", "Expected ';' after field");

    return {
      fieldNumber,
      name,
      type: valueType,
      repeated: true,
      optional,
      map: { key: keyType, value: valueType },
      oneof: null,
    };
  }

  private parseEnum(): ProtoEnum {
    const name = this.readIdentifier();
    this.consume("{", "Expected '{' after enum name");

    const values: ProtoEnumValue[] = [];

    while (!this.check("}") && !this.isAtEnd()) {
      this.skipWhitespaceAndComments();

      if (this.check("}")) {
        break;
      }

      const enumName = this.readIdentifier();
      if (!enumName) {
        break;
      }
      this.consume("=", "Expected '=' after enum value name");
      const enumNumber = parseInt(this.readNumber(), 10);

      // Skip options if present
      while (this.match("[")) {
        this.skipUntilMatchingBracket();
        this.consume("]", "Expected ']'");
        if (!this.match(",")) {
          break;
        }
      }

      this.consume(";", "Expected ';' after enum value");

      values.push({
        name: enumName,
        number: enumNumber,
      });
    }

    this.consume("}", "Expected '}' to close enum");
    return { name, values };
  }

  private parseService(): ProtoService {
    const name = this.readIdentifier();
    this.consume("{", "Expected '{' after service name");

    const methods: ProtoMethod[] = [];

    while (!this.check("}") && !this.isAtEnd()) {
      this.skipWhitespaceAndComments();

      if (this.check("}")) {
        break;
      }

      if (this.match("rpc")) {
        methods.push(this.parseRpcMethod());
      }
    }

    this.consume("}", "Expected '}' to close service");
    return { name, methods };
  }

  private parseRpcMethod(): ProtoMethod {
    const name = this.readIdentifier();
    this.consume("(", "Expected '(' after method name");

    let clientStreaming = false;
    if (this.match("stream")) {
      clientStreaming = true;
    }

    const inputType = this.readIdentifier();
    this.consume(")", "Expected ')' after input type");

    this.consume("returns", "Expected 'returns' keyword");
    this.consume("(", "Expected '(' after returns");

    let serverStreaming = false;
    if (this.match("stream")) {
      serverStreaming = true;
    }

    const outputType = this.readIdentifier();
    this.consume(")", "Expected ')' after output type");

    // Parse method options if present
    if (this.match("{")) {
      while (!this.check("}") && !this.isAtEnd()) {
        this.skipWhitespaceAndComments();
        this.advance();
      }
      this.consume("}", "Expected '}' after method options");
    } else {
      this.consume(";", "Expected ';' or '{' after method signature");
    }

    return {
      name,
      inputType,
      outputType,
      clientStreaming,
      serverStreaming,
    };
  }

  // Helper methods
  private match(...keywords: string[]): boolean {
    this.skipWhitespaceAndComments();
    for (const keyword of keywords) {
      if (this.check(keyword)) {
        for (let i = 0; i < keyword.length; i++) {
          this.advance();
        }
        return true;
      }
    }
    return false;
  }

  private check(str: string): boolean {
    const saved = this.pos;
    let match = true;
    for (const char of str) {
      if (this.peek() !== char) {
        match = false;
        break;
      }
      this.advance();
    }
    this.pos = saved;

    // If it's a keyword, ensure next char is not alphanumeric
    if (match && /^[a-zA-Z_]/.test(str)) {
      const nextChar = this.input[this.pos + str.length];
      if (nextChar && /[a-zA-Z0-9_.]/.test(nextChar)) {
        return false;
      }
    }

    return match;
  }

  private consume(expected: string, message: string): void {
    this.skipWhitespaceAndComments();
    if (!this.match(expected)) {
      throw this.error(message);
    }
  }

  private readIdentifier(): string {
    this.skipWhitespaceAndComments();

    // Check if next character is actually part of an identifier
    if (!/[a-zA-Z_]/.test(this.peek())) {
      return ""; // Return empty string for incomplete identifiers
    }

    let result = "";
    while (!this.isAtEnd() && /[a-zA-Z0-9_]/.test(this.peek())) {
      result += this.peek();
      this.advance();
    }
    // Handle dotted names (e.g., com.example.Type)
    while (this.peek() === "." && /[a-zA-Z0-9_]/.test(this.peekNext())) {
      result += this.peek();
      this.advance();
      while (!this.isAtEnd() && /[a-zA-Z0-9_]/.test(this.peek())) {
        result += this.peek();
        this.advance();
      }
    }
    if (!result) {
      throw this.error("Expected identifier");
    }
    return result;
  }

  private readNumber(): string {
    this.skipWhitespaceAndComments();
    let result = "";
    while (!this.isAtEnd() && /[0-9]/.test(this.peek())) {
      result += this.peek();
      this.advance();
    }
    if (!result) {
      throw this.error("Expected number");
    }
    return result;
  }

  private readUntil(char: string): string {
    let result = "";
    while (!this.isAtEnd() && this.peek() !== char) {
      result += this.peek();
      this.advance();
    }
    return result;
  }

  private skipUntilMatchingBracket(): void {
    let depth = 1;
    this.advance(); // skip opening bracket
    while (!this.isAtEnd() && depth > 0) {
      if (this.peek() === "[") {
        depth++;
      } else if (this.peek() === "]") {
        depth--;
      }
      this.advance();
    }
  }

  private skipWhitespaceAndComments(): void {
    while (!this.isAtEnd()) {
      if (/\s/.test(this.peek())) {
        this.advance();
      } else if (this.peek() === "/" && this.peekNext() === "/") {
        // Skip line comment
        while (!this.isAtEnd() && this.peek() !== "\n") {
          this.advance();
        }
        if (!this.isAtEnd()) this.advance();
      } else if (this.peek() === "/" && this.peekNext() === "*") {
        // Skip block comment
        this.advance();
        this.advance();
        while (!this.isAtEnd()) {
          if (this.peek() === "*" && this.peekNext() === "/") {
            this.advance();
            this.advance();
            break;
          }
          this.advance();
        }
      } else {
        break;
      }
    }
  }

  private peek(): string {
    return this.input[this.pos] || "";
  }

  private peekNext(): string {
    return this.input[this.pos + 1] || "";
  }

  private advance(): void {
    this.pos++;
  }

  private isAtEnd(): boolean {
    return this.pos >= this.input.length;
  }

  private error(message: string): Error {
    const line = this.input.substring(0, this.pos).split("\n").length;
    const col = this.pos - this.input.lastIndexOf("\n", this.pos);
    return new Error(`${message} at line ${line}, column ${col}`);
  }
}
