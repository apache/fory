using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using Fury.Context;

namespace Fury.Serialization;

public abstract class TimeSpanSerializer<TTimeSpan> : AbstractSerializer<TTimeSpan>
    where TTimeSpan : notnull
{
    private bool _hasWrittenSecond;
    private bool _hasWrittenNanosecond;

    public sealed override void Reset()
    {
        _hasWrittenSecond = false;
        _hasWrittenNanosecond = false;
    }

    public sealed override bool Serialize(SerializationWriter writer, in TTimeSpan value)
    {
        var (seconds, nanoseconds) = GetSecondsAndNanoseconds(value);
        var writerRef = writer.ByrefWriter;
        if (!_hasWrittenSecond)
        {
            _hasWrittenSecond = writerRef.WriteUInt64(seconds);
            if (!_hasWrittenSecond)
            {
                return false;
            }
        }

        if (!_hasWrittenNanosecond)
        {
            _hasWrittenNanosecond = writerRef.WriteInt32(nanoseconds);
            if (!_hasWrittenNanosecond)
            {
                return false;
            }
        }

        return true;
    }

    protected abstract (long Seconds, int Nanoseconds) GetSecondsAndNanoseconds(in TTimeSpan value);
}

public abstract class TimeSpanDeserializer<TTimeSpan> : AbstractDeserializer<TTimeSpan>
    where TTimeSpan : notnull
{
    public sealed override object ReferenceableObject => ThrowInvalidOperationException_AcyclicType();

    private long? _seconds;
    private int? _nanoseconds;

    public sealed override void Reset()
    {
        _seconds = null;
        _nanoseconds = null;
    }

    public sealed override ReadValueResult<TTimeSpan> Deserialize(DeserializationReader reader)
    {
        var task = Deserialize(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public sealed override ValueTask<ReadValueResult<TTimeSpan>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    )
    {
        return Deserialize(reader, true, cancellationToken);
    }

    private async ValueTask<ReadValueResult<TTimeSpan>> Deserialize(
        DeserializationReader reader,
        bool isAsync,
        CancellationToken cancellationToken
    )
    {
        if (_seconds is null)
        {
            var secondResult = await reader.ReadInt64(isAsync, cancellationToken);
            if (!secondResult.IsSuccess)
            {
                return ReadValueResult<TTimeSpan>.Failed;
            }

            _seconds = secondResult.Value;
        }

        if (_nanoseconds is null)
        {
            var nanosecondResult = await reader.ReadInt32(isAsync, cancellationToken);
            if (!nanosecondResult.IsSuccess)
            {
                return ReadValueResult<TTimeSpan>.Failed;
            }

            _nanoseconds = nanosecondResult.Value;
        }

        return ReadValueResult<TTimeSpan>.FromValue(CreateTimeSpan(_seconds.Value, _nanoseconds.Value));
    }

    protected abstract TTimeSpan CreateTimeSpan(long seconds, int nanoseconds);
}

file static class TimeSpanHelper
{
    // Must be the same as TimeSpan.NanosecondsPerTick
    public const long NanosecondsPerTick = 100;
}

internal sealed class StandardTimeSpanSerializer : TimeSpanSerializer<TimeSpan>
{
    protected override (long Seconds, int Nanoseconds) GetSecondsAndNanoseconds(in TimeSpan value)
    {
        var seconds = value.Ticks / TimeSpan.TicksPerSecond;
        var nanoseconds = value.Ticks % TimeSpan.TicksPerSecond * TimeSpanHelper.NanosecondsPerTick;
        return (seconds, (int)nanoseconds);
    }
}

internal sealed class StandardTimeSpanDeserializer : TimeSpanDeserializer<TimeSpan>
{
    protected override TimeSpan CreateTimeSpan(long seconds, int nanoseconds)
    {
        var ticks = seconds * TimeSpan.TicksPerSecond + nanoseconds / TimeSpanHelper.NanosecondsPerTick;
        return new TimeSpan(ticks);
    }
}

public abstract class DateOnlySerializer<TDate> : AbstractSerializer<TDate>
    where TDate : notnull
{
    private bool _hasWrittenYear;
    private bool _hasWrittenMonth;
    private bool _hasWrittenDay;

    public sealed override void Reset()
    {
        _hasWrittenYear = false;
        _hasWrittenMonth = false;
        _hasWrittenDay = false;
    }

    public override bool Serialize(SerializationWriter writer, in TDate value)
    {
        var (year, month, day) = GetDateParts(value);
        var writerRef = writer.ByrefWriter;
        if (!_hasWrittenYear)
        {
            _hasWrittenYear = writerRef.WriteInt32(year);
            if (!_hasWrittenYear)
            {
                return false;
            }
        }

        if (!_hasWrittenMonth)
        {
            _hasWrittenMonth = writerRef.WriteUInt8(month);
            if (!_hasWrittenMonth)
            {
                return false;
            }
        }

        if (!_hasWrittenDay)
        {
            _hasWrittenDay = writerRef.WriteUInt8(day);
            if (!_hasWrittenDay)
            {
                return false;
            }
        }

        return true;
    }

    protected abstract (int Year, byte Month, byte Day) GetDateParts(in TDate value);
}

public abstract class DateOnlyDeserializer<TDate> : AbstractDeserializer<TDate>
    where TDate : notnull
{
    public sealed override object ReferenceableObject => ThrowInvalidOperationException_AcyclicType();

    private int? _year;
    private byte? _month;
    private byte? _day;

    public sealed override void Reset()
    {
        _year = null;
        _month = null;
        _day = null;
    }

    public sealed override ReadValueResult<TDate> Deserialize(DeserializationReader reader)
    {
        var task = Deserialize(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public sealed override ValueTask<ReadValueResult<TDate>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    )
    {
        return Deserialize(reader, true, cancellationToken);
    }

    private async ValueTask<ReadValueResult<TDate>> Deserialize(
        DeserializationReader reader,
        bool isAsync,
        CancellationToken cancellationToken
    )
    {
        if (_year is null)
        {
            var yearResult = await reader.ReadInt32(isAsync, cancellationToken);
            if (!yearResult.IsSuccess)
            {
                return ReadValueResult<TDate>.Failed;
            }

            _year = yearResult.Value;
        }

        if (_month is null)
        {
            var monthResult = await reader.ReadUInt8(isAsync, cancellationToken);
            if (!monthResult.IsSuccess)
            {
                return ReadValueResult<TDate>.Failed;
            }

            _month = monthResult.Value;
        }

        if (_day is null)
        {
            var dayResult = await reader.ReadUInt8(isAsync, cancellationToken);
            if (!dayResult.IsSuccess)
            {
                return ReadValueResult<TDate>.Failed;
            }

            _day = dayResult.Value;
        }

        return ReadValueResult<TDate>.FromValue(CreateDate(_year.Value, _month.Value, _day.Value));
    }

    protected abstract TDate CreateDate(int year, byte month, byte day);
}

#if NET6_0_OR_GREATER
internal sealed class StandardDateOnlySerializer : DateOnlySerializer<DateOnly>
{
    protected override (int Year, byte Month, byte Day) GetDateParts(in DateOnly value)
    {
        var year = value.Year;
        var month = (byte)value.Month;
        var day = (byte)value.Day;
        return (year, month, day);
    }
}

internal sealed class StandardDateOnlyDeserializer : DateOnlyDeserializer<DateOnly>
{
    protected override DateOnly CreateDate(int year, byte month, byte day)
    {
        return new DateOnly(year, month, day);
    }
}
#endif

public abstract class DateTimeSerializer<TDateTime> : AbstractSerializer<TDateTime>
    where TDateTime : notnull
{
    public sealed override void Reset() { }

    public sealed override bool Serialize(SerializationWriter writer, in TDateTime value)
    {
        var millisecond = GetMillisecond(value);
        return writer.WriteUInt64(millisecond);
    }

    protected abstract long GetMillisecond(in TDateTime value);
}

public abstract class DateTimeDeserializer<TDateTime> : AbstractDeserializer<TDateTime>
    where TDateTime : notnull
{
    public sealed override object ReferenceableObject => ThrowInvalidOperationException_AcyclicType();

    public sealed override void Reset() { }

    public sealed override ReadValueResult<TDateTime> Deserialize(DeserializationReader reader)
    {
        var task = Deserialize(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public sealed override ValueTask<ReadValueResult<TDateTime>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    )
    {
        return Deserialize(reader, true, cancellationToken);
    }

    private async ValueTask<ReadValueResult<TDateTime>> Deserialize(
        DeserializationReader reader,
        bool isAsync,
        CancellationToken cancellationToken
    )
    {
        var millisecondResult = await reader.ReadInt64(isAsync, cancellationToken);
        if (!millisecondResult.IsSuccess)
        {
            return ReadValueResult<TDateTime>.Failed;
        }

        return ReadValueResult<TDateTime>.FromValue(CreateDateTime(millisecondResult.Value));
    }

    protected abstract TDateTime CreateDateTime(long millisecond);
}

internal sealed class StandardDateTimeSerializer : DateTimeSerializer<DateTime>
{
    public static readonly StandardDateTimeSerializer Instance = new StandardDateTimeSerializer();

    private StandardDateTimeSerializer() { }

    protected override long GetMillisecond(in DateTime value)
    {
        return new DateTimeOffset(value.ToUniversalTime()).ToUnixTimeMilliseconds();
    }
}

internal sealed class StandardDateTimeDeserializer : DateTimeDeserializer<DateTime>
{
    public static readonly StandardDateTimeDeserializer Instance = new StandardDateTimeDeserializer();

    private StandardDateTimeDeserializer() { }

    protected override DateTime CreateDateTime(long millisecond)
    {
        return DateTimeOffset.FromUnixTimeMilliseconds(millisecond).UtcDateTime;
    }
}
