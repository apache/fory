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

use crate::models::complex::{ECommerceData, ForyCustomer, ForyOrder, ForyOrderItem, ForyProduct};
use crate::models::medium::{Company, ForyAddress, Person};
use crate::models::realworld::{ForyAPIMetrics, ForyLogEntry, ForyUserProfile, SystemData};
use crate::models::simple::{SimpleList, SimpleMap, SimpleStruct};
use crate::serializers::{naive_datetime_to_timestamp, timestamp_to_naive_datetime, Serializer};
use prost::Message;

// Import protobuf types from the generated code (included directly in lib.rs)
use crate::{
    Address, ApiMetrics, Customer, LogEntry, Order, OrderItem, Product, ProtoCompany,
    ProtoECommerceData, ProtoPerson, ProtoSimpleList, ProtoSimpleMap, ProtoSimpleStruct,
    ProtoSystemData, UserProfile,
};

#[derive(Default)]
pub struct ProtobufSerializer;

impl ProtobufSerializer {
    pub fn new() -> Self {
        Self
    }
}

// Conversion functions from Fory models to Protobuf models
impl From<&SimpleStruct> for ProtoSimpleStruct {
    fn from(f: &SimpleStruct) -> Self {
        ProtoSimpleStruct {
            id: f.id,
            name: f.name.clone(),
            active: f.active,
            score: f.score,
        }
    }
}

impl From<&SimpleList> for ProtoSimpleList {
    fn from(f: &SimpleList) -> Self {
        ProtoSimpleList {
            numbers: f.numbers.clone(),
            names: f.names.clone(),
        }
    }
}

impl From<&SimpleMap> for ProtoSimpleMap {
    fn from(f: &SimpleMap) -> Self {
        ProtoSimpleMap {
            string_to_int: f.string_to_int.clone(),
            int_to_string: f.int_to_string.clone(),
        }
    }
}

impl From<&ForyAddress> for Address {
    fn from(f: &ForyAddress) -> Self {
        Address {
            street: f.street.clone(),
            city: f.city.clone(),
            country: f.country.clone(),
            zip_code: f.zip_code.clone(),
        }
    }
}

impl From<&Person> for ProtoPerson {
    fn from(f: &Person) -> Self {
        ProtoPerson {
            name: f.name.clone(),
            age: f.age,
            address: Some((&f.address).into()),
            hobbies: f.hobbies.clone(),
            metadata: f.metadata.clone(),
            created_at: Some(naive_datetime_to_timestamp(f.created_at)),
        }
    }
}

impl From<&Company> for ProtoCompany {
    fn from(f: &Company) -> Self {
        ProtoCompany {
            name: f.name.clone(),
            employees: f.employees.iter().map(|e| e.into()).collect(),
            offices: f
                .offices
                .iter()
                .map(|(k, v)| (k.clone(), v.into()))
                .collect(),
            is_public: f.is_public,
        }
    }
}

impl From<&ForyProduct> for Product {
    fn from(f: &ForyProduct) -> Self {
        Product {
            id: f.id.clone(),
            name: f.name.clone(),
            price: f.price,
            categories: f.categories.clone(),
            attributes: f.attributes.clone(),
        }
    }
}

impl From<&ForyOrderItem> for OrderItem {
    fn from(f: &ForyOrderItem) -> Self {
        OrderItem {
            product: Some((&f.product).into()),
            quantity: f.quantity,
            unit_price: f.unit_price,
            customizations: f.customizations.clone(),
        }
    }
}

impl From<&ForyCustomer> for Customer {
    fn from(f: &ForyCustomer) -> Self {
        Customer {
            id: f.id.clone(),
            name: f.name.clone(),
            email: f.email.clone(),
            phone_numbers: f.phone_numbers.clone(),
            preferences: f.preferences.clone(),
            member_since: Some(naive_datetime_to_timestamp(f.member_since)),
        }
    }
}

impl From<&ForyOrder> for Order {
    fn from(f: &ForyOrder) -> Self {
        Order {
            id: f.id.clone(),
            customer: Some((&f.customer).into()),
            items: f.items.iter().map(|i| i.into()).collect(),
            total_amount: f.total_amount,
            status: f.status.clone(),
            order_date: Some(naive_datetime_to_timestamp(f.order_date)),
            metadata: f.metadata.clone(),
        }
    }
}

impl From<&ECommerceData> for ProtoECommerceData {
    fn from(f: &ECommerceData) -> Self {
        ProtoECommerceData {
            orders: f.orders.iter().map(|o| o.into()).collect(),
            customers: f.customers.iter().map(|c| c.into()).collect(),
            products: f.products.iter().map(|p| p.into()).collect(),
            order_lookup: f
                .order_lookup
                .iter()
                .map(|(k, v)| (k.clone(), v.into()))
                .collect(),
        }
    }
}

impl From<&ForyLogEntry> for LogEntry {
    fn from(f: &ForyLogEntry) -> Self {
        LogEntry {
            id: f.id.clone(),
            level: f.level,
            message: f.message.clone(),
            service: f.service.clone(),
            timestamp: Some(naive_datetime_to_timestamp(f.timestamp)),
            context: f.context.clone(),
            tags: f.tags.clone(),
            duration_ms: f.duration_ms,
        }
    }
}

