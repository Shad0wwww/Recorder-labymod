package com.magnus.mintmc.core.capture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Keeps the frames of the last seconds in memory, like the instant replay of Medal or ShadowPlay.
 * A clip is a copy of what is in here at that moment. Thread safe: the encoder adds frames, the
 * game thread takes snapshots.
 */
public class ReplayBuffer {

  /** A hard limit, so a long buffer at a high resolution can't take the game's memory. */
  private static final long MAX_BYTES = 1024L * 1024L * 1024L;

  private final ArrayDeque<EncodedFrame> frames = new ArrayDeque<>();
  private long bytes;

  public synchronized void add(EncodedFrame frame, long keepNanos) {
    EncodedFrame newest = this.frames.peekLast();
    if (newest != null && !newest.sameSize(frame.width(), frame.height())) {
      // The window was resized, a video can't change its size midway
      this.clear();
    }

    this.frames.addLast(frame);
    this.bytes += frame.jpeg().length;

    long oldest = frame.nanos() - keepNanos;
    EncodedFrame first;
    while ((first = this.frames.peekFirst()) != null
        && (first.nanos() < oldest || this.bytes > MAX_BYTES)) {
      this.frames.removeFirst();
      this.bytes -= first.jpeg().length;
    }
  }

  /**
   * @return the frames of the last {@code nanos}, oldest first
   */
  public synchronized List<EncodedFrame> snapshot(long nanos) {
    List<EncodedFrame> result = new ArrayList<>(this.frames.size());
    EncodedFrame newest = this.frames.peekLast();
    if (newest == null) {
      return result;
    }

    long oldest = newest.nanos() - nanos;
    Iterator<EncodedFrame> iterator = this.frames.iterator();
    while (iterator.hasNext()) {
      EncodedFrame frame = iterator.next();
      if (frame.nanos() >= oldest) {
        result.add(frame);
      }
    }

    return result;
  }

  public synchronized void clear() {
    this.frames.clear();
    this.bytes = 0;
  }

  public synchronized long bytes() {
    return this.bytes;
  }

  /**
   * @return how many seconds of gameplay the buffer holds right now
   */
  public synchronized int seconds() {
    EncodedFrame first = this.frames.peekFirst();
    EncodedFrame last = this.frames.peekLast();
    if (first == null || last == null) {
      return 0;
    }

    return (int) ((last.nanos() - first.nanos()) / 1_000_000_000L);
  }
}
