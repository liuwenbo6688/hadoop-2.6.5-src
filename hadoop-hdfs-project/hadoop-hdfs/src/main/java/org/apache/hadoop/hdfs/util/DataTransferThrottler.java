/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.util;

import static org.apache.hadoop.util.Time.monotonicNow;

/** 
 * a class to throttle the data transfers.
 * This class is thread safe. It can be shared by multiple threads.
 * The parameter bandwidthPerSec specifies the total bandwidth shared by
 * threads.
 */
public class DataTransferThrottler {
  private final long period;          // period over which bw is imposed
  // period*3
  private final long periodExtension; // Max period over which bw accumulates.
  private long bytesPerPeriod;  // total number of bytes can be sent in each period
  private long curPeriodStart;  // current period starting time
  // 当前窗口内（500ms），还允许传输多少数据
  private long curReserve;      // remaining bytes can be sent in the period
  private long bytesAlreadyUsed;

  /** Constructor 
   * @param bandwidthPerSec bandwidth allowed in bytes per second. 
   */
  public DataTransferThrottler(long bandwidthPerSec) {
    this(500, bandwidthPerSec);  // by default throttling period is 500ms 
  }

  /**
   * Constructor
   * @param period in milliseconds. Bandwidth is enforced over this
   *        period.
   * @param bandwidthPerSec bandwidth allowed in bytes per second. 
   */
  public DataTransferThrottler(long period, long bandwidthPerSec) {
    this.curPeriodStart = monotonicNow();
    this.period = period;
    // 每一个 Period（默认500ms） 周期传输的 字节
    this.curReserve = this.bytesPerPeriod = bandwidthPerSec*period/1000;
    // 默认 1500ms
    this.periodExtension = period*3;
  }

  /**
   * @return current throttle bandwidth in bytes per second.
   */
  public synchronized long getBandwidth() {
    return bytesPerPeriod*1000/period;
  }
  
  /**
   * Sets throttle bandwidth. This takes affect latest by the end of current
   * period.
   */
  public synchronized void setBandwidth(long bytesPerSecond) {
    if ( bytesPerSecond <= 0 ) {
      throw new IllegalArgumentException("" + bytesPerSecond);
    }
    bytesPerPeriod = bytesPerSecond*period/1000;
  }
  
  /** Given the numOfBytes sent/received since last time throttle was called,
   * make the current thread sleep if I/O rate is too fast
   * compared to the given bandwidth.
   *
   * @param numOfBytes
   *     number of bytes sent/received since last time throttle was called
   */
  public synchronized void throttle(long numOfBytes) {
    throttle(numOfBytes, null);
  }

  /** Given the numOfBytes sent/received since last time throttle was called,
   * make the current thread sleep if I/O rate is too fast
   * compared to the given bandwidth.  Allows for optional external cancelation.
   *
   * @param numOfBytes
   *     number of bytes sent/received since last time throttle was called
   * @param canceler
   *     optional canceler to check for abort of throttle
   */
  public synchronized void throttle(long numOfBytes, Canceler canceler) {
    if ( numOfBytes <= 0 ) {
      return;
    }
    // 当前窗口内（500ms），还允许传输多少数据
    curReserve -= numOfBytes;
    // 累计窗口内，传输了多少数据
    bytesAlreadyUsed += numOfBytes;

    while (curReserve <= 0) {
      // 达到了窗口期内限流的阈值
      if (canceler != null && canceler.isCancelled()) {
        return;
      }
      long now = monotonicNow();
      long curPeriodEnd = curPeriodStart + period;

      if ( now < curPeriodEnd ) {
        // Wait for next period so that curReserve can be increased.
        try {
          // 等待一段时间
          wait( curPeriodEnd - now );
        } catch (InterruptedException e) {
          // Abort throttle and reset interrupted status to make sure other
          // interrupt handling higher in the call stack executes.
          Thread.currentThread().interrupt();
          break;
        }
      } else if ( now <  (curPeriodStart + periodExtension)) {
        curPeriodStart = curPeriodEnd;
        curReserve += bytesPerPeriod;
      } else {
        // discard the prev period. Throttler might not have
        // been used for a long time.
        curPeriodStart = now;
        curReserve = bytesPerPeriod - bytesAlreadyUsed;
      }
    }

    bytesAlreadyUsed -= numOfBytes;
  }
}
