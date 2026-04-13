import 'package:fory/fory.dart';
import 'package:test/test.dart';

final class ManualValue {
  ManualValue(this.name, this.score);

  final String name;
  final int score;
}

final class ManualValueSerializer extends Serializer<ManualValue> {
  const ManualValueSerializer();

  @override
  void write(WriteContext context, ManualValue value) {
    final buffer = context.buffer;
    buffer.writeUtf8(value.name);
    buffer.writeInt64(value.score);
  }

  @override
  ManualValue read(ReadContext context) {
    final buffer = context.buffer;
    return ManualValue(buffer.readUtf8(), buffer.readInt64());
  }
}

final class RefPayload {
  RefPayload(this.name);

  final String name;
}

final class RefPayloadSerializer extends Serializer<RefPayload> {
  const RefPayloadSerializer();

  @override
  void write(WriteContext context, RefPayload value) {
    context.writeString(value.name);
  }

  @override
  RefPayload read(ReadContext context) {
    return RefPayload(context.readString());
  }
}

final class NonRefThenRefValue {
  NonRefThenRefValue(this.first, this.second);

  final RefPayload first;
  final RefPayload second;
}

final class NonRefThenRefValueSerializer
    extends Serializer<NonRefThenRefValue> {
  const NonRefThenRefValueSerializer();

  @override
  void write(WriteContext context, NonRefThenRefValue value) {
    context.writeNonRef(value.first);
    context.writeRef(value.second);
  }

  @override
  NonRefThenRefValue read(ReadContext context) {
    final first = context.readNonRef() as RefPayload;
    final second = context.readRef() as RefPayload;
    return NonRefThenRefValue(first, second);
  }
}

final class RefValueFlagValue {
  RefValueFlagValue(this.payload);

  final RefPayload payload;
}

final class RefValueFlagValueSerializer extends Serializer<RefValueFlagValue> {
  const RefValueFlagValueSerializer();

  @override
  void write(WriteContext context, RefValueFlagValue value) {
    if (context.writeRefValueFlag(value.payload)) {
      context.writeNonRef(value.payload);
    }
  }

  @override
  RefValueFlagValue read(ReadContext context) {
    return RefValueFlagValue(context.readRef() as RefPayload);
  }
}

void main() {
  test('registers custom serializer through public registerSerializer api', () {
    final fory = Fory();
    fory.registerSerializer(
      ManualValue,
      const ManualValueSerializer(),
      namespace: 'manual',
      typeName: 'ManualValue',
    );

    final value = ManualValue('alpha', 99);
    final bytes = fory.serialize(value);
    final roundTrip = fory.deserialize<ManualValue>(bytes);
    expect(roundTrip.name, equals('alpha'));
    expect(roundTrip.score, equals(99));
  });

  test('writeNonRef does not seed later back-references', () {
    final fory = Fory();
    fory.registerSerializer(
      RefPayload,
      const RefPayloadSerializer(),
      namespace: 'manual',
      typeName: 'RefPayload',
    );
    fory.registerSerializer(
      NonRefThenRefValue,
      const NonRefThenRefValueSerializer(),
      namespace: 'manual',
      typeName: 'NonRefThenRefValue',
    );

    final payload = RefPayload('shared');
    final roundTrip = fory.deserialize<NonRefThenRefValue>(
      fory.serialize(NonRefThenRefValue(payload, payload)),
    );

    expect(roundTrip.first.name, equals('shared'));
    expect(roundTrip.second.name, equals('shared'));
    expect(identical(roundTrip.first, roundTrip.second), isFalse);
  });

  test('writeRefValueFlag returns true only when payload bytes must follow',
      () {
    final fory = Fory();
    fory.registerSerializer(
      RefPayload,
      const RefPayloadSerializer(),
      namespace: 'manual',
      typeName: 'RefPayload',
    );
    fory.registerSerializer(
      RefValueFlagValue,
      const RefValueFlagValueSerializer(),
      namespace: 'manual',
      typeName: 'RefValueFlagValue',
    );

    final roundTrip = fory.deserialize<RefValueFlagValue>(
      fory.serialize(RefValueFlagValue(RefPayload('flagged'))),
    );

    expect(roundTrip.payload.name, equals('flagged'));
  });

  test('root trackRef supports repeated strings', () {
    final fory = Fory();
    final shared = String.fromCharCodes('shared'.codeUnits);
    final roundTrip = fory.deserialize<Object?>(
        fory.serialize(<Object?>[shared, shared], trackRef: true)) as List;

    expect(roundTrip[0], equals('shared'));
    expect(roundTrip[1], equals('shared'));
    expect(identical(roundTrip[0], roundTrip[1]), isTrue);
  });

  test('constructor forwards direct config parameters', () {
    final fory = Fory(
      compatible: true,
      maxDepth: 64,
      maxCollectionSize: 1024,
      maxBinarySize: 4096,
    );
    final bytes = fory.serialize(Int32(42));
    expect(fory.deserialize<Int32>(bytes), equals(Int32(42)));
  });
}