impl From<&ForyUserProfile> for UserProfile {
    fn from(f: &ForyUserProfile) -> Self {
        UserProfile {
            user_id: f.user_id.clone(),
            username: f.username.clone(),
            email: f.email.clone(),
            preferences: f.preferences.clone(),
            permissions: f.permissions.clone(),
            last_login: Some(naive_datetime_to_timestamp(f.last_login)),
            is_active: f.is_active,
        }
    }
}

impl From<&ForyAPIMetrics> for ApiMetrics {
    fn from(f: &ForyAPIMetrics) -> Self {
        ApiMetrics {
            endpoint: f.endpoint.clone(),
            request_count: f.request_count,
            avg_response_time: f.avg_response_time,
            error_count: f.error_count,
            status_codes: f.status_codes.clone(),
            measured_at: Some(naive_datetime_to_timestamp(f.measured_at)),
        }
    }
}

impl From<&SystemData> for ProtoSystemData {
    fn from(f: &SystemData) -> Self {
        ProtoSystemData {
            logs: f.logs.iter().map(|l| l.into()).collect(),
            users: f.users.iter().map(|u| u.into()).collect(),
            metrics: f.metrics.iter().map(|m| m.into()).collect(),
            system_info: f.system_info.clone(),
        }
    }
}

// Conversion functions from Protobuf models to Fory models
impl From<ProtoSimpleStruct> for SimpleStruct {
    fn from(p: ProtoSimpleStruct) -> Self {
        SimpleStruct {
            id: p.id,
            name: p.name,
            active: p.active,
            score: p.score,
        }
    }
}

impl From<ProtoSimpleList> for SimpleList {
    fn from(p: ProtoSimpleList) -> Self {
        SimpleList {
            numbers: p.numbers,
            names: p.names,
        }
    }
}

impl From<ProtoSimpleMap> for SimpleMap {
    fn from(p: ProtoSimpleMap) -> Self {
        SimpleMap {
            string_to_int: p.string_to_int,
            int_to_string: p.int_to_string,
        }
    }
}

impl From<Address> for ForyAddress {
    fn from(p: Address) -> Self {
        ForyAddress {
            street: p.street,
            city: p.city,
            country: p.country,
            zip_code: p.zip_code,
        }
    }
}

impl From<ProtoPerson> for Person {
    fn from(p: ProtoPerson) -> Self {
        Person {
            name: p.name,
            age: p.age,
            address: p.address.map(|a| a.into()).unwrap_or_default(),
            hobbies: p.hobbies,
            metadata: p.metadata,
            created_at: p
                .created_at
                .map(timestamp_to_naive_datetime)
                .unwrap_or_default(),
        }
    }
}

impl From<ProtoCompany> for Company {
    fn from(p: ProtoCompany) -> Self {
        Company {
            name: p.name,
            employees: p.employees.into_iter().map(|e| e.into()).collect(),
            offices: p.offices.into_iter().map(|(k, v)| (k, v.into())).collect(),
            is_public: p.is_public,
        }
    }
}

impl From<Product> for ForyProduct {
    fn from(p: Product) -> Self {
        ForyProduct {
            id: p.id,
            name: p.name,
            price: p.price,
            categories: p.categories,
            attributes: p.attributes,
        }
    }
}

impl From<OrderItem> for ForyOrderItem {
    fn from(p: OrderItem) -> Self {
        ForyOrderItem {
            product: p.product.map(|prod| prod.into()).unwrap_or_default(),
            quantity: p.quantity,
            unit_price: p.unit_price,
            customizations: p.customizations,
        }
    }
}

impl From<Customer> for ForyCustomer {
    fn from(p: Customer) -> Self {
        ForyCustomer {
            id: p.id,
            name: p.name,
            email: p.email,
            phone_numbers: p.phone_numbers,
            preferences: p.preferences,
            member_since: p
                .member_since
                .map(timestamp_to_naive_datetime)
                .unwrap_or_default(),
        }
    }
}

impl From<Order> for ForyOrder {
    fn from(p: Order) -> Self {
        ForyOrder {
            id: p.id,
            customer: p.customer.map(|c| c.into()).unwrap_or_default(),
            items: p.items.into_iter().map(|i| i.into()).collect(),
            total_amount: p.total_amount,
            status: p.status,
            order_date: p
                .order_date
                .map(timestamp_to_naive_datetime)
                .unwrap_or_default(),
            metadata: p.metadata,
        }
    }
}

impl From<ProtoECommerceData> for ECommerceData {
    fn from(p: ProtoECommerceData) -> Self {
        ECommerceData {
            orders: p.orders.into_iter().map(|o| o.into()).collect(),
            customers: p.customers.into_iter().map(|c| c.into()).collect(),
            products: p.products.into_iter().map(|prod| prod.into()).collect(),
            order_lookup: p
                .order_lookup
                .into_iter()
                .map(|(k, v)| (k, v.into()))
                .collect(),
        }
    }
}

