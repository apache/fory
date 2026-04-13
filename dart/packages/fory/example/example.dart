import 'package:fory/fory.dart';

part 'example.fory.dart';

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
  List<String> tags = <String>[];
}

void main() {
  final fory = Fory();
  ExampleFory.register(
    fory,
    Color,
    namespace: 'example',
    typeName: 'Color',
  );
  ExampleFory.register(
    fory,
    Person,
    namespace: 'example',
    typeName: 'Person',
  );

  final person = Person()
    ..name = 'Ada'
    ..age = Int32(36)
    ..favoriteColor = Color.blue
    ..tags = <String>['engineer', 'mathematician'];

  final bytes = fory.serialize(person);
  final roundTrip = fory.deserialize<Person>(bytes);

  print('${roundTrip.name} ${roundTrip.age} ${roundTrip.favoriteColor}');
  print(roundTrip.tags);
}
