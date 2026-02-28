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

#pragma once

#include <iterator>
#include <type_traits>

namespace fory {

namespace meta {

// dependent name for constant `false` to workaround static_assert(false) issue
// before C++23
template <typename T> constexpr inline bool AlwaysFalse = false;

// T U::* -> T
template <typename> struct RemoveMemberPointer;

template <typename T, typename U> struct RemoveMemberPointer<T U::*> {
  using type = T;
};

template <typename T>
using RemoveMemberPointerT = typename RemoveMemberPointer<T>::type;

// same as std::remove_cvref_t since C++20
template <typename T>
using RemoveCVRefT = std::remove_cv_t<std::remove_reference_t<T>>;

template <typename T>
using RemoveMemberPointerCVRefT = RemoveMemberPointerT<RemoveCVRefT<T>>;

template <auto V1, auto V2>
inline constexpr bool IsSameValue =
    std::is_same_v<std::integral_constant<decltype(V1), V1>,
                   std::integral_constant<decltype(V2), V2>>;

template <auto V, auto... Vs>
inline constexpr bool ContainsValue =
    std::disjunction_v<std::bool_constant<IsSameValue<V, Vs>>...>;

template <auto...> struct IsUnique : std::true_type {};

template <auto V1, auto... VN>
struct IsUnique<V1, VN...>
    : std::bool_constant<!ContainsValue<V1, VN...> && IsUnique<VN...>::value> {
};

template <auto> using Void = void;

/// \brief Metafunction to allow checking if a type matches any of another set
/// of types
template <typename T, typename... Args>
struct IsOneOf : std::disjunction<std::is_same<T, Args>...> {};

/// \brief Shorthand for using IsOneOf + std::enable_if
template <typename T, typename... Args>
using EnableIfIsOneOf =
    typename std::enable_if<IsOneOf<T, Args...>::value, T>::type;

namespace details {
using std::begin;
using std::end;

template <typename T,
          typename U = std::void_t<
              decltype(*begin(std::declval<T &>()),
                       ++std::declval<decltype(begin(std::declval<T &>())) &>(),
                       begin(std::declval<T &>()) != end(std::declval<T &>()))>>
std::true_type is_iterable_impl(int);

template <typename T> std::false_type is_iterable_impl(...);

template <typename T> struct GetValueTypeImpl {
  using type = std::remove_reference_t<decltype(*begin(std::declval<T &>()))>;
};
} // namespace details

template <typename T>
constexpr inline bool IsIterable =
    decltype(details::is_iterable_impl<T>(0))::value;

template <typename T>
using GetValueType = typename details::GetValueTypeImpl<T>::type;

namespace details {

template <typename> constexpr inline bool IsPair = false;

template <typename T1, typename T2>
constexpr inline bool IsPair<std::pair<T1, T2>> = true;

template <typename> std::false_type is_pair_iterable_impl(...);

template <
    typename T,
    std::enable_if_t<IsIterable<T> && IsPair<typename T::value_type>, int> = 0>
std::true_type is_pair_iterable_impl(int);

} // namespace details

template <typename T>
constexpr inline bool IsPairIterable =
    decltype(details::is_pair_iterable_impl<T>(0))::value;

template <typename T> struct StructEvolving : std::true_type {};

} // namespace meta

} // namespace fory
