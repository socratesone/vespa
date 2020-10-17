// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.applicationmodel.ApplicationInstance;
import com.yahoo.vespa.applicationmodel.HostName;
import com.yahoo.vespa.applicationmodel.ServiceInstance;
import com.yahoo.vespa.applicationmodel.ServiceStatus;
import com.yahoo.vespa.curator.stats.LatencyMetrics;
import com.yahoo.vespa.curator.stats.LockStats;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.History;
import com.yahoo.vespa.hosted.provision.persistence.CacheStats;
import com.yahoo.vespa.orchestrator.Orchestrator;
import com.yahoo.vespa.service.monitor.ServiceModel;
import com.yahoo.vespa.service.monitor.ServiceMonitor;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.yahoo.config.provision.NodeResources.DiskSpeed.any;
import static com.yahoo.vespa.hosted.provision.Node.State.active;

/**
 * @author oyving
 */
public class MetricsReporter extends NodeRepositoryMaintainer {

    private final Metric metric;
    private final Orchestrator orchestrator;
    private final ServiceMonitor serviceMonitor;
    private final Map<Map<String, String>, Metric.Context> contextMap = new HashMap<>();
    private final Supplier<Integer> pendingRedeploymentsSupplier;
    private final Clock clock;

    MetricsReporter(NodeRepository nodeRepository,
                    Metric metric,
                    Orchestrator orchestrator,
                    ServiceMonitor serviceMonitor,
                    Supplier<Integer> pendingRedeploymentsSupplier,
                    Duration interval,
                    Clock clock) {
        super(nodeRepository, interval, metric);
        this.metric = metric;
        this.orchestrator = orchestrator;
        this.serviceMonitor = serviceMonitor;
        this.pendingRedeploymentsSupplier = pendingRedeploymentsSupplier;
        this.clock = clock;
    }

    @Override
    public boolean maintain() {
        NodeList nodes = nodeRepository().list();
        ServiceModel serviceModel = serviceMonitor.getServiceModelSnapshot();

        updateLockMetrics();
        nodes.forEach(node -> updateNodeMetrics(node, serviceModel));
        updateStateMetrics(nodes);
        updateMaintenanceMetrics();
        updateDockerMetrics(nodes);
        updateTenantUsageMetrics(nodes);
        updateCacheMetrics();
        return true;
    }

    private void updateCacheMetrics() {
        CacheStats nodeCacheStats = nodeRepository().database().nodeSerializerCacheStats();
        metric.set("cache.nodeObject.hitRate", nodeCacheStats.hitRate(), null);
        metric.set("cache.nodeObject.evictionCount", nodeCacheStats.evictionCount(), null);
        metric.set("cache.nodeObject.size", nodeCacheStats.size(), null);

        CacheStats curatorCacheStats = nodeRepository().database().cacheStats();
        metric.set("cache.curator.hitRate", curatorCacheStats.hitRate(), null);
        metric.set("cache.curator.evictionCount", curatorCacheStats.evictionCount(), null);
        metric.set("cache.curator.size", curatorCacheStats.size(), null);
    }

    private void updateMaintenanceMetrics() {
        metric.set("hostedVespa.pendingRedeployments", pendingRedeploymentsSupplier.get(), null);
    }

