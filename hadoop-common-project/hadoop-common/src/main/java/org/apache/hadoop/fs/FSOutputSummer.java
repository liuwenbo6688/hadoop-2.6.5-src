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

package org.apache.hadoop.fs;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.util.DataChecksum;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Checksum;

/**
 * This is a generic output stream for generating checksums for
 * data before it is written to the underlying stream
 */
@InterfaceAudience.LimitedPrivate({"HDFS"})
@InterfaceStability.Unstable
abstract public class FSOutputSummer extends OutputStream {
  // data checksum
  private final DataChecksum sum;
  // internal buffer for storing data before it is checksumed

  // 缓冲
  // buf缓冲的大小 =  一个chunk512字节 * 9
  // 也就是这个buf可以写9个chunk
  private byte buf[];

  // internal buffer for storing checksum
  private byte checksum[];

  // 记录 buf数组 实际存储的有效字节数
  // The number of valid bytes in the buffer.
  private int count;
  
  // We want this value to be a multiple of 3 because the native code checksums
  // 3 chunks simultaneously. The chosen value of 9 strikes a balance between
  // limiting the number of JNI calls and flushing to the underlying stream
  // relatively frequently.
  private static final int BUFFER_NUM_CHUNKS = 9;
  
  protected FSOutputSummer(DataChecksum sum) {
    this.sum = sum;
    // buf缓冲的大小 =  一个chunk512字节 * 9
    this.buf = new byte[sum.getBytesPerChecksum() * BUFFER_NUM_CHUNKS];
    // 检验和的数组，也是能放9个校验和
    this.checksum = new byte[getChecksumSize() * BUFFER_NUM_CHUNKS];
    this.count = 0;
  }
  
  /* write the data chunk in <code>b</code> staring at <code>offset</code> with
   * a length of <code>len > 0</code>, and its checksum
   */
  protected abstract void writeChunk(byte[] b, int bOffset, int bLen,
      byte[] checksum, int checksumOffset, int checksumLen) throws IOException;
  
  /**
   * Check if the implementing OutputStream is closed and should no longer
   * accept writes. Implementations should do nothing if this stream is not
   * closed, and should throw an {@link IOException} if it is closed.
   * 
   * @throws IOException if this stream is already closed.
   */
  protected abstract void checkClosed() throws IOException;

  /** Write one byte */
  @Override
  public synchronized void write(int b) throws IOException {
    buf[count++] = (byte)b;
    if(count == buf.length) {
      flushBuffer();
    }
  }

  /**
   * Writes <code>len</code> bytes from the specified byte array 
   * starting at offset <code>off</code> and generate a checksum for
   * each data chunk.
   *
   * checksum，保证一个数据传输过程中不会发生破损，或者存储到磁盘以后不会发生破损
   *
   *
   * <p> This method stores bytes from the given array into this
   * stream's buffer before it gets checksumed. The buffer gets checksumed 
   * and flushed to the underlying output stream when all data 
   * in a checksum chunk are in the buffer.  If the buffer is empty and
   * requested length is at least as large as the size of next checksum chunk
   * size, this method will checksum and write the chunk directly 
   * to the underlying output stream.  Thus it avoids uneccessary data copy.
   *
   * @param      b     the data.
   * @param      off   the start offset in the data.
   * @param      len   the number of bytes to write.
   * @exception  IOException  if an I/O error occurs.
   */
  @Override
  public synchronized void write(byte b[], int off, int len)
      throws IOException {
    
    checkClosed();
    
    if (off < 0 || len < 0 || off > b.length - len) {
      throw new ArrayIndexOutOfBoundsException();
    }

    /**
     *  调用write1()方法
     *
     *  循环的调用write1() ，直到数据写完
     */
    for (int n = 0; n < len; n += write1(b, off + n, len - n)) {
    }
  }
  
  /**
   * Write a portion of an array, flushing to the underlying
   * stream at most once if necessary.
   */
  private int write1(byte b[], int off, int len) throws IOException {
    if (count == 0 && len >= buf.length) {
      // 你写入的这个字节数组的大小，直接达到了chunk的大小了
      // 就不需要将字节数组的数据写入一个缓冲了，直接将这个字节数组的数据作为一个chunk写入底层的流就可以了
      // local buffer is empty and user buffer size >= local buffer size, so
      // simply checksum the user buffer and send it directly to the underlying
      // stream
      final int length = buf.length;
      writeChecksumChunks(b, off, length);

      // 返回这次写入的数据大小
      return length;
    }

    // copy user data to local buffer
    /**
     *  当前这个字节数组的数据还没达到一个chunk的大小
     *  此时可以将这个字节数组的数据写入 buf 缓冲中
     */

    // buf就是缓冲
    // bytesToCopy这次可以写的数据上限
    int bytesToCopy = buf.length - count;
    bytesToCopy = (len < bytesToCopy) ? len : bytesToCopy;

    // 把要写入的数据拷贝到缓冲buf中
    System.arraycopy(b, off, buf, count, bytesToCopy);
    count += bytesToCopy; // 总数累加

    if (count == buf.length) {
      // local buffer is full
      // buf写满的时候，进行buf的刷新，最终也要调用 writeChecksumChunks()
      flushBuffer();
    }

    return bytesToCopy;
  }

