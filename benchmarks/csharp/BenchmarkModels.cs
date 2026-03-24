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

using Apache.Fory;
using MessagePack;
using ProtoBuf;

namespace Apache.Fory.Benchmarks.CSharp;

[ForyObject]
[MessagePackObject(keyAsPropertyName: true)]
[ProtoContract]
public sealed class NumericStruct
{
    [Field(Id = 1)]
    [ProtoMember(1)]
    public int F1 { get; set; }

    [Field(Id = 2)]
    [ProtoMember(2)]
    public int F2 { get; set; }

    [Field(Id = 3)]
    [ProtoMember(3)]
    public int F3 { get; set; }

    [Field(Id = 4)]
    [ProtoMember(4)]
    public int F4 { get; set; }

    [Field(Id = 5)]
    [ProtoMember(5)]
    public int F5 { get; set; }

    [Field(Id = 6)]
    [ProtoMember(6)]
    public int F6 { get; set; }

    [Field(Id = 7)]
    [ProtoMember(7)]
    public int F7 { get; set; }

    [Field(Id = 8)]
    [ProtoMember(8)]
    public int F8 { get; set; }
}

[ForyObject]
[MessagePackObject(keyAsPropertyName: true)]
[ProtoContract]
public sealed class StructList
{
    [Field(Id = 1)]
    [ProtoMember(1)]
    public List<NumericStruct> Values { get; set; } = [];
}

[ForyObject]
[MessagePackObject(keyAsPropertyName: true)]
[ProtoContract]
public sealed class Sample
{
    [Field(Id = 1)]
    [ProtoMember(1)]
    public int IntValue { get; set; }

    [Field(Id = 2)]
    [ProtoMember(2)]
    public long LongValue { get; set; }

    [Field(Id = 3)]
    [ProtoMember(3)]
    public float FloatValue { get; set; }

    [Field(Id = 4)]
    [ProtoMember(4)]
    public double DoubleValue { get; set; }

    [Field(Id = 5)]
    [ProtoMember(5)]
    public int ShortValue { get; set; }

    [Field(Id = 6)]
    [ProtoMember(6)]
    public int CharValue { get; set; }

    [Field(Id = 7)]
    [ProtoMember(7)]
    public bool BooleanValue { get; set; }

    [Field(Id = 8)]
    [ProtoMember(8)]
    public int IntValueBoxed { get; set; }

    [Field(Id = 9)]
    [ProtoMember(9)]
    public long LongValueBoxed { get; set; }

    [Field(Id = 10)]
    [ProtoMember(10)]
    public float FloatValueBoxed { get; set; }

    [Field(Id = 11)]
    [ProtoMember(11)]
    public double DoubleValueBoxed { get; set; }

    [Field(Id = 12)]
    [ProtoMember(12)]
    public int ShortValueBoxed { get; set; }

    [Field(Id = 13)]
    [ProtoMember(13)]
    public int CharValueBoxed { get; set; }

    [Field(Id = 14)]
    [ProtoMember(14)]
    public bool BooleanValueBoxed { get; set; }

    [Field(Id = 15)]
    [ProtoMember(15)]
    public int[] IntArray { get; set; } = [];

    [Field(Id = 16)]
    [ProtoMember(16)]
    public long[] LongArray { get; set; } = [];

    [Field(Id = 17)]
    [ProtoMember(17)]
    public float[] FloatArray { get; set; } = [];

    [Field(Id = 18)]
    [ProtoMember(18)]
    public double[] DoubleArray { get; set; } = [];

    [Field(Id = 19)]
    [ProtoMember(19)]
    public int[] ShortArray { get; set; } = [];

    [Field(Id = 20)]
    [ProtoMember(20)]
    public int[] CharArray { get; set; } = [];

    [Field(Id = 21)]
    [ProtoMember(21)]
    public bool[] BooleanArray { get; set; } = [];

    [Field(Id = 22)]
    [ProtoMember(22)]
    public string String { get; set; } = string.Empty;
}

[ForyObject]
[MessagePackObject(keyAsPropertyName: true)]
[ProtoContract]
public sealed class SampleList
{
    [Field(Id = 1)]
    [ProtoMember(1)]
    public List<Sample> Values { get; set; } = [];
}

[ForyObject]
[ProtoContract]
public enum Player
{
    [ProtoEnum]
    Java,
    [ProtoEnum]
    Flash,
}

[ForyObject]
[ProtoContract]
public enum MediaSize
{
    [ProtoEnum]
    Small,
    [ProtoEnum]
    Large,
}

[ForyObject]
[MessagePackObject(keyAsPropertyName: true)]
[ProtoContract]
public sealed class Media
{
    [Field(Id = 1)]
    [ProtoMember(1)]
    public string Uri { get; set; } = string.Empty;

    [Field(Id = 2)]
    [ProtoMember(2)]
    public string Title { get; set; } = string.Empty;

    [Field(Id = 3)]
    [ProtoMember(3)]
    public int Width { get; set; }

    [Field(Id = 4)]
    [ProtoMember(4)]
    public int Height { get; set; }

    [Field(Id = 5)]
    [ProtoMember(5)]
    public string Format { get; set; } = string.Empty;

    [Field(Id = 6)]
    [ProtoMember(6)]
    public long Duration { get; set; }

    [Field(Id = 7)]
    [ProtoMember(7)]
    public long Size { get; set; }

