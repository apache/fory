/// Marks a class for Fory struct code generation.
final class ForyStruct {
  /// Whether the generated struct should use evolving field metadata.
  ///
  /// Set this to `false` for a fixed schema when you do not need compatible
  /// field evolution.
  final bool evolving;

  /// Creates struct-level generation options.
  const ForyStruct({this.evolving = true});
}
