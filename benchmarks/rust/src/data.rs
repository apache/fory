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

use fory::{Error, Fory};
use fory_derive::ForyObject;

const LIST_SIZE: usize = 5;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DataKind {
    Struct,
    Sample,
    MediaContent,
    StructList,
    SampleList,
    MediaContentList,
}

impl DataKind {
    pub const ALL: [Self; 6] = [
        Self::Struct,
        Self::Sample,
        Self::MediaContent,
        Self::StructList,
        Self::SampleList,
        Self::MediaContentList,
    ];

    pub fn group_name(self) -> &'static str {
        match self {
            Self::Struct => "struct",
            Self::Sample => "sample",
            Self::MediaContent => "mediacontent",
            Self::StructList => "structlist",
            Self::SampleList => "samplelist",
            Self::MediaContentList => "mediacontentlist",
        }
    }

    pub fn display_name(self) -> &'static str {
        match self {
            Self::Struct => "Struct",
            Self::Sample => "Sample",
            Self::MediaContent => "MediaContent",
            Self::StructList => "StructList",
            Self::SampleList => "SampleList",
            Self::MediaContentList => "MediaContentList",
        }
    }
}

pub trait BenchmarkCase: Clone + PartialEq + Sized + 'static {
    const KIND: DataKind;

    fn create() -> Self;
}

#[derive(Debug, Clone, PartialEq, Eq, ForyObject)]
pub struct NumericStruct {
    #[fory(id = 1)]
    pub f1: i32,
    #[fory(id = 2)]
    pub f2: i32,
    #[fory(id = 3)]
    pub f3: i32,
    #[fory(id = 4)]
    pub f4: i32,
    #[fory(id = 5)]
    pub f5: i32,
    #[fory(id = 6)]
    pub f6: i32,
    #[fory(id = 7)]
    pub f7: i32,
    #[fory(id = 8)]
    pub f8: i32,
}

#[derive(Debug, Clone, PartialEq, ForyObject)]
pub struct Sample {
    #[fory(id = 1)]
    pub int_value: i32,
    #[fory(id = 2)]
    pub long_value: i64,
    #[fory(id = 3)]
    pub float_value: f32,
    #[fory(id = 4)]
    pub double_value: f64,
    #[fory(id = 5)]
    pub short_value: i32,
    #[fory(id = 6)]
    pub char_value: i32,
    #[fory(id = 7)]
    pub boolean_value: bool,
    #[fory(id = 8)]
    pub int_value_boxed: i32,
    #[fory(id = 9)]
    pub long_value_boxed: i64,
    #[fory(id = 10)]
    pub float_value_boxed: f32,
    #[fory(id = 11)]
    pub double_value_boxed: f64,
    #[fory(id = 12)]
    pub short_value_boxed: i32,
    #[fory(id = 13)]
    pub char_value_boxed: i32,
    #[fory(id = 14)]
    pub boolean_value_boxed: bool,
    #[fory(id = 15)]
    pub int_array: Vec<i32>,
    #[fory(id = 16)]
    pub long_array: Vec<i64>,
    #[fory(id = 17)]
    pub float_array: Vec<f32>,
    #[fory(id = 18)]
    pub double_array: Vec<f64>,
    #[fory(id = 19)]
    pub short_array: Vec<i32>,
    #[fory(id = 20)]
    pub char_array: Vec<i32>,
    #[fory(id = 21)]
    pub boolean_array: Vec<bool>,
    #[fory(id = 22)]
    pub string: String,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, ForyObject)]
#[repr(i32)]
pub enum Player {
    #[default]
    Java = 0,
    Flash = 1,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, ForyObject)]
#[repr(i32)]
pub enum Size {
    #[default]
    Small = 0,
    Large = 1,
}

#[derive(Debug, Clone, PartialEq, Eq, ForyObject)]
pub struct Media {
    #[fory(id = 1)]
    pub uri: String,
    #[fory(id = 2)]
    pub title: String,
    #[fory(id = 3)]
    pub width: i32,
    #[fory(id = 4)]
    pub height: i32,
    #[fory(id = 5)]
    pub format: String,
    #[fory(id = 6)]
    pub duration: i64,
    #[fory(id = 7)]
    pub size: i64,
    #[fory(id = 8)]
    pub bitrate: i32,
    #[fory(id = 9)]
    pub has_bitrate: bool,
    #[fory(id = 10)]
    pub persons: Vec<String>,
    #[fory(id = 11)]
    pub player: Player,
    #[fory(id = 12)]
    pub copyright: String,
}

#[derive(Debug, Clone, PartialEq, Eq, ForyObject)]
pub struct Image {
    #[fory(id = 1)]
    pub uri: String,
    #[fory(id = 2)]
    pub title: String,
    #[fory(id = 3)]
    pub width: i32,
    #[fory(id = 4)]
    pub height: i32,
    #[fory(id = 5)]
    pub size: Size,
}

#[derive(Debug, Clone, PartialEq, Eq, ForyObject)]
pub struct MediaContent {
    #[fory(id = 1)]
    pub media: Media,
    #[fory(id = 2)]
    pub images: Vec<Image>,
}

