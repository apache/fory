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
#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct ForyLogEntry {
    #[fory(id = 0)]
    pub id: String,
    #[fory(id = 1)]
    pub level: i32, // 0=DEBUG, 1=INFO, 2=WARN, 3=ERROR, 4=FATAL
    #[fory(id = 2)]
    pub message: String,
    #[fory(id = 3)]
    pub service: String,
    #[fory(id = 4)]
    pub timestamp: NaiveDateTime,
    #[fory(id = 5)]
    pub context: HashMap<String, String>,
    #[fory(id = 6)]
    pub tags: Vec<String>,
    #[fory(id = 7)]
    pub duration_ms: f64,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct ForyUserProfile {
    #[fory(id = 0)]
    pub user_id: String,
    #[fory(id = 1)]
    pub username: String,
    #[fory(id = 2)]
    pub email: String,
    #[fory(id = 3)]
    pub preferences: HashMap<String, String>,
    #[fory(id = 4)]
    pub permissions: Vec<String>,
    #[fory(id = 5)]
    pub last_login: NaiveDateTime,
    #[fory(id = 6)]
    pub is_active: bool,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct ForyAPIMetrics {
    #[fory(id = 0)]
    pub endpoint: String,
    #[fory(id = 1)]
    pub request_count: i64,
    #[fory(id = 2)]
    pub avg_response_time: f64,
    #[fory(id = 3)]
    pub error_count: i64,
    #[fory(id = 4)]
    pub status_codes: HashMap<String, i64>,
    #[fory(id = 5)]
    pub measured_at: NaiveDateTime,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct SystemData {
    #[fory(id = 0)]
    pub logs: Vec<ForyLogEntry>,
    #[fory(id = 1)]
    pub users: Vec<ForyUserProfile>,
    #[fory(id = 2)]
    pub metrics: Vec<ForyAPIMetrics>,
    #[fory(id = 3)]
    pub system_info: HashMap<String, String>,
}

// Serde models
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeLogEntry {
    pub id: String,
    pub level: i32,
    pub message: String,
    pub service: String,
    pub timestamp: DateTime<Utc>,
    pub context: HashMap<String, String>,
    pub tags: Vec<String>,
    pub duration_ms: f64,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeUserProfile {
    pub user_id: String,
    pub username: String,
    pub email: String,
    pub preferences: HashMap<String, String>,
    pub permissions: Vec<String>,
    pub last_login: DateTime<Utc>,
    pub is_active: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeAPIMetrics {
    pub endpoint: String,
    pub request_count: i64,
    pub avg_response_time: f64,
    pub error_count: i64,
    pub status_codes: HashMap<String, i64>,
    pub measured_at: DateTime<Utc>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeSystemData {
    pub logs: Vec<SerdeLogEntry>,
    pub users: Vec<SerdeUserProfile>,
    pub metrics: Vec<SerdeAPIMetrics>,
    pub system_info: HashMap<String, String>,
}

impl TestDataGenerator for SystemData {
    type Data = SystemData;

    fn generate_small() -> Self::Data {
        let log = ForyLogEntry {
            id: "log_1".to_string(),
            level: 1, // INFO
            message: "User login successful".to_string(),
            service: "auth-service".to_string(),
            timestamp: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
            context: HashMap::from([
                ("user_id".to_string(), "123".to_string()),
                ("ip".to_string(), "192.168.1.1".to_string()),
            ]),
            tags: vec!["auth".to_string(), "login".to_string()],
            duration_ms: 45.2,
        };

        let user = ForyUserProfile {
            user_id: "user_123".to_string(),
            username: "johndoe".to_string(),
            email: "john@example.com".to_string(),
            preferences: HashMap::from([
                ("theme".to_string(), "dark".to_string()),
                ("language".to_string(), "en".to_string()),
            ]),
            permissions: vec!["read".to_string(), "write".to_string()],
            last_login: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
            is_active: true,
        };

        let metric = ForyAPIMetrics {
            endpoint: "/api/users".to_string(),
            request_count: 1000,
            avg_response_time: 150.5,
            error_count: 5,
            status_codes: HashMap::from([
                ("200".to_string(), 950),
                ("404".to_string(), 45),
                ("500".to_string(), 5),
            ]),
            measured_at: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
        };

        SystemData {
            logs: vec![log],
            users: vec![user],
            metrics: vec![metric],
            system_info: HashMap::from([
                ("version".to_string(), "1.0.0".to_string()),
                ("environment".to_string(), "production".to_string()),
            ]),
        }
    }

    fn generate_medium() -> Self::Data {
        let mut logs = Vec::new();
        let mut users = Vec::new();
        let mut metrics = Vec::new();

        // Generate 50 log entries
        for i in 1..=50 {
            let log = ForyLogEntry {
                id: format!("log_{}", i),
                level: i % 5,
                message: generate_random_string(100),
                service: format!("service_{}", i % 10),
                timestamp: DateTime::from_timestamp(1640995200 + i as i64, 0)
                    .unwrap()
                    .naive_utc(),
                context: {
                    let mut map = HashMap::new();
                    for j in 1..=5 {
                        map.insert(format!("ctx_{}", j), generate_random_string(20));
                    }
                    map
                },
                tags: generate_random_strings(3, 10),
                duration_ms: (i as f64) * 10.0,
            };
            logs.push(log);
        }

        // Generate 20 users
        for i in 1..=20 {
            let user = ForyUserProfile {
                user_id: format!("user_{}", i),
                username: generate_random_string(20),
                email: format!("user{}@example.com", i),
                preferences: {
                    let mut map = HashMap::new();
                    for j in 1..=10 {
                        map.insert(format!("pref_{}", j), generate_random_string(15));
                    }
                    map
                },
                permissions: generate_random_strings(5, 15),
                last_login: DateTime::from_timestamp(1640995200 + i as i64, 0)
                    .unwrap()
                    .naive_utc(),
                is_active: i % 3 != 0,
            };
            users.push(user);
        }

        // Generate 10 metrics
        for i in 1..=10 {
            let metric = ForyAPIMetrics {
                endpoint: format!("/api/endpoint_{}", i),
                request_count: (i * 1000) as i64,
                avg_response_time: (i as f64) * 50.0,
                error_count: (i * 10) as i64,
                status_codes: {
                    let mut map = HashMap::new();
                    map.insert("200".to_string(), (i * 800) as i64);
                    map.insert("404".to_string(), (i * 50) as i64);
                    map.insert("500".to_string(), (i * 10) as i64);
                    map
                },
                measured_at: DateTime::from_timestamp(1640995200 + i as i64, 0)
                    .unwrap()
                    .naive_utc(),
            };
            metrics.push(metric);
        }

        SystemData {
            logs,
            users,
            metrics,
            system_info: {
                let mut map = HashMap::new();
                for i in 1..=10 {
                    map.insert(format!("info_{}", i), generate_random_string(30));
                }
                map
            },
        }
    }

    fn generate_large() -> Self::Data {
        let mut logs = Vec::new();
        let mut users = Vec::new();
        let mut metrics = Vec::new();

        // Generate 500 log entries
        for i in 1..=500 {
            let log = ForyLogEntry {
                id: format!("log_{}", i),
                level: i % 5,
                message: generate_random_string(200),
                service: format!("service_{}", i % 50),
                timestamp: DateTime::from_timestamp(1640995200 + i as i64, 0)
                    .unwrap()
                    .naive_utc(),
                context: {
                    let mut map = HashMap::new();
                    for j in 1..=10 {
                        map.insert(format!("ctx_{}", j), generate_random_string(30));
                    }
                    map
                },
                tags: generate_random_strings(5, 15),
                duration_ms: (i as f64) * 5.0,
            };
            logs.push(log);
        }

        // Generate 100 users
        for i in 1..=100 {
            let user = ForyUserProfile {
                user_id: format!("user_{}", i),
                username: generate_random_string(30),
                email: format!("user{}@example.com", i),
                preferences: {
                    let mut map = HashMap::new();
                    for j in 1..=20 {
                        map.insert(format!("pref_{}", j), generate_random_string(25));
                    }
                    map
                },
                permissions: generate_random_strings(10, 20),
                last_login: DateTime::from_timestamp(1640995200 + i as i64, 0)
                    .unwrap()
                    .naive_utc(),
                is_active: i % 4 != 0,
            };
            users.push(user);
        }

        // Generate 50 metrics
        for i in 1..=50 {
            let metric = ForyAPIMetrics {
                endpoint: format!("/api/endpoint_{}", i),
                request_count: (i * 2000) as i64,
                avg_response_time: (i as f64) * 25.0,
                error_count: (i * 20) as i64,
                status_codes: {
                    let mut map = HashMap::new();
                    map.insert("200".to_string(), (i * 1600) as i64);
                    map.insert("404".to_string(), (i * 200) as i64);
                    map.insert("500".to_string(), (i * 20) as i64);
                    map
                },
                measured_at: DateTime::from_timestamp(1640995200 + i as i64, 0)
                    .unwrap()
                    .naive_utc(),
            };
            metrics.push(metric);
        }

        SystemData {
            logs,
            users,
            metrics,
            system_info: {
                let mut map = HashMap::new();
                for i in 1..=20 {
                    map.insert(format!("info_{}", i), generate_random_string(50));
                }
                map
            },
        }
    }
}

// Conversion functions for Serde
impl From<ForyLogEntry> for SerdeLogEntry {
    fn from(f: ForyLogEntry) -> Self {
        SerdeLogEntry {
            id: f.id,
            level: f.level,
            message: f.message,
            service: f.service,
            timestamp: f.timestamp.and_utc(),
            context: f.context,
            tags: f.tags,
            duration_ms: f.duration_ms,
        }
    }
}

impl From<ForyUserProfile> for SerdeUserProfile {
    fn from(f: ForyUserProfile) -> Self {
        SerdeUserProfile {
            user_id: f.user_id,
            username: f.username,
            email: f.email,
            preferences: f.preferences,
            permissions: f.permissions,
            last_login: f.last_login.and_utc(),
            is_active: f.is_active,
        }
    }
}

impl From<ForyAPIMetrics> for SerdeAPIMetrics {
    fn from(f: ForyAPIMetrics) -> Self {
        SerdeAPIMetrics {
            endpoint: f.endpoint,
            request_count: f.request_count,
            avg_response_time: f.avg_response_time,
            error_count: f.error_count,
            status_codes: f.status_codes,
            measured_at: f.measured_at.and_utc(),
        }
    }
}

impl From<SystemData> for SerdeSystemData {
    fn from(f: SystemData) -> Self {
        SerdeSystemData {
            logs: f.logs.into_iter().map(|l| l.into()).collect(),
            users: f.users.into_iter().map(|u| u.into()).collect(),
            metrics: f.metrics.into_iter().map(|m| m.into()).collect(),
            system_info: f.system_info,
        }
    }
}

// Reverse conversions from Serde to Fory
impl From<SerdeLogEntry> for ForyLogEntry {
    fn from(s: SerdeLogEntry) -> Self {
        ForyLogEntry {
            id: s.id,
            level: s.level,
            message: s.message,
            service: s.service,
            timestamp: s.timestamp.naive_utc(),
            context: s.context,
            tags: s.tags,
            duration_ms: s.duration_ms,
        }
    }
}

impl From<SerdeUserProfile> for ForyUserProfile {
    fn from(s: SerdeUserProfile) -> Self {
        ForyUserProfile {
            user_id: s.user_id,
            username: s.username,
            email: s.email,
            preferences: s.preferences,
            permissions: s.permissions,
            last_login: s.last_login.naive_utc(),
            is_active: s.is_active,
        }
    }
}

impl From<SerdeAPIMetrics> for ForyAPIMetrics {
    fn from(s: SerdeAPIMetrics) -> Self {
        ForyAPIMetrics {
            endpoint: s.endpoint,
            request_count: s.request_count,
            avg_response_time: s.avg_response_time,
            error_count: s.error_count,
            status_codes: s.status_codes,
            measured_at: s.measured_at.naive_utc(),
        }
    }
}

impl From<SerdeSystemData> for SystemData {
    fn from(s: SerdeSystemData) -> Self {
        SystemData {
            logs: s.logs.into_iter().map(|l| l.into()).collect(),
            users: s.users.into_iter().map(|u| u.into()).collect(),
            metrics: s.metrics.into_iter().map(|m| m.into()).collect(),
            system_info: s.system_info,
        }
    }
}
