// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.admin.clustercontroller;

import com.yahoo.cloud.config.ZookeeperServerConfig;
import com.yahoo.component.ComponentSpecification;
import com.yahoo.config.model.api.container.ContainerServiceType;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.model.producer.AbstractConfigProducer;
import com.yahoo.container.bundle.BundleInstantiationSpecification;
import com.yahoo.container.core.documentapi.DocumentAccessProvider;
import com.yahoo.container.di.config.PlatformBundlesConfig;
import com.yahoo.documentmodel.NewDocumentType;
import com.yahoo.osgi.provider.model.ComponentModel;
import com.yahoo.vespa.config.content.FleetcontrollerConfig;
import com.yahoo.vespa.config.content.reindexing.ReindexingConfig;
import com.yahoo.vespa.model.application.validation.RestartConfigs;
import com.yahoo.vespa.model.container.Container;
import com.yahoo.vespa.model.container.component.AccessLogComponent;
import com.yahoo.vespa.model.container.component.Component;
import com.yahoo.vespa.model.container.component.Handler;
import com.yahoo.vespa.model.container.component.SimpleComponent;
import com.yahoo.vespa.model.container.component.SystemBindingPattern;
import com.yahoo.vespa.model.container.xml.ContainerModelBuilder;
import com.yahoo.vespa.model.container.xml.PlatformBundles;

import java.util.Set;
import java.util.TreeSet;

/**
 * Container implementation for cluster-controllers
 */
@RestartConfigs({FleetcontrollerConfig.class, ZookeeperServerConfig.class})
public class ClusterControllerContainer extends Container implements
        PlatformBundlesConfig.Producer,
        ZookeeperServerConfig.Producer,
        ReindexingConfig.Producer
{
    private static final ComponentSpecification CLUSTERCONTROLLER_BUNDLE = new ComponentSpecification("clustercontroller-apps");
    private static final ComponentSpecification ZOOKEEPER_SERVER_BUNDLE = new ComponentSpecification("zookeeper-server");
    private static final ComponentSpecification REINDEXING_CONTROLLER_BUNDLE = new ComponentSpecification("clustercontroller-reindexer");

    private final Set<String> bundles = new TreeSet<>();

    public ClusterControllerContainer(AbstractConfigProducer<?> parent,
                                      int index,
                                      boolean runStandaloneZooKeeper,
                                      DeployState deployState,
                                      boolean retired) {
        super(parent, "" + index, retired, index, deployState.isHosted());
        addHandler("clustercontroller-status",
                   "com.yahoo.vespa.clustercontroller.apps.clustercontroller.StatusHandler",
                   "/clustercontroller-status/*",
                   CLUSTERCONTROLLER_BUNDLE);
        addHandler("clustercontroller-state-restapi-v2",
                   "com.yahoo.vespa.clustercontroller.apps.clustercontroller.StateRestApiV2Handler",
                   "/cluster/v2/*",
                   CLUSTERCONTROLLER_BUNDLE);
        addComponent(new AccessLogComponent(containerCluster().orElse(null), AccessLogComponent.AccessLogType.jsonAccessLog,
                AccessLogComponent.CompressionType.GZIP,
                "controller",
                deployState.isHosted()));

        // TODO: Why are bundles added here instead of in the cluster?
        addFileBundle("clustercontroller-apps");
        addFileBundle("clustercontroller-core");
        addFileBundle("clustercontroller-utils");
        addFileBundle("zookeeper-server");
        configureReindexing();
        configureZooKeeperServer(runStandaloneZooKeeper);
    }

    @Override
    public int getWantedPort() {
        return 19050;
    }

    @Override
    public boolean requiresWantedPort() {
        return false;
    }

    @Override
    public ContainerServiceType myServiceType() {
        return ContainerServiceType.CLUSTERCONTROLLER_CONTAINER;
    }

    private void configureZooKeeperServer(boolean runStandaloneZooKeeper) {
        if (runStandaloneZooKeeper)
            ContainerModelBuilder.addReconfigurableZooKeeperServerComponents(this);
        else
            addComponent("clustercontroller-zookeeper-server",
                         "com.yahoo.vespa.zookeeper.DummyVespaZooKeeperServer",
                         ZOOKEEPER_SERVER_BUNDLE);
    }

    private void addHandler(Handler<?> h, String path) {
        h.addServerBindings(SystemBindingPattern.fromHttpPath(path));
        super.addHandler(h);
    }

    private void addFileBundle(String bundleName) {
        bundles.add(PlatformBundles.absoluteBundlePath(bundleName).toString());
    }

    private ComponentModel createComponentModel(String id, String className, ComponentSpecification bundle) {
        return new ComponentModel(new BundleInstantiationSpecification(new ComponentSpecification(id),
                                                                       new ComponentSpecification(className),
                                                                       bundle));
    }

    private void addComponent(String id, String className, ComponentSpecification bundle) {
        addComponent(new Component<>(createComponentModel(id, className, bundle)));
    }

    private void addHandler(String id, String className, String path, ComponentSpecification bundle) {
        addHandler(new Handler<>(createComponentModel(id, className, bundle)), path);
    }

    private ReindexingContext reindexingContext() {
        return ((ClusterControllerContainerCluster) parent).reindexingContext();
    }

    private void configureReindexing() {
        addFileBundle(REINDEXING_CONTROLLER_BUNDLE.getName());
        addComponent(new SimpleComponent(DocumentAccessProvider.class.getName()));
        addComponent("reindexing-maintainer",
                     "ai.vespa.reindexing.ReindexingMaintainer",
                     REINDEXING_CONTROLLER_BUNDLE);
        addHandler("reindexing-status",
                   "ai.vespa.reindexing.http.ReindexingV1ApiHandler",
                   "/reindexing/v1/*",
                   REINDEXING_CONTROLLER_BUNDLE);
    }


    @Override
    public void getConfig(PlatformBundlesConfig.Builder builder) {
        bundles.forEach(builder::bundlePaths);
    }

    @Override
    public void getConfig(ZookeeperServerConfig.Builder builder) {
        builder.myid(index());
        builder.dynamicReconfiguration(true);
    }

    @Override
    public void getConfig(ReindexingConfig.Builder builder) {
        ReindexingContext ctx = reindexingContext();
        if (!ctx.reindexing().enabled()) {
            builder.enabled(false);
            return;
        }

        builder.enabled(ctx.reindexing().enabled());
        for (String clusterId : ctx.clusterIds()) {
            ReindexingConfig.Clusters.Builder clusterBuilder = new ReindexingConfig.Clusters.Builder();
            for (NewDocumentType type : ctx.documentTypesForCluster(clusterId)) {
                String typeName = type.getFullName().getName();
                ctx.reindexing().status(clusterId, typeName).ifPresent(
                        status -> clusterBuilder.documentTypes(
                                typeName,
                                new ReindexingConfig.Clusters.DocumentTypes.Builder()
                                        .readyAtMillis(status.ready().toEpochMilli())));
            }
            builder.clusters(clusterId, clusterBuilder);
        }
    }

    @Override
    protected String defaultPreload() {
        return "";
    }

}
