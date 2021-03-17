// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.google.common.collect.Sets;
import com.yahoo.vdslib.state.ClusterState;
import com.yahoo.vespa.clustercontroller.core.hostinfo.HostInfo;
import com.yahoo.vespa.clustercontroller.core.status.statuspage.VdsClusterHtmlRenderer;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONWriter;
import org.junit.Before;
import org.junit.Test;

import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.TreeMap;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

public class ContentClusterHtmlRendrerTest {
    private final VdsClusterHtmlRenderer renderer = new VdsClusterHtmlRenderer();
    private final static int slobrokGeneration = 34;
    private final static String clusterName = "clustername";
    private final TreeMap<Integer, NodeInfo> storageNodeInfoByIndex = new TreeMap<>();
    private final TreeMap<Integer, NodeInfo> distributorNodeInfoByIndex = new TreeMap<>();
    private String result;

    @Before
    public void before() throws JSONException {
        final ClusterStateBundle stateBundle = ClusterStateBundle.ofBaselineOnly(
                AnnotatedClusterState.withoutAnnotations(
                        ClusterState.stateFromString("version:34633 bits:24 distributor:211 storage:211")));
        final EventLog eventLog = new EventLog(new FakeTimer(), null);

        final VdsClusterHtmlRenderer.Table table = renderer.createNewClusterHtmlTable(clusterName, slobrokGeneration);

        final ContentCluster contentCluster = mock(ContentCluster.class);

        for (int x = 0; x < 10; x++) {
            NodeInfo nodeInfo = new DistributorNodeInfo(contentCluster, x, "dist " + x, null);
            final Writer writer = new StringWriter();
            new JSONWriter(writer)
                    .object().key("vtag")
                    // Let one node have a different release tag.
                    .object().key("version").value("release1" + (x == 2 ? "bad" : ""))
                    .endObject()
                    .endObject();
            nodeInfo.setHostInfo(HostInfo.createHostInfo(writer.toString()));
            distributorNodeInfoByIndex.put(x, nodeInfo);
        }
        storageNodeInfoByIndex.put(2, new StorageNodeInfo(contentCluster, 2, false, "storage" + 2, null));
        ClusterStatsAggregator statsAggregator = new ClusterStatsAggregator(Sets.newHashSet(2), Sets.newHashSet(2));

        table.renderNodes(
                storageNodeInfoByIndex,
                distributorNodeInfoByIndex,
                new FakeTimer(),
                stateBundle,
                statsAggregator,
                1.0,
                10,
                Collections.emptyMap(),
                eventLog,
                "pathPrefix",
                "name");
        final StringBuilder stringBuilder = new StringBuilder();
        table.addTable(stringBuilder, 34);
        result = stringBuilder.toString();
    }

    @Test
    public void testVtagRendering() {
        // 9 distribution nodes should have green tag on release1.
        assertThat(result.split("<td bgcolor=\"#c0ffc0\" align=\"right\"><nobr>release1</nobr></td>").length, is(10));
        // 1 distribution node should have warning on release1bad.
        assertThat(result.split("<td bgcolor=\"#ffffc0\" align=\"right\"><nobr>release1bad</nobr></td>").length, is(2));
        // 1 storage node should should have warning on release "not set".
        assertThat(result.split("<td bgcolor=\"#ffffc0\" align=\"right\"><nobr>not set</nobr></td>").length, is(2));
    }
}
