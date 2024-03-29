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
package org.apache.hadoop.hdfs.server.protocol;

/**
 * Utilization report for a Datanode storage
 * 一块存储的使用情况
 */
public class StorageReport {
  private final DatanodeStorage storage;
  private final boolean failed;
  // 总的磁盘容量
  private final long capacity;
  // 已经使用的磁盘空间
  private final long dfsUsed;
  // 剩余磁盘空间
  private final long remaining;
  // datanode的 block使用多少空间
  private final long blockPoolUsed;

  public static final StorageReport[] EMPTY_ARRAY = {};
  
  public StorageReport(DatanodeStorage storage, boolean failed,
      long capacity, long dfsUsed, long remaining, long bpUsed) {
    this.storage = storage;
    this.failed = failed;
    this.capacity = capacity;
    this.dfsUsed = dfsUsed;
    this.remaining = remaining;
    this.blockPoolUsed = bpUsed;
  }

  public DatanodeStorage getStorage() {
    return storage;
  }

  public boolean isFailed() {
    return failed;
  }

  public long getCapacity() {
    return capacity;
  }

  public long getDfsUsed() {
    return dfsUsed;
  }

  public long getRemaining() {
    return remaining;
  }

  public long getBlockPoolUsed() {
    return blockPoolUsed;
  }
}