impl From<LogEntry> for ForyLogEntry {
    fn from(p: LogEntry) -> Self {
        ForyLogEntry {
            id: p.id,
            level: p.level,
            message: p.message,
            service: p.service,
            timestamp: p
                .timestamp
                .map(timestamp_to_naive_datetime)
                .unwrap_or_default(),
            context: p.context,
            tags: p.tags,
            duration_ms: p.duration_ms,
        }
    }
}

impl From<UserProfile> for ForyUserProfile {
    fn from(p: UserProfile) -> Self {
        ForyUserProfile {
            user_id: p.user_id,
            username: p.username,
            email: p.email,
            preferences: p.preferences,
            permissions: p.permissions,
            last_login: p
                .last_login
                .map(timestamp_to_naive_datetime)
                .unwrap_or_default(),
            is_active: p.is_active,
        }
    }
}

impl From<ApiMetrics> for ForyAPIMetrics {
    fn from(p: ApiMetrics) -> Self {
        ForyAPIMetrics {
            endpoint: p.endpoint,
            request_count: p.request_count,
            avg_response_time: p.avg_response_time,
            error_count: p.error_count,
            status_codes: p.status_codes,
            measured_at: p
                .measured_at
                .map(timestamp_to_naive_datetime)
                .unwrap_or_default(),
        }
    }
}

impl From<ProtoSystemData> for SystemData {
    fn from(p: ProtoSystemData) -> Self {
        SystemData {
            logs: p.logs.into_iter().map(|l| l.into()).collect(),
            users: p.users.into_iter().map(|u| u.into()).collect(),
            metrics: p.metrics.into_iter().map(|m| m.into()).collect(),
            system_info: p.system_info,
        }
    }
}

// Serializer implementations
impl Serializer<SimpleStruct> for ProtobufSerializer {
    fn serialize(&self, data: &SimpleStruct) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        let proto: ProtoSimpleStruct = data.into();
        let mut buf = Vec::new();
        proto.encode(&mut buf)?;
        Ok(buf)
    }

    fn deserialize(&self, data: &[u8]) -> Result<SimpleStruct, Box<dyn std::error::Error>> {
        let proto = ProtoSimpleStruct::decode(data)?;
        Ok(proto.into())
    }
}

impl Serializer<SimpleList> for ProtobufSerializer {
    fn serialize(&self, data: &SimpleList) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        let proto: ProtoSimpleList = data.into();
        let mut buf = Vec::new();
        proto.encode(&mut buf)?;
        Ok(buf)
    }

    fn deserialize(&self, data: &[u8]) -> Result<SimpleList, Box<dyn std::error::Error>> {
        let proto = ProtoSimpleList::decode(data)?;
        Ok(proto.into())
    }
}

impl Serializer<SimpleMap> for ProtobufSerializer {
    fn serialize(&self, data: &SimpleMap) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        let proto: ProtoSimpleMap = data.into();
        let mut buf = Vec::new();
        proto.encode(&mut buf)?;
        Ok(buf)
    }

    fn deserialize(&self, data: &[u8]) -> Result<SimpleMap, Box<dyn std::error::Error>> {
        let proto = ProtoSimpleMap::decode(data)?;
        Ok(proto.into())
    }
}

impl Serializer<Person> for ProtobufSerializer {
    fn serialize(&self, data: &Person) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        let proto: ProtoPerson = data.into();
        let mut buf = Vec::new();
        proto.encode(&mut buf)?;
        Ok(buf)
    }

    fn deserialize(&self, data: &[u8]) -> Result<Person, Box<dyn std::error::Error>> {
        let proto = ProtoPerson::decode(data)?;
        Ok(proto.into())
    }
}

impl Serializer<Company> for ProtobufSerializer {
    fn serialize(&self, data: &Company) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        let proto: ProtoCompany = data.into();
        let mut buf = Vec::new();
        proto.encode(&mut buf)?;
        Ok(buf)
    }

    fn deserialize(&self, data: &[u8]) -> Result<Company, Box<dyn std::error::Error>> {
        let proto = ProtoCompany::decode(data)?;
        Ok(proto.into())
    }
}

impl Serializer<ECommerceData> for ProtobufSerializer {
    fn serialize(&self, data: &ECommerceData) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        let proto: ProtoECommerceData = data.into();
        let mut buf = Vec::new();
        proto.encode(&mut buf)?;
        Ok(buf)
    }

    fn deserialize(&self, data: &[u8]) -> Result<ECommerceData, Box<dyn std::error::Error>> {
        let proto = ProtoECommerceData::decode(data)?;
        Ok(proto.into())
    }
}

impl Serializer<SystemData> for ProtobufSerializer {
    fn serialize(&self, data: &SystemData) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        let proto: ProtoSystemData = data.into();
        let mut buf = Vec::new();
        proto.encode(&mut buf)?;
        Ok(buf)
    }

    fn deserialize(&self, data: &[u8]) -> Result<SystemData, Box<dyn std::error::Error>> {
        let proto = ProtoSystemData::decode(data)?;
        Ok(proto.into())
    }
}