  /* Forces any buffered output bytes to be checksumed and written out to
   * the underlying output stream. 
   */
  protected synchronized void flushBuffer() throws IOException {
    // 刷新 buffer
    flushBuffer(false, true);
  }

  /* Forces buffered output bytes to be checksummed and written out to
   * the underlying output stream. If there is a trailing partial chunk in the
   * buffer,
   * 1) flushPartial tells us whether to flush that chunk
   * 2) if flushPartial is true, keep tells us whether to keep that chunk in the
   * buffer (if flushPartial is false, it is always kept in the buffer)
   *
   * Returns the number of bytes that were flushed but are still left in the
   * buffer (can only be non-zero if keep is true).
   */
  protected synchronized int flushBuffer(boolean keep,
      boolean flushPartial) throws IOException {
    int bufLen = count;
    int partialLen = bufLen % sum.getBytesPerChecksum();
    int lenToFlush = flushPartial ? bufLen : bufLen - partialLen;
    if (lenToFlush != 0) {

      /**
       * 切分成 一个一个的 chunk
       */
      writeChecksumChunks(buf, 0, lenToFlush);

      if (!flushPartial || keep) {
        count = partialLen;
        System.arraycopy(buf, bufLen - count, buf, 0, count);
      } else {
        count = 0;
      }
    }

    // total bytes left minus unflushed bytes left
    return count - (bufLen - lenToFlush);
  }

  /**
   * Checksums all complete data chunks and flushes them to the underlying
   * stream. If there is a trailing partial chunk, it is not flushed and is
   * maintained in the buffer.
   */
  public void flush() throws IOException {
    flushBuffer(false, false);
  }

  /**
   * Return the number of valid bytes currently in the buffer.
   */
  protected synchronized int getBufferedDataSize() {
    return count;
  }

  /** @return the size for a checksum. */
  protected int getChecksumSize() {
    return sum.getChecksumSize();
  }

  /** Generate checksums for the given data chunks and output chunks & checksums
   * to the underlying output stream.
   */
  private void writeChecksumChunks(byte b[], int off, int len)
  throws IOException {


    /**
     * 在这里计算校验和，应该就是填充 checksum变量的地方
     */
    sum.calculateChunkedSums(b, off, len, checksum, 0);


    for (int i = 0; i < len; i += sum.getBytesPerChecksum()) {

      int chunkLen = Math.min(sum.getBytesPerChecksum(), len - i);

      int ckOffset = i / sum.getBytesPerChecksum() * getChecksumSize();

      /**
       * 一个 Chunk 一个 Chunk的写入
       */
      writeChunk(b,  // 其实就是内存缓冲buf
              off + i,  // 内存缓冲buf的起始偏移量
              chunkLen, // 写入的大小：一般就是一个chunk的大小，如果是最后一个，可能不到一个chunk的大小
              checksum, // 校验和
              ckOffset, // 校验和写入起始偏移量
              getChecksumSize() //校验和写入的长度
      );
    }
  }

  /**
   * Converts a checksum integer value to a byte stream
   */
  static public byte[] convertToByteStream(Checksum sum, int checksumSize) {
    return int2byte((int)sum.getValue(), new byte[checksumSize]);
  }

  static byte[] int2byte(int integer, byte[] bytes) {
    if (bytes.length != 0) {
      bytes[0] = (byte) ((integer >>> 24) & 0xFF);
      bytes[1] = (byte) ((integer >>> 16) & 0xFF);
      bytes[2] = (byte) ((integer >>> 8) & 0xFF);
      bytes[3] = (byte) ((integer >>> 0) & 0xFF);
      return bytes;
    }
    return bytes;
  }

  /**
   * Resets existing buffer with a new one of the specified size.
   */
  protected synchronized void setChecksumBufSize(int size) {
    this.buf = new byte[size];
    this.checksum = new byte[sum.getChecksumSize(size)];
    this.count = 0;
  }

  protected synchronized void resetChecksumBufSize() {
    setChecksumBufSize(sum.getBytesPerChecksum() * BUFFER_NUM_CHUNKS);
  }
}
