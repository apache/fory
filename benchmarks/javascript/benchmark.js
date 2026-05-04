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

const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const process = require("node:process");

const REPO_ROOT = path.resolve(__dirname, "..", "..");
const JS_ROOT = path.join(REPO_ROOT, "javascript");
const core = require(path.join(JS_ROOT, "packages", "core", "dist", "index.js"));
const protobuf = require(path.join(JS_ROOT, "node_modules", "protobufjs"));

const Fory = core.default;
const { Type } = core;

const DEFAULT_DURATION_SECONDS = 3;
const SERIALIZER_ORDER = ["fory", "protobuf", "json"];
const DATA_ORDER = [
  "struct",
  "sample",
  "mediacontent",
  "structlist",
  "samplelist",
  "mediacontentlist",
];
const LIST_SIZE = 5;
const PLAYER_ENUM = { JAVA: 0, FLASH: 1 };
const SIZE_ENUM = { SMALL: 0, LARGE: 1 };

let blackhole = 0;

function parseArgs(argv) {
  const options = {
    data: "",
    serializer: "",
    durationSeconds: DEFAULT_DURATION_SECONDS,
    output: path.join(__dirname, "benchmark_results.json"),
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    switch (arg) {
      case "--data":
        options.data = String(argv[++i] || "");
        break;
      case "--serializer":
        options.serializer = String(argv[++i] || "");
        break;
      case "--duration":
        options.durationSeconds = Number(argv[++i] || DEFAULT_DURATION_SECONDS);
        break;
      case "--output":
      case "--benchmark_out":
        options.output = path.resolve(String(argv[++i] || options.output));
        break;
      case "--help":
      case "-h":
        printUsage();
        process.exit(0);
        break;
      default:
        throw new Error(`Unknown option: ${arg}`);
    }
  }
  if (!Number.isFinite(options.durationSeconds) || options.durationSeconds <= 0) {
    throw new Error(`duration must be a positive number, got ${options.durationSeconds}`);
  }
  if (options.data && !DATA_ORDER.includes(options.data.toLowerCase())) {
    throw new Error(`Unknown data type: ${options.data}`);
  }
  if (options.serializer && !SERIALIZER_ORDER.includes(options.serializer.toLowerCase())) {
    throw new Error(`Unknown serializer: ${options.serializer}`);
  }
  options.data = options.data.toLowerCase();
  options.serializer = options.serializer.toLowerCase();
  return options;
}

function printUsage() {
  console.log(`Usage: node benchmark.js [OPTIONS]

Options:
  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>
                               Filter benchmark by data type
  --serializer <fory|protobuf|json>
                               Filter benchmark by serializer
  --duration <seconds>         Minimum time to run each benchmark
  --output <file>              Output JSON file
`);
}

function int32Field(id) {
  return Type.int32().setId(id);
}

function int64Field(id) {
  return Type.int64().setId(id);
}

function float32Field(id) {
  return Type.float32().setId(id);
}

function float64Field(id) {
  return Type.float64().setId(id);
}

function boolField(id) {
  return Type.bool().setId(id);
}

function stringField(id) {
  return Type.string().setId(id);
}

function listField(id, inner) {
  return Type.list(inner).setId(id);
}

function boolArrayField(id) {
  return Type.boolArray().setId(id);
}

function int32ArrayField(id) {
  return Type.int32Array().setId(id);
}

function int64ArrayField(id) {
  return Type.int64Array().setId(id);
}

function float32ArrayField(id) {
  return Type.float32Array().setId(id);
}

function float64ArrayField(id) {
  return Type.float64Array().setId(id);
}

function enumField(id, userTypeId, enumProps) {
  return Type.enum(userTypeId, enumProps).setId(id);
}

function structField(id, typeId) {
  return Type.struct(typeId).setId(id);
}

