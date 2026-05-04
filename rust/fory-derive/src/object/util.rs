// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

use super::field_meta::IntEncoding;
use fory_core::type_id::TypeId;
use fory_core::util::to_snake_case;
use proc_macro2::TokenStream;
use quote::{quote, ToTokens};
use std::cell::RefCell;
use syn::{Field, GenericArgument, Index, PathArguments, Type};

/// Get field name for a field, handling both named and tuple struct fields.
/// For named fields, returns the field name.
/// For tuple struct fields, returns the index as a string (e.g., "0", "1").
pub(super) fn get_field_name(field: &Field, index: usize) -> String {
    match &field.ident {
        Some(ident) => ident.to_string(),
        None => index.to_string(),
    }
}

/// Get the field accessor token for a field.
/// For named fields: `self.field_name`
/// For tuple struct fields: `self.0`, `self.1`, etc.
pub(super) fn get_field_accessor(field: &Field, index: usize, use_self: bool) -> TokenStream {
    let prefix = if use_self {
        quote! { self. }
    } else {
        quote! {}
    };

    match &field.ident {
        Some(ident) => quote! { #prefix #ident },
        None => {
            let idx = Index::from(index);
            quote! { #prefix #idx }
        }
    }
}

/// Check if this is a tuple struct (all fields are unnamed)
pub fn is_tuple_struct(fields: &[&Field]) -> bool {
    !fields.is_empty() && fields[0].ident.is_none()
}

thread_local! {
    static MACRO_CONTEXT: RefCell<Option<MacroContext>> = const {RefCell::new(None)};
}

#[derive(Clone)]
struct MacroContext {
    struct_name: String,
    debug_enabled: bool,
}

/// Set the macro context with struct name and debug flag.
pub(super) fn set_struct_context(name: &str, debug_enabled: bool) {
    MACRO_CONTEXT.with(|ctx| {
        *ctx.borrow_mut() = Some(MacroContext {
            struct_name: name.to_string(),
            debug_enabled,
        });
    });
}

pub(super) fn clear_struct_context() {
    MACRO_CONTEXT.with(|ctx| {
        *ctx.borrow_mut() = None;
    });
}

pub(super) fn get_struct_name() -> Option<String> {
    MACRO_CONTEXT.with(|ctx| ctx.borrow().as_ref().map(|c| c.struct_name.clone()))
}

pub(super) fn is_debug_enabled() -> bool {
    MACRO_CONTEXT.with(|ctx| {
        ctx.borrow()
            .as_ref()
            .map(|c| c.debug_enabled)
            .unwrap_or(false)
    })
}

fn is_forward_field(ty: &Type) -> bool {
    let struct_name = match get_struct_name() {
        Some(name) => name,
        None => return false,
    };
    is_forward_field_internal(ty, &struct_name)
}

fn is_forward_field_internal(ty: &Type, struct_name: &str) -> bool {
    match ty {
        Type::TraitObject(_) => true,

        Type::Path(type_path) => {
            if let Some(seg) = type_path.path.segments.last() {
                // Direct match: type is the struct itself
                if seg.ident == struct_name {
                    return true;
                }

                // Special cases for weak pointers
                if seg.ident == "RcWeak" || seg.ident == "ArcWeak" {
                    return true;
                }

                // Check smart pointers: Rc<T> / Arc<T>
                // Only return true if:
                // 1. Inner type is Rc<dyn Any> (polymorphic)
                // 2. Inner type references the containing struct (forward reference)
                if seg.ident == "Rc" || seg.ident == "Arc" {
                    if let PathArguments::AngleBracketed(args) = &seg.arguments {
                        if let Some(GenericArgument::Type(inner_ty)) = args.args.first() {
                            match inner_ty {
                                // Inner type is trait object
                                Type::TraitObject(trait_obj) => {
                                    if trait_obj
                                        .bounds
                                        .iter()
                                        .any(|b| b.to_token_stream().to_string() == "Any")
                                    {
                                        // Rc<dyn Any> → return true
                                        return true;
                                    } else {
                                        // Rc<dyn SomethingElse> → return false
                                        return false;
                                    }
                                }
                                // Inner type is not a trait object - recursively check
                                // if it references the containing struct
                                _ => {
                                    return is_forward_field_internal(inner_ty, struct_name);
                                }
                            }
                        }
                    }
                }

                // Recursively check other generic args
                if let PathArguments::AngleBracketed(args) = &seg.arguments {
                    for arg in &args.args {
                        if let GenericArgument::Type(inner_ty) = arg {
                            if is_forward_field_internal(inner_ty, struct_name) {
                                return true;
                            }
                        }
                    }
                }
            }

            false
        }

        _ => false,
    }
}

#[derive(Clone)]
struct FieldSortKey {
    id: Option<i32>,
    text: String,
}

impl FieldSortKey {
    fn id(id: i32) -> Self {
        Self {
            id: Some(id),
            text: id.to_string(),
        }
    }

    fn name(name: String) -> Self {
        Self {
            id: None,
            text: name,
        }
    }
}

fn compare_field_sort_key(a: &FieldSortKey, b: &FieldSortKey) -> std::cmp::Ordering {
    match (a.id, b.id) {
        (Some(id_a), Some(id_b)) => id_a.cmp(&id_b),
        _ => a.text.cmp(&b.text),
    }
}

type FieldGroup = Vec<(String, FieldSortKey, u32)>;
type FieldGroups = (
    FieldGroup,
    FieldGroup,
    FieldGroup,
    FieldGroup,
    FieldGroup,
    FieldGroup,
    FieldGroup,
);

fn extract_option_inner(s: &str) -> Option<&str> {
    s.strip_prefix("Option<")?.strip_suffix(">")
}

const PRIMITIVE_TYPE_NAMES: [&str; 17] = [
    "bool", "i8", "i16", "i32", "i64", "i128", "float16", "Float16", "bfloat16", "BFloat16", "f32",
    "f64", "u8", "u16", "u32", "u64", "u128",
];

fn get_primitive_type_id(ty: &str) -> u32 {
    match ty {
        "bool" => TypeId::BOOL as u32,
        "i8" => TypeId::INT8 as u32,
        "i16" => TypeId::INT16 as u32,
        // Use VARINT32 for i32 to match Java xlang mode and Rust type resolver registration
        "i32" => TypeId::VARINT32 as u32,
        // Use VARINT64 for i64 to match Java xlang mode and Rust type resolver registration
        "i64" => TypeId::VARINT64 as u32,
        "float16" | "Float16" => TypeId::FLOAT16 as u32,
        "bfloat16" | "BFloat16" => TypeId::BFLOAT16 as u32,
        "f32" => TypeId::FLOAT32 as u32,
        "f64" => TypeId::FLOAT64 as u32,
        "u8" => TypeId::UINT8 as u32,
        "u16" => TypeId::UINT16 as u32,
        // Use VAR_UINT32 for u32 to match Rust type resolver registration
        "u32" => TypeId::VAR_UINT32 as u32,
        // Use VAR_UINT64 for u64 to match Rust type resolver registration
        "u64" => TypeId::VAR_UINT64 as u32,
        "u128" => TypeId::U128 as u32,
        "i128" => TypeId::INT128 as u32,
        _ => unreachable!("Unknown primitive type: {}", ty),
    }
}

pub(crate) fn get_type_id_by_type_ast(ty: &Type) -> u32 {
    let ty_str: String = ty
        .to_token_stream()
        .to_string()
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>();
    get_type_id_by_name(&ty_str)
}

/// Get the type ID for a given type string.
///
/// Returns:
/// - `type_id` for known types (primitives, internal types, collections)
/// - `UNKNOWN` for unknown/user-defined types
pub(crate) fn get_type_id_by_name(ty: &str) -> u32 {
    let ty = extract_option_inner(ty).unwrap_or(ty);
    let unqualified_ty = ty.rsplit("::").next().unwrap_or(ty);
    // Check primitive types
    if PRIMITIVE_TYPE_NAMES.contains(&ty) {
        return get_primitive_type_id(ty);
    }
    if PRIMITIVE_TYPE_NAMES.contains(&unqualified_ty) {
        return get_primitive_type_id(unqualified_ty);
    }

    // Check internal types
    match unqualified_ty {
        "String" => return TypeId::STRING as u32,
        "NaiveDate" => return TypeId::DATE as u32,
        "NaiveDateTime" => return TypeId::TIMESTAMP as u32,
        "Duration" => return TypeId::DURATION as u32,
        "Decimal" => return TypeId::DECIMAL as u32,
        "bytes" => return TypeId::BINARY as u32,
        _ => {}
    }

    // Check primitive arrays (fixed-size arrays [T; N])
    // These will be serialized similarly to Vec but with fixed size
    if ty.starts_with('[') && ty.contains(';') {
        // Extract the element type from [T; N]
        if let Some(elem_ty) = ty.strip_prefix('[').and_then(|s| s.split(';').next()) {
            match elem_ty {
                "bool" => return TypeId::BOOL_ARRAY as u32,
                "i8" => return TypeId::INT8_ARRAY as u32,
                "i16" => return TypeId::INT16_ARRAY as u32,
                "i32" => return TypeId::INT32_ARRAY as u32,
                "i64" => return TypeId::INT64_ARRAY as u32,
                "i128" => return TypeId::INT128_ARRAY as u32,
                "float16" | "Float16" => return TypeId::FLOAT16_ARRAY as u32,
                "bfloat16" | "BFloat16" => return TypeId::BFLOAT16_ARRAY as u32,
                "f32" => return TypeId::FLOAT32_ARRAY as u32,
                "f64" => return TypeId::FLOAT64_ARRAY as u32,
                "u16" => return TypeId::UINT16_ARRAY as u32,
                "u32" => return TypeId::UINT32_ARRAY as u32,
                "u64" => return TypeId::UINT64_ARRAY as u32,
                "u128" => return TypeId::U128_ARRAY as u32,
                _ => {
                    // Non-primitive array elements, treat as LIST
                    return TypeId::LIST as u32;
                }
            }
        }
    }

    // Check collection types
    if ty.starts_with("Vec<") || ty.starts_with("VecDeque<") || ty.starts_with("LinkedList<") {
        return TypeId::LIST as u32;
    }

    if ty.starts_with("HashSet<") || ty.starts_with("BTreeSet<") || ty.starts_with("BinaryHeap<") {
        return TypeId::SET as u32;
    }

    if ty.starts_with("HashMap<") || ty.starts_with("BTreeMap<") {
        return TypeId::MAP as u32;
    }

    // Check tuple types (represented as "Tuple" by extract_type_name or starts with '(')
    if ty == "Tuple" || ty.starts_with('(') {
        return TypeId::LIST as u32;
    }

    // Unknown type
    TypeId::UNKNOWN as u32
}

fn get_primitive_type_size(type_id_num: u32) -> i32 {
    if type_id_num > u8::MAX as u32 {
        return 0;
    }
    let type_id = TypeId::try_from(type_id_num as u8).unwrap();
    match type_id {
        TypeId::BOOL => 1,
        TypeId::INT8 => 1,
        TypeId::INT16 => 2,
        TypeId::INT32 => 4,
        TypeId::VARINT32 => 4,
        TypeId::INT64 => 8,
        TypeId::VARINT64 => 8,
        TypeId::TAGGED_INT64 => 8,
        TypeId::FLOAT8 => 1,
        TypeId::FLOAT16 => 2,
        TypeId::BFLOAT16 => 2,
        TypeId::FLOAT32 => 4,
        TypeId::FLOAT64 => 8,
        TypeId::INT128 => 16,
        TypeId::UINT8 => 1,
        TypeId::UINT16 => 2,
        TypeId::UINT32 => 4,
        TypeId::VAR_UINT32 => 4,
        TypeId::UINT64 => 8,
        TypeId::VAR_UINT64 => 8,
        TypeId::TAGGED_UINT64 => 8,
        TypeId::U128 => 16,
        TypeId::USIZE => std::mem::size_of::<usize>() as i32,
        TypeId::ISIZE => std::mem::size_of::<isize>() as i32,
        _ => unreachable!(),
    }
}

fn is_compress(type_id: u32) -> bool {
    // Variable-length and tagged types are marked as compressible
    // This must match Java's Types.isCompressedType() for xlang compatibility
    [
        // Signed compressed types
        TypeId::VARINT32 as u32,
        TypeId::VARINT64 as u32,
        TypeId::TAGGED_INT64 as u32,
        // Unsigned compressed types
        TypeId::VAR_UINT32 as u32,
        TypeId::VAR_UINT64 as u32,
        TypeId::TAGGED_UINT64 as u32,
    ]
    .contains(&type_id)
}

fn is_internal_type_id(type_id: u32) -> bool {
    [
        TypeId::STRING as u32,
        TypeId::DATE as u32,
        TypeId::TIMESTAMP as u32,
        TypeId::DURATION as u32,
        TypeId::DECIMAL as u32,
        TypeId::BINARY as u32,
        TypeId::BOOL_ARRAY as u32,
        TypeId::INT8_ARRAY as u32,
        TypeId::INT16_ARRAY as u32,
        TypeId::INT32_ARRAY as u32,
        TypeId::INT64_ARRAY as u32,
        TypeId::INT128_ARRAY as u32,
        TypeId::FLOAT8_ARRAY as u32,
        TypeId::FLOAT16_ARRAY as u32,
        TypeId::BFLOAT16_ARRAY as u32,
        TypeId::FLOAT32_ARRAY as u32,
        TypeId::FLOAT64_ARRAY as u32,
        TypeId::UINT8_ARRAY as u32,
        TypeId::UINT16_ARRAY as u32,
        TypeId::UINT32_ARRAY as u32,
        TypeId::UINT64_ARRAY as u32,
        TypeId::U128_ARRAY as u32,
    ]
    .contains(&type_id)
}

/// Group fields into serialization categories while normalizing field names to snake_case.
/// The returned groups preserve the ordering rules required by the serialization layout.
fn group_fields_by_type(fields: &[&Field]) -> FieldGroups {
    use super::field_meta::parse_field_meta;

    let mut primitive_fields = Vec::new();
    let mut nullable_primitive_fields = Vec::new();
    let mut internal_type_fields = Vec::new();
    let mut list_fields = Vec::new();
    let mut set_fields = Vec::new();
    let mut map_fields = Vec::new();
    let mut other_fields = Vec::new();

    // First handle Forward fields separately to avoid borrow checker issues
    for (idx, field) in fields.iter().enumerate() {
        if is_forward_field(&field.ty) {
            let raw_ident = get_field_name(field, idx);
            let ident = to_snake_case(&raw_ident);
            // Forward fields don't have explicit IDs; sort by name.
            other_fields.push((
                ident.clone(),
                FieldSortKey::name(ident),
                TypeId::UNKNOWN as u32,
            ));
        }
    }

    for (idx, field) in fields.iter().enumerate() {
        let raw_ident = get_field_name(field, idx);
        let ident = to_snake_case(&raw_ident);

        // Skip if already handled as Forward field
        if is_forward_field(&field.ty) {
            continue;
        }

        // Parse field metadata to get encoding attributes and field ID
        let meta = parse_field_meta(field).unwrap_or_default();
        let sort_key = if meta.uses_tag_id() {
            FieldSortKey::id(meta.effective_id())
        } else {
            FieldSortKey::name(ident.clone())
        };

        let ty: String = field
            .ty
            .to_token_stream()
            .to_string()
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect::<String>();

        // Closure to group non-option fields, considering encoding attributes
        let mut group_field =
            |ident: String, sort_key: FieldSortKey, ty_str: &str, is_primitive: bool| {
                let type_id = if meta.bytes {
                    TypeId::BINARY as u32
                } else if meta.array {
                    array_type_id_for_vec_name(ty_str).unwrap_or(TypeId::UNKNOWN as u32)
                } else {
                    // Adjust type ID based on encoding attributes for u32/u64 fields.
                    adjust_type_id_for_encoding(get_type_id_by_name(ty_str), &meta)
                };

                // Categorize based on type_id
                if is_primitive {
                    primitive_fields.push((ident, sort_key, type_id));
                } else if is_internal_type_id(type_id) {
                    internal_type_fields.push((ident, sort_key, type_id));
                } else if type_id == TypeId::LIST as u32 {
                    list_fields.push((ident, sort_key, type_id));
                } else if type_id == TypeId::SET as u32 {
                    set_fields.push((ident, sort_key, type_id));
                } else if type_id == TypeId::MAP as u32 {
                    map_fields.push((ident, sort_key, type_id));
                } else {
                    // User-defined type
                    other_fields.push((ident, sort_key, type_id));
                }
            };

        // handle Option<Primitive> specially
        if let Some(inner) = extract_option_inner(&ty) {
            if PRIMITIVE_TYPE_NAMES.contains(&inner) {
                // Get base type ID and adjust for encoding attributes
                let base_type_id = get_primitive_type_id(inner);
                let type_id = adjust_type_id_for_encoding(base_type_id, &meta);
                nullable_primitive_fields.push((ident, sort_key, type_id));
            } else {
                group_field(ident, sort_key, inner, false);
            }
        } else if PRIMITIVE_TYPE_NAMES.contains(&ty.as_str()) {
            group_field(ident, sort_key, &ty, true);
        } else {
            group_field(ident, sort_key, &ty, false);
        }
    }

    fn numeric_sorter(
        a: &(String, FieldSortKey, u32),
        b: &(String, FieldSortKey, u32),
    ) -> std::cmp::Ordering {
        let compress_a = is_compress(a.2);
        let compress_b = is_compress(b.2);
        let size_a = get_primitive_type_size(a.2);
        let size_b = get_primitive_type_size(b.2);
        compress_a
            .cmp(&compress_b)
            .then_with(|| size_b.cmp(&size_a))
            .then_with(|| a.2.cmp(&b.2))
            // Field identifier (numeric tag ID or name) as tie-breaker.
            .then_with(|| compare_field_sort_key(&a.1, &b.1))
            // Deterministic fallback for duplicate identifiers
            .then_with(|| a.0.cmp(&b.0))
    }

    fn type_id_then_name_sorter(
        a: &(String, FieldSortKey, u32),
        b: &(String, FieldSortKey, u32),
    ) -> std::cmp::Ordering {
        a.2.cmp(&b.2)
            .then_with(|| compare_field_sort_key(&a.1, &b.1))
            .then_with(|| a.0.cmp(&b.0))
    }

    fn name_sorter(
        a: &(String, FieldSortKey, u32),
        b: &(String, FieldSortKey, u32),
    ) -> std::cmp::Ordering {
        compare_field_sort_key(&a.1, &b.1).then_with(|| a.0.cmp(&b.0))
    }

    primitive_fields.sort_by(numeric_sorter);
    nullable_primitive_fields.sort_by(numeric_sorter);
    internal_type_fields.sort_by(type_id_then_name_sorter);
    list_fields.sort_by(name_sorter);
    set_fields.sort_by(name_sorter);
    map_fields.sort_by(name_sorter);
    other_fields.sort_by(name_sorter);

    (
        primitive_fields,
        nullable_primitive_fields,
        internal_type_fields,
        list_fields,
        set_fields,
        map_fields,
        other_fields,
    )
}

pub(crate) fn get_sorted_field_names(fields: &[&Field]) -> Vec<String> {
    // For tuple structs, preserve the original field order.
    // Tuple struct field names are "0", "1", "2", etc., which are positional.
    // Sorting would break schema evolution when adding fields in the middle
    // (e.g., (f64, u8) -> (f64, u8, f64) would change sorted order).
    if is_tuple_struct(fields) {
        return fields
            .iter()
            .enumerate()
            .map(|(idx, field)| get_field_name(field, idx))
            .collect();
    }

    // For named structs, sort by Fory field order. Tag IDs are the field identifier inside each
    // group, but they do not bypass the language-neutral grouping rules.
    let (
        primitive_fields,
        nullable_primitive_fields,
        internal_type_fields,
        list_fields,
        set_fields,
        map_fields,
        other_fields,
    ) = group_fields_by_type(fields);

    let mut all_fields = primitive_fields;
    all_fields.extend(nullable_primitive_fields);
    all_fields.extend(internal_type_fields);
    all_fields.extend(list_fields);
    all_fields.extend(set_fields);
    all_fields.extend(map_fields);
    all_fields.extend(other_fields);

    all_fields.into_iter().map(|(name, _, _)| name).collect()
}

pub(crate) fn get_filtered_fields_iter<'a>(
    fields: &'a [&'a Field],
) -> impl Iterator<Item = &'a Field> {
    fields.iter().filter(|field| !is_skip_field(field)).copied()
}

