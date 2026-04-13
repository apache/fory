/// Field-level code generation options for [ForyStruct].
final class ForyField {
  /// Skips this field entirely in generated serializers.
  final bool skip;

  /// Stable field identifier used by compatible structs.
  ///
  /// When omitted, the generator derives an identifier from the field name.
  final int? id;

  /// Overrides the generator's inferred nullability.
  ///
  /// `null` means "use the Dart type as written".
  final bool? nullable;

  /// Enables reference tracking for this field.
  ///
  /// Basic scalar types never track references even if this flag is `true`.
  final bool ref;

  /// Controls whether generated code writes runtime type metadata for this
  /// field.
  ///
  /// `null` means "auto", `false` means "use the declared type", and `true`
  /// means "write runtime type information".
  final bool? dynamic;

  /// Creates field-level generation overrides.
  const ForyField({
    this.skip = false,
    this.id,
    this.nullable,
    this.ref = false,
    this.dynamic,
  });
}
