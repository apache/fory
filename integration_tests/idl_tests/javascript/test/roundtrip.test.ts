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
 * Integration tests for TypeScript IDL-generated code.
 *
 * These tests verify that:
 * 1. Generated TypeScript types compile correctly
 * 2. Objects can be constructed conforming to the generated interfaces
 * 3. Roundtrip serialization works via the Fory JS runtime
 */

import Fory, { Type } from '@apache-fory/core';
import {
  AddressBook,
  Person,
  Animal,
  AnimalCase,
  Dog,
  Cat,
  
  
  registerAddressbookTypes,
} from '../generated/addressbook';
import {
  AllOptionalTypes,
  registerOptionalTypesTypes,
} from '../generated/optional_types';
import { TreeNode } from '../generated/tree';
import {
  Envelope,
  
  Status,
  
  WrapperCase,
  registerAutoIdTypes,
} from '../generated/auto_id';

// ---------------------------------------------------------------------------
// Helper: build test objects that conform to generated interfaces
// ---------------------------------------------------------------------------

function builddog(): Dog {
  return { name: 'Rex', barkVolume: 5 };
}

function buildcat(): Cat {
  return { name: 'Mimi', lives: 9 };
}

function buildPersonPhoneNumber(num: string, pt: Person.PhoneType): Person.PhoneNumber {
  return { number_: num, phoneType: pt };
}

function buildperson(): Person {
  return {
    name: 'Alice',
    id: 123,
    email: 'alice@example.com',
    tags: ['friend', 'colleague'],
    scores: { math: 100, science: 98 },
    salary: 120000.5,
    phones: [
      buildPersonPhoneNumber('555-0100', Person.PhoneType.MOBILE),
      buildPersonPhoneNumber('555-0111', Person.PhoneType.WORK),
    ],
    pet: { case: AnimalCase.CAT, value: buildcat() },
  };
}

function buildaddressBook(): AddressBook {
  const person = buildperson();
  return {
    people: [person],
    peopleByName: { [person.name]: person },
  };
}

function buildtreeNode(): TreeNode {
  const child1: TreeNode = {
    id: 'child-1',
    name: 'Child 1',
    children: [],
    parent: undefined,
  };
  const child2: TreeNode = {
    id: 'child-2',
    name: 'Child 2',
    children: [],
    parent: undefined,
  };
  return {
    id: 'root',
    name: 'Root',
    children: [child1, child2],
    parent: undefined,
  };
}

function buildAutoIdenvelope(): Envelope {
  const payload: Envelope.Payload = { value: 42 };
  return {
    id: 'env-1',
    payload,
    detail: { case: Envelope.DetailCase.PAYLOAD, value: payload },
    status: Status.OK,
  };
}

// ---------------------------------------------------------------------------
// 1. Compilation & type-construction tests
//    (If these tests run at all, the generated types compile correctly.)
// ---------------------------------------------------------------------------

describe('Generated types compile and construct correctly', () => {
  test('addressBook type construction', () => {
    const book = buildaddressBook();
    expect(book.people).toHaveLength(1);
    expect(book.people[0].name).toBe('Alice');
    expect(book.people[0].id).toBe(123);
    expect(book.people[0].email).toBe('alice@example.com');
    expect(book.people[0].tags).toEqual(['friend', 'colleague']);
    expect(book.people[0].salary).toBe(120000.5);
    expect(book.people[0].phones).toHaveLength(2);
    expect(book.people[0].phones[0].phoneType).toBe(Person.PhoneType.MOBILE);
    expect(book.people[0].phones[1].phoneType).toBe(Person.PhoneType.WORK);
    expect(book.peopleByName['Alice']).toBe(book.people[0]);
  });

  test('Union (animal) type construction', () => {
    const doganimal: Animal = {
      case: AnimalCase.DOG,
      value: builddog(),
    };
    expect(doganimal.case).toBe(AnimalCase.DOG);
    expect((doganimal.value as Dog).name).toBe('Rex');

    const catanimal: Animal = {
      case: AnimalCase.CAT,
      value: buildcat(),
    };
    expect(catanimal.case).toBe(AnimalCase.CAT);
    expect((catanimal.value as Cat).lives).toBe(9);
  });

  test('Enum values are correct', () => {
    expect(Person.PhoneType.MOBILE).toBe(0);
    expect(Person.PhoneType.HOME).toBe(1);
    expect(Person.PhoneType.WORK).toBe(2);

    expect(AnimalCase.DOG).toBe(1);
    expect(AnimalCase.CAT).toBe(2);
  });

  test('treeNode type construction with optional parent', () => {
    const tree = buildtreeNode();
    expect(tree.id).toBe('root');
    expect(tree.children).toHaveLength(2);
    expect(tree.parent).toBeUndefined();
    expect(tree.children[0].name).toBe('Child 1');
  });

  test('AutoId types type construction', () => {
    const myEnvelope = buildAutoIdenvelope();
    expect(myEnvelope.id).toBe('env-1');
    expect(myEnvelope.payload?.value).toBe(42);
    expect(myEnvelope.status).toBe(Status.OK);

    expect(Status.UNKNOWN).toBe(0);
    expect(Status.OK).toBe(1);

    expect(WrapperCase.ENVELOPE).toBe(1);
    expect(WrapperCase.RAW).toBe(2);

    expect(Envelope.DetailCase.PAYLOAD).toBe(1);
    expect(Envelope.DetailCase.NOTE).toBe(2);
  });
});

