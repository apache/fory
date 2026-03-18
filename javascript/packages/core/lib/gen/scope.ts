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

export class Scope {
  private declares: Map<string, string> = new Map();
  private varDeclares: Map<string, string> = new Map();
  private idx = 0;

  private addDeclar(stmt: string, name: string) {
    if (this.declares.has(stmt)) {
      return this.declares.get(stmt)!;
    }
    this.declares.set(stmt, name);
    return name;
  }

  uniqueName(prefix: string) {
    return `${prefix}_${this.idx++}`;
  }

  declareByName(name: string, stmt: string) {
    return this.addDeclar(stmt, name);
  }

  declareVar(name: string, stmt: string) {
    name = this.uniqueName(name);
    this.varDeclares.set(name, stmt);
    return name;
  }

  assertNameNotDuplicate(name: string) {
    for (const item of this.declares.values()) {
      if (item === name) {
        throw new Error(`const ${name} declare duplicate`);
      }
    }
    for (const item of this.varDeclares.keys()) {
      if (item === name) {
        throw new Error(`let ${name} declare duplicate`);
      }
    }
  }

  declare(prefix: string, stmt: string) {
    return this.addDeclar(stmt, this.uniqueName(prefix));
  }

  generate() {
    return `
      ${Array.from(this.declares.entries()).map(x => `const ${x[1]} = ${x[0]};`).join("\n")}
      ${Array.from(this.varDeclares.entries()).map(x => `let ${x[0]} = ${x[1]};`).join("\n")}
    `;
  }
}
