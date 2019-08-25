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

import static org.apache.hadoop.util.Time.now;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.util.Daemon;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/**
 * LeaseManager does the lease housekeeping for writing on files.   
 * This class also provides useful static methods for lease recovery.
 * 
 * Lease Recovery Algorithm
 * 1) Namenode retrieves lease information
 * 2) For each file f in the lease, consider the last block b of f
 * 2.1) Get the datanodes which contains b
 * 2.2) Assign one of the datanodes as the primary datanode p

 * 2.3) p obtains a new generation stamp from the namenode
 * 2.4) p gets the block info from each datanode
 * 2.5) p computes the minimum block length
 * 2.6) p updates the datanodes, which have a valid generation stamp,
 *      with the new generation stamp and the minimum block length 
 * 2.7) p acknowledges the namenode the update results

 * 2.8) Namenode updates the BlockInfo
 * 2.9) Namenode removes f from the lease
 *      and removes the lease once all files have been removed
 * 2.10) Namenode commit changes to edit log
 */
@InterfaceAudience.Private
public class LeaseManager {
  public static final Log LOG = LogFactory.getLog(LeaseManager.class);

  private final FSNamesystem fsnamesystem;

  private long softLimit = HdfsConstants.LEASE_SOFTLIMIT_PERIOD;
  private long hardLimit = HdfsConstants.LEASE_HARDLIMIT_PERIOD;

  //
  // Used for handling lock-leases
  // Mapping: leaseHolder -> Lease
  // key可以排序的 Map
  // 一个 lease 代表了某个客户端对一个文件拥有的契约
  // key就是客户端的名称，value就是客户端的lease契约
  private final SortedMap<String, Lease> leases = new TreeMap<String, Lease>();
  // Set of: Lease
  // 默认根据 Lease 的续约时间进行排序
  private final NavigableSet<Lease> sortedLeases = new TreeSet<Lease>();

  // 
  // Map path names to leases. It is protected by the sortedLeases lock.
  // The map stores pathnames in lexicographical order.
  // key是文件路径名  value是客户端的契约
  private final SortedMap<String, Lease> sortedLeasesByPath = new TreeMap<String, Lease>();

  /**
   * 后台线程
   */
  private Daemon lmthread;

  private volatile boolean shouldRunMonitor;

  LeaseManager(FSNamesystem fsnamesystem) {this.fsnamesystem = fsnamesystem;}

  Lease getLease(String holder) {
    return leases.get(holder);
  }

  @VisibleForTesting
  int getNumSortedLeases() {return sortedLeases.size();}

  /**
   * This method iterates through all the leases and counts the number of blocks
   * which are not COMPLETE. The FSNamesystem read lock MUST be held before
   * calling this method.
   *
   * 获取正在构造中的block
   * 最早分析namenode启动的时候，会进行一个 safe mode的检查，当时调用了这个方法
   *
   * FSNamesystem#getCompleteBlocksTotal()
   *
   * @return
   */
  synchronized long getNumUnderConstructionBlocks() {
    assert this.fsnamesystem.hasReadLock() : "The FSNamesystem read lock wasn't"
      + "acquired before counting under construction blocks";
    long numUCBlocks = 0;
    for (Lease lease : sortedLeases) {
      for (String path : lease.getPaths()) {
        final INodeFile cons;
        try {
          cons = this.fsnamesystem.getFSDirectory().getINode(path).asFile();
          if (!cons.isUnderConstruction()) {
            LOG.warn("The file " + cons.getFullPathName()
                + " is not under construction but has lease.");
            continue;
          }
        } catch (UnresolvedLinkException e) {
          throw new AssertionError("Lease files should reside on this FS");
        }
        BlockInfo[] blocks = cons.getBlocks();
        if(blocks == null)
          continue;
        for(BlockInfo b : blocks) {
          if(!b.isComplete())
            numUCBlocks++;
        }
      }
    }
    LOG.info("Number of blocks under construction: " + numUCBlocks);
    return numUCBlocks;
  }

  /** @return the lease containing src */
  public Lease getLeaseByPath(String src) {return sortedLeasesByPath.get(src);}

  /** @return the number of leases currently in the system */
  public synchronized int countLease() {return sortedLeases.size();}

  /** @return the number of paths contained in all leases */
  synchronized int countPath() {
    int count = 0;
    for(Lease lease : sortedLeases) {
      count += lease.getPaths().size();
    }
    return count;
  }
  
