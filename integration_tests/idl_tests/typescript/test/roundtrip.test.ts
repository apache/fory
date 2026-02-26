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

import Fory, { Type } from '@fory/fory';
import {
  AddressBook,
  Person,
  Animal,
  AnimalCase,
  Dog,
  Cat,
  PhoneNumber,
  PhoneType,
} from '../generated/addressbook';
import { TreeNode } from '../generated/tree';
import {
  Envelope,
  Payload,
  Status,
  DetailCase,
  WrapperCase,
} from '../generated/autoId';

// ---------------------------------------------------------------------------
// Helper: build test objects that conform to generated interfaces
// ---------------------------------------------------------------------------

function buildDog(): Dog {
  return { name: 'Rex', barkVolume: 5 };
}

function buildCat(): Cat {
  return { name: 'Mimi', lives: 9 };
}

function buildPhoneNumber(num: string, pt: PhoneType): PhoneNumber {
  return { number_: num, phoneType: pt };
}

function buildPerson(): Person {
  return {
    name: 'Alice',
    id: 123,
    email: 'alice@example.com',
    tags: ['friend', 'colleague'],
    scores: { math: 100, science: 98 },
    salary: 120000.5,
    phones: [
      buildPhoneNumber('555-0100', PhoneType.MOBILE),
      buildPhoneNumber('555-0111', PhoneType.WORK),
    ],
    pet: { case: AnimalCase.CAT, value: buildCat() },
  };
}

function buildAddressBook(): AddressBook {
  const person = buildPerson();
  return {
    people: [person],
    peopleByName: { [person.name]: person },
  };
}

function buildTreeNode(): TreeNode {
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

function buildAutoIdEnvelope(): Envelope {
  const payload: Payload = { value: 42 };
  return {
    id: 'env-1',
    payload,
    detail: { case: DetailCase.PAYLOAD, value: payload },
    status: Status.OK,
  };
}

// ---------------------------------------------------------------------------
// 1. Compilation & type-construction tests
//    (If these tests run at all, the generated types compile correctly.)
// ---------------------------------------------------------------------------

describe('Generated types compile and construct correctly', () => {
  test('AddressBook type construction', () => {
    const book = buildAddressBook();
    expect(book.people).toHaveLength(1);
    expect(book.people[0].name).toBe('Alice');
    expect(book.people[0].id).toBe(123);
    expect(book.people[0].email).toBe('alice@example.com');
    expect(book.people[0].tags).toEqual(['friend', 'colleague']);
    expect(book.people[0].salary).toBe(120000.5);
    expect(book.people[0].phones).toHaveLength(2);
    expect(book.people[0].phones[0].phoneType).toBe(PhoneType.MOBILE);
    expect(book.people[0].phones[1].phoneType).toBe(PhoneType.WORK);
    expect(book.peopleByName['Alice']).toBe(book.people[0]);
  });

  test('Union (Animal) type construction', () => {
    const dogAnimal: Animal = {
      case: AnimalCase.DOG,
      value: buildDog(),
    };
    expect(dogAnimal.case).toBe(AnimalCase.DOG);
    expect((dogAnimal.value as Dog).name).toBe('Rex');

    const catAnimal: Animal = {
      case: AnimalCase.CAT,
      value: buildCat(),
    };
    expect(catAnimal.case).toBe(AnimalCase.CAT);
    expect((catAnimal.value as Cat).lives).toBe(9);
  });

  test('Enum values are correct', () => {
    expect(PhoneType.MOBILE).toBe(0);
    expect(PhoneType.HOME).toBe(1);
    expect(PhoneType.WORK).toBe(2);

    expect(AnimalCase.DOG).toBe(1);
    expect(AnimalCase.CAT).toBe(2);
  });

  test('TreeNode type construction with optional parent', () => {
    const tree = buildTreeNode();
    expect(tree.id).toBe('root');
    expect(tree.children).toHaveLength(2);
    expect(tree.parent).toBeUndefined();
    expect(tree.children[0].name).toBe('Child 1');
  });

  test('AutoId types type construction', () => {
    const envelope = buildAutoIdEnvelope();
    expect(envelope.id).toBe('env-1');
    expect(envelope.payload?.value).toBe(42);
    expect(envelope.status).toBe(Status.OK);

    expect(Status.UNKNOWN).toBe(0);
    expect(Status.OK).toBe(1);

    expect(WrapperCase.ENVELOPE).toBe(1);
    expect(WrapperCase.RAW).toBe(2);

    expect(DetailCase.PAYLOAD).toBe(1);
    expect(DetailCase.NOTE).toBe(2);
  });
});

// ---------------------------------------------------------------------------
// 2. Serialization roundtrip tests using the Fory JS runtime
//    We manually build TypeInfo objects matching the generated interfaces.
// ---------------------------------------------------------------------------

describe('Serialization roundtrip', () => {
  test('Dog struct roundtrip', () => {
    const fory = new Fory();
    const dogType = Type.struct(104, {
      name: Type.string(),
      barkVolume: Type.int32(),
    });
    const { serialize, deserialize } = fory.registerSerializer(dogType);

    const dog: Dog = buildDog();
    const bytes = serialize(dog);
    const result = deserialize(bytes) as Dog;

    expect(result).toEqual(dog);
  });

  test('Cat struct roundtrip', () => {
    const fory = new Fory();
    const catType = Type.struct(105, {
      name: Type.string(),
      lives: Type.int32(),
    });
    const { serialize, deserialize } = fory.registerSerializer(catType);

    const cat: Cat = buildCat();
    const bytes = serialize(cat);
    const result = deserialize(bytes) as Cat;

    expect(result).toEqual(cat);
  });

  test('PhoneNumber struct roundtrip', () => {
    const fory = new Fory();
    const phoneType = Type.struct(102, {
      number_: Type.string(),
      phoneType: Type.int32(),
    });
    const { serialize, deserialize } = fory.registerSerializer(phoneType);

    const phone: PhoneNumber = buildPhoneNumber('555-0100', PhoneType.MOBILE);
    const bytes = serialize(phone);
    const result = deserialize(bytes) as PhoneNumber;

    expect(result).toEqual(phone);
  });

  test('Payload (autoId) struct roundtrip', () => {
    const fory = new Fory();
    const payloadType = Type.struct(2862577837, {
      value: Type.int32(),
    });
    const { serialize, deserialize } = fory.registerSerializer(payloadType);

    const payload: Payload = { value: 42 };
    const bytes = serialize(payload);
    const result = deserialize(bytes) as Payload;

    expect(result).toEqual(payload);
  });
});

// ---------------------------------------------------------------------------
// 3. Optional field tests
// ---------------------------------------------------------------------------

describe('Optional field handling', () => {
  test('struct with nullable string field', () => {
    const fory = new Fory();
    const optType = Type.struct(
      { typeName: 'test.OptionalStruct' },
      {
        name: Type.string(),
        nickname: Type.string().setNullable(true),
      },
    );
    const { serialize, deserialize } = fory.registerSerializer(optType);

    // With value present
    const withValue = { name: 'Alice', nickname: 'Ali' };
    const bytes1 = serialize(withValue);
    const result1 = deserialize(bytes1);
    expect(result1).toEqual(withValue);

    // With null value
    const withNull = { name: 'Bob', nickname: null };
    const bytes2 = serialize(withNull);
    const result2 = deserialize(bytes2);
    expect(result2).toEqual(withNull);
  });
});
