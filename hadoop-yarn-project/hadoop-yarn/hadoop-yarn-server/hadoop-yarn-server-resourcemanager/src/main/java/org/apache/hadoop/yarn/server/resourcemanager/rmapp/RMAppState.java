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

package org.apache.hadoop.yarn.server.resourcemanager.rmapp;

public enum RMAppState {
  NEW, // RMApp的初始状态
  NEW_SAVING,
  SUBMITTED,
  ACCEPTED, // 表示该App已经提交给调度器
  RUNNING, //  AM已经启动并注册到RM上
  FINAL_SAVING,
  FINISHING,
  FINISHED, // RMAppImpl的结束状态
  FAILED,
  KILLING,
  KILLED
}