    [Field(Id = 8)]
    [ProtoMember(8)]
    public int Bitrate { get; set; }

    [Field(Id = 9)]
    [ProtoMember(9)]
    public bool HasBitrate { get; set; }

    [Field(Id = 10)]
    [ProtoMember(10)]
    public List<string> Persons { get; set; } = [];

    [Field(Id = 11)]
    [ProtoMember(11)]
    public Player Player { get; set; }

    [Field(Id = 12)]
    [ProtoMember(12)]
    public string Copyright { get; set; } = string.Empty;
}

[ForyObject]
[MessagePackObject(keyAsPropertyName: true)]
[ProtoContract]
public sealed class Image
{
    [Field(Id = 1)]
    [ProtoMember(1)]
    public string Uri { get; set; } = string.Empty;

    [Field(Id = 2)]
    [ProtoMember(2)]
    public string Title { get; set; } = string.Empty;

    [Field(Id = 3)]
    [ProtoMember(3)]
    public int Width { get; set; }

    [Field(Id = 4)]
    [ProtoMember(4)]
    public int Height { get; set; }

    [Field(Id = 5)]
    [ProtoMember(5)]
    public MediaSize Size { get; set; }
}

[ForyObject]
[MessagePackObject(keyAsPropertyName: true)]
[ProtoContract]
public sealed class MediaContent
{
    [Field(Id = 1)]
    [ProtoMember(1)]
    public Media Media { get; set; } = new();

    [Field(Id = 2)]
    [ProtoMember(2)]
    public List<Image> Images { get; set; } = [];
}

[ForyObject]
[MessagePackObject(keyAsPropertyName: true)]
[ProtoContract]
public sealed class MediaContentList
{
    [Field(Id = 1)]
    [ProtoMember(1)]
    public List<MediaContent> Values { get; set; } = [];
}

public static class BenchmarkDataFactory
{
    private const int ListSize = 5;

    public static NumericStruct CreateNumericStruct()
    {
        return new NumericStruct
        {
            F1 = -12345,
            F2 = 987654321,
            F3 = -31415,
            F4 = 27182818,
            F5 = -32000,
            F6 = 1000000,
            F7 = -999999999,
            F8 = 42,
        };
    }

    public static Sample CreateSample()
    {
        return new Sample
        {
            IntValue = 123,
            LongValue = 1230000,
            FloatValue = 12.345f,
            DoubleValue = 1.234567,
            ShortValue = 12345,
            CharValue = '!',
            BooleanValue = true,
            IntValueBoxed = 321,
            LongValueBoxed = 3210000,
            FloatValueBoxed = 54.321f,
            DoubleValueBoxed = 7.654321,
            ShortValueBoxed = 32100,
            CharValueBoxed = '$',
            BooleanValueBoxed = false,
            IntArray = [-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
            LongArray = [-123400, -12300, -1200, -100, 0, 100, 1200, 12300, 123400],
            FloatArray = [-12.34f, -12.3f, -12.0f, -1.0f, 0.0f, 1.0f, 12.0f, 12.3f, 12.34f],
            DoubleArray = [-1.234, -1.23, -12.0, -1.0, 0.0, 1.0, 12.0, 1.23, 1.234],
            ShortArray = [-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
            CharArray = ['a', 's', 'd', 'f', 'A', 'S', 'D', 'F'],
            BooleanArray = [true, false, false, true],
            String = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
        };
    }

    public static MediaContent CreateMediaContent()
    {
        return new MediaContent
        {
            Media = new Media
            {
                Uri = "http://javaone.com/keynote.ogg",
                Title = string.Empty,
                Width = 641,
                Height = 481,
                Format = "video/theora\u1234",
                Duration = 18_000_001,
                Size = 58_982_401,
                Bitrate = 0,
                HasBitrate = false,
                Persons = ["Bill Gates, Jr.", "Steven Jobs"],
                Player = Player.Flash,
                Copyright = "Copyright (c) 2009, Scooby Dooby Doo",
            },
            Images =
            [
                new Image
                {
                    Uri = "http://javaone.com/keynote_huge.jpg",
                    Title = "Javaone Keynote\u1234",
                    Width = 32_000,
                    Height = 24_000,
                    Size = MediaSize.Large,
                },
                new Image
                {
                    Uri = "http://javaone.com/keynote_large.jpg",
                    Title = string.Empty,
                    Width = 1_024,
                    Height = 768,
                    Size = MediaSize.Large,
                },
                new Image
                {
                    Uri = "http://javaone.com/keynote_small.jpg",
                    Title = string.Empty,
                    Width = 320,
                    Height = 240,
                    Size = MediaSize.Small,
                },
            ],
        };
    }

    public static StructList CreateStructList()
    {
        StructList list = new();
        for (int i = 0; i < ListSize; i++)
        {
            list.Values.Add(CreateNumericStruct());
        }

        return list;
    }

    public static SampleList CreateSampleList()
    {
        SampleList list = new();
        for (int i = 0; i < ListSize; i++)
        {
            list.Values.Add(CreateSample());
        }

        return list;
    }

    public static MediaContentList CreateMediaContentList()
    {
        MediaContentList list = new();
        for (int i = 0; i < ListSize; i++)
        {
            list.Values.Add(CreateMediaContent());
        }

        return list;
    }
}
