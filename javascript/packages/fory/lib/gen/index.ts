/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import { TypeId, Serializer } from "../type";
import { ArrayTypeInfo, MapTypeInfo, StructTypeInfo, SetTypeInfo, TypeInfo } from "../typeInfo";
import { CodegenRegistry } from "./router";
import { CodecBuilder } from "./builder";
import { Scope } from "./scope";
import "./array";
import "./struct";
import "./string";
import "./binary";
import "./bool";
import "./datetime";
import "./map";
import "./number";
import "./set";
import "./struct";
import "./typedArray";
import "./enum";
import "./any";
import Fory from "../fory";

export class Gen {
  static external = CodegenRegistry.getExternal();

  constructor(private fory: Fory, private regOptions: { [key: string]: any } = {}) {

  }

  private generate(typeInfo: TypeInfo) {
    // Robust check for typeId with fallback to internal _typeId property
    const typeId = typeInfo.typeId ?? (typeInfo as any)._typeId;

    if (typeId === undefined) {
      throw new Error(`Cannot generate serializer for undefined typeId. Object: ${JSON.stringify(typeInfo)}`);
    }

    const InnerGeneratorClass = CodegenRegistry.get(typeId);
    if (!InnerGeneratorClass) {
      throw new Error(`${typeId} generator not exists`);
    }
    const scope = new Scope();
    const generator = new InnerGeneratorClass(typeInfo, new CodecBuilder(scope, this.fory), scope);

    const funcString = generator.toSerializer();
    if (this.fory.config && this.fory.config.hooks) {
      const afterCodeGenerated = this.fory.config.hooks.afterCodeGenerated;
      if (typeof afterCodeGenerated === "function") {
        return new Function(afterCodeGenerated(funcString));
      }
    }
    return new Function(funcString);
  }

  private register(typeInfo: StructTypeInfo, serializer?: Serializer) {
    this.fory.typeResolver.registerSerializer(typeInfo, serializer);
  }

  private isRegistered(typeInfo: TypeInfo) {
    return !!this.fory.typeResolver.typeInfoExists(typeInfo);
  }

  private traversalContainer(typeInfo: TypeInfo) {
    if (!typeInfo) return;

    // Use fallback for typeId here as well
    const typeId = typeInfo.typeId ?? (typeInfo as any)._typeId;

    // 1. Handle User Defined Types (Structs/Classes)
    if (TypeId.userDefinedType(typeId)) {
      if (this.isRegistered(typeInfo)) {
        return;
      }
      const options = (typeInfo as StructTypeInfo).options;
      if (options && options.props) {
        this.register(typeInfo as StructTypeInfo);
        Object.values(options.props).forEach((x) => {
          this.traversalContainer(x);
        });
        const func = this.generate(typeInfo);
        this.register(typeInfo as StructTypeInfo, func()(this.fory, Gen.external, typeInfo, this.regOptions));
      }
      return; 
    }
    
    // 2. Handle Collection Types (Recursive check for nested types)
    if (typeId === TypeId.LIST) {
      const inner = (typeInfo as ArrayTypeInfo).options?.inner;
      if (inner) this.traversalContainer(inner);
    } else if (typeId === TypeId.SET) {
      const inner = (typeInfo as SetTypeInfo).options?.key;
      if (inner) this.traversalContainer(inner);
    } else if (typeId === TypeId.MAP) {
      const options = (typeInfo as MapTypeInfo).options;
      if (options?.key) this.traversalContainer(options.key);
      if (options?.value) this.traversalContainer(options.value);
    }
  }

  reGenerateSerializer(typeInfo: TypeInfo) {
    const func = this.generate(typeInfo);
    return func()(this.fory, Gen.external, typeInfo, this.regOptions);
  }

  generateSerializer(typeInfo: TypeInfo) {
    this.traversalContainer(typeInfo);
    
    const typeId = typeInfo.typeId ?? (typeInfo as any)._typeId;

    if (TypeId.userDefinedType(typeId) && this.isRegistered(typeInfo)) {
      return this.fory.typeResolver.getSerializerByTypeInfo(typeInfo);
    }
    
    return this.reGenerateSerializer(typeInfo);
  }
}
