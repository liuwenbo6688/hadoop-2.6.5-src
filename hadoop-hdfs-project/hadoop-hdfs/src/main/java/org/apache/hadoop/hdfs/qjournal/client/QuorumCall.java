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
package org.apache.hadoop.hdfs.qjournal.client;

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.util.Time;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;

/**
 * Represents a set of calls for which a quorum of results is needed.
 * @param <KEY> a key used to identify each of the outgoing calls
 * @param <RESULT> the type of the call result
 */
class QuorumCall<KEY, RESULT> {
  private final Map<KEY, RESULT> successes = Maps.newHashMap();
  private final Map<KEY, Throwable> exceptions = Maps.newHashMap();

  /**
   * Interval, in milliseconds, at which a log message will be made
   * while waiting for a quorum call.
   */
  private static final int WAIT_PROGRESS_INTERVAL_MILLIS = 1000;
  
  /**
   * Start logging messages at INFO level periodically after waiting for
   * this fraction of the configured timeout for any call.
   */
  private static final float WAIT_PROGRESS_INFO_THRESHOLD = 0.3f;
  /**
   * Start logging messages at WARN level after waiting for this
   * fraction of the configured timeout for any call.
   */
  private static final float WAIT_PROGRESS_WARN_THRESHOLD = 0.7f;
  
  static <KEY, RESULT> QuorumCall<KEY, RESULT> create(
      Map<KEY, ? extends ListenableFuture<RESULT>> calls) {
    final QuorumCall<KEY, RESULT> qr = new QuorumCall<KEY, RESULT>();
    for (final Entry<KEY, ? extends ListenableFuture<RESULT>> e : calls.entrySet()) {
      Preconditions.checkArgument(e.getValue() != null,
          "null future for key: " + e.getKey());
      Futures.addCallback(e.getValue(), new FutureCallback<RESULT>() {
        @Override
        public void onFailure(Throwable t) {
          qr.addException(e.getKey(), t);
        }

        @Override
        public void onSuccess(RESULT res) {
          qr.addResult(e.getKey(), res);
        }
      });
    }
    return qr;
  }
  
  private QuorumCall() {
    // Only instantiated from factory method above
  }
  
  /**
   * Wait for the quorum to achieve a certain number of responses.
   * 
   * Note that, even after this returns, more responses may arrive,
   * causing the return value of other methods in this class to change.
   *
   * @param minResponses return as soon as this many responses have been
   * received, regardless of whether they are successes or exceptions
   * @param minSuccesses return as soon as this many successful (non-exception)
   * responses have been received
   * @param maxExceptions return as soon as this many exception responses
   * have been received. Pass 0 to return immediately if any exception is
   * received.
   * @param millis the number of milliseconds to wait for
   * @throws InterruptedException if the thread is interrupted while waiting
   * @throws TimeoutException if the specified timeout elapses before
   * achieving the desired conditions
   */
  public synchronized void waitFor(
      int minResponses,  // 3
      int minSuccesses,  // (3/2) + 1= 2
      int maxExceptions, // (3/2) + 1= 2
      int millis, // 超时时间，默认20s
      String operationName)
      throws InterruptedException, TimeoutException {

    long st = Time.monotonicNow(); // 当前时间
    long nextLogTime = st + (long)(millis * WAIT_PROGRESS_INFO_THRESHOLD);  // 下一次打印日志的时间， 超时时间（20s） * 0.3 = 6s
    long et = st + millis; // 超时时间

    // 初始化计时器
    StopWatch stopWatch = new StopWatch();

    while (true) {

      stopWatch.start(); // 开启计时器

      checkAssertionErrors();

      /**
       * 满足返回的条件
       */
      if (minResponses > 0 && countResponses() >= minResponses) return;
      if (minSuccesses > 0 && countSuccesses() >= minSuccesses) return;
      if (maxExceptions >= 0 && countExceptions() > maxExceptions) return;

      /**
       * *************************************************
       * 如果在这里，jvm fullGC卡顿 30s，导致直接超时
       * *************************************************
       */
      // 第一个可能导致

      long now = Time.monotonicNow();
      
      if (now > nextLogTime) {
        /**
         * 如果一小段时间还没有达到返回条件，就输出一些日志信息
         */
        long waited = now - st;
        String msg = String.format(
            "Waited %s ms (timeout=%s ms) for a response for %s",
            waited, millis, operationName);
        if (!successes.isEmpty()) {
          msg += ". Succeeded so far: [" + Joiner.on(",").join(successes.keySet()) + "]";
        }
        if (!exceptions.isEmpty()) {
          msg += ". Exceptions so far: [" + getExceptionMapString() + "]";
        }
        if (successes.isEmpty() && exceptions.isEmpty()) {
          msg += ". No responses yet.";
        }
        if (waited > millis * WAIT_PROGRESS_WARN_THRESHOLD) {
          QuorumJournalManager.LOG.warn(msg);
        } else {
          QuorumJournalManager.LOG.info(msg);
        }
        nextLogTime = now + WAIT_PROGRESS_INTERVAL_MILLIS; // 后面每 1s 打印一次日志
      }

      /**
       * 超时，向上抛出异常
       */
      long rem = et - now;
      if (rem <= 0) {

        long elapsed = stopWatch.elapsed();
        if (elapsed > 5000L) {
          // 如果流逝时间大于jvm FullGC阈值(发生了full gc), 延长一下最终时间
          et = et + elapsed;
        } else {
          throw new TimeoutException();
        }
      }

      stopWatch.restart(); // 重新计时

      /**
       * 休眠等待一段时间
       */
      rem = Math.min(rem, nextLogTime - now);
      rem = Math.max(rem, 1);
      wait(rem);

      long elapsed =  stopWatch.elapsed();
      if(elapsed - rem > 5000L) {
        // 可能发生fullgc，延长一下截止时间
        et += (elapsed - rem);
      }

    }

  }

