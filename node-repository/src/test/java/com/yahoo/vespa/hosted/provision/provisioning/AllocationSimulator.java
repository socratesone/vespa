// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.Generation;
import com.yahoo.vespa.hosted.provision.node.IP;

import javax.swing.JFrame;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;


/**
 * Graphically run allocation procedure to ease manual comprehension.
 *
 * Extremely useful when trying to understand test cases and build corner cases.
 */
public class AllocationSimulator {

    private AllocationVisualizer visualizer;
    private NodeList nodes;
    private NodeFlavors flavors;

    private AllocationSimulator(AllocationVisualizer visualizer) {
        this.visualizer = visualizer;

        //
        // Setup flavors
        //
        FlavorConfigBuilder b = new FlavorConfigBuilder();
        b.addFlavor("host-large", 8., 8., 8, 8, Flavor.Type.BARE_METAL);
        b.addFlavor("host-small", 5., 5., 5, 8, Flavor.Type.BARE_METAL);
        b.addFlavor("d-1", 1, 1., 1, 1, Flavor.Type.DOCKER_CONTAINER);
        b.addFlavor("d-2", 2, 2., 2, 2, Flavor.Type.DOCKER_CONTAINER);
        b.addFlavor("d-3", 3, 3., 3, 3, Flavor.Type.DOCKER_CONTAINER);
        flavors = new NodeFlavors(b.build());

        //
        // Initiate nodes in system
        //
        List<Node> initialNodes = new ArrayList<>();
        initialNodes.add(host("host1", flavors.getFlavorOrThrow("host-large")));
        initialNodes.add(host("host2", flavors.getFlavorOrThrow("host-large")));
        initialNodes.add(host("host3", flavors.getFlavorOrThrow("host-large")));
        initialNodes.add(host("host4", flavors.getFlavorOrThrow("host-large")));
        initialNodes.add(host("host5", flavors.getFlavorOrThrow("host-large")));
        initialNodes.add(host("host6", flavors.getFlavorOrThrow("host-large")));
        initialNodes.add(host("host7", flavors.getFlavorOrThrow("host-small")));
        initialNodes.add(host("host8", flavors.getFlavorOrThrow("host-small")));
        initialNodes.add(host("host9", flavors.getFlavorOrThrow("host-small")));
        initialNodes.add(host("host10", flavors.getFlavorOrThrow("host-small")));
        initialNodes.add(node("node1", flavors.getFlavorOrThrow("d-2"), Optional.of("host1"), Optional.of("test")));
        nodes = NodeList.copyOf(initialNodes);

        visualizer.addStep(nodes.asList(), "Initial state", "");
    }

    /* ------------ Create node and flavor methods ----------------*/

    private Node host(String hostname, Flavor flavor) {
        return node(hostname, flavor, Optional.empty(), Optional.empty());
    }

    private Node node(String hostname, Flavor flavor, Optional<String> parent, Optional<String> tenant) {
        Node.Builder builder = Node.create("fake", hostname, flavor,
                parent.isPresent() ? Node.State.ready : Node.State.active,
                parent.isPresent() ? NodeType.tenant : NodeType.host)
                .ipConfig(IP.Config.of(Set.of("127.0.0.1"), parent.isPresent() ? Set.of() : getAdditionalIP(), List.of()));
        parent.ifPresent(builder::parentHostname);
        allocation(tenant, flavor).ifPresent(builder::allocation);

        return builder.build();
    }

    private Set<String> getAdditionalIP() {
        Set<String> h = new HashSet<String>();
        Collections.addAll(h, "::1", "::2", "::3", "::4", "::5", "::6", "::7", "::8");
        return h;
    }

    private Optional<Allocation> allocation(Optional<String> tenant, Flavor flavor) {
        if (tenant.isPresent()) {
            Allocation allocation = new Allocation(app(tenant.get()),
                                                   ClusterMembership.from("container/id1/3", new Version(), Optional.empty()),
                                                   flavor.resources(),
                                                   Generation.initial(),
                                                   false);
            return Optional.of(allocation);
        }
        return Optional.empty();
    }

    private ApplicationId app(String tenant) {
        return new ApplicationId.Builder()
                .tenant(tenant)
                .applicationName("test")
                .instanceName("default").build();
    }

    /* ------------ Methods to add events to the system ----------------*/

    public void addCluster(String task, int count, Flavor flavor, String id) {
        // TODO: Implement
        nodes = NodeList.copyOf(nodes.asList());
    }


    public static void main(String[] args) {

        AllocationVisualizer visualizer = new AllocationVisualizer();

        javax.swing.SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Allocation Simulator");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(visualizer);
            frame.pack();
            frame.setVisible(true);
        });

        AllocationSimulator simulator = new AllocationSimulator(visualizer);
        simulator.addCluster("App1 : 3 * d-1 nodes", 3, simulator.flavors.getFlavorOrThrow("d-1"), "App1");
        simulator.addCluster("App2 : 2 * d-2 nodes", 2, simulator.flavors.getFlavorOrThrow("d-2"), "App2");
        simulator.addCluster("App3 : 3 * d-2 nodes", 3, simulator.flavors.getFlavorOrThrow("d-2"), "App3");
        simulator.addCluster("App4 : 3 * d-3 nodes", 3, simulator.flavors.getFlavorOrThrow("d-3"), "App4");
        simulator.addCluster("App5 : 3 * d-3 nodes", 3, simulator.flavors.getFlavorOrThrow("d-3"), "App5");
        simulator.addCluster("App6 : 5 * d-2 nodes", 5, simulator.flavors.getFlavorOrThrow("d-2"), "App6");
    }

}
