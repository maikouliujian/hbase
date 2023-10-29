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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import static org.apache.hadoop.hbase.client.RegionInfo.DEFAULT_REPLICA_ID;
import static org.apache.hadoop.hbase.client.RegionInfoBuilder.FIRST_META_REGIONINFO;
import static org.apache.hadoop.hbase.client.RegionReplicaUtil.getRegionInfoForDefaultReplica;
import static org.apache.hadoop.hbase.client.RegionReplicaUtil.getRegionInfoForReplica;
import static org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil.lengthOfPBMagic;
import static org.apache.hadoop.hbase.util.FutureUtils.addListener;
import static org.apache.hadoop.hbase.zookeeper.ZKMetadata.removeMetaData;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterId;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.RegionLocations;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.master.RegionState;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.zookeeper.ReadOnlyZKClient;
import org.apache.hadoop.hbase.zookeeper.ZNodePaths;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ZooKeeperProtos;

/**
 * Zookeeper based registry implementation.
 */
@InterfaceAudience.Private
class ZKConnectionRegistry implements ConnectionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ZKConnectionRegistry.class);
  //todo zk的只读客户端
  private final ReadOnlyZKClient zk;

  private final ZNodePaths znodePaths;

  ZKConnectionRegistry(Configuration conf) {
    this.znodePaths = new ZNodePaths(conf);
    this.zk = new ReadOnlyZKClient(conf);
  }

  private interface Converter<T> {
    T convert(byte[] data) throws Exception;
  }

  private <T> CompletableFuture<T> getAndConvert(String path, Converter<T> converter) {
    CompletableFuture<T> future = new CompletableFuture<>();
    addListener(zk.get(path), (data, error) -> {
      if (error != null) {
        future.completeExceptionally(error);
        return;
      }
      try {
        future.complete(converter.convert(data));
      } catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  private static String getClusterId(byte[] data) throws DeserializationException {
    if (data == null || data.length == 0) {
      return null;
    }
    data = removeMetaData(data);
    return ClusterId.parseFrom(data).toString();
  }

  @Override
  public CompletableFuture<String> getClusterId() {
    return getAndConvert(znodePaths.clusterIdZNode, ZKConnectionRegistry::getClusterId);
  }

  ReadOnlyZKClient getZKClient() {
    return zk;
  }

  private static ZooKeeperProtos.MetaRegionServer getMetaProto(byte[] data) throws IOException {
    if (data == null || data.length == 0) {
      return null;
    }
    data = removeMetaData(data);
    int prefixLen = lengthOfPBMagic();
    //todo 字节数组转化为pb对象
    return ZooKeeperProtos.MetaRegionServer.parser().parseFrom(data, prefixLen,
      data.length - prefixLen);
  }

  private static void tryComplete(MutableInt remaining, Collection<HRegionLocation> locs,
    CompletableFuture<RegionLocations> future) {
    remaining.decrement();
    if (remaining.intValue() > 0) {
      return;
    }
    future.complete(new RegionLocations(locs));
  }

  private Pair<RegionState.State, ServerName>
    getStateAndServerName(ZooKeeperProtos.MetaRegionServer proto) {
    RegionState.State state;
    if (proto.hasState()) {
      state = RegionState.State.convert(proto.getState());
    } else {
      state = RegionState.State.OPEN;
    }
    HBaseProtos.ServerName snProto = proto.getServer();
    return Pair.newPair(state,
      ServerName.valueOf(snProto.getHostName(), snProto.getPort(), snProto.getStartCode()));
  }

  private void getMetaRegionLocation(CompletableFuture<RegionLocations> future,
    List<String> metaReplicaZNodes) {
    // TODO 注释： 如果 metaReplicaZNodes 集合为空，证明没有 meta znode 节点
    if (metaReplicaZNodes.isEmpty()) {
      future.completeExceptionally(new IOException("No meta znode available"));
    }
    // Note, the list of metaReplicaZNodes may be discontiguous regards replicaId; i.e. we may have
    // a znode for the default -- replicaId=0 -- and perhaps replicaId '2' but be could be missing
    // znode for replicaId '1'. This is a transient condition. Because of this we are careful
    // accumulating locations. We use a Map so retries overwrite rather than aggregate and the
    // Map sorts just to be kind to further processing. The Map will retain the discontinuity on
    // replicaIds but on completion (of the future), the Map values are passed to the
    // RegionLocations constructor which knows how to deal with discontinuities.
    // TODO 注释： 用来保存获取到的 meta region 的位置信息
    final Map<Integer, HRegionLocation> locs = new TreeMap<>();
    MutableInt remaining = new MutableInt(metaReplicaZNodes.size());
    /*************************************************
     * TODO 马中华 https://blog.csdn.net/zhongqi2513
     *  注释： 遍历每个 meta znode 节点
     */
    for (String metaReplicaZNode : metaReplicaZNodes) {
      // TODO 注释： 读取 meta 的 replicaId
      int replicaId = znodePaths.getMetaReplicaIdFromZNode(metaReplicaZNode);
      // TODO 注释： 拼接得到其中一个 meta znode 节点的完整路径
      String path = ZNodePaths.joinZNode(znodePaths.baseZNode, metaReplicaZNode);
      // TODO 注释： 然后去读取 znode 的数据
      // TODO 注释： 第一个分支，表示只有一个 meta region
      if (replicaId == DEFAULT_REPLICA_ID) {
        addListener(getAndConvert(path, ZKConnectionRegistry::getMetaProto), (proto, error) -> {
          if (error != null) {
            future.completeExceptionally(error);
            return;
          }
          if (proto == null) {
            future.completeExceptionally(new IOException("Meta znode is null"));
            return;
          }
          // TODO 注释： 获取 meta region 的状态 和 ServerName 也就是存储在哪个 RegionServer 的位置信息
          Pair<RegionState.State, ServerName> stateAndServerName = getStateAndServerName(proto);
          if (stateAndServerName.getFirst() != RegionState.State.OPEN) {
            LOG.warn("Meta region is in state " + stateAndServerName.getFirst());
          }
          /*************************************************
           * TODO 马中华 https://blog.csdn.net/zhongqi2513
           *  注释： 将 meta region 的位置信息存储起来
           */
          locs.put(replicaId, new HRegionLocation(
            // TODO 注释： 第一个参数： RegionInfo，// TODO 注释： 第二个参数： ServerName
            getRegionInfoForDefaultReplica(FIRST_META_REGIONINFO), stateAndServerName.getSecond()));
          // TODO 注释： 设置返回值
          tryComplete(remaining, locs.values(), future);
        });
      } else {
        addListener(getAndConvert(path, ZKConnectionRegistry::getMetaProto), (proto, error) -> {
          if (future.isDone()) {
            return;
          }
          if (error != null) {
            LOG.warn("Failed to fetch " + path, error);
            locs.put(replicaId, null);
          } else if (proto == null) {
            LOG.warn("Meta znode for replica " + replicaId + " is null");
            locs.put(replicaId, null);
          } else {
            Pair<RegionState.State, ServerName> stateAndServerName = getStateAndServerName(proto);
            if (stateAndServerName.getFirst() != RegionState.State.OPEN) {
              LOG.warn("Meta region for replica " + replicaId + " is in state "
                + stateAndServerName.getFirst());
              locs.put(replicaId, null);
            } else {
              locs.put(replicaId,
                new HRegionLocation(getRegionInfoForReplica(FIRST_META_REGIONINFO, replicaId),
                  stateAndServerName.getSecond()));
            }
          }
          tryComplete(remaining, locs.values(), future);
        });
      }
    }
  }

  @Override
  public CompletableFuture<RegionLocations> getMetaRegionLocations() {
    CompletableFuture<RegionLocations> future = new CompletableFuture<>();
    // TODO 注释： 从 ZooKeeper 上遍历出来 /hbase znode 节点下 meta-region-server 开头的 子 znode 节点
    // TODO 注释： baseZNode = /hbase
    addListener(
      // TODO 注释： 第一个参数
      zk.list(znodePaths.baseZNode).thenApply(children -> children.stream()
        //todo 过滤出： meta-region-server
        .filter(c -> this.znodePaths.isMetaZNodePrefix(c)).collect(Collectors.toList())),
      // TODO 注释： 第二个参数
      (metaReplicaZNodes, error) -> {
        if (error != null) {
          future.completeExceptionally(error);
          return;
        }
        // TODO 注释： 获取 znode 节点上的数据
        getMetaRegionLocation(future, metaReplicaZNodes);
      });
    return future;
  }

  private static ZooKeeperProtos.Master getMasterProto(byte[] data) throws IOException {
    if (data == null || data.length == 0) {
      return null;
    }
    data = removeMetaData(data);
    int prefixLen = lengthOfPBMagic();
    return ZooKeeperProtos.Master.parser().parseFrom(data, prefixLen, data.length - prefixLen);
  }

  @Override
  public CompletableFuture<ServerName> getActiveMaster() {
    return getAndConvert(znodePaths.masterAddressZNode, ZKConnectionRegistry::getMasterProto)
      .thenApply(proto -> {
        if (proto == null) {
          return null;
        }
        HBaseProtos.ServerName snProto = proto.getMaster();
        return ServerName.valueOf(snProto.getHostName(), snProto.getPort(), snProto.getStartCode());
      });
  }

  @Override
  public void close() {
    zk.close();
  }
}
