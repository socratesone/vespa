package com.yahoo.vespa.hosted.node.admin.integrationTests;

import com.yahoo.vespa.hosted.node.admin.ComponentsProvider;
import com.yahoo.vespa.hosted.node.admin.NodeAdminStateUpdater;
import com.yahoo.vespa.hosted.node.admin.noderepository.NodeRepository;

/**
 * For setting up test with mocks.
 *
 * @author dybis
 */
public class ComponentsProviderWithMocks implements ComponentsProvider {
    private NodeRepoMock nodeRepositoryMock = new NodeRepoMock();
    private NodeAdminMock nodeAdminMock = new NodeAdminMock();
    private OrchestratorMock orchestratorMock = new OrchestratorMock();

    @Override
    public NodeAdminStateUpdater getNodeAdminStateUpdater() {
        return new NodeAdminStateUpdater(nodeRepositoryMock, nodeAdminMock, 1, 5, orchestratorMock, "hostname");
    }
}
