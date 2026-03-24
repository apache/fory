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

import { TypeId, Serializer } from "../type";
import { TypeInfo } from "../typeInfo";
import { CodegenRegistry } from "./router";
import { CodecBuilder } from "./builder";
import { Scope } from "./scope";
import "./array";
import "./struct";
import "./string";
import "./bool";
import "./datetime";
import "./map";
import "./number";
import "./set";
import "./struct";
import "./typedArray";
import "./enum";
import "./any";
import "./ext";
import Fory from "../fory";

export class Gen {
  static external = CodegenRegistry.getExternal();

  constructor(private fory: Fory, private regOptions: { [key: string]: any } = {}) {

  }

  private generate(typeInfo: TypeInfo) {
    const InnerGeneratorClass = CodegenRegistry.get(typeInfo.typeId);
    if (!InnerGeneratorClass) {
      throw new Error(`${typeInfo.typeId} generator not exists`);
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

  private register(typeInfo: TypeInfo, serializer?: Serializer) {
    this.fory.typeResolver.registerSerializer(typeInfo, serializer);
  }

  private isRegistered(typeInfo: TypeInfo) {
    return !!this.fory.typeResolver.getSerializerByTypeInfo(typeInfo);
  }

  private traversalContainer(typeInfo: TypeInfo) {
    if (TypeId.userDefinedType(typeInfo.typeId)) {
      if (this.isRegistered(typeInfo)) {
        return;
      }
      const options = (typeInfo).options;
      if (options?.props) {
        this.register(typeInfo);
        Object.values(options.props).forEach((x) => {
          this.traversalContainer(x);
        });
        const func = this.generate(typeInfo);
        this.register(typeInfo, func()(this.fory, Gen.external, typeInfo, this.regOptions));
      }
    }
    if (typeInfo.typeId === TypeId.LIST) {
      this.traversalContainer(typeInfo.options!.inner!);
    }
    if (typeInfo.typeId === TypeId.SET) {
      this.traversalContainer((typeInfo).options!.key!);
    }
    if (typeInfo.typeId === TypeId.MAP) {
      if (!typeInfo.options?.key || !typeInfo.options?.value) {
        throw new Error("map type must have key and value");
      }
      this.traversalContainer((typeInfo).options!.key!);
      this.traversalContainer((typeInfo).options!.value!);
    }
  }

  reGenerateSerializer(typeInfo: TypeInfo) {
    const func = this.generate(typeInfo);
    return func()(this.fory, Gen.external, typeInfo, this.regOptions);
  }

  generateSerializer(typeInfo: TypeInfo) {
    this.traversalContainer(typeInfo);
    const exists = this.isRegistered(typeInfo);
    if (exists) {
      return this.fory.typeResolver.getSerializerByTypeInfo(typeInfo);
    }
    return this.reGenerateSerializer(typeInfo);
  }
}
