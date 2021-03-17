// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.vdslib.state.*;
import com.yahoo.vespa.clustercontroller.utils.util.ComponentMetricReporter;
import com.yahoo.vespa.clustercontroller.utils.util.MetricReporter;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

public class MetricUpdater {

    private final ComponentMetricReporter metricReporter;

    public MetricUpdater(MetricReporter metricReporter, int controllerIndex) {
        this.metricReporter = new ComponentMetricReporter(metricReporter, "cluster-controller.");
        this.metricReporter.addDimension("controller-index", String.valueOf(controllerIndex));
    }

    public MetricReporter.Context createContext(Map<String, String> dimensions) {
        return metricReporter.createContext(dimensions);
    }

    private static int nodesInAvailableState(Map<State, Integer> nodeCounts) {
        return nodeCounts.getOrDefault(State.INITIALIZING, 0)
                + nodeCounts.getOrDefault(State.RETIRED, 0)
                + nodeCounts.getOrDefault(State.UP, 0)
                // Even though technically not true, we here treat Maintenance as an available state to
                // avoid triggering false alerts when a node is taken down transiently in an orchestrated manner.
                + nodeCounts.getOrDefault(State.MAINTENANCE, 0);
    }

    public void updateClusterStateMetrics(ContentCluster cluster, ClusterState state, ResourceUsageStats resourceUsage) {
        Map<String, String> dimensions = new HashMap<>();
        dimensions.put("cluster", cluster.getName());
        dimensions.put("clusterid", cluster.getName());
        for (NodeType type : NodeType.getTypes()) {
            dimensions.put("node-type", type.toString().toLowerCase());
            MetricReporter.Context context = createContext(dimensions);
            Map<State, Integer> nodeCounts = new HashMap<>();
            for (State s : State.values()) {
                nodeCounts.put(s, 0);
            }
            for (Integer i : cluster.getConfiguredNodes().keySet()) {
                NodeState s = state.getNodeState(new Node(type, i));
                Integer count = nodeCounts.get(s.getState());
                nodeCounts.put(s.getState(), count + 1);
            }
            for (State s : State.values()) {
                String name = s.toString().toLowerCase() + ".count";
                metricReporter.set(name, nodeCounts.get(s), context);
            }

            final int availableNodes = nodesInAvailableState(nodeCounts);
            final int totalNodes = Math.max(cluster.getConfiguredNodes().size(), 1); // Assumes 1-1 between distributor and storage
            metricReporter.set("available-nodes.ratio", (double)availableNodes / totalNodes, context);
        }
        dimensions.remove("node-type");
        MetricReporter.Context context = createContext(dimensions);
        metricReporter.add("cluster-state-change", 1, context);

        metricReporter.set("resource_usage.max_disk_utilization", resourceUsage.getMaxDiskUtilization(), context);
        metricReporter.set("resource_usage.max_memory_utilization", resourceUsage.getMaxMemoryUtilization(), context);
        metricReporter.set("resource_usage.nodes_above_limit", resourceUsage.getNodesAboveLimit(), context);
        metricReporter.set("resource_usage.disk_limit", resourceUsage.getDiskLimit(), context);
        metricReporter.set("resource_usage.memory_limit", resourceUsage.getMemoryLimit(), context);
    }

    public void updateMasterElectionMetrics(Map<Integer, Integer> data) {
        Map<Integer, Integer> voteCounts = new HashMap<>();
        for(Integer i : data.values()) {
            int count = (voteCounts.get(i) == null ? 0 : voteCounts.get(i));
            voteCounts.put(i, count + 1);
        }
        SortedSet<Integer> counts = new TreeSet<>(voteCounts.values());
        if (counts.size() > 1 && counts.first() > counts.last()) {
            throw new IllegalStateException("Assumed smallest count is sorted first");
        }
        int maxCount = counts.isEmpty() ? 0 : counts.last();
        metricReporter.set("agreed-master-votes", maxCount);
    }

    public void updateMasterState(boolean isMaster) {
        metricReporter.set("is-master", isMaster ? 1 : 0);
    }

    public void addTickTime(long millis, boolean didWork) {
        if (didWork) {
            metricReporter.set("busy-tick-time-ms", millis);
        } else {
            metricReporter.set("idle-tick-time-ms", millis);
        }
    }

    public void recordNewNodeEvent() {
        // TODO(hakonhall): Replace add() with a persistent aggregate metric.
        metricReporter.add("node-event", 1);
    }

}
