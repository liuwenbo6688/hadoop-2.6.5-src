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
 * Block report for a Datanode storage
 */
public class StorageBlockReport {

  /**
   * 就是上报每块磁盘对应的所有 block id数组
   */
  private final DatanodeStorage storage;
  private final long[] blocks;
  
  public StorageBlockReport(DatanodeStorage storage, long[] blocks) {
    this.storage = storage;
    this.blocks = blocks;
  }

  public DatanodeStorage getStorage() {
    return storage;
  }

  public long[] getBlocks() {
    return blocks;
  }
}