function createSchemas() {
  return {
    NumericStruct: Type.struct(1, {
      f1: int32Field(1),
      f2: int32Field(2),
      f3: int32Field(3),
      f4: int32Field(4),
      f5: int32Field(5),
      f6: int32Field(6),
      f7: int32Field(7),
      f8: int32Field(8),
    }),
    Sample: Type.struct(2, {
      int_value: int32Field(1),
      long_value: int64Field(2),
      float_value: float32Field(3),
      double_value: float64Field(4),
      short_value: int32Field(5),
      char_value: int32Field(6),
      boolean_value: boolField(7),
      int_value_boxed: int32Field(8),
      long_value_boxed: int64Field(9),
      float_value_boxed: float32Field(10),
      double_value_boxed: float64Field(11),
      short_value_boxed: int32Field(12),
      char_value_boxed: int32Field(13),
      boolean_value_boxed: boolField(14),
      int_array: int32ArrayField(15),
      long_array: int64ArrayField(16),
      float_array: float32ArrayField(17),
      double_array: float64ArrayField(18),
      short_array: int32ArrayField(19),
      char_array: int32ArrayField(20),
      boolean_array: boolArrayField(21),
      string: stringField(22),
    }),
    Media: Type.struct(3, {
      uri: stringField(1),
      title: stringField(2),
      width: int32Field(3),
      height: int32Field(4),
      format: stringField(5),
      duration: int64Field(6),
      size: int64Field(7),
      bitrate: int32Field(8),
      has_bitrate: boolField(9),
      persons: listField(10, Type.string()),
      player: enumField(11, 101, PLAYER_ENUM),
      copyright: stringField(12),
    }),
    Image: Type.struct(4, {
      uri: stringField(1),
      title: stringField(2),
      width: int32Field(3),
      height: int32Field(4),
      size: enumField(5, 102, SIZE_ENUM),
    }),
    MediaContent: Type.struct(5, {
      media: structField(1, 3),
      images: listField(2, Type.struct(4)),
    }),
    StructList: Type.struct(6, {
      struct_list: listField(1, Type.struct(1)),
    }),
    SampleList: Type.struct(7, {
      sample_list: listField(1, Type.struct(2)),
    }),
    MediaContentList: Type.struct(8, {
      media_content_list: listField(1, Type.struct(5)),
    }),
  };
}

function createNumericStruct() {
  return {
    f1: -12345,
    f2: 987654321,
    f3: -31415,
    f4: 27182818,
    f5: -32000,
    f6: 1000000,
    f7: -999999999,
    f8: 42,
  };
}

