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
import { containerDeclaresElementTypes, TypeInfo } from "../typeInfo";
import { CodegenRegistry } from "./router";
import { CodecBuilder } from "./builder";
import { Scope } from "./scope";
import { CompatibleScalarConverter } from "../compatible/scalar";
import "./array";
import "./struct";
import "./string";
import "./bool";
import "./datetime";
import "./decimal";
import "./map";
import "./number";
import "./set";
import "./struct";
import "./typedArray";
import "./enum";
import "./any";
import "./union";
import "./ext";
import TypeResolver from "../typeResolver";
import {
  checkedTypeMetaSerializerSymbol,
  checkedTypeMetaWireTypeIdSymbol,
  localTypeMetaSymbol,
  TypeMeta,
} from "../meta/TypeMeta";

CodegenRegistry.registerExternal(CompatibleScalarConverter);

type SerializerFactoryBuilder = () => (
  typeResolver: TypeResolver,
  external: unknown,
  typeInfo: TypeInfo,
  options: { [key: string]: unknown },
  localTypeMeta: TypeMeta | undefined,
  localTypeMetaSymbol: symbol,
  checkedTypeMetaSerializerSymbol: symbol,
  checkedTypeMetaWireTypeIdSymbol: symbol,
) => Serializer;

export class Gen {
  static external = CodegenRegistry.getExternal();

  constructor(
    private typeResolver: TypeResolver,
    private regOptions: { [key: string]: any } = {},
  ) {}

  private generate(typeInfo: TypeInfo): Serializer {
    const InnerGeneratorClass = CodegenRegistry.get(typeInfo.typeId);
    if (!InnerGeneratorClass) {
      throw new Error(`${typeInfo.typeId} generator not exists`);
    }
    const scope = new Scope();
    const generator = new InnerGeneratorClass(
      typeInfo,
      new CodecBuilder(scope, this.typeResolver),
      scope,
    );

    const funcString = generator.toSerializer();
    let factoryBuilder: SerializerFactoryBuilder;
    if (this.typeResolver.config && this.typeResolver.config.hooks) {
      const afterCodeGenerated = this.typeResolver.config.hooks.afterCodeGenerated;
      if (typeof afterCodeGenerated === "function") {
        factoryBuilder = new Function(afterCodeGenerated(funcString)) as SerializerFactoryBuilder;
      } else {
        factoryBuilder = new Function(funcString) as SerializerFactoryBuilder;
      }
    } else {
      factoryBuilder = new Function(funcString) as SerializerFactoryBuilder;
    }
    return factoryBuilder()(
      this.typeResolver,
      Gen.external,
      typeInfo,
      this.regOptions,
      generator.getLocalTypeMeta(),
      localTypeMetaSymbol,
      checkedTypeMetaSerializerSymbol,
      checkedTypeMetaWireTypeIdSymbol,
    );
  }

  private register(typeInfo: TypeInfo, serializer?: Serializer) {
    this.typeResolver.registerSerializer(typeInfo, serializer);
  }

  private isRegistered(typeInfo: TypeInfo) {
    return !!this.typeResolver.getSerializerByTypeInfo(typeInfo);
  }

  private isFullyGenerated(typeInfo: TypeInfo) {
    const ser = this.typeResolver.getSerializerByTypeInfo(typeInfo);
    return ser && ser._initialized;
  }

  private traversalContainer(typeInfo: TypeInfo) {
    if (TypeId.userDefinedType(typeInfo.typeId)) {
      if (this.isFullyGenerated(typeInfo)) {
        return;
      }
      const options = typeInfo.options;
      const unionType =
        typeInfo.typeId === TypeId.UNION ||
        typeInfo.typeId === TypeId.TYPED_UNION ||
        typeInfo.typeId === TypeId.NAMED_UNION;
      if (unionType && options?.cases && Object.keys(options.cases).length > 0) {
        this.register(typeInfo);
        Object.values(options.cases).forEach((x) => {
          this.traversalContainer(x);
        });
        this.register(typeInfo, this.generate(typeInfo));
        return;
      } else if (options?.props && Object.keys(options.props).length > 0) {
        this.register(typeInfo);
        Object.values(options.props).forEach((x) => {
          this.traversalContainer(x);
        });
        this.register(typeInfo, this.generate(typeInfo));
      } else if (
        !this.isRegistered(typeInfo) &&
        (TypeId.structType(typeInfo.typeId) || TypeId.extType(typeInfo.typeId))
      ) {
        // Forward reference to a struct or ext type not yet fully defined —
        // register a placeholder so that serializer factories can capture the
        // object reference.  The placeholder will be filled in via
        // Object.assign when the real serializer is generated later.
        this.register(typeInfo);
      } else if (TypeId.enumType(typeInfo.typeId) && !this.isRegistered(typeInfo)) {
        this.register(typeInfo, this.generate(typeInfo));
      }
    }
    if (typeInfo.typeId === TypeId.LIST) {
      this.traversalContainer(typeInfo.options!.inner!);
    }
    if (typeInfo.typeId === TypeId.SET) {
      this.traversalContainer(typeInfo.options!.key!);
    }
    if (typeInfo.typeId === TypeId.MAP) {
      if (!typeInfo.options?.key || !typeInfo.options?.value) {
        throw new Error("map type must have key and value");
      }
      this.traversalContainer(typeInfo.options!.key!);
      this.traversalContainer(typeInfo.options!.value!);
    }
    if (typeInfo.options?.cases) {
      Object.values(typeInfo.options.cases).forEach((caseTypeInfo) => {
        this.traversalContainer(caseTypeInfo);
      });
    }
  }

  reGenerateSerializer(typeInfo: TypeInfo) {
    return this.generate(typeInfo);
  }

  generateSerializer(typeInfo: TypeInfo) {
    this.traversalContainer(typeInfo);
    if (containerDeclaresElementTypes(typeInfo)) {
      // The type-id keyed registry only holds the dynamic container
      // serializer; a container with declared element types gets a dedicated
      // serializer for this registration.
      return this.generate(typeInfo);
    }
    const serializer = this.typeResolver.getSerializerByTypeInfo(typeInfo);
    if (serializer?._initialized) {
      return serializer;
    }
    return this.reGenerateSerializer(typeInfo);
  }
}