    private void updateNodeMetrics(Node node, ServiceModel serviceModel) {
        Metric.Context context;

        Optional<Allocation> allocation = node.allocation();
        if (allocation.isPresent()) {
            ApplicationId applicationId = allocation.get().owner();
            context = getContextAt(
                    "state", node.state().name(),
                    "host", node.hostname(),
                    "tenantName", applicationId.tenant().value(),
                    "applicationId", applicationId.serializedForm().replace(':', '.'),
                    "app", toApp(applicationId),
                    "clustertype", allocation.get().membership().cluster().type().name(),
                    "clusterid", allocation.get().membership().cluster().id().value());

            long wantedRestartGeneration = allocation.get().restartGeneration().wanted();
            metric.set("wantedRestartGeneration", wantedRestartGeneration, context);
            long currentRestartGeneration = allocation.get().restartGeneration().current();
            metric.set("currentRestartGeneration", currentRestartGeneration, context);
            boolean wantToRestart = currentRestartGeneration < wantedRestartGeneration;
            metric.set("wantToRestart", wantToRestart ? 1 : 0, context);

            metric.set("retired", allocation.get().membership().retired() ? 1 : 0, context);

            Version wantedVersion = allocation.get().membership().cluster().vespaVersion();
            double wantedVersionNumber = getVersionAsNumber(wantedVersion);
            metric.set("wantedVespaVersion", wantedVersionNumber, context);

            Optional<Version> currentVersion = node.status().vespaVersion();
            boolean converged = currentVersion.isPresent() &&
                    currentVersion.get().equals(wantedVersion);
            metric.set("wantToChangeVespaVersion", converged ? 0 : 1, context);
        } else {
            context = getContextAt(
                    "state", node.state().name(),
                    "host", node.hostname());
        }

        Optional<Version> currentVersion = node.status().vespaVersion();
        if (currentVersion.isPresent()) {
            double currentVersionNumber = getVersionAsNumber(currentVersion.get());
            metric.set("currentVespaVersion", currentVersionNumber, context);
        }

        long wantedRebootGeneration = node.status().reboot().wanted();
        metric.set("wantedRebootGeneration", wantedRebootGeneration, context);
        long currentRebootGeneration = node.status().reboot().current();
        metric.set("currentRebootGeneration", currentRebootGeneration, context);
        boolean wantToReboot = currentRebootGeneration < wantedRebootGeneration;
        metric.set("wantToReboot", wantToReboot ? 1 : 0, context);

        metric.set("wantToRetire", node.status().wantToRetire() ? 1 : 0, context);
        metric.set("wantToDeprovision", node.status().wantToDeprovision() ? 1 : 0, context);
        metric.set("failReport", NodeFailer.reasonsToFailParentHost(node).isEmpty() ? 0 : 1, context);

        HostName hostname = new HostName(node.hostname());

        serviceModel.getApplication(hostname)
                .map(ApplicationInstance::reference)
                .map(reference -> orchestrator.getHostInfo(reference, hostname))
                .ifPresent(info -> {
                    int suspended = info.status().isSuspended() ? 1 : 0;
                    metric.set("suspended", suspended, context);
                    metric.set("allowedToBeDown", suspended, context); // remove summer 2020.
                    long suspendedSeconds = info.suspendedSince()
                            .map(suspendedSince -> Duration.between(suspendedSince, clock.instant()).getSeconds())
                            .orElse(0L);
                    metric.set("suspendedSeconds", suspendedSeconds, context);
                });

        long numberOfServices;
        List<ServiceInstance> services = serviceModel.getServiceInstancesByHostName().get(hostname);
        if (services == null) {
            numberOfServices = 0;
        } else {
            Map<ServiceStatus, Long> servicesCount = services.stream().collect(
                    Collectors.groupingBy(ServiceInstance::serviceStatus, Collectors.counting()));

            numberOfServices = servicesCount.values().stream().mapToLong(Long::longValue).sum();

            metric.set(
                    "numberOfServicesUp",
                    servicesCount.getOrDefault(ServiceStatus.UP, 0L),
                    context);

            metric.set(
                    "numberOfServicesNotChecked",
                    servicesCount.getOrDefault(ServiceStatus.NOT_CHECKED, 0L),
                    context);

            long numberOfServicesDown = servicesCount.getOrDefault(ServiceStatus.DOWN, 0L);
            metric.set("numberOfServicesDown", numberOfServicesDown, context);

            metric.set("someServicesDown", (numberOfServicesDown > 0 ? 1 : 0), context);

            boolean badNode = NodeFailer.badNode(services);
            metric.set("nodeFailerBadNode", (badNode ? 1 : 0), context);

            boolean nodeDownInNodeRepo = node.history().event(History.Event.Type.down).isPresent();
            metric.set("downInNodeRepo", (nodeDownInNodeRepo ? 1 : 0), context);
        }

        metric.set("numberOfServices", numberOfServices, context);
    }

    private static String toApp(ApplicationId applicationId) {
        return applicationId.application().value() + "." + applicationId.instance().value();
    }

    /**
     * A version 6.163.20 will be returned as a number 163.020. The major
     * version can normally be inferred. As long as the micro version stays
     * below 1000 these numbers sort like Version.
     */
    private static double getVersionAsNumber(Version version) {
        return version.getMinor() + version.getMicro() / 1000.0;
    }

    private Metric.Context getContextAt(String... point) {
        if (point.length % 2 != 0)
            throw new IllegalArgumentException("Dimension specification comes in pairs");

        Map<String, String> dimensions = new HashMap<>();
        for (int i = 0; i < point.length; i += 2) {
            dimensions.put(point[i], point[i + 1]);
        }

        return contextMap.computeIfAbsent(dimensions, metric::createContext);
    }

    private void updateStateMetrics(NodeList nodes) {
        Map<Node.State, List<Node>> nodesByState = nodes.nodeType(NodeType.tenant).asList().stream()
                .collect(Collectors.groupingBy(Node::state));

        // Metrics pr state
        for (Node.State state : Node.State.values()) {
            List<Node> nodesInState = nodesByState.getOrDefault(state, List.of());
            metric.set("hostedVespa." + state.name() + "Hosts", nodesInState.size(), null);
        }
    }

