/// <reference types="jest" />
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.
 */

// path: up from javascript/test to javascript/ root, then into packages/fory/index
import Fory, { TypeInfo, Type } from "../packages/fory/index";

/** @jest-environment node */

describe("fory xlang protocol", () => {
  test("should deserialize null work", () => {
    const fory = new Fory();
    expect(fory.deserialize(new Uint8Array([1]))).toBe(null);
  });

  test("should deserialize xlang disable work", () => {
    const fory = new Fory();
    try {
      fory.deserialize(new Uint8Array([0]));
      throw new Error("unreachable code");
    } catch (error: any) {
      expect(error.message).toBe("support crosslanguage mode only");
    }
  });

  test("can serialize and deserialize primitive types", () => {
    testTypeInfo(Type.int32(), 123);
    testTypeInfo(Type.bool(), true);
    testTypeInfo(Type.string(), "Apache Fury");
  });

  test("can serialize and deserialize Array<string> and Map<string,string>", () => {
    const arrayTypeInfo = Type.array(Type.string());
    testTypeInfo(arrayTypeInfo, ["a", "b", "c"]);

    const mapTypeInfo = Type.map(Type.string(), Type.string());

    /**
     * FIX: Changed plain object {} to native Map.
     * The library's MapAnySerializer requires .entries() and .size
     */
    const inputMap = new Map([
      ["foo", "bar"],
      ["baz", "qux"],
    ]);

    testTypeInfo(mapTypeInfo, inputMap);
  });

  function testTypeInfo(typeinfo: TypeInfo, input: any, expected?: any) {
    const fory = new Fory();
    const serializer = fory.registerSerializer(typeinfo);
    const result = serializer.deserialize(serializer.serialize(input));
    expect(result).toEqual(expected ?? input);
  }
});