#[derive(Debug, Clone, PartialEq, Eq, ForyObject)]
pub struct StructList {
    #[fory(id = 1)]
    pub struct_list: Vec<NumericStruct>,
}

#[derive(Debug, Clone, PartialEq, ForyObject)]
pub struct SampleList {
    #[fory(id = 1)]
    pub sample_list: Vec<Sample>,
}

#[derive(Debug, Clone, PartialEq, Eq, ForyObject)]
pub struct MediaContentList {
    #[fory(id = 1)]
    pub media_content_list: Vec<MediaContent>,
}

impl BenchmarkCase for NumericStruct {
    const KIND: DataKind = DataKind::Struct;

    fn create() -> Self {
        create_numeric_struct()
    }
}

impl BenchmarkCase for Sample {
    const KIND: DataKind = DataKind::Sample;

    fn create() -> Self {
        create_sample()
    }
}

impl BenchmarkCase for MediaContent {
    const KIND: DataKind = DataKind::MediaContent;

    fn create() -> Self {
        create_media_content()
    }
}

impl BenchmarkCase for StructList {
    const KIND: DataKind = DataKind::StructList;

    fn create() -> Self {
        StructList {
            struct_list: (0..LIST_SIZE).map(|_| create_numeric_struct()).collect(),
        }
    }
}

impl BenchmarkCase for SampleList {
    const KIND: DataKind = DataKind::SampleList;

    fn create() -> Self {
        SampleList {
            sample_list: (0..LIST_SIZE).map(|_| create_sample()).collect(),
        }
    }
}

impl BenchmarkCase for MediaContentList {
    const KIND: DataKind = DataKind::MediaContentList;

    fn create() -> Self {
        MediaContentList {
            media_content_list: (0..LIST_SIZE).map(|_| create_media_content()).collect(),
        }
    }
}

pub fn register_fory_types(fory: &mut Fory) -> Result<(), Error> {
    fory.register::<NumericStruct>(1)?;
    fory.register::<Sample>(2)?;
    fory.register::<Media>(3)?;
    fory.register::<Image>(4)?;
    fory.register::<MediaContent>(5)?;
    fory.register::<Player>(6)?;
    fory.register::<Size>(7)?;
    fory.register::<StructList>(8)?;
    fory.register::<SampleList>(9)?;
    fory.register::<MediaContentList>(10)?;
    Ok(())
}

fn create_numeric_struct() -> NumericStruct {
    NumericStruct {
        f1: -12_345,
        f2: 987_654_321,
        f3: -31_415,
        f4: 27_182_818,
        f5: -32_000,
        f6: 1_000_000,
        f7: -999_999_999,
        f8: 42,
    }
}

fn create_sample() -> Sample {
    Sample {
        int_value: 123,
        long_value: 1_230_000,
        float_value: 12.345,
        double_value: 1.234_567,
        short_value: 12_345,
        char_value: '!' as i32,
        boolean_value: true,
        int_value_boxed: 321,
        long_value_boxed: 3_210_000,
        float_value_boxed: 54.321,
        double_value_boxed: 7.654_321,
        short_value_boxed: 32_100,
        char_value_boxed: '$' as i32,
        boolean_value_boxed: false,
        int_array: vec![-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
        long_array: vec![
            -123_400, -12_300, -1200, -100, 0, 100, 1200, 12_300, 123_400,
        ],
        float_array: vec![-12.34, -12.3, -12.0, -1.0, 0.0, 1.0, 12.0, 12.3, 12.34],
        double_array: vec![-1.234, -1.23, -12.0, -1.0, 0.0, 1.0, 12.0, 1.23, 1.234],
        short_array: vec![-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
        char_array: vec![
            'a' as i32, 's' as i32, 'd' as i32, 'f' as i32, 'A' as i32, 'S' as i32, 'D' as i32,
            'F' as i32,
        ],
        boolean_array: vec![true, false, false, true],
        string: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".to_string(),
    }
}

fn create_media_content() -> MediaContent {
    MediaContent {
        media: Media {
            uri: "http://javaone.com/keynote.ogg".to_string(),
            title: String::new(),
            width: 641,
            height: 481,
            format: "video/theora\u{1234}".to_string(),
            duration: 18_000_001,
            size: 58_982_401,
            bitrate: 0,
            has_bitrate: false,
            persons: vec!["Bill Gates, Jr.".to_string(), "Steven Jobs".to_string()],
            player: Player::Flash,
            copyright: "Copyright (c) 2009, Scooby Dooby Doo".to_string(),
        },
        images: vec![
            Image {
                uri: "http://javaone.com/keynote_huge.jpg".to_string(),
                title: "Javaone Keynote\u{1234}".to_string(),
                width: 32_000,
                height: 24_000,
                size: Size::Large,
            },
            Image {
                uri: "http://javaone.com/keynote_large.jpg".to_string(),
                title: String::new(),
                width: 1024,
                height: 768,
                size: Size::Large,
            },
            Image {
                uri: "http://javaone.com/keynote_small.jpg".to_string(),
                title: String::new(),
                width: 320,
                height: 240,
                size: Size::Small,
            },
        ],
    }
}
