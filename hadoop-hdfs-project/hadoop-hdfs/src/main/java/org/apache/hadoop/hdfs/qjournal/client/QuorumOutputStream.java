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

import java.io.IOException;

import org.apache.hadoop.hdfs.server.namenode.EditLogOutputStream;
import org.apache.hadoop.hdfs.server.namenode.EditsDoubleBuffer;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp;
import org.apache.hadoop.io.DataOutputBuffer;

/**
 * EditLogOutputStream implementation that writes to a quorum of
 * remote journals.
 */
class QuorumOutputStream extends EditLogOutputStream {

  // loggers 是从QuorumJournalManager中传递过来的
  private final AsyncLoggerSet loggers;

  // 也是一个双缓冲
  private EditsDoubleBuffer buf;
  private final long segmentTxId;
  private final int writeTimeoutMs;

  public QuorumOutputStream(AsyncLoggerSet loggers,
      long txId, int outputBufferCapacity,
      int writeTimeoutMs) throws IOException {
    super();
    this.buf = new EditsDoubleBuffer(outputBufferCapacity);
    this.loggers = loggers;
    this.segmentTxId = txId;
    this.writeTimeoutMs = writeTimeoutMs;
  }

  @Override
  public void write(FSEditLogOp op) throws IOException {
    // 也是写到双缓冲中去
    buf.writeOp(op);
  }

  @Override
  public void writeRaw(byte[] bytes, int offset, int length) throws IOException {
    buf.writeRaw(bytes, offset, length);
  }

  @Override
  public void create(int layoutVersion) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() throws IOException {
    if (buf != null) {
      buf.close();
      buf = null;
    }
  }

  @Override
  public void abort() throws IOException {
    QuorumJournalManager.LOG.warn("Aborting " + this);
    buf = null;
    close();
  }

  @Override
  public void setReadyToFlush() throws IOException {
    buf.setReadyToFlush();
  }


  @Override
  protected void flushAndSync(boolean durable) throws IOException {
    int numReadyBytes = buf.countReadyBytes();
    if (numReadyBytes > 0) {
      int numReadyTxns = buf.countReadyTxns();
      long firstTxToFlush = buf.getFirstReadyTxId();

      assert numReadyTxns > 0;

      // Copy from our double-buffer into a new byte array. This is for
      // two reasons:
      // 1) The IPC code has no way of specifying to send only a slice of
      //    a larger array.
      // 2) because the calls to the underlying nodes are asynchronous, we
      //    need a defensive copy to avoid accidentally mutating the buffer
      //    before it is sent.
      /**
       * 一次性把buffer里的数据发送到journal node里面去
       * 因为是异步发送的，防止还没发送的时候，buffer里的数据被修改了
       * 所有先把buffer里的数据copy到一个新的字节数组里面去
       */
      // 下面两行代码就是把buf里的数据先写到一个新的DataOutputBuffer里面去
      DataOutputBuffer bufToSend = new DataOutputBuffer(numReadyBytes);
      buf.flushTo(bufToSend);

      assert bufToSend.getLength() == numReadyBytes;
      byte[] data = bufToSend.getData();// 新的字节数组
      assert data.length == bufToSend.getLength();

      /**
       * 通过 AsyncLoggerSet 组件到大多数的 journal node上去
       * AsyncLoggerSet loggers  是从QuorumJournalManager中传递过来的
       */
      QuorumCall<AsyncLogger, Void> qcall = loggers.sendEdits(
          segmentTxId, firstTxToFlush,
          numReadyTxns, data);

      // 必须等待大多数journal node成功从以后，阻塞住
      /**
       * 通过观察每个Future的结果，来实现quorum算法
       * 3个journal node，必须有 (3/2) + 1 = 2 个都推送成功了才可以
       * 5个journal node，必须有 (5/2) + 1 = 3 个都推送成功了才可以
       */
      loggers.waitForWriteQuorum(qcall, writeTimeoutMs, "sendEdits");
      
      // Since we successfully wrote this batch, let the loggers know. Any future
      // RPCs will thus let the loggers know of the most recent transaction, even
      // if a logger has fallen behind.
      loggers.setCommittedTxId(firstTxToFlush + numReadyTxns - 1);
    }
  }

  @Override
  public String generateReport() {
    StringBuilder sb = new StringBuilder();
    sb.append("Writing segment beginning at txid " + segmentTxId + ". \n");
    loggers.appendReport(sb);
    return sb.toString();
  }
  
  @Override
  public String toString() {
    return "QuorumOutputStream starting at txid " + segmentTxId;
  }
}
