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
package org.apache.hadoop.hdfs.server.namenode;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.Writer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.IOUtils;

import com.google.common.base.Preconditions;

/**
 * A double-buffer for edits. New edits are written into the first buffer
 * while the second is available to be flushed. Each time the double-buffer
 * is flushed, the two internal buffers are swapped. This allows edits
 * to progress concurrently to flushes without allocating new buffers each
 * time.
 * 内存双缓冲
 * 新的edits log是写入到第一个buffer缓冲区里面去的，他里面有两个buffer缓冲区的
 * 第二个buffer缓冲区是用来刷新内存数据到磁盘上或者网络中去的
 * 每次双缓冲区刷新的时候（第二个buffer缓冲区被刷新到磁盘或者网络），两个buffer缓冲区会被交换一下
 * 这个可以允许edits log持续写入内存缓冲，还可以同时将内存缓冲中的数据刷新到磁盘或者网络中去
 *
 *
 */
@InterfaceAudience.Private
public class EditsDoubleBuffer {

  /**
   *  里面放了两个缓冲区
   *
   */
  private TxnBuffer bufCurrent; // current buffer for writing
  private TxnBuffer bufReady; // buffer ready for flushing
  private final int initBufferSize;



  public EditsDoubleBuffer(int defaultBufferSize) {
    initBufferSize = defaultBufferSize;
    bufCurrent = new TxnBuffer(initBufferSize);
    bufReady = new TxnBuffer(initBufferSize);

  }
    
  public void writeOp(FSEditLogOp op) throws IOException {
    // 只是写到双缓冲中的一个缓冲区而已哦
    bufCurrent.writeOp(op);
  }

  public void writeRaw(byte[] bytes, int offset, int length) throws IOException {
    bufCurrent.write(bytes, offset, length);
  }
  
  public void close() throws IOException {
    Preconditions.checkNotNull(bufCurrent);
    Preconditions.checkNotNull(bufReady);

    int bufSize = bufCurrent.size();
    if (bufSize != 0) {
      throw new IOException("FSEditStream has " + bufSize
          + " bytes still to be flushed and cannot be closed.");
    }

    IOUtils.cleanup(null, bufCurrent, bufReady);
    bufCurrent = bufReady = null;
  }

  /**
   * 交换两个缓冲区
   */
  public void setReadyToFlush() {
    assert isFlushed() : "previous data not flushed yet";
    TxnBuffer tmp = bufReady;
    bufReady = bufCurrent;
    bufCurrent = tmp;
  }
  
  /**
   * Writes the content of the "ready" buffer to the given output stream,
   * and resets it. Does not swap any buffers.
   */
  public void flushTo(OutputStream out) throws IOException {
    bufReady.writeTo(out); // write data to file
    bufReady.reset(); // erase all data in the buffer
  }
  
  public boolean shouldForceSync() {
    return bufCurrent.size() >= initBufferSize;
  }

  DataOutputBuffer getReadyBuf() {
    return bufReady;
  }
  
  DataOutputBuffer getCurrentBuf() {
    return bufCurrent;
  }

  public boolean isFlushed() {
    return bufReady.size() == 0;
  }

  public int countBufferedBytes() {
    return bufReady.size() + bufCurrent.size();
  }

  /**
   * @return the transaction ID of the first transaction ready to be flushed 
   */
  public long getFirstReadyTxId() {
    assert bufReady.firstTxId > 0;
    return bufReady.firstTxId;
  }

  /**
   * @return the number of transactions that are ready to be flushed
   */
  public int countReadyTxns() {
    return bufReady.numTxns;
  }

  /**
   * @return the number of bytes that are ready to be flushed
   */
  public int countReadyBytes() {
    return bufReady.size();
  }
  
  private static class TxnBuffer extends DataOutputBuffer {
    // 缓冲区第一个 txid
    long firstTxId;
    // 缓冲区总共的edits log数量
    int numTxns;
    private final Writer writer;
    
    public TxnBuffer(int initBufferSize) {
      super(initBufferSize);
      writer = new FSEditLogOp.Writer(this);
      reset();
    }

    public void writeOp(FSEditLogOp op) throws IOException {
      if (firstTxId == HdfsConstants.INVALID_TXID) {
        firstTxId = op.txid;
      } else {
        assert op.txid > firstTxId;
      }
      writer.writeOp(op);
      numTxns++;
    }
    
    @Override
    public DataOutputBuffer reset() {
      super.reset();
      firstTxId = HdfsConstants.INVALID_TXID;
      numTxns = 0;
      return this;
    }
  }

}
