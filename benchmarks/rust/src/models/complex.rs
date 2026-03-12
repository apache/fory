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

use crate::models::{generate_random_string, generate_random_strings, TestDataGenerator};
use chrono::{DateTime, NaiveDateTime, Utc};
use fory_derive::ForyObject;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// Fory models
#[derive(ForyObject, Debug, Clone, PartialEq, Default)]
pub struct ForyProduct {
    #[fory(id = 0)]
    pub id: String,
    #[fory(id = 1)]
    pub name: String,
    #[fory(id = 2)]
    pub price: f64,
    #[fory(id = 3)]
    pub categories: Vec<String>,
    #[fory(id = 4)]
    pub attributes: HashMap<String, String>,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct ForyOrderItem {
    #[fory(id = 0)]
    pub product: ForyProduct,
    #[fory(id = 1)]
    pub quantity: i32,
    #[fory(id = 2)]
    pub unit_price: f64,
    #[fory(id = 3)]
    pub customizations: HashMap<String, String>,
}

#[derive(ForyObject, Debug, Clone, PartialEq, Default)]
pub struct ForyCustomer {
    #[fory(id = 0)]
    pub id: String,
    #[fory(id = 1)]
    pub name: String,
    #[fory(id = 2)]
    pub email: String,
    #[fory(id = 3)]
    pub phone_numbers: Vec<String>,
    #[fory(id = 4)]
    pub preferences: HashMap<String, String>,
    #[fory(id = 5)]
    pub member_since: NaiveDateTime,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct ForyOrder {
    #[fory(id = 0)]
    pub id: String,
    #[fory(id = 1)]
    pub customer: ForyCustomer,
    #[fory(id = 2)]
    pub items: Vec<ForyOrderItem>,
    #[fory(id = 3)]
    pub total_amount: f64,
    #[fory(id = 4)]
    pub status: String,
    #[fory(id = 5)]
    pub order_date: NaiveDateTime,
    #[fory(id = 6)]
    pub metadata: HashMap<String, String>,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct ECommerceData {
    #[fory(id = 0)]
    pub orders: Vec<ForyOrder>,
    #[fory(id = 1)]
    pub customers: Vec<ForyCustomer>,
    #[fory(id = 2)]
    pub products: Vec<ForyProduct>,
    #[fory(id = 3)]
    pub order_lookup: HashMap<String, ForyOrder>,
}

// Serde models
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeProduct {
    pub id: String,
    pub name: String,
    pub price: f64,
    pub categories: Vec<String>,
    pub attributes: HashMap<String, String>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeOrderItem {
    pub product: SerdeProduct,
    pub quantity: i32,
    pub unit_price: f64,
    pub customizations: HashMap<String, String>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeCustomer {
    pub id: String,
    pub name: String,
    pub email: String,
    pub phone_numbers: Vec<String>,
    pub preferences: HashMap<String, String>,
    pub member_since: DateTime<Utc>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeOrder {
    pub id: String,
    pub customer: SerdeCustomer,
    pub items: Vec<SerdeOrderItem>,
    pub total_amount: f64,
    pub status: String,
    pub order_date: DateTime<Utc>,
    pub metadata: HashMap<String, String>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeECommerceData {
    pub orders: Vec<SerdeOrder>,
    pub customers: Vec<SerdeCustomer>,
    pub products: Vec<SerdeProduct>,
    pub order_lookup: HashMap<String, SerdeOrder>,
}

impl TestDataGenerator for ECommerceData {
    type Data = ECommerceData;

    fn generate_small() -> Self::Data {
        let product = ForyProduct {
            id: "prod_1".to_string(),
            name: "Laptop".to_string(),
            price: 999.99,
            categories: vec!["Electronics".to_string(), "Computers".to_string()],
            attributes: HashMap::from([
                ("brand".to_string(), "TechCorp".to_string()),
                ("model".to_string(), "X1".to_string()),
            ]),
        };

        let customer = ForyCustomer {
            id: "cust_1".to_string(),
            name: "John Doe".to_string(),
            email: "john@example.com".to_string(),
            phone_numbers: vec!["+1234567890".to_string()],
            preferences: HashMap::from([
                ("language".to_string(), "en".to_string()),
                ("currency".to_string(), "USD".to_string()),
            ]),
            member_since: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
        };

        let order_item = ForyOrderItem {
            product: product.clone(),
            quantity: 1,
            unit_price: 999.99,
            customizations: HashMap::from([("color".to_string(), "black".to_string())]),
        };

        let order = ForyOrder {
            id: "order_1".to_string(),
            customer: customer.clone(),
            items: vec![order_item],
            total_amount: 999.99,
            status: "completed".to_string(),
            order_date: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
            metadata: HashMap::from([("payment_method".to_string(), "credit_card".to_string())]),
        };

        let mut order_lookup = HashMap::new();
        order_lookup.insert(order.id.clone(), order.clone());

        ECommerceData {
            orders: vec![order],
            customers: vec![customer],
            products: vec![product],
            order_lookup,
        }
    }

    fn generate_medium() -> Self::Data {
        let mut products = Vec::new();
        let mut customers = Vec::new();
        let mut orders = Vec::new();
        let mut order_lookup = HashMap::new();

        // Generate 20 products
        for i in 1..=20 {
            let product = ForyProduct {
                id: format!("prod_{}", i),
                name: generate_random_string(30),
                price: (i as f64) * 50.0,
                categories: generate_random_strings(3, 15),
                attributes: {
                    let mut map = HashMap::new();
                    for j in 1..=5 {
                        map.insert(format!("attr_{}", j), generate_random_string(20));
                    }
                    map
                },
            };
            products.push(product);
        }

        // Generate 10 customers
        for i in 1..=10 {
            let customer = ForyCustomer {
                id: format!("cust_{}", i),
                name: generate_random_string(40),
                email: format!("user{}@example.com", i),
                phone_numbers: generate_random_strings(2, 12),
                preferences: {
                    let mut map = HashMap::new();
                    for j in 1..=10 {
                        map.insert(format!("pref_{}", j), generate_random_string(15));
                    }
                    map
                },
                member_since: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
            };
            customers.push(customer);
        }

        // Generate 15 orders
        for i in 1..=15 {
            let customer = customers[i % customers.len()].clone();
            let mut items = Vec::new();
            let mut total = 0.0;

            for j in 1..=3 {
                let product = products[j % products.len()].clone();
                let quantity = ((j % 5) + 1) as i32;
                let unit_price = product.price;
                total += unit_price * quantity as f64;

                let item = ForyOrderItem {
                    product,
                    quantity,
                    unit_price,
                    customizations: {
                        let mut map = HashMap::new();
                        for k in 1..=3 {
                            map.insert(format!("custom_{}", k), generate_random_string(10));
                        }
                        map
                    },
                };
                items.push(item);
            }

            let order = ForyOrder {
                id: format!("order_{}", i),
                customer,
                items,
                total_amount: total,
                status: if i % 3 == 0 {
                    "pending".to_string()
                } else {
                    "completed".to_string()
                },
                order_date: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
                metadata: {
                    let mut map = HashMap::new();
                    for k in 1..=5 {
                        map.insert(format!("meta_{}", k), generate_random_string(20));
                    }
                    map
                },
            };

            order_lookup.insert(order.id.clone(), order.clone());
            orders.push(order);
        }

        ECommerceData {
            orders,
            customers,
            products,
            order_lookup,
        }
    }

    fn generate_large() -> Self::Data {
        let mut products = Vec::new();
        let mut customers = Vec::new();
        let mut orders = Vec::new();
        let mut order_lookup = HashMap::new();

        // Generate 100 products
        for i in 1..=100 {
            let product = ForyProduct {
                id: format!("prod_{}", i),
                name: generate_random_string(50),
                price: (i as f64) * 25.0,
                categories: generate_random_strings(5, 20),
                attributes: {
                    let mut map = HashMap::new();
                    for j in 1..=10 {
                        map.insert(format!("attr_{}", j), generate_random_string(30));
                    }
                    map
                },
            };
            products.push(product);
        }

        // Generate 50 customers
        for i in 1..=50 {
            let customer = ForyCustomer {
                id: format!("cust_{}", i),
                name: generate_random_string(60),
                email: format!("user{}@example.com", i),
                phone_numbers: generate_random_strings(3, 15),
                preferences: {
                    let mut map = HashMap::new();
                    for j in 1..=20 {
                        map.insert(format!("pref_{}", j), generate_random_string(25));
                    }
                    map
                },
                member_since: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
            };
            customers.push(customer);
        }

        // Generate 100 orders
        for i in 1..=100 {
            let customer = customers[i % customers.len()].clone();
            let mut items = Vec::new();
            let mut total = 0.0;

            for j in 1..=5 {
                let product = products[j % products.len()].clone();
                let quantity = ((j % 10) + 1) as i32;
                let unit_price = product.price;
                total += unit_price * quantity as f64;

                let item = ForyOrderItem {
                    product,
                    quantity,
                    unit_price,
                    customizations: {
                        let mut map = HashMap::new();
                        for k in 1..=5 {
                            map.insert(format!("custom_{}", k), generate_random_string(20));
                        }
                        map
                    },
                };
                items.push(item);
            }

            let order = ForyOrder {
                id: format!("order_{}", i),
                customer,
                items,
                total_amount: total,
                status: if i % 4 == 0 {
                    "pending".to_string()
                } else {
                    "completed".to_string()
                },
                order_date: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
                metadata: {
                    let mut map = HashMap::new();
                    for k in 1..=10 {
                        map.insert(format!("meta_{}", k), generate_random_string(30));
                    }
                    map
                },
            };

            order_lookup.insert(order.id.clone(), order.clone());
            orders.push(order);
        }

        ECommerceData {
            orders,
            customers,
            products,
            order_lookup,
        }
    }
}

// Conversion functions for Serde
impl From<ForyProduct> for SerdeProduct {
    fn from(f: ForyProduct) -> Self {
        SerdeProduct {
            id: f.id,
            name: f.name,
            price: f.price,
            categories: f.categories,
            attributes: f.attributes,
        }
    }
}

impl From<ForyOrderItem> for SerdeOrderItem {
    fn from(f: ForyOrderItem) -> Self {
        SerdeOrderItem {
            product: f.product.into(),
            quantity: f.quantity,
            unit_price: f.unit_price,
            customizations: f.customizations,
        }
    }
}

impl From<ForyCustomer> for SerdeCustomer {
    fn from(f: ForyCustomer) -> Self {
        SerdeCustomer {
            id: f.id,
            name: f.name,
            email: f.email,
            phone_numbers: f.phone_numbers,
            preferences: f.preferences,
            member_since: f.member_since.and_utc(),
        }
    }
}

impl From<ForyOrder> for SerdeOrder {
    fn from(f: ForyOrder) -> Self {
        SerdeOrder {
            id: f.id,
            customer: f.customer.into(),
            items: f.items.into_iter().map(|i| i.into()).collect(),
            total_amount: f.total_amount,
            status: f.status,
            order_date: f.order_date.and_utc(),
            metadata: f.metadata,
        }
    }
}

impl From<ECommerceData> for SerdeECommerceData {
    fn from(f: ECommerceData) -> Self {
        SerdeECommerceData {
            orders: f.orders.into_iter().map(|o| o.into()).collect(),
            customers: f.customers.into_iter().map(|c| c.into()).collect(),
            products: f.products.into_iter().map(|p| p.into()).collect(),
            order_lookup: f
                .order_lookup
                .into_iter()
                .map(|(k, v)| (k, v.into()))
                .collect(),
        }
    }
}

// Reverse conversions from Serde to Fory
impl From<SerdeProduct> for ForyProduct {
    fn from(s: SerdeProduct) -> Self {
        ForyProduct {
            id: s.id,
            name: s.name,
            price: s.price,
            categories: s.categories,
            attributes: s.attributes,
        }
    }
}

impl From<SerdeOrderItem> for ForyOrderItem {
    fn from(s: SerdeOrderItem) -> Self {
        ForyOrderItem {
            product: s.product.into(),
            quantity: s.quantity,
            unit_price: s.unit_price,
            customizations: s.customizations,
        }
    }
}

impl From<SerdeCustomer> for ForyCustomer {
    fn from(s: SerdeCustomer) -> Self {
        ForyCustomer {
            id: s.id,
            name: s.name,
            email: s.email,
            phone_numbers: s.phone_numbers,
            preferences: s.preferences,
            member_since: s.member_since.naive_utc(),
        }
    }
}

impl From<SerdeOrder> for ForyOrder {
    fn from(s: SerdeOrder) -> Self {
        ForyOrder {
            id: s.id,
            customer: s.customer.into(),
            items: s.items.into_iter().map(|i| i.into()).collect(),
            total_amount: s.total_amount,
            status: s.status,
            order_date: s.order_date.naive_utc(),
            metadata: s.metadata,
        }
    }
}

impl From<SerdeECommerceData> for ECommerceData {
    fn from(s: SerdeECommerceData) -> Self {
        ECommerceData {
            orders: s.orders.into_iter().map(|o| o.into()).collect(),
            customers: s.customers.into_iter().map(|c| c.into()).collect(),
            products: s.products.into_iter().map(|p| p.into()).collect(),
            order_lookup: s
                .order_lookup
                .into_iter()
                .map(|(k, v)| (k, v.into()))
                .collect(),
        }
    }
}