    private void updateLockMetrics() {
        LockStats.getGlobal().getLockMetricsByPath()
                .forEach((lockPath, lockMetrics) -> {
                    Metric.Context context = getContextAt("lockPath", lockPath);

                    metric.set("lockAttempt.acquire", lockMetrics.getAndResetAcquireCount(), context);
                    metric.set("lockAttempt.acquireFailed", lockMetrics.getAndResetAcquireFailedCount(), context);
                    metric.set("lockAttempt.acquireTimedOut", lockMetrics.getAndResetAcquireTimedOutCount(), context);
                    metric.set("lockAttempt.locked", lockMetrics.getAndResetAcquireSucceededCount(), context);
                    metric.set("lockAttempt.release", lockMetrics.getAndResetReleaseCount(), context);
                    metric.set("lockAttempt.releaseFailed", lockMetrics.getAndResetReleaseFailedCount(), context);
                    metric.set("lockAttempt.reentry", lockMetrics.getAndResetReentryCount(), context);

                    setLockLatencyMetrics("acquire", lockMetrics.getAndResetAcquireLatencyMetrics(), context);
                    setLockLatencyMetrics("locked", lockMetrics.getAndResetLockedLatencyMetrics(), context);
                });
    }

    private void setLockLatencyMetrics(String name, LatencyMetrics latencyMetrics, Metric.Context context) {
        metric.set("lockAttempt." + name + "Latency", latencyMetrics.latencySeconds(), context);
        metric.set("lockAttempt." + name + "MaxActiveLatency", latencyMetrics.maxActiveLatencySeconds(), context);
        metric.set("lockAttempt." + name + "Hz", latencyMetrics.startHz(), context);
        metric.set("lockAttempt." + name + "Load", latencyMetrics.load(), context);
    }

    private void updateDockerMetrics(NodeList nodes) {
        NodeResources totalCapacity = getCapacityTotal(nodes);
        metric.set("hostedVespa.docker.totalCapacityCpu", totalCapacity.vcpu(), null);
        metric.set("hostedVespa.docker.totalCapacityMem", totalCapacity.memoryGb(), null);
        metric.set("hostedVespa.docker.totalCapacityDisk", totalCapacity.diskGb(), null);

        NodeResources totalFreeCapacity = getFreeCapacityTotal(nodes);
        metric.set("hostedVespa.docker.freeCapacityCpu", totalFreeCapacity.vcpu(), null);
        metric.set("hostedVespa.docker.freeCapacityMem", totalFreeCapacity.memoryGb(), null);
        metric.set("hostedVespa.docker.freeCapacityDisk", totalFreeCapacity.diskGb(), null);
    }

    private void updateTenantUsageMetrics(NodeList nodes) {
        nodes.nodeType(NodeType.tenant).stream()
                .filter(node -> node.allocation().isPresent())
                .collect(Collectors.groupingBy(node -> node.allocation().get().owner()))
                .forEach(
                        (applicationId, applicationNodes) -> {
                            var allocatedCapacity = applicationNodes.stream()
                                    .map(node -> node.allocation().get().requestedResources().justNumbers())
                                    .reduce(new NodeResources(0, 0, 0, 0, any), NodeResources::add);

                            var context = getContextAt(
                                    "tenantName", applicationId.tenant().value(),
                                    "applicationId", applicationId.serializedForm().replace(':', '.'),
                                    "app", toApp(applicationId));

                            metric.set("hostedVespa.docker.allocatedCapacityCpu", allocatedCapacity.vcpu(), context);
                            metric.set("hostedVespa.docker.allocatedCapacityMem", allocatedCapacity.memoryGb(), context);
                            metric.set("hostedVespa.docker.allocatedCapacityDisk", allocatedCapacity.diskGb(), context);
                        }
                );
    }

    private static NodeResources getCapacityTotal(NodeList nodes) {
        return nodes.hosts().state(active).asList().stream()
                .map(host -> host.flavor().resources())
                .map(NodeResources::justNumbers)
                .reduce(new NodeResources(0, 0, 0, 0, any), NodeResources::add);
    }

    private static NodeResources getFreeCapacityTotal(NodeList nodes) {
        return nodes.hosts().state(active).asList().stream()
                .map(n -> freeCapacityOf(nodes, n))
                .map(NodeResources::justNumbers)
                .reduce(new NodeResources(0, 0, 0, 0, any), NodeResources::add);
    }

    private static NodeResources freeCapacityOf(NodeList nodes, Node dockerHost) {
        return nodes.childrenOf(dockerHost).asList().stream()
                .map(node -> node.flavor().resources().justNumbers())
                .reduce(dockerHost.flavor().resources().justNumbers(), NodeResources::subtract);
    }
}
