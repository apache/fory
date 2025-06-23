using System;
using Fury.Collections;

namespace Fury.Context;

internal sealed class FrameStack<TFrame>
    where TFrame : class, new()
{
    private readonly SpannableList<TFrame> _frames = [];

    private int _frameCount;
    private int _currentFrameIndex = -1;

    /// <summary>
    /// Get the current frame. When resuming serialization or deserialization, the current frame may not be the last frame.
    /// </summary>
    public TFrame CurrentFrame => _frames[_currentFrameIndex];
    public bool IsCurrentTheLastFrame => _currentFrameIndex == _frameCount - 1;

    public void MoveNext()
    {
        _currentFrameIndex++;
        _frameCount = Math.Max(_frameCount, _currentFrameIndex + 1);
        if (_frames.Count < _frameCount)
        {
            _frames.Add(new TFrame());
        }
    }

    public void MoveLast()
    {
        _currentFrameIndex--;
    }

    public TFrame PopFrame()
    {
        _frameCount--;
        return _frames[_currentFrameIndex--];
    }

    public void Reset()
    {
        _currentFrameIndex = -1;
        _frameCount = 0;
    }

    public ReadOnlySpan<TFrame> Frames => _frames.AsSpan().Slice(_frameCount);
}
