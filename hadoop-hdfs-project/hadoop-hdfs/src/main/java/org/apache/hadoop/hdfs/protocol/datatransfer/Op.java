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
package org.apache.hadoop.hdfs.protocol.datatransfer;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/** Operation */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public enum Op {
  WRITE_BLOCK((byte)80),
  READ_BLOCK((byte)81),
  READ_METADATA((byte)82),
  REPLACE_BLOCK((byte)83),
  COPY_BLOCK((byte)84),
  BLOCK_CHECKSUM((byte)85),
  TRANSFER_BLOCK((byte)86),
  REQUEST_SHORT_CIRCUIT_FDS((byte)87),
  RELEASE_SHORT_CIRCUIT_FDS((byte)88),
  REQUEST_SHORT_CIRCUIT_SHM((byte)89);

  /** The code for this operation. */
  public final byte code;
  
  private Op(byte code) {
    this.code = code;
  }
  
  private static final int FIRST_CODE = values()[0].code;
  /** Return the object represented by the code. */
  private static Op valueOf(byte code) {
    /**
     *  枚举中的一个特殊方法，values()， 为什么说特殊呢，因为在Enum 的 API 文档中也找不到这个方法。
        理论上此方法可以将枚举类转变为一个枚举类型的数组，因为枚举中没有下标，我们没有办法通过下标来快速找到需要的枚举类，
         这时候，转变为数组之后，我们就可以通过数组的下标，来找到我们需要的枚举类。
     */
    final int i = (code & 0xff) - FIRST_CODE;
    return i < 0 || i >= values().length? null: values()[i];
  }

  /** Read from in */
  public static Op read(DataInput in) throws IOException {
    return valueOf(in.readByte());
  }

  /** Write to out */
  public void write(DataOutput out) throws IOException {
    out.write(code);
  }
}