// ---------------------------------------------------------------------------
// 2. Serialization roundtrip tests using the Fory JS runtime
//    We manually build TypeInfo objects matching the generated interfaces.
// ---------------------------------------------------------------------------

describe('Serialization roundtrip', () => {
  test('dog struct roundtrip', () => {
    const fory = new Fory();
    registerAddressbookTypes(fory, Type);
    const serializer = fory.typeResolver.getSerializerByTypeInfo(
      Type.struct(104),
    );

    const dog: Dog = builddog();
    const bytes = fory.serialize(dog, serializer);
    const result = fory.deserialize(bytes, serializer) as Dog;

    expect(result).toEqual(dog);
  });

  test('cat struct roundtrip', () => {
    const fory = new Fory();
    registerAddressbookTypes(fory, Type);
    const serializer = fory.typeResolver.getSerializerByTypeInfo(
      Type.struct(105),
    );

    const cat: Cat = buildcat();
    const bytes = fory.serialize(cat, serializer);
    const result = fory.deserialize(bytes, serializer) as Cat;

    expect(result).toEqual(cat);
  });

  test('person.phoneNumber struct roundtrip', () => {
    const fory = new Fory();
    registerAddressbookTypes(fory, Type);
    const serializer = fory.typeResolver.getSerializerByTypeInfo(
      Type.struct(102),
    );

    const phone: Person.PhoneNumber = buildPersonPhoneNumber('555-0100', Person.PhoneType.MOBILE);
    const bytes = fory.serialize(phone, serializer);
    const result = fory.deserialize(bytes, serializer) as Person.PhoneNumber;

    expect(result).toEqual(phone);
  });

  test('envelope.payload (autoId) struct roundtrip', () => {
    const fory = new Fory();
    registerAutoIdTypes(fory, Type);
    const serializer = fory.typeResolver.getSerializerByTypeInfo(
      Type.struct(2862577837),
    );

    const payload: Envelope.Payload = { value: 42 };
    const bytes = fory.serialize(payload, serializer);
    const result = fory.deserialize(bytes, serializer) as Envelope.Payload;

    expect(result).toEqual(payload);
  });
});

// ---------------------------------------------------------------------------
// 3. Optional field tests — use generated registration helpers, not manual
//    TypeInfo construction, so we validate the generated code end-to-end.
// ---------------------------------------------------------------------------

describe('Optional field handling', () => {
  test('allOptionalTypes roundtrip with present and absent optional fields', () => {
    const fory = new Fory();
    registerOptionalTypesTypes(fory, Type);
    const serializer = fory.typeResolver.getSerializerByTypeInfo(
      Type.struct(120),
    );

    // All optional fields set
    const full: AllOptionalTypes = {
      boolValue: true,
      int32Value: 42,
      stringValue: 'hello',
    };
    const bytes1 = fory.serialize(full, serializer);
    const result1 = fory.deserialize(bytes1, serializer) as AllOptionalTypes;
    expect(result1.boolValue).toBe(true);
    expect(result1.int32Value).toBe(42);
    expect(result1.stringValue).toBe('hello');

    // Optional fields absent (undefined)
    const sparse: AllOptionalTypes = {};
    const bytes2 = fory.serialize(sparse, serializer);
    const result2 = fory.deserialize(bytes2, serializer) as AllOptionalTypes;
    expect(result2.stringValue).toBeNull();
    expect(result2.int32Value).toBeNull();
  });
});
