import 'package:fory/fory.dart';
import 'package:test/test.dart';

void main() {
  test('workspace root can use the rewritten fory package', () {
    final fory = Fory();
    final bytes = fory.serialize(Int32(42));
    final roundTrip = fory.deserialize<Int32>(bytes);
    expect(roundTrip, equals(Int32(42)));
  });
}
