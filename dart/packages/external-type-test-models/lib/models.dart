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

library;

import 'dart:typed_data';

final class User {
  const User({required this.name, required this.age});

  final String name;
  final int age;
}

final class Point {
  const Point(this.x, this.y);

  final int x;
  final int y;
}

final class Money {
  const Money.fromParts({required this.currency, required this.units});

  final String currency;
  final int units;
}

final class MutableProfile {
  MutableProfile();

  String name = '';
  int score = 0;
}

final class NamedMutableProfile {
  NamedMutableProfile.empty();

  String name = '';
  int score = 0;
}

class ValueBase<T> {
  ValueBase({required this.value});

  T value;
}

final class DerivedValue extends ValueBase<String> {
  DerivedValue({required super.value, required this.count});

  int count;
}

final class Box<T> {
  const Box(this.value);

  final T value;
}

final class Node {
  Node.empty();

  String label = '';
  Node? next;
}

final class LinkA {
  LinkA();

  String label = '';
  LinkB? link;
}

final class LinkB {
  LinkB();

  String label = '';
  LinkA? link;
}

final class Envelope {
  Envelope();

  User? user;
  List<User> users = <User>[];
  Set<User> userSet = <User>{};
  Map<String, User> usersByName = <String, User>{};
  Map<User, String> namesByUser = <User, String>{};
  List<Map<String, List<User>>> nested = <Map<String, List<User>>>[];
  Object? value;
  List<Object?> values = <Object?>[];
}

final class ScalarValues {
  const ScalarValues({
    required this.flag,
    required this.int8,
    required this.int16,
    required this.int32,
    required this.int64,
    required this.uint8,
    required this.uint16,
    required this.uint32,
    required this.uint64,
    required this.float16,
    required this.bfloat16,
    required this.float32,
    required this.float64,
    required this.text,
    required this.timestamp,
    required this.duration,
    required this.binary,
  });

  final bool flag;
  final int int8;
  final int int16;
  final int int32;
  final int int64;
  final int uint8;
  final int uint16;
  final int uint32;
  final int uint64;
  final double float16;
  final double bfloat16;
  final double float32;
  final double float64;
  final String text;
  final DateTime timestamp;
  final Duration duration;
  final Uint8List binary;
}

final class ContactVersion1 {
  const ContactVersion1({required this.firstName, this.nickname});

  final String firstName;
  final String? nickname;
}

final class ContactVersion2 {
  const ContactVersion2({required this.fullName, this.nickname, this.email});

  final String? email;
  final String fullName;
  final String? nickname;
}

final class EquivalentUser {
  const EquivalentUser({required this.name, required this.age});

  final String name;
  final int age;
}
