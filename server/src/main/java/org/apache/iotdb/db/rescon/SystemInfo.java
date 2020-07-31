/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.rescon;

import java.util.TreeMap;
import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.flush.FlushManager;
import org.apache.iotdb.db.engine.storagegroup.StorageGroupInfo;
import org.apache.iotdb.db.engine.storagegroup.TsFileProcessor;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;

public class SystemInfo {

  private static final IoTDBConfig config = IoTDBDescriptor.getInstance().getConfig();

  private long totalSgInfoMemCost;
  private long arrayPoolMemCost;
  private boolean rejected = false;

  private TreeMap<StorageGroupInfo, Long> reportedSgMemCostMap = new TreeMap<>(
      (o1, o2) -> (int) (o2.getStorageGroupMemCost() - o1
          .getStorageGroupMemCost()));

  private static final double flushProportion = config.getFlushProportion();
  private static final double rejectProportion = config.getRejectProportion();

  /**
   * Report applying a new out of buffered array to system. Attention: It should be invoked before
   * applying new OOB array actually.
   *
   * @param dataType data type of array
   * @param size     size of array
   * @return Return true if it's agreed when memory is enough.
   */
  public synchronized boolean applyNewOOBArray(TSDataType dataType, int size) {
    // if current memory is enough
    if (arrayPoolMemCost + totalSgInfoMemCost + dataType.getDataTypeSize() * size
        < config.getAllocateMemoryForWrite() * flushProportion) {
      arrayPoolMemCost += dataType.getDataTypeSize() * size;
      return true;
    } else if (arrayPoolMemCost + totalSgInfoMemCost + dataType.getDataTypeSize() * size
        < config.getAllocateMemoryForWrite() * rejectProportion) {
      arrayPoolMemCost += dataType.getDataTypeSize() * size;
      // invoke flush()
      flush();
      return true;
    } else {
      rejected = true;
      flush();
      return false;
    }
  }

  /**
   * Report current mem cost of storage group to system.
   *
   * @param StorageGroupInfo
   * @return Return true if it's agreed when memory is enough.
   */
  public synchronized void reportStorageGroupStatus(StorageGroupInfo storageGroupInfo, 
      long delta) {
    this.totalSgInfoMemCost += delta;
    if (this.arrayPoolMemCost + this.totalSgInfoMemCost
        >= config.getAllocateMemoryForWrite() * flushProportion) {
      flush();
    } 
    if (this.arrayPoolMemCost + this.totalSgInfoMemCost
        >= config.getAllocateMemoryForWrite() * rejectProportion) {
      rejected = true;
    }
  }

  /**
   * Update the current mem cost of buffered array pool.
   *
   * @param increasingArraySize increasing size of buffered array
   */
  public synchronized void reportIncreasingArraySize(int increasingArraySize) {
    this.arrayPoolMemCost += increasingArraySize;
  }

  /**
   * Report releasing an out of buffered array to system. Attention: It should be invoked after
   * releasing.
   *
   * @param dataType data type of array
   * @param size     size of array
   */
  public synchronized void reportReleaseOOBArray(TSDataType dataType, int size) {
    this.arrayPoolMemCost -= dataType.getDataTypeSize() * size;
  }

  /**
   * Report resetting the mem cost of sg to system. It will be invoked after closing file.
   *
   * @param processor closing processor
   */
  public synchronized void resetStorageGroupInfoStatus(StorageGroupInfo storageGroupInfo) {
    if (reportedSgMemCostMap.containsKey(storageGroupInfo)) {
      this.totalSgInfoMemCost -= reportedSgMemCostMap.get(storageGroupInfo)
          - storageGroupInfo.getStorageGroupMemCost();
      if (this.arrayPoolMemCost + this.totalSgInfoMemCost 
          < config.getAllocateMemoryForWrite() * rejectProportion) {
        rejected = false;
      }
      reportedSgMemCostMap.put(storageGroupInfo, storageGroupInfo.getStorageGroupMemCost());
    }
  }

  /**
   * Flush the tsfileProcessor in SG with the max mem cost. If the queue size of flushing > threshold,
   * it's identified as flushing is in progress.
   */
  public void flush() {
    if (FlushManager.getInstance().getTsFileProcessorQueueSize() >= 1) {
      return;
    }

    // get the first processor which has the max mem cost
    StorageGroupInfo storageGroupInfo = reportedSgMemCostMap.firstKey();
    TsFileProcessor flushedProcessor = storageGroupInfo.getLargestTsFileProcessor();

    if (flushedProcessor != null) {
      flushedProcessor.asyncFlush();
    }
  }

  public boolean isRejected() {
    return rejected;
  }

  public static SystemInfo getInstance() {
    return InstanceHolder.instance;
  }

  private static class InstanceHolder {

    private InstanceHolder() {
    }

    private static SystemInfo instance = new SystemInfo();
  }
}
