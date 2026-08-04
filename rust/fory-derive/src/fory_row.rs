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

use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::fold::{self, Fold};
use syn::{Data, DeriveInput, Fields, GenericParam, Lifetime, LifetimeParam, Path};

pub fn derive_row(ast: &DeriveInput, runtime_root: proc_macro2::TokenStream) -> TokenStream {
    match expand_row(ast, runtime_root) {
        Ok(tokens) => tokens.into(),
        Err(error) => error.into_compile_error().into(),
    }
}

fn expand_row(
    ast: &DeriveInput,
    runtime_root: proc_macro2::TokenStream,
) -> syn::Result<proc_macro2::TokenStream> {
    let fields = match &ast.data {
        Data::Struct(data) => match &data.fields {
            Fields::Named(fields) => &fields.named,
            Fields::Unnamed(fields) => {
                return Err(syn::Error::new_spanned(
                    fields,
                    "ForyRow can only be derived for structs with named fields",
                ));
            }
            Fields::Unit => {
                return Err(syn::Error::new_spanned(
                    ast,
                    "ForyRow can only be derived for structs with named fields",
                ));
            }
        },
        Data::Enum(_) => {
            return Err(syn::Error::new_spanned(
                ast,
                "ForyRow cannot be derived for enums",
            ));
        }
        Data::Union(_) => {
            return Err(syn::Error::new_spanned(
                ast,
                "ForyRow cannot be derived for unions",
            ));
        }
    };

    let name = &ast.ident;
    let visibility = &ast.vis;
    let view = format_ident!("{}RowView", name);
    let view_doc = format!("A zero-copy Standard Row Format view of `{name}`.");
    let num_fields = fields.len();
    let row_lifetime = row_lifetime(ast);

    let mut row_generics = ast.generics.clone();
    for field in fields {
        let ty = &field.ty;
        let predicate = syn::parse2(quote! {
            #ty: #runtime_root::row::RowValue
        })?;
        row_generics.make_where_clause().predicates.push(predicate);
    }

    let source_path: Path = {
        let (_, source_ty_generics, _) = row_generics.split_for_impl();
        syn::parse2(quote! { #name #source_ty_generics })?
    };
    // Copied bounds and field types live on the generated view, where an
    // unmodified `Self` would refer to the view instead of the source row.
    let mut view_generics = SelfTypeRewriter {
        source_path: source_path.clone(),
    }
    .fold_generics(ast.generics.clone());
    view_generics.params.insert(
        0,
        GenericParam::Lifetime(LifetimeParam::new(row_lifetime.clone())),
    );
    let (impl_generics, ty_generics, where_clause) = row_generics.split_for_impl();
    let (view_impl_generics, view_ty_generics, view_where_clause) = view_generics.split_for_impl();

    let mut writes = Vec::with_capacity(num_fields);
    let mut field_methods = Vec::with_capacity(num_fields);
    for (index, field) in fields.iter().enumerate() {
        let ident = field.ident.as_ref().ok_or_else(|| {
            syn::Error::new_spanned(field, "ForyRow requires named struct fields")
        })?;
        let field_visibility = &field.vis;
        let field_doc = format!("Reads the `{ident}` field from this row view.");
        let ty = &field.ty;
        let field_ty = SelfTypeRewriter {
            source_path: source_path.clone(),
        }
        .fold_type(ty.clone());
        writes.push(quote! {
            struct_writer.write(#index, &self.#ident)?;
        });
        field_methods.push(quote! {
            #[doc = #field_doc]
            #[inline]
            #field_visibility fn #ident(
                &self,
            ) -> ::core::result::Result<
                <#field_ty as #runtime_root::row::RowValue>::View<#row_lifetime>,
                #runtime_root::error::Error,
            >
            where
                #field_ty: #runtime_root::row::RowValue,
            {
                self.struct_data.get::<#field_ty>(#index)
            }
        });
    }

    Ok(quote! {
        #[doc = #view_doc]
        #visibility struct #view #view_generics #view_where_clause {
            struct_data: #runtime_root::row::StructView<#row_lifetime>,
            _marker: ::core::marker::PhantomData<fn() -> *const #name #ty_generics>,
        }

        impl #view_impl_generics #view #view_ty_generics #view_where_clause {
            #(#field_methods)*
        }

        impl #view_impl_generics ::core::marker::Copy for #view #view_ty_generics #view_where_clause {}

        impl #view_impl_generics ::core::clone::Clone for #view #view_ty_generics #view_where_clause {
            fn clone(&self) -> Self {
                *self
            }
        }

        // Backing bytes are a trait capability so schema fields named
        // `as_bytes` or `encoded_len` keep owning their generated methods.
        impl #view_impl_generics #runtime_root::row::RowView<#row_lifetime>
            for #view #view_ty_generics #view_where_clause
        {
            #[inline]
            fn as_bytes(&self) -> &#row_lifetime [u8] {
                #runtime_root::row::RowView::as_bytes(&self.struct_data)
            }
        }

        impl #impl_generics #runtime_root::row::RowValue for #name #ty_generics #where_clause {
            type View<#row_lifetime> = #view #view_ty_generics;

            const FIXED_SIZE: ::core::option::Option<usize> = ::core::option::Option::None;

            fn write(
                &self,
                writer: #runtime_root::row::ValueWriter<'_, '_>,
            ) -> ::core::result::Result<(), #runtime_root::error::Error> {
                let mut struct_writer = writer.struct_writer(#num_fields)?;
                #(#writes)*
                ::core::result::Result::Ok(())
            }

            fn read<#row_lifetime>(
                bytes: &#row_lifetime [u8],
            ) -> ::core::result::Result<Self::View<#row_lifetime>, #runtime_root::error::Error> {
                ::core::result::Result::Ok(#view {
                    struct_data: #runtime_root::row::StructView::new(bytes, #num_fields)?,
                    _marker: ::core::marker::PhantomData,
                })
            }
        }

        impl #impl_generics #runtime_root::row::Row for #name #ty_generics #where_clause {}
    })
}

fn row_lifetime(ast: &DeriveInput) -> Lifetime {
    let mut collector = LifetimeCollector::default();
    collector.fold_derive_input(ast.clone());
    let mut suffix = 0usize;
    loop {
        let name = if suffix == 0 {
            "__fory_row".to_owned()
        } else {
            format!("__fory_row_{suffix}")
        };
        if !collector.names.iter().any(|used| used == &name) {
            return Lifetime::new(&format!("'{name}"), proc_macro2::Span::call_site());
        }
        suffix += 1;
    }
}

#[derive(Default)]
struct LifetimeCollector {
    names: Vec<String>,
}

impl Fold for LifetimeCollector {
    fn fold_lifetime(&mut self, lifetime: Lifetime) -> Lifetime {
        self.names.push(lifetime.ident.to_string());
        lifetime
    }
}

struct SelfTypeRewriter {
    source_path: Path,
}

impl Fold for SelfTypeRewriter {
    fn fold_path(&mut self, path: Path) -> Path {
        let mut path = fold::fold_path(self, path);
        let Some(first) = path.segments.first() else {
            return path;
        };
        if path.leading_colon.is_some() || first.ident != "Self" {
            return path;
        }

        let mut segments = self.source_path.segments.clone();
        segments.extend(path.segments.into_iter().skip(1));
        path.leading_colon = self.source_path.leading_colon;
        path.segments = segments;
        path
    }
}
