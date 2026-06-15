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

use proc_macro2::TokenStream;
use proc_macro_crate::{crate_name, FoundCrate};
use quote::quote;

pub(crate) fn resolve_runtime_root() -> syn::Result<TokenStream> {
    if let Some(root) = resolve_fory()? {
        return Ok(root);
    }
    if let Some(root) = resolve_fory_core()? {
        return Ok(root);
    }
    Err(syn::Error::new(
        proc_macro2::Span::call_site(),
        "Fory derives require a direct dependency on `fory`; lower-level crates may depend on `fory-core` directly",
    ))
}

fn resolve_fory() -> syn::Result<Option<TokenStream>> {
    match crate_name("fory") {
        Ok(FoundCrate::Itself) => Ok(Some(crate_path("::fory::__private")?)),
        Ok(FoundCrate::Name(name)) => Ok(Some(crate_path(&format!("::{name}::__private"))?)),
        Err(_) => Ok(None),
    }
}

fn resolve_fory_core() -> syn::Result<Option<TokenStream>> {
    match crate_name("fory-core") {
        Ok(FoundCrate::Itself) => Ok(Some(crate_path("::fory_core")?)),
        Ok(FoundCrate::Name(name)) => Ok(Some(crate_path(&format!("::{name}"))?)),
        Err(_) => Ok(None),
    }
}

fn crate_path(path: &str) -> syn::Result<TokenStream> {
    let path = syn::parse_str::<syn::Path>(path)?;
    Ok(quote! { #path })
}
