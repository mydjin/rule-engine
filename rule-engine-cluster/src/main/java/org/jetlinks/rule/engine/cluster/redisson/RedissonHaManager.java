package org.jetlinks.rule.engine.cluster.redisson;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.rule.engine.cluster.NodeInfo;
import org.jetlinks.rule.engine.cluster.ha.HaManager;
import org.redisson.api.RMap;
import org.redisson.api.RPatternTopic;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @author zhouhao
 * @since 1.0.0
 */
@Slf4j
public class RedissonHaManager implements HaManager {

    @Getter
    @Setter
    private NodeInfo currentNode;

    @Getter
    @Setter
    private RedissonClient redissonClient;

    @Getter
    @Setter
    private ScheduledExecutorService executorService;

    protected RPatternTopic clusterNodeTopic;

    protected RTopic clusterNodeKeepTopic;
    protected RTopic clusterNodeLeaveTopic;

    @Setter
    @Getter
    private long timeToLeave = 10;

    private RMap<String, NodeInfo> allNodeInfo;
    private Map<String, NodeInfo>  localAllNode;

    protected void doNodeJoin(NodeInfo nodeInfo) {
        if (nodeInfo.getId().equals(currentNode.getId())) {
            return;
        }
        nodeInfo.setLastKeepAliveTime(System.currentTimeMillis());
        localAllNode.put(nodeInfo.getId(), nodeInfo);
        allNodeInfo.put(nodeInfo.getId(), nodeInfo);
        joinConsumer.accept(nodeInfo);
    }

    protected void doNodeLeave(NodeInfo nodeInfo) {
        if (nodeInfo.getId().equals(currentNode.getId())) {
            return;
        }
        localAllNode.remove(nodeInfo.getId());
        allNodeInfo.fastRemove(nodeInfo.getId());
        leaveConsumer.accept(nodeInfo);
    }

    public void start() {
        Assert.notNull(redissonClient, "redissonClient");
        Assert.notNull(currentNode, "currentNode");
        Assert.notNull(currentNode.getId(), "currentNode.id");
        Assert.notNull(executorService, "executorService");

        allNodeInfo = redissonClient.getMap("cluster:nodes");
        //注册自己
        allNodeInfo.put(currentNode.getId(), currentNode);

        localAllNode = new HashMap<>(allNodeInfo);

        clusterNodeTopic = redissonClient.getPatternTopic("cluster:node:*");
        clusterNodeKeepTopic = redissonClient.getTopic("cluster:node:keep");
        clusterNodeLeaveTopic = redissonClient.getTopic("cluster:node:leave");

        //订阅节点上下线
        clusterNodeTopic.addListener(NodeInfo.class, (pattern, channel, msg) -> {
            String operation = String.valueOf(channel);
            if ("cluster:node:join".equals(operation)) {
                doNodeJoin(msg);
            } else if ("cluster:node:leave".equals(operation)) {
                doNodeLeave(msg);
            } else if ("cluster:node:keep".equals(operation)) {
                NodeInfo nodeInfo = localAllNode.get(msg.getId());
                if (nodeInfo == null) {
                    doNodeJoin(msg);
                } else {
                    nodeInfo.setLastKeepAliveTime(System.currentTimeMillis());
                }
            } else {
                log.info("unknown channel:{} {}", operation, msg);
            }
        });

        executorService.scheduleAtFixedRate(() -> {
            //保活
            currentNode.setLastKeepAliveTime(System.currentTimeMillis());
            clusterNodeKeepTopic.publish(currentNode);
            //注册自己
            allNodeInfo.put(currentNode.getId(), currentNode);

            //检查节点是否存活
            localAllNode
                    .values()
                    .stream()
                    .filter(info -> System.currentTimeMillis() - info.getLastKeepAliveTime() > TimeUnit.SECONDS.toMillis(timeToLeave))
                    .forEach(clusterNodeLeaveTopic::publish);
        }, 1, Math.min(2, timeToLeave), TimeUnit.SECONDS);
    }

    public void shutdown() {
        clusterNodeLeaveTopic.publish(currentNode);
    }

    @Override
    public List<NodeInfo> getAllAliveNode() {
        return new ArrayList<>(localAllNode.values());
    }

    private volatile Consumer<NodeInfo> joinConsumer = (info) -> log.info("node join:{}", info);

    private volatile Consumer<NodeInfo> leaveConsumer = (info) -> log.info("node leave:{}", info);

    @Override
    public synchronized HaManager onNodeJoin(Consumer<NodeInfo> consumer) {
        joinConsumer = joinConsumer.andThen(consumer);
        return this;
    }

    @Override
    public synchronized HaManager onNodeLeave(Consumer<NodeInfo> consumer) {
        leaveConsumer = leaveConsumer.andThen(consumer);
        return this;
    }
}
