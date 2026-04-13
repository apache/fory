library;

import 'package:fory/fory.dart';

part 'person.fory.dart';

enum Color {
  red,
  blue,
}

@ForyStruct()
class Person {
  Person();

  String name = '';
  Int32 age = Int32(0);
  Color favoriteColor = Color.red;
  List<String?> tags = <String?>[];
  Map<String, Int32> scores = <String, Int32>{};
}

@ForyStruct()
class RefNode {
  RefNode();

  String name = '';

  @ForyField(ref: true)
  RefNode? self;
}

@ForyStruct()
class EvolvingPayload {
  EvolvingPayload();

  String value = '';
}

@ForyStruct(evolving: false)
class FixedPayload {
  FixedPayload();

  String value = '';
}

@ForyStruct()
class PrivatePayload {
  PrivatePayload([this._secret = '']);

  String _secret;

  String get secret => _secret;

  void updateSecret(String value) {
    _secret = value;
  }
}

@ForyStruct()
class PrivateImmutablePayload {
  PrivateImmutablePayload(this._secret);

  final String _secret;

  String get secret => _secret;
}