  /**
   * 时间流逝的计时器
   */
  class StopWatch {

    boolean isStarted;
    long startNanos;
    long elapsedNanos;

    public StopWatch() {
    }

    public StopWatch reset() {
      this.isStarted = false;
      this.elapsedNanos = 0;
      return this;
    }


    public StopWatch start() {
      this.isStarted = true;
      this.startNanos = System.nanoTime();
      return this;
    }

    public StopWatch restart() {
      this.reset()
          .start();
      return this;
    }

    public StopWatch stop() {
      this.isStarted = false;
      this.elapsedNanos += System.nanoTime() - this.startNanos;
      return this;
    }

    public long elapsed() {
      long resultElapsedNanos = 0L;

      if (isStarted) {
        resultElapsedNanos = System.nanoTime() - this.startNanos + elapsedNanos;
      } else {
        resultElapsedNanos = elapsedNanos;
      }
      return TimeUnit.MILLISECONDS.convert(resultElapsedNanos, TimeUnit.NANOSECONDS);
    }

  }


  /**
   * Check if any of the responses came back with an AssertionError.
   * If so, it re-throws it, even if there was a quorum of responses.
   * This code only runs if assertions are enabled for this class,
   * otherwise it should JIT itself away.
   * 
   * This is done since AssertionError indicates programmer confusion
   * rather than some kind of expected issue, and thus in the context
   * of test cases we'd like to actually fail the test case instead of
   * continuing through.
   */
  private synchronized void checkAssertionErrors() {
    boolean assertsEnabled = false;
    assert assertsEnabled = true; // sets to true if enabled
    if (assertsEnabled) {
      for (Throwable t : exceptions.values()) {
        if (t instanceof AssertionError) {
          throw (AssertionError)t;
        } else if (t instanceof RemoteException &&
            ((RemoteException)t).getClassName().equals(
                AssertionError.class.getName())) {
          throw new AssertionError(t);
        }
      }
    }
  }

  private synchronized void addResult(KEY k, RESULT res) {
    successes.put(k, res);
    notifyAll();
  }
  
  private synchronized void addException(KEY k, Throwable t) {
    exceptions.put(k, t);
    notifyAll();
  }
  
  /**
   * @return the total number of calls for which a response has been received,
   * regardless of whether it threw an exception or returned a successful
   * result.
   */
  public synchronized int countResponses() {
    return successes.size() + exceptions.size();
  }
  
  /**
   * @return the number of calls for which a non-exception response has been
   * received.
   */
  public synchronized int countSuccesses() {
    return successes.size();
  }
  
  /**
   * @return the number of calls for which an exception response has been
   * received.
   */
  public synchronized int countExceptions() {
    return exceptions.size();
  }

  /**
   * @return the map of successful responses. A copy is made such that this
   * map will not be further mutated, even if further results arrive for the
   * quorum.
   */
  public synchronized Map<KEY, RESULT> getResults() {
    return Maps.newHashMap(successes);
  }

  public synchronized void rethrowException(String msg) throws QuorumException {
    Preconditions.checkState(!exceptions.isEmpty());
    throw QuorumException.create(msg, successes, exceptions);
  }

  public static <K> String mapToString(
      Map<K, ? extends Message> map) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Map.Entry<K, ? extends Message> e : map.entrySet()) {
      if (!first) {
        sb.append("\n");
      }
      first = false;
      sb.append(e.getKey()).append(": ")
        .append(TextFormat.shortDebugString(e.getValue()));
    }
    return sb.toString();
  }

  /**
   * Return a string suitable for displaying to the user, containing
   * any exceptions that have been received so far.
   */
  private String getExceptionMapString() {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Map.Entry<KEY, Throwable> e : exceptions.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(e.getKey()).append(": ")
        .append(e.getValue().getLocalizedMessage());
    }
    return sb.toString();
  }
}