pub(super) fn get_sort_fields_ts(fields: &[&Field]) -> TokenStream {
    let filterd_fields: Vec<&Field> = get_filtered_fields_iter(fields).collect();
    let sorted_names = get_sorted_field_names(&filterd_fields);
    let names = sorted_names.iter().map(|name| {
        quote! { #name }
    });
    quote! {
        &[#(#names),*]
    }
}

/// Field metadata for fingerprint computation.
struct FieldFingerprintInfo {
    /// Field ID, or -1 when the field is identified by name.
    field_id: i32,
    /// Field name (snake_case) or field ID as string.
    name_or_id: String,
    /// Recursive field type fingerprint.
    type_fingerprint: String,
}

/// Adjusts integer type IDs based on semantic `encoding` attributes.
fn adjust_type_id_for_encoding(base_type_id: u32, meta: &super::field_meta::ForyFieldMeta) -> u32 {
    match meta.encoding.unwrap_or(IntEncoding::Varint) {
        IntEncoding::Varint => base_type_id,
        IntEncoding::Fixed => match base_type_id {
            id if id == TypeId::VARINT32 as u32 => TypeId::INT32 as u32,
            id if id == TypeId::VARINT64 as u32 => TypeId::INT64 as u32,
            id if id == TypeId::VAR_UINT32 as u32 => TypeId::UINT32 as u32,
            id if id == TypeId::VAR_UINT64 as u32 => TypeId::UINT64 as u32,
            _ => base_type_id,
        },
        IntEncoding::Tagged => match base_type_id {
            id if id == TypeId::VARINT64 as u32 => TypeId::TAGGED_INT64 as u32,
            id if id == TypeId::VAR_UINT64 as u32 => TypeId::TAGGED_UINT64 as u32,
            _ => base_type_id,
        },
    }
}

fn array_type_id_for_vec_name(ty: &str) -> Option<u32> {
    let elem = ty.strip_prefix("Vec<")?.strip_suffix('>')?;
    match elem {
        "bool" => Some(TypeId::BOOL_ARRAY as u32),
        "i8" => Some(TypeId::INT8_ARRAY as u32),
        "i16" => Some(TypeId::INT16_ARRAY as u32),
        "i32" => Some(TypeId::INT32_ARRAY as u32),
        "i64" => Some(TypeId::INT64_ARRAY as u32),
        "u8" => Some(TypeId::UINT8_ARRAY as u32),
        "u16" => Some(TypeId::UINT16_ARRAY as u32),
        "u32" => Some(TypeId::UINT32_ARRAY as u32),
        "u64" => Some(TypeId::UINT64_ARRAY as u32),
        "float16" | "Float16" => Some(TypeId::FLOAT16_ARRAY as u32),
        "bfloat16" | "BFloat16" => Some(TypeId::BFLOAT16_ARRAY as u32),
        "f32" => Some(TypeId::FLOAT32_ARRAY as u32),
        "f64" => Some(TypeId::FLOAT64_ARRAY as u32),
        _ => None,
    }
}

fn fingerprint_type_id(type_id: u32) -> u32 {
    if type_id == TypeId::UNKNOWN as u32
        || type_id == TypeId::UNION as u32
        || type_id == TypeId::TYPED_UNION as u32
        || type_id == TypeId::NAMED_UNION as u32
    {
        TypeId::UNKNOWN as u32
    } else {
        type_id
    }
}

fn type_name_and_args(
    ty: &Type,
) -> Option<(
    String,
    Option<&syn::punctuated::Punctuated<GenericArgument, syn::token::Comma>>,
)> {
    let Type::Path(type_path) = ty else {
        return None;
    };
    let seg = type_path.path.segments.last()?;
    let PathArguments::AngleBracketed(args) = &seg.arguments else {
        return Some((seg.ident.to_string(), None));
    };
    Some((seg.ident.to_string(), Some(&args.args)))
}

fn single_type_arg(
    args: &syn::punctuated::Punctuated<GenericArgument, syn::token::Comma>,
) -> Option<&Type> {
    args.iter().find_map(|arg| match arg {
        GenericArgument::Type(ty) => Some(ty),
        _ => None,
    })
}

fn two_type_args(
    args: &syn::punctuated::Punctuated<GenericArgument, syn::token::Comma>,
) -> Option<(&Type, &Type)> {
    let mut iter = args.iter().filter_map(|arg| match arg {
        GenericArgument::Type(ty) => Some(ty),
        _ => None,
    });
    Some((iter.next()?, iter.next()?))
}

fn build_type_fingerprint(
    ty: &Type,
    meta: &super::field_meta::ForyFieldMeta,
    include_ref: bool,
    include_nullable: bool,
) -> String {
    use super::field_meta::{classify_field_type, extract_option_inner_type, is_option_type};

    let type_class = classify_field_type(ty);
    let nullable = meta.effective_nullable(type_class) || is_option_type(ty);
    let track_ref = include_ref && meta.effective_ref(type_class);
    let container_ty = extract_option_inner_type(ty).unwrap_or_else(|| ty.clone());
    let container_ty_str = container_ty
        .to_token_stream()
        .to_string()
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>();
    let type_id = fingerprint_type_id(if meta.bytes {
        TypeId::BINARY as u32
    } else if meta.array {
        array_type_id_for_vec_name(&container_ty_str).unwrap_or(TypeId::UNKNOWN as u32)
    } else {
        adjust_type_id_for_encoding(get_type_id_by_name(&container_ty_str), meta)
    });

    let mut fingerprint = format!(
        "{},{},{}",
        type_id,
        if track_ref { 1 } else { 0 },
        if include_nullable && nullable { 1 } else { 0 }
    );

    if let Type::Array(array) = &container_ty {
        if type_id == TypeId::LIST as u32 {
            fingerprint.push('[');
            fingerprint.push_str(&build_type_fingerprint(
                array.elem.as_ref(),
                &meta.element_meta(),
                false,
                false,
            ));
            fingerprint.push(']');
        }
        return fingerprint;
    }

    let Some((name, args)) = type_name_and_args(&container_ty) else {
        return fingerprint;
    };

    match name.as_str() {
        "Vec" | "VecDeque" | "LinkedList" if type_id == TypeId::LIST as u32 => {
            if let Some(elem_ty) = args.and_then(single_type_arg) {
                fingerprint.push('[');
                fingerprint.push_str(&build_type_fingerprint(
                    elem_ty,
                    &meta.element_meta(),
                    false,
                    false,
                ));
                fingerprint.push(']');
            }
        }
        "HashSet" | "BTreeSet" | "BinaryHeap" if type_id == TypeId::SET as u32 => {
            if let Some(elem_ty) = args.and_then(single_type_arg) {
                fingerprint.push('[');
                fingerprint.push_str(&build_type_fingerprint(
                    elem_ty,
                    &meta.element_meta(),
                    false,
                    false,
                ));
                fingerprint.push(']');
            }
        }
        "HashMap" | "BTreeMap" if type_id == TypeId::MAP as u32 => {
            if let Some((key_ty, value_ty)) = args.and_then(two_type_args) {
                fingerprint.push('[');
                fingerprint.push_str(&build_type_fingerprint(
                    key_ty,
                    &meta.map_key_meta(),
                    false,
                    false,
                ));
                fingerprint.push('|');
                fingerprint.push_str(&build_type_fingerprint(
                    value_ty,
                    &meta.map_value_meta(),
                    false,
                    false,
                ));
                fingerprint.push(']');
            }
        }
        _ => {}
    }

    fingerprint
}

/// Computes struct fingerprint string at compile time (during proc-macro execution).
///
/// **Fingerprint Format:** `<field_name_or_id>,<type_id>,<ref>,<nullable>[<child...>];`
/// Tagged fields are sorted by numeric ID. Untagged fields are sorted by name lexicographically.
fn compute_struct_fingerprint(fields: &[&Field]) -> String {
    use super::field_meta::parse_field_meta;
    use std::cmp::Ordering;

    let mut field_infos: Vec<FieldFingerprintInfo> = Vec::with_capacity(fields.len());

    for (idx, field) in fields.iter().enumerate() {
        let meta = parse_field_meta(field).unwrap_or_default();
        if meta.skip {
            continue;
        }

        let name = get_field_name(field, idx);
        let field_id = meta.effective_id();
        let name_or_id = if field_id >= 0 {
            field_id.to_string()
        } else {
            to_snake_case(&name)
        };

        field_infos.push(FieldFingerprintInfo {
            field_id,
            name_or_id,
            type_fingerprint: build_type_fingerprint(&field.ty, &meta, true, true),
        });
    }

    field_infos.sort_by(|a, b| match (a.field_id >= 0, b.field_id >= 0) {
        (true, true) => a
            .field_id
            .cmp(&b.field_id)
            .then_with(|| a.name_or_id.cmp(&b.name_or_id)),
        (true, false) => Ordering::Less,
        (false, true) => Ordering::Greater,
        (false, false) => a.name_or_id.cmp(&b.name_or_id),
    });

    // Build fingerprint string
    let mut fingerprint = String::new();
    for info in &field_infos {
        fingerprint.push_str(&info.name_or_id);
        fingerprint.push(',');
        fingerprint.push_str(&info.type_fingerprint);
        fingerprint.push(';');
    }

    fingerprint
}

/// Generates TokenStream for struct version hash (computed at compile time).
pub(crate) fn gen_struct_version_hash_ts(fields: &[&Field]) -> TokenStream {
    let fingerprint = compute_struct_fingerprint(fields);
    let (hash, _) = fory_core::util::murmurhash3_x64_128(fingerprint.as_bytes(), 47);
    let version_hash = (hash & 0xFFFF_FFFF) as i32;

    quote! {
        {
            const VERSION_HASH: i32 = #version_hash;
            if fory_core::util::ENABLE_FORY_DEBUG_OUTPUT {
                println!(
                    "[rust][fory-debug] struct {} version fingerprint=\"{}\" hash={}",
                    std::any::type_name::<Self>(),
                    #fingerprint,
                    VERSION_HASH
                );
            }
            VERSION_HASH
        }
    }
}

pub(crate) fn is_skip_field(field: &syn::Field) -> bool {
    super::field_meta::is_skip_field(field)
}

pub(crate) fn is_skip_enum_variant(variant: &syn::Variant) -> bool {
    variant.attrs.iter().any(|attr| {
        attr.path().is_ident("fory") && {
            let mut skip = false;
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("skip") {
                    skip = true;
                }
                Ok(())
            });
            skip
        }
    })
}