function createSample() {
  return {
    int_value: 123,
    long_value: 1230000,
    float_value: 12.345,
    double_value: 1.234567,
    short_value: 12345,
    char_value: "!".charCodeAt(0),
    boolean_value: true,
    int_value_boxed: 321,
    long_value_boxed: 3210000,
    float_value_boxed: 54.321,
    double_value_boxed: 7.654321,
    short_value_boxed: 32100,
    char_value_boxed: "$".charCodeAt(0),
    boolean_value_boxed: false,
    int_array: [-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
    long_array: [-123400, -12300, -1200, -100, 0, 100, 1200, 12300, 123400],
    float_array: [-12.34, -12.3, -12.0, -1.0, 0.0, 1.0, 12.0, 12.3, 12.34],
    double_array: [-1.234, -1.23, -12.0, -1.0, 0.0, 1.0, 12.0, 1.23, 1.234],
    short_array: [-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
    char_array: Array.from("asdfASDF", (char) => char.charCodeAt(0)),
    boolean_array: [true, false, false, true],
    string: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
  };
}

function createMediaContent() {
  return {
    media: {
      uri: "http://javaone.com/keynote.ogg",
      title: "",
      width: 641,
      height: 481,
      format: "video/theora\u1234",
      duration: 18000001,
      size: 58982401,
      bitrate: 0,
      has_bitrate: false,
      persons: ["Bill Gates, Jr.", "Steven Jobs"],
      player: 1,
      copyright: "Copyright (c) 2009, Scooby Dooby Doo",
    },
    images: [
      {
        uri: "http://javaone.com/keynote_huge.jpg",
        title: "Javaone Keynote\u1234",
        width: 32000,
        height: 24000,
        size: 1,
      },
      {
        uri: "http://javaone.com/keynote_large.jpg",
        title: "",
        width: 1024,
        height: 768,
        size: 1,
      },
      {
        uri: "http://javaone.com/keynote_small.jpg",
        title: "",
        width: 320,
        height: 240,
        size: 0,
      },
    ],
  };
}

function repeat(factory) {
  return Array.from({ length: LIST_SIZE }, () => factory());
}

function createStructList() {
  return {
    struct_list: repeat(createNumericStruct),
  };
}

function createSampleList() {
  return {
    sample_list: repeat(createSample),
  };
}

function createMediaContentList() {
  return {
    media_content_list: repeat(createMediaContent),
  };
}

function toProtoStruct(value) {
  return { ...value };
}

function fromProtoStruct(value) {
  return {
    f1: value.f1,
    f2: value.f2,
    f3: value.f3,
    f4: value.f4,
    f5: value.f5,
    f6: value.f6,
    f7: value.f7,
    f8: value.f8,
  };
}

function toProtoSample(value) {
  return {
    intValue: value.int_value,
    longValue: value.long_value,
    floatValue: value.float_value,
    doubleValue: value.double_value,
    shortValue: value.short_value,
    charValue: value.char_value,
    booleanValue: value.boolean_value,
    intValueBoxed: value.int_value_boxed,
    longValueBoxed: value.long_value_boxed,
    floatValueBoxed: value.float_value_boxed,
    doubleValueBoxed: value.double_value_boxed,
    shortValueBoxed: value.short_value_boxed,
    charValueBoxed: value.char_value_boxed,
    booleanValueBoxed: value.boolean_value_boxed,
    intArray: value.int_array,
    longArray: value.long_array,
    floatArray: value.float_array,
    doubleArray: value.double_array,
    shortArray: value.short_array,
    charArray: value.char_array,
    booleanArray: value.boolean_array,
    string: value.string,
  };
}

function fromProtoSample(value) {
  return {
    int_value: value.intValue,
    long_value: value.longValue,
    float_value: value.floatValue,
    double_value: value.doubleValue,
    short_value: value.shortValue,
    char_value: value.charValue,
    boolean_value: value.booleanValue,
    int_value_boxed: value.intValueBoxed,
    long_value_boxed: value.longValueBoxed,
    float_value_boxed: value.floatValueBoxed,
    double_value_boxed: value.doubleValueBoxed,
    short_value_boxed: value.shortValueBoxed,
    char_value_boxed: value.charValueBoxed,
    boolean_value_boxed: value.booleanValueBoxed,
    int_array: value.intArray,
    long_array: value.longArray,
    float_array: value.floatArray,
    double_array: value.doubleArray,
    short_array: value.shortArray,
    char_array: value.charArray,
    boolean_array: value.booleanArray,
    string: value.string,
  };
}

function toProtoImage(value) {
  return {
    uri: value.uri,
    width: value.width,
    height: value.height,
    size: value.size,
    ...(value.title ? { title: value.title } : {}),
  };
}

function fromProtoImage(value) {
  return {
    uri: value.uri,
    title: value.title || "",
    width: value.width,
    height: value.height,
    size: value.size,
  };
}

function toProtoMedia(value) {
  return {
    uri: value.uri,
    width: value.width,
    height: value.height,
    format: value.format,
    duration: value.duration,
    size: value.size,
    bitrate: value.bitrate,
    hasBitrate: value.has_bitrate,
    persons: value.persons,
    player: value.player,
    copyright: value.copyright,
    ...(value.title ? { title: value.title } : {}),
  };
}

function fromProtoMedia(value) {
  return {
    uri: value.uri,
    title: value.title || "",
    width: value.width,
    height: value.height,
    format: value.format,
    duration: value.duration,
    size: value.size,
    bitrate: value.bitrate,
    has_bitrate: value.hasBitrate,
    persons: value.persons || [],
    player: value.player,
    copyright: value.copyright,
  };
}

function toProtoMediaContent(value) {
  return {
    media: toProtoMedia(value.media),
    images: value.images.map(toProtoImage),
  };
}

function fromProtoMediaContent(value) {
  return {
    media: fromProtoMedia(value.media),
    images: (value.images || []).map(fromProtoImage),
  };
}

function toProtoStructList(value) {
  return {
    structList: value.struct_list.map(toProtoStruct),
  };
}

function fromProtoStructList(value) {
  return {
    struct_list: (value.structList || []).map(fromProtoStruct),
  };
}

function toProtoSampleList(value) {
  return {
    sampleList: value.sample_list.map(toProtoSample),
  };
}

function fromProtoSampleList(value) {
  return {
    sample_list: (value.sampleList || []).map(fromProtoSample),
  };
}

function toProtoMediaContentList(value) {
  return {
    mediaContentList: value.media_content_list.map(toProtoMediaContent),
  };
}

function fromProtoMediaContentList(value) {
  return {
    media_content_list: (value.mediaContentList || []).map(fromProtoMediaContent),
  };
}

function createForyBenchmarks() {
  const fory = new Fory({
    compatible: true,
    ref: false,
  });
  const schemas = createSchemas();
  const serializers = {
    struct: fory.register(schemas.NumericStruct),
    sample: fory.register(schemas.Sample),
    media: fory.register(schemas.Media),
    image: fory.register(schemas.Image),
    mediacontent: fory.register(schemas.MediaContent),
    structlist: fory.register(schemas.StructList),
    samplelist: fory.register(schemas.SampleList),
    mediacontentlist: fory.register(schemas.MediaContentList),
  };
  return { fory, serializers };
}

function createDatasets(root) {
  const StructType = root.lookupType("protobuf.Struct");
  const SampleType = root.lookupType("protobuf.Sample");
  const MediaContentType = root.lookupType("protobuf.MediaContent");
  const StructListType = root.lookupType("protobuf.StructList");
  const SampleListType = root.lookupType("protobuf.SampleList");
  const MediaContentListType = root.lookupType("protobuf.MediaContentList");

  const { serializers } = createForyBenchmarks();

  return [
    {
      key: "struct",
      label: "Struct",
      createValue: createNumericStruct,
      toProto: toProtoStruct,
      fromProto: fromProtoStruct,
      protoType: StructType,
      forySerializer: serializers.struct,
      sizeKey: "struct",
    },
    {
      key: "sample",
      label: "Sample",
      createValue: createSample,
      toProto: toProtoSample,
      fromProto: fromProtoSample,
      protoType: SampleType,
      forySerializer: serializers.sample,
      sizeKey: "sample",
    },
    {
      key: "mediacontent",
      label: "MediaContent",
      createValue: createMediaContent,
      toProto: toProtoMediaContent,
      fromProto: fromProtoMediaContent,
      protoType: MediaContentType,
      forySerializer: serializers.mediacontent,
      sizeKey: "media",
    },
    {
      key: "structlist",
      label: "StructList",
      createValue: createStructList,
      toProto: toProtoStructList,
      fromProto: fromProtoStructList,
      protoType: StructListType,
      forySerializer: serializers.structlist,
      sizeKey: "struct_list",
    },
    {
      key: "samplelist",
      label: "SampleList",
      createValue: createSampleList,
      toProto: toProtoSampleList,
      fromProto: fromProtoSampleList,
      protoType: SampleListType,
      forySerializer: serializers.samplelist,
      sizeKey: "sample_list",
    },
    {
      key: "mediacontentlist",
      label: "MediaContentList",
      createValue: createMediaContentList,
      toProto: toProtoMediaContentList,
      fromProto: fromProtoMediaContentList,
      protoType: MediaContentListType,
      forySerializer: serializers.mediacontentlist,
      sizeKey: "media_list",
    },
  ];
}

function decodeProtoObject(protoType, bytes) {
  const message = protoType.decode(bytes);
  return protoType.toObject(message, {
    longs: Number,
    enums: Number,
    defaults: true,
  });
}

function toFloat32(value) {
  return new Float32Array([value])[0];
}

function normalizeForyValue(datasetKey, value) {
  switch (datasetKey) {
    case "sample":
      return {
        ...value,
        long_value: BigInt(value.long_value),
        long_value_boxed: BigInt(value.long_value_boxed),
        float_value: toFloat32(value.float_value),
        float_value_boxed: toFloat32(value.float_value_boxed),
        int_array: Int32Array.from(value.int_array),
        long_array: BigInt64Array.from(value.long_array, (item) => BigInt(item)),
        float_array: Float32Array.from(value.float_array, toFloat32),
        double_array: Float64Array.from(value.double_array),
        short_array: Int32Array.from(value.short_array),
        char_array: Int32Array.from(value.char_array),
      };
    case "mediacontent":
      return {
        media: {
          ...value.media,
          duration: BigInt(value.media.duration),
          size: BigInt(value.media.size),
        },
        images: value.images.map((image) => ({ ...image })),
      };
    case "structlist":
      return {
        struct_list: value.struct_list.map((item) => ({ ...item })),
      };
    case "samplelist":
      return {
        sample_list: value.sample_list.map((item) => normalizeForyValue("sample", item)),
      };
    case "mediacontentlist":
      return {
        media_content_list: value.media_content_list.map((item) =>
          normalizeForyValue("mediacontent", item)
        ),
      };
    default:
      return value;
  }
}

function normalizeProtobufValue(datasetKey, value) {
  switch (datasetKey) {
    case "sample":
      return {
        ...value,
        float_value: toFloat32(value.float_value),
        float_value_boxed: toFloat32(value.float_value_boxed),
        float_array: value.float_array.map(toFloat32),
      };
    case "samplelist":
      return {
        sample_list: value.sample_list.map((item) => normalizeProtobufValue("sample", item)),
      };
    default:
      return value;
  }
}

function ensureSerializationWorks(dataset) {
  const value = dataset.createValue();
  const foryValue = normalizeForyValue(dataset.key, value);
  const foryBytes = dataset.forySerializer.serialize(foryValue);
  const foryRoundTrip = dataset.forySerializer.deserialize(foryBytes);
  assert.deepStrictEqual(foryRoundTrip, foryValue);

  const protoPayload = dataset.toProto(value);
  const protoBytes = dataset.protoType.encode(dataset.protoType.create(protoPayload)).finish();
  const protoRoundTrip = dataset.fromProto(decodeProtoObject(dataset.protoType, protoBytes));
  assert.deepStrictEqual(protoRoundTrip, normalizeProtobufValue(dataset.key, value));

  const jsonBytes = Buffer.from(JSON.stringify(value), "utf8");
  const jsonRoundTrip = JSON.parse(jsonBytes.toString("utf8"));
  assert.deepStrictEqual(jsonRoundTrip, value);
}

function serializeBytes(serializerName, dataset, value) {
  switch (serializerName) {
    case "fory":
      return dataset.forySerializer.serialize(normalizeForyValue(dataset.key, value));
    case "protobuf":
      return dataset.protoType.encode(dataset.protoType.create(dataset.toProto(value))).finish();
    case "json":
      return Buffer.from(JSON.stringify(value), "utf8");
    default:
      throw new Error(`Unknown serializer ${serializerName}`);
  }
}

function createBenchmarkCase(serializerName, dataset, operation) {
  const value = dataset.createValue();

  if (serializerName === "fory") {
    const foryValue = normalizeForyValue(dataset.key, value);
    if (operation === "Serialize") {
      return () => {
        const bytes = dataset.forySerializer.serialize(foryValue);
        blackhole ^= bytes.length;
      };
    }
    const bytes = dataset.forySerializer.serialize(foryValue);
    return () => {
      const decoded = dataset.forySerializer.deserialize(bytes);
      blackhole ^= Array.isArray(decoded) ? decoded.length : 1;
    };
  }

  if (serializerName === "protobuf") {
    if (operation === "Serialize") {
      return () => {
        const bytes = dataset.protoType.encode(dataset.protoType.create(dataset.toProto(value))).finish();
        blackhole ^= bytes.length;
      };
    }
    const bytes = dataset.protoType.encode(dataset.protoType.create(dataset.toProto(value))).finish();
    return () => {
      const decoded = dataset.fromProto(decodeProtoObject(dataset.protoType, bytes));
      blackhole ^= Array.isArray(decoded) ? decoded.length : 1;
    };
  }

  if (serializerName === "json") {
    if (operation === "Serialize") {
      return () => {
        const json = JSON.stringify(value);
        blackhole ^= json.length;
      };
    }
    const json = JSON.stringify(value);
    return () => {
      const decoded = JSON.parse(json);
      blackhole ^= Array.isArray(decoded) ? decoded.length : 1;
    };
  }

  throw new Error(`Unknown serializer ${serializerName}`);
}

function measureBatch(fn, batchSize) {
  const start = process.hrtime.bigint();
  for (let i = 0; i < batchSize; i += 1) {
    fn();
  }
  return process.hrtime.bigint() - start;
}

function benchmark(fn, minDurationSeconds) {
  fn();
  let batchSize = 1;
  while (batchSize < 1_000_000) {
    const elapsed = measureBatch(fn, batchSize);
    if (elapsed >= 10_000_000n) {
      break;
    }
    batchSize *= 2;
  }

  const targetNs = BigInt(Math.floor(minDurationSeconds * 1e9));
  let totalElapsed = 0n;
  let totalIterations = 0;

  while (totalElapsed < targetNs) {
    const elapsed = measureBatch(fn, batchSize);
    totalElapsed += elapsed;
    totalIterations += batchSize;
  }

  return Number(totalElapsed) / totalIterations;
}

function buildResults(datasets, options) {
  const benchmarks = [];

  for (const dataset of datasets) {
    if (options.data && options.data !== dataset.key) {
      continue;
    }
    for (const serializerName of SERIALIZER_ORDER) {
      if (options.serializer && options.serializer !== serializerName) {
        continue;
      }
      for (const operation of ["Serialize", "Deserialize"]) {
        const benchName = `BM_${serializerName[0].toUpperCase()}${serializerName.slice(1)}_${dataset.label}_${operation}`;
        const fn = createBenchmarkCase(serializerName, dataset, operation);
        const realTimeNs = benchmark(fn, options.durationSeconds);
        benchmarks.push({
          name: benchName,
          real_time: realTimeNs,
          cpu_time: realTimeNs,
          time_unit: "ns",
        });
        console.log(`${benchName}: ${realTimeNs.toFixed(1)} ns/op`);
      }
    }
  }

  const sizeCounters = {
    name: "BM_PrintSerializedSizes",
  };
  for (const dataset of datasets) {
    const value = dataset.createValue();
    for (const serializerName of SERIALIZER_ORDER) {
      const bytes = serializeBytes(serializerName, dataset, value);
      sizeCounters[`${serializerName}_${dataset.sizeKey}_size`] = bytes.length;
    }
  }
  benchmarks.push(sizeCounters);
  return benchmarks;
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const root = protobuf.loadSync(path.join(REPO_ROOT, "benchmarks", "proto", "bench.proto"));
  const datasets = createDatasets(root);
  datasets.forEach(ensureSerializationWorks);

  const structSize = serializeBytes("fory", datasets.find((item) => item.key === "struct"), createNumericStruct()).length;
  console.log(`Fory Struct serialized size: ${structSize} bytes`);

  const result = {
    context: {
      date: new Date().toISOString(),
      host_name: os.hostname(),
      executable: process.execPath,
      num_cpus: os.cpus().length,
      node_version: process.version,
      v8_version: process.versions.v8,
      duration_seconds: options.durationSeconds,
    },
    benchmarks: buildResults(datasets, options),
  };

  fs.writeFileSync(options.output, JSON.stringify(result, null, 2));
  console.log(`Saved benchmark results to ${options.output}`);
  if (blackhole === Number.MIN_SAFE_INTEGER) {
    console.log("unreachable blackhole guard");
  }
}

main();
