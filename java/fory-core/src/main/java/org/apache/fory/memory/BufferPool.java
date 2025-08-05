package org.apache.fory.memory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A global, non-blocking, thread-safe byte array buffer pool.
 *
 * <p>This class provides direct access to pooled {@code byte[]} arrays to minimize allocation
 * overhead and GC pressure in high-performance applications.
 *
 * <p><strong>WARNING:</strong> This is a low-level utility. The consumer is solely responsible for
 * returning the borrowed buffer by calling {@link #release(byte[])} in a {@code finally} block.
 * Failure to do so will result in a buffer leak, eventually exhausting the pool.
 */
public final class BufferPool {

  /** Singleton instance for global access. */
  public static final BufferPool INSTANCE = new BufferPool();

  /**
   * Defines the pooling strategy (buffer size -> number of buffers). This map is made unmodifiable
   * for safety.
   */
  private static final Map<Integer, Integer> POOL_CONFIG;

  static {
    // This static initializer block is compatible with all Java versions, including Java 8.
    Map<Integer, Integer> config = new HashMap<>();
    // These sizes are chosen based on common network packet sizes and memory page alignment.
    config.put(512, 10);
    config.put(1024, 6);
    config.put(2048, 4); // 1KB, for small messages
    config.put(3072, 2); // 4KB, common page size
    config.put(4096, 1); // 16KB, for medium data chunks
    POOL_CONFIG = Collections.unmodifiableMap(config);
  }

  /**
   * The core data structure of the pool. Key: The size of the buffer arrays in a tier. Value: A
   * thread-safe queue holding the available buffers for that size.
   */
  private final Map<Integer, ConcurrentLinkedDeque<byte[]>> pool;

  /**
   * An array of the available buffer sizes, sorted in ascending order. This is used for efficient
   * lookup of the appropriate buffer tier.
   */
  private final int[] bufferSizes;

  /** Private constructor to enforce singleton pattern and initialize the pool. */
  private BufferPool() {
    this.pool = new ConcurrentHashMap<>();

    // Initialize the sorted array of buffer sizes for fast lookups.
    this.bufferSizes = POOL_CONFIG.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();

    // Pre-allocate all buffers and populate the pool.
    for (int size : bufferSizes) {
      ConcurrentLinkedDeque<byte[]> queue = new ConcurrentLinkedDeque<>();
      int count = POOL_CONFIG.get(size);
      for (int i = 0; i < count; i++) {
        queue.offer(new byte[size]);
      }
      this.pool.put(size, queue);
    }
  }

  /**
   * Borrows a {@code byte[]} from the pool, only searching for buffers large enough to satisfy the
   * request.
   *
   * @param requiredSize The minimum required capacity of the buffer in bytes.
   * @return An {@link Optional} containing a {@code byte[]}, or an empty Optional if no suitable
   *     buffer is available.
   */
  public byte[] borrow(int requiredSize) {
    return borrow(requiredSize, false);
  }

  /**
   * Borrows a {@code byte[]} from the pool with extended search options.
   *
   * <p>This is a non-blocking method. It first attempts to find the smallest available buffer that
   * is greater than or equal to the required size. If no such buffer is found and {@code
   * acceptSmaller} is true, it will then search downwards from the largest tier to find any
   * available buffer.
   *
   * @param requiredSize The minimum required capacity of the buffer in bytes.
   * @param acceptSmaller If true, the method will return a smaller buffer if no suitable large
   *     buffer is found.
   * @return An {@link Optional} containing a {@code byte[]}, or an empty Optional if the pool is
   *     exhausted.
   */
  public byte[] borrow(int requiredSize, boolean acceptSmaller) {
    // Use binary search to find the smallest suitable buffer tier.
    int searchResult = Arrays.binarySearch(bufferSizes, requiredSize);
    int startIndex = (searchResult >= 0) ? searchResult : (-(searchResult + 1));

    // First, iterate upwards from the best-fit size to find a suitable buffer.
    for (int i = startIndex; i < bufferSizes.length; i++) {
      int size = bufferSizes[i];
      ConcurrentLinkedDeque<byte[]> queue = pool.get(size);
      byte[] buffer = queue.poll(); // poll() is non-blocking.
      if (buffer != null) {
        return buffer;
      }
    }
    // If no suitable large buffer was found and the user accepts a smaller one...
    if (acceptSmaller) {
      // ...iterate downwards from the largest tier to find any available buffer.
      for (int i = bufferSizes.length - 1; i >= 0; i--) {
        int size = bufferSizes[i];
        ConcurrentLinkedDeque<byte[]> queue = pool.get(size);
        byte[] buffer = queue.poll();
        if (buffer != null) {
          return buffer;
        }
      }
    }
    // All suitable pools are empty, or the requested size is larger than any available tier.
    return null;
  }

  /**
   * Returns a borrowed {@code byte[]} to the pool.
   *
   * <p>It is critical for the consumer to call this method for every buffer borrowed to prevent
   * pool leaks.
   *
   * @param buffer The byte array to return to the pool. It must be an array that was previously
   *     borrowed from this pool.
   */
  public void release(byte[] buffer) {
    if (buffer == null) {
      return;
    }
    int size = buffer.length;
    ConcurrentLinkedDeque<byte[]> queue = pool.get(size);
    if (queue != null) {
      queue.offer(buffer);
    }
    // Note: In a production system, returning a buffer of an unknown size
    // might be logged to a proper logging framework. Here, we silently ignore it
    // for maximum performance.
  }

  /** Provides statistics about the current state of the pool for monitoring or debugging. */
  public void printStats() {
    System.out.println("--- BufferPool Stats ---");
    for (int size : bufferSizes) {
      System.out.printf("Tier Size %5d B: %d available%n", size, pool.get(size).size());
    }
    System.out.println("------------------------");
  }
}