pub(crate) fn enum_variant_id(variant: &syn::Variant) -> Option<u32> {
    for attr in &variant.attrs {
        if !attr.path().is_ident("fory") {
            continue;
        }
        let mut id = None;
        let _ = attr.parse_nested_meta(|meta| {
            if meta.path.is_ident("id") {
                if let Ok(value) = meta.value() {
                    if let Ok(lit) = value.parse::<syn::LitInt>() {
                        if let Ok(parsed) = lit.base10_parse::<u32>() {
                            id = Some(parsed);
                        }
                    }
                }
            }
            Ok(())
        });
        if id.is_some() {
            return id;
        }
    }
    None
}

pub(crate) fn is_default_value_variant(variant: &syn::Variant) -> bool {
    variant
        .attrs
        .iter()
        .any(|attr| attr.path().is_ident("default"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use syn::parse_quote;

    #[test]
    fn group_fields_normalizes_names_and_preserves_ordering() {
        let fields: Vec<syn::Field> = vec![
            parse_quote!(pub camelCase: i32),
            parse_quote!(pub optionalValue: Option<i64>),
            parse_quote!(pub simpleString: String),
            parse_quote!(pub listItems: Vec<String>),
            parse_quote!(pub setItems: HashSet<i32>),
            parse_quote!(pub mapValues: HashMap<String, i32>),
            parse_quote!(pub customType: CustomType),
        ];
        let field_refs: Vec<&syn::Field> = fields.iter().collect();

        let (
            primitive_fields,
            nullable_primitive_fields,
            internal_type_fields,
            list_fields,
            set_fields,
            map_fields,
            other_fields,
        ) = group_fields_by_type(&field_refs);

        let primitive_names: Vec<&str> = primitive_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(primitive_names, vec!["camel_case"]);

        let nullable_names: Vec<&str> = nullable_primitive_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(nullable_names, vec!["optional_value"]);

        let internal_names: Vec<&str> = internal_type_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(internal_names, vec!["simple_string"]);

        let list_names: Vec<&str> = list_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(list_names, vec!["list_items"]);

        let set_names: Vec<&str> = set_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(set_names, vec!["set_items"]);

        let map_names: Vec<&str> = map_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(map_names, vec!["map_values"]);

        let other_names: Vec<&str> = other_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(other_names, vec!["custom_type"]);

        let sorted_names = get_sorted_field_names(&field_refs);
        assert_eq!(
            sorted_names,
            vec![
                "camel_case".to_string(),
                "optional_value".to_string(),
                "simple_string".to_string(),
                "list_items".to_string(),
                "set_items".to_string(),
                "map_values".to_string(),
                "custom_type".to_string(),
            ]
        );
    }

    #[test]
    fn group_fields_sorts_uint8_array_with_dense_arrays() {
        let fields: Vec<syn::Field> = vec![
            parse_quote!(#[fory(id = 21, bytes)] pub bytesValue: Vec<u8>),
            parse_quote!(#[fory(id = 306, array)] pub uint8Array: Vec<u8>),
            parse_quote!(#[fory(id = 302, array)] pub int8Array: Vec<i8>),
            parse_quote!(#[fory(id = 307, array)] pub uint16Array: Vec<u16>),
            parse_quote!(pub customType: CustomType),
        ];
        let field_refs: Vec<&syn::Field> = fields.iter().collect();

        let (
            _primitive_fields,
            _nullable_primitive_fields,
            internal_type_fields,
            _list_fields,
            _set_fields,
            _map_fields,
            other_fields,
        ) = group_fields_by_type(&field_refs);

        let internal_names: Vec<&str> = internal_type_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(
            internal_names,
            vec!["bytes_value", "int8_array", "uint8_array", "uint16_array"]
        );

        let other_names: Vec<&str> = other_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(other_names, vec!["custom_type"]);

        let sorted_names = get_sorted_field_names(&field_refs);
        assert_eq!(
            sorted_names,
            vec![
                "bytes_value".to_string(),
                "int8_array".to_string(),
                "uint8_array".to_string(),
                "uint16_array".to_string(),
                "custom_type".to_string(),
            ]
        );
    }

    #[test]
    fn group_fields_sorts_tag_ids_numerically_inside_group() {
        let fields: Vec<syn::Field> = vec![
            parse_quote!(#[fory(id = 10)] pub ten: i32),
            parse_quote!(#[fory(id = 2)] pub two: i32),
            parse_quote!(#[fory(id = 1)] pub one: i32),
        ];
        let field_refs: Vec<&syn::Field> = fields.iter().collect();

        let sorted_names = get_sorted_field_names(&field_refs);
        assert_eq!(
            sorted_names,
            vec!["one".to_string(), "two".to_string(), "ten".to_string()]
        );
    }
}
