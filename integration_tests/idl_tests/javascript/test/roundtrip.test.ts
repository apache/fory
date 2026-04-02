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
  addressBook,
  person,
  animal,
  animalCase,
  dog,
  cat,
  personPhoneNumber,
  personPhoneType,
  registerAddressbookTypes,
} from '../generated/addressbook';
import {
  allOptionalTypes,
  registerOptionalTypesTypes,
} from '../generated/optional_types';
import { treeNode } from '../generated/tree';
import {
  envelope,
  envelopePayload,
  status,
  envelopeDetailCase,
  wrapperCase,
  registerAutoIdTypes,
} from '../generated/auto_id';

// ---------------------------------------------------------------------------
// Helper: build test objects that conform to generated interfaces
// ---------------------------------------------------------------------------

function builddog(): dog {
  return { name: 'Rex', barkVolume: 5 };
}

function buildcat(): cat {
  return { name: 'Mimi', lives: 9 };
}

function buildpersonPhoneNumber(num: string, pt: personPhoneType): personPhoneNumber {
  return { number_: num, phoneType: pt };
}

function buildperson(): person {
  return {
    name: 'Alice',
    id: 123,
    email: 'alice@example.com',
    tags: ['friend', 'colleague'],
    scores: { math: 100, science: 98 },
    salary: 120000.5,
    phones: [
      buildpersonPhoneNumber('555-0100', personPhoneType.MOBILE),
      buildpersonPhoneNumber('555-0111', personPhoneType.WORK),
    ],
    pet: { case: animalCase.CAT, value: buildcat() },
  };
}

function buildaddressBook(): addressBook {
  const person = buildperson();
  return {
    people: [person],
    peopleByName: { [person.name]: person },
  };
}

function buildtreeNode(): treeNode {
  const child1: treeNode = {
    id: 'child-1',
    name: 'Child 1',
    children: [],
    parent: undefined,
  };
  const child2: treeNode = {
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

function buildAutoIdenvelope(): envelope {
  const payload: envelopePayload = { value: 42 };
  return {
    id: 'env-1',
    payload,
    detail: { case: envelopeDetailCase.PAYLOAD, value: payload },
    status: status.OK,
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
    expect(book.people[0].phones[0].phoneType).toBe(personPhoneType.MOBILE);
    expect(book.people[0].phones[1].phoneType).toBe(personPhoneType.WORK);
    expect(book.peopleByName['Alice']).toBe(book.people[0]);
  });

  test('Union (animal) type construction', () => {
    const doganimal: animal = {
      case: animalCase.DOG,
      value: builddog(),
    };
    expect(doganimal.case).toBe(animalCase.DOG);
    expect((doganimal.value as dog).name).toBe('Rex');

    const catanimal: animal = {
      case: animalCase.CAT,
      value: buildcat(),
    };
    expect(catanimal.case).toBe(animalCase.CAT);
    expect((catanimal.value as cat).lives).toBe(9);
  });

  test('Enum values are correct', () => {
    expect(personPhoneType.MOBILE).toBe(0);
    expect(personPhoneType.HOME).toBe(1);
    expect(personPhoneType.WORK).toBe(2);

    expect(animalCase.DOG).toBe(1);
    expect(animalCase.CAT).toBe(2);
  });

  test('treeNode type construction with optional parent', () => {
    const tree = buildtreeNode();
    expect(tree.id).toBe('root');
    expect(tree.children).toHaveLength(2);
    expect(tree.parent).toBeUndefined();
    expect(tree.children[0].name).toBe('Child 1');
  });

  test('AutoId types type construction', () => {
    const envelope = buildAutoIdenvelope();
    expect(envelope.id).toBe('env-1');
    expect(envelope.payload?.value).toBe(42);
    expect(envelope.status).toBe(status.OK);

    expect(status.UNKNOWN).toBe(0);
    expect(status.OK).toBe(1);

    expect(wrapperCase.ENVELOPE).toBe(1);
    expect(wrapperCase.RAW).toBe(2);

    expect(envelopeDetailCase.PAYLOAD).toBe(1);
    expect(envelopeDetailCase.NOTE).toBe(2);
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

    const dog: dog = builddog();
    const bytes = fory.serialize(dog, serializer);
    const result = fory.deserialize(bytes, serializer) as dog;

    expect(result).toEqual(dog);
  });

  test('cat struct roundtrip', () => {
    const fory = new Fory();
    registerAddressbookTypes(fory, Type);
    const serializer = fory.typeResolver.getSerializerByTypeInfo(
      Type.struct(105),
    );

    const cat: cat = buildcat();
    const bytes = fory.serialize(cat, serializer);
    const result = fory.deserialize(bytes, serializer) as cat;

    expect(result).toEqual(cat);
  });

  test('personPhoneNumber struct roundtrip', () => {
    const fory = new Fory();
    registerAddressbookTypes(fory, Type);
    const serializer = fory.typeResolver.getSerializerByTypeInfo(
      Type.struct(102),
    );

    const phone: personPhoneNumber = buildpersonPhoneNumber('555-0100', personPhoneType.MOBILE);
    const bytes = fory.serialize(phone, serializer);
    const result = fory.deserialize(bytes, serializer) as personPhoneNumber;

    expect(result).toEqual(phone);
  });

  test('envelopePayload (autoId) struct roundtrip', () => {
    const fory = new Fory();
    registerAutoIdTypes(fory, Type);
    const serializer = fory.typeResolver.getSerializerByTypeInfo(
      Type.struct(2862577837),
    );

    const envelopePayload: envelopePayload = { value: 42 };
    const bytes = fory.serialize(envelopePayload, serializer);
    const result = fory.deserialize(bytes, serializer) as envelopePayload;

    expect(result).toEqual(envelopePayload);
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
    const full: allOptionalTypes = {
      boolValue: true,
      int32Value: 42,
      stringValue: 'hello',
    };
    const bytes1 = fory.serialize(full, serializer);
    const result1 = fory.deserialize(bytes1, serializer) as allOptionalTypes;
    expect(result1.boolValue).toBe(true);
    expect(result1.int32Value).toBe(42);
    expect(result1.stringValue).toBe('hello');

    // Optional fields absent (undefined)
    const sparse: allOptionalTypes = {};
    const bytes2 = fory.serialize(sparse, serializer);
    const result2 = fory.deserialize(bytes2, serializer) as allOptionalTypes;
    expect(result2.stringValue).toBeNull();
    expect(result2.int32Value).toBeNull();
  });
});