  /**
   * Adds (or re-adds) the lease for the specified file.
   */
  synchronized Lease addLease(String holder, String src) {
    Lease lease = getLease(holder);

    if (lease == null) {
      // 刚开始肯定是空的
      // 构造一个Lease对象，然后放入 leases 和 sortedLeases 种
      lease = new Lease(holder);
      leases.put(holder, lease);
      sortedLeases.add(lease);
    } else {
      renewLease(lease);
    }

    // 根据文件路径，放入 sortedLeasesByPath 中
    sortedLeasesByPath.put(src, lease);
    lease.paths.add(src);

    return lease;
  }

  /**
   * Remove the specified lease and src.
   */
  synchronized void removeLease(Lease lease, String src) {
    sortedLeasesByPath.remove(src);
    if (!lease.removePath(src)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(src + " not found in lease.paths (=" + lease.paths + ")");
      }
    }

    if (!lease.hasPath()) {
      leases.remove(lease.holder);
      if (!sortedLeases.remove(lease)) {
        LOG.error(lease + " not found in sortedLeases");
      }
    }
  }

  /**
   * Remove the lease for the specified holder and src
   */
  synchronized void removeLease(String holder, String src) {
    Lease lease = getLease(holder);
    if (lease != null) {
      removeLease(lease, src);
    } else {
      LOG.warn("Removing non-existent lease! holder=" + holder +
          " src=" + src);
    }
  }

  synchronized void removeAllLeases() {
    sortedLeases.clear();
    sortedLeasesByPath.clear();
    leases.clear();
  }

  /**
   * Reassign lease for file src to the new holder.
   */
  synchronized Lease reassignLease(Lease lease, String src, String newHolder) {
    assert newHolder != null : "new lease holder is null";
    if (lease != null) {
      removeLease(lease, src);
    }
    return addLease(newHolder, src);
  }

  /**
   * Renew the lease(s) held by the given client
   * spring cloud eureka 源码中，也有 lease 和 renew 的概念
   *
   * 续约
   */
  synchronized void renewLease(String holder) {
    renewLease(getLease(holder));
  }
  synchronized void renewLease(Lease lease) {
    if (lease != null) {
      sortedLeases.remove(lease);
      lease.renew();
      sortedLeases.add(lease);
    }
  }

  /**
   * Renew all of the currently open leases.
   */
  synchronized void renewAllLeases() {
    for (Lease l : leases.values()) {
      renewLease(l);
    }
  }

  /************************************************************
   * A Lease governs all the locks held by a single client.
   * For each client there's a corresponding lease, whose
   * timestamp is updated when the client periodically
   * checks in.  If the client dies and allows its lease to
   * expire, all the corresponding locks can be released.
   *************************************************************/
  class Lease implements Comparable<Lease> {
    // holder  哪个客户端持有这份契约
    private final String holder;
    // 最近一次进行续约的时间
    private long lastUpdate;
    // 这个客户端在这份契约里，声明了针对哪些文件的所有权
    private final Collection<String> paths = new TreeSet<String>();
  
    /** Only LeaseManager object can create a lease */
    private Lease(String holder) {
      this.holder = holder;
      renew();
    }
    /** Only LeaseManager object can renew a lease */
    private void renew() {
      this.lastUpdate = now();
    }

    /** @return true if the Hard Limit Timer has expired */
    public boolean expiredHardLimit() {
      /**
       * 当前时间 - 上次续约时间 > 1 小时
       * 就算超期了
       */
      return now() - lastUpdate > hardLimit;
    }

    /** @return true if the Soft Limit Timer has expired */
    public boolean expiredSoftLimit() {
      return now() - lastUpdate > softLimit;
    }

    /** Does this lease contain any path? */
    boolean hasPath() {return !paths.isEmpty();}

    boolean removePath(String src) {
      return paths.remove(src);
    }

    @Override
    public String toString() {
      return "[Lease.  Holder: " + holder
          + ", pendingcreates: " + paths.size() + "]";
    }
  
    @Override
    public int compareTo(Lease o) {
      Lease l1 = this;
      Lease l2 = o;
      long lu1 = l1.lastUpdate;
      long lu2 = l2.lastUpdate;
      if (lu1 < lu2) {
        return -1;
      } else if (lu1 > lu2) {
        return 1;
      } else {
        return l1.holder.compareTo(l2.holder);
      }
    }
  
    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Lease)) {
        return false;
      }
      Lease obj = (Lease) o;
      if (lastUpdate == obj.lastUpdate &&
          holder.equals(obj.holder)) {
        return true;
      }
      return false;
    }
  
    @Override
    public int hashCode() {
      return holder.hashCode();
    }
    
    Collection<String> getPaths() {
      return paths;
    }

    String getHolder() {
      return holder;
    }

    void replacePath(String oldpath, String newpath) {
      paths.remove(oldpath);
      paths.add(newpath);
    }
    
    @VisibleForTesting
    long getLastUpdate() {
      return lastUpdate;
    }
  }

  synchronized void changeLease(String src, String dst) {
    if (LOG.isDebugEnabled()) {
      LOG.debug(getClass().getSimpleName() + ".changelease: " +
               " src=" + src + ", dest=" + dst);
    }

    final int len = src.length();
    for(Map.Entry<String, Lease> entry
        : findLeaseWithPrefixPath(src, sortedLeasesByPath).entrySet()) {
      final String oldpath = entry.getKey();
      final Lease lease = entry.getValue();
      // replace stem of src with new destination
      final String newpath = dst + oldpath.substring(len);
      if (LOG.isDebugEnabled()) {
        LOG.debug("changeLease: replacing " + oldpath + " with " + newpath);
      }
      lease.replacePath(oldpath, newpath);
      sortedLeasesByPath.remove(oldpath);
      sortedLeasesByPath.put(newpath, lease);
    }
  }

  synchronized void removeLeaseWithPrefixPath(String prefix) {
    for(Map.Entry<String, Lease> entry
        : findLeaseWithPrefixPath(prefix, sortedLeasesByPath).entrySet()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(LeaseManager.class.getSimpleName()
            + ".removeLeaseWithPrefixPath: entry=" + entry);
      }
      removeLease(entry.getValue(), entry.getKey());    
    }
  }

  static private Map<String, Lease> findLeaseWithPrefixPath(
      String prefix, SortedMap<String, Lease> path2lease) {
    if (LOG.isDebugEnabled()) {
      LOG.debug(LeaseManager.class.getSimpleName() + ".findLease: prefix=" + prefix);
    }

    final Map<String, Lease> entries = new HashMap<String, Lease>();
    int srclen = prefix.length();
    
    // prefix may ended with '/'
    if (prefix.charAt(srclen - 1) == Path.SEPARATOR_CHAR) {
      srclen -= 1;
    }

    for(Map.Entry<String, Lease> entry : path2lease.tailMap(prefix).entrySet()) {
      final String p = entry.getKey();
      if (!p.startsWith(prefix)) {
        return entries;
      }
      if (p.length() == srclen || p.charAt(srclen) == Path.SEPARATOR_CHAR) {
        entries.put(entry.getKey(), entry.getValue());
      }
    }
    return entries;
  }

  public void setLeasePeriod(long softLimit, long hardLimit) {
    this.softLimit = softLimit;
    this.hardLimit = hardLimit; 
  }
  
  /******************************************************
   * Monitor checks for leases that have expired,
   * and disposes of them.
   ******************************************************/
  /**
   * 这是一个后台线程
   * 这个东西一定会运行起来，在后台不断监控各个lease契约
   * 如果某个客户端申请持有了一个文件的契约，结果这个客户端莫名其妙的死亡了，然后没有删除这个契约
   * 那么此时就需要namenode中的 Monitor后台线程
   * 不断的监控各个lease，如果某个客户端申请了契约之后，长时间范围内没有来续约，就认为他死了
   * 此时释放这个lease
   */
  class Monitor implements Runnable {
    final String name = getClass().getSimpleName();

    /** Check leases periodically. */
    @Override
    public void run() {
      for(; shouldRunMonitor && fsnamesystem.isRunning(); ) {
        boolean needSync = false;
        try {
          fsnamesystem.writeLockInterruptibly();
          try {

            /**
             * 检查契约的逻辑
             */
            if (!fsnamesystem.isInSafeMode()) {
              needSync = checkLeases();
            }

          } finally {
            fsnamesystem.writeUnlock();
            // lease reassignments should to be sync'ed.
            if (needSync) {
              fsnamesystem.getEditLog().logSync();
            }
          }

          // 每隔2s对所有契约进行一次检查
          Thread.sleep(HdfsServerConstants.NAMENODE_LEASE_RECHECK_INTERVAL);
        } catch(InterruptedException ie) {
          if (LOG.isDebugEnabled()) {
            LOG.debug(name + " is interrupted", ie);
          }
        }
      }
    }
  }

  /**
   * Get the list of inodes corresponding to valid leases.
   * @return list of inodes
   */
  Map<String, INodeFile> getINodesUnderConstruction() {
    Map<String, INodeFile> inodes = new TreeMap<String, INodeFile>();
    for (String p : sortedLeasesByPath.keySet()) {
      // verify that path exists in namespace
      try {
        INodeFile node = INodeFile.valueOf(fsnamesystem.dir.getINode(p), p);
        if (node.isUnderConstruction()) {
          inodes.put(p, node);
        } else {
          LOG.warn("Ignore the lease of file " + p
              + " for checkpoint since the file is not under construction");
        }
      } catch (IOException ioe) {
        LOG.error(ioe);
      }
    }
    return inodes;
  }
  
  /** Check the leases beginning from the oldest.
   *  @return true is sync is needed.
   */
  @VisibleForTesting
  synchronized boolean checkLeases() {
    boolean needSync = false;
    assert fsnamesystem.hasWriteLock();
    Lease leaseToCheck = null;
    try {
      // sortedLeases 是按照续约时间排序的
      // 续约时间越老的越靠前， first() 拿到最老的契约
      leaseToCheck = sortedLeases.first();
    } catch(NoSuchElementException e) {}

    while(leaseToCheck != null) {


      if (!leaseToCheck.expiredHardLimit()) {
        // 如果没有超期，就break掉
        // 如果最老的一个都没有超过1小时没有续约，那后面的肯定也不会超期的，就不用检查了
        // 这才是用 续约时间 进行排序的TreeSet结构的原因
        break;
      }

      LOG.info(leaseToCheck + " has expired hard limit");

      final List<String> removing = new ArrayList<String>();
      // need to create a copy of the oldest lease paths, because 
      // internalReleaseLease() removes paths corresponding to empty files,
      // i.e. it needs to modify the collection being iterated over
      // causing ConcurrentModificationException
      String[] leasePaths = new String[leaseToCheck.getPaths().size()];
      leaseToCheck.getPaths().toArray(leasePaths);
      for(String p : leasePaths) {
        try {

          boolean completed = fsnamesystem.internalReleaseLease(leaseToCheck, p,
              HdfsServerConstants.NAMENODE_LEASE_HOLDER);

          if (LOG.isDebugEnabled()) {
            if (completed) {
              LOG.debug("Lease recovery for " + p + " is complete. File closed.");
            } else {
              LOG.debug("Started block recovery " + p + " lease " + leaseToCheck);
            }
          }
          // If a lease recovery happened, we need to sync later.
          if (!needSync && !completed) {
            needSync = true;
          }
        } catch (IOException e) {
          LOG.error("Cannot release the path " + p + " in the lease "
              + leaseToCheck, e);
          removing.add(p);
        }
      }

      // 删除契约，清理掉各种数据结构的中的数据
      for(String p : removing) {
        removeLease(leaseToCheck, p);
      }

      /**
       * 找到下一个需要删除的契约，higher() 找到第二个比他稍微新一点的契约
       * 继续while循环
       */
      leaseToCheck = sortedLeases.higher(leaseToCheck);
    }

    try {
      if(leaseToCheck != sortedLeases.first()) {
        LOG.warn("Unable to release hard-limit expired lease: "
          + sortedLeases.first());
      }
    } catch(NoSuchElementException e) {}
    return needSync;
  }

  @Override
  public synchronized String toString() {
    return getClass().getSimpleName() + "= {"
        + "\n leases=" + leases
        + "\n sortedLeases=" + sortedLeases
        + "\n sortedLeasesByPath=" + sortedLeasesByPath
        + "\n}";
  }

  // 一看就是启动后台线程 Monitor
  void startMonitor() {
    Preconditions.checkState(lmthread == null,
        "Lease Monitor already running");
    shouldRunMonitor = true;
    lmthread = new Daemon(new Monitor());
    lmthread.start();
  }
  
  void stopMonitor() {
    if (lmthread != null) {
      shouldRunMonitor = false;
      try {
        lmthread.interrupt();
        lmthread.join(3000);
      } catch (InterruptedException ie) {
        LOG.warn("Encountered exception ", ie);
      }
      lmthread = null;
    }
  }

  /**
   * Trigger the currently-running Lease monitor to re-check
   * its leases immediately. This is for use by unit tests.
   */
  @VisibleForTesting
  void triggerMonitorCheckNow() {
    Preconditions.checkState(lmthread != null,
        "Lease monitor is not running");
    lmthread.interrupt();
  }
}
