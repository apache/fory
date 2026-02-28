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

use criterion::{criterion_group, criterion_main, Criterion};
use fory::Fory;
use fory_derive::ForyObject;
use std::hint::black_box;
use std::sync::Arc;
use std::thread;

#[cfg(feature = "profiling")]
use pprof::criterion::{Output, PProfProfiler};

#[derive(Debug, ForyObject)]
pub struct UserSessionMetrics {
    #[fory(id = 0)]
    pub request_count: u64,
    #[fory(id = 1)]
    pub unique_ip_count: u64,
    #[fory(id = 2)]
    pub unique_user_agent_count: u64,
    #[fory(id = 3)]
    pub unique_url_count: u64,
    #[fory(id = 4)]
    pub unique_resource_count: u64,
    #[fory(id = 5)]
    pub active_duration_secs: u64,
    #[fory(id = 6)]
    pub first_seen_time: u64,
    #[fory(id = 7)]
    pub last_seen_time: u64,
    #[fory(id = 8)]
    pub updated_at: u64,
}

fn create_fory() -> Fory {
    let mut fory = Fory::default();
    fory.register::<UserSessionMetrics>(2)
        .expect("register UserSessionMetrics");
    fory
}

fn sample_metrics() -> UserSessionMetrics {
    UserSessionMetrics {
        request_count: 256,
        unique_ip_count: 32,
        unique_user_agent_count: 12,
        unique_url_count: 64,
        unique_resource_count: 48,
        active_duration_secs: 90,
        first_seen_time: 1_699_999_900_000,
        last_seen_time: 1_700_000_000_000,
        updated_at: 1_700_000_000_000,
    }
}

fn bench_threaded_serialization(c: &mut Criterion) {
    const ITERATIONS: usize = 200_000;
    const THREAD_COUNT: usize = 8;

    let mut group = c.benchmark_group("ThreadedSerialization");

    // Single-thread baseline
    let fory = create_fory();
    let value = sample_metrics();
    group.bench_function("single_thread", |b| {
        b.iter(|| {
            for _ in 0..ITERATIONS {
                black_box(fory.serialize(black_box(&value)).unwrap());
            }
        });
    });

    // Multi-threaded with shared Fory instance
    let shared_fory = Arc::new(create_fory());
    let shared_value = Arc::new(sample_metrics());
    group.bench_function("8_threads_shared_fory", |b| {
        b.iter(|| {
            thread::scope(|s| {
                for _ in 0..THREAD_COUNT {
                    let fory = Arc::clone(&shared_fory);
                    let value = Arc::clone(&shared_value);
                    s.spawn(move || {
                        for _ in 0..(ITERATIONS / THREAD_COUNT) {
                            black_box(fory.serialize(value.as_ref()).unwrap());
                        }
                    });
                }
            });
        });
    });

    group.finish();
}

#[cfg(feature = "profiling")]
criterion_group! {
    name = benches;
    config = Criterion::default().with_profiler(PProfProfiler::new(100, Output::Flamegraph(None)));
    targets = bench_threaded_serialization
}

#[cfg(not(feature = "profiling"))]
criterion_group!(benches, bench_threaded_serialization);

criterion_main!(benches);
