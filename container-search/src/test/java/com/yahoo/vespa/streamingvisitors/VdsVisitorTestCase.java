// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.streamingvisitors;

import com.yahoo.document.select.parser.ParseException;
import com.yahoo.documentapi.*;
import com.yahoo.documentapi.messagebus.loadtypes.LoadType;
import com.yahoo.documentapi.messagebus.loadtypes.LoadTypeSet;
import com.yahoo.documentapi.messagebus.protocol.DocumentProtocol;
import com.yahoo.documentapi.messagebus.protocol.DocumentSummaryMessage;
import com.yahoo.documentapi.messagebus.protocol.QueryResultMessage;
import com.yahoo.documentapi.messagebus.protocol.SearchResultMessage;
import com.yahoo.messagebus.Message;
import com.yahoo.messagebus.Trace;
import com.yahoo.messagebus.routing.Route;
import com.yahoo.prelude.fastsearch.TimeoutException;
import com.yahoo.search.Query;
import com.yahoo.search.grouping.vespa.GroupingExecutor;
import com.yahoo.searchlib.aggregation.Grouping;
import com.yahoo.text.Utf8String;
import com.yahoo.vdslib.DocumentSummary;
import com.yahoo.vdslib.SearchResult;
import com.yahoo.vespa.objects.BufferSerializer;
import org.junit.Test;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author <a href="mailto:ulf@yahoo-inc.com">Ulf Carlin</a>
 */
public class VdsVisitorTestCase {
    private LoadTypeSet loadTypeSet = new LoadTypeSet();

    public VdsVisitorTestCase() {
        loadTypeSet.addLoadType(1, "low", DocumentProtocol.Priority.LOW_1);
        loadTypeSet.addLoadType(2, "normal", DocumentProtocol.Priority.NORMAL_1);
    }

    private SearchResult createSR(String docId, double rank) {
        BufferSerializer serializer = new BufferSerializer();
        serializer.putInt(null, 2); // total hits
        serializer.putInt(null, 1); // hit count
        serializer.put(null, docId);
        serializer.putDouble(null, rank);
        serializer.putInt(null, 0); // sort blob count
        serializer.putInt(null, 0); // aggregator count
        serializer.putInt(null, 0); // grouping count
        serializer.getBuf().flip();
        return new SearchResult(serializer);
    }

    private DocumentSummary createDS(String docId) {
        BufferSerializer serializer = new BufferSerializer();
        serializer.putInt(null, 0); // old seq id
        serializer.putInt(null, 1); // summary count
        serializer.put(null, docId);
        serializer.putInt(null, 1); // summary size
        serializer.putInt(null, 0); // summary buffer
        serializer.getBuf().flip();
        return new DocumentSummary(serializer);
    }

    private QueryResultMessage createQRM(String docId, double rank) {
        QueryResultMessage qrm = new QueryResultMessage();
        qrm.setSearchResult(createSR(docId, rank));
        qrm.setSummary(createDS(docId));
        return qrm;
    }

    private SearchResultMessage createSRM(String docId, double rank) {
        SearchResultMessage srm = new SearchResultMessage();
        srm.setSearchResult(createSR(docId, rank));
        return srm;
    }

    private DocumentSummaryMessage createDSM(String docId) {
        DocumentSummaryMessage dsm = new DocumentSummaryMessage();
        dsm.setDocumentSummary(createDS(docId));
        return dsm;
    }

    private Message createM() {
        return new Message() {
            @Override
            public Utf8String getProtocol() {
                return null;
            }

            @Override
            public int getType() {
                return 0;
            }
        };
    }

    private class QueryArguments {
        // General query parameters
        String query = "test";
        double timeout = 0.5;
        int offset = 0;
        int hits = 10;
        int traceLevel = 0;
        String summary = null;
        String profile = null;
        String location = null; // "pos.ll=N37.416383;W122.024683" requires PosSearcher?
        String sortSpec = null;
        String rankProperties = null;

        // Streaming query parameters
        String userId = null;
        String groupName = null;
        String selection = null;
        long from = 0;
        long to = 0;
        String loadTypeName = null;
        DocumentProtocol.Priority priority = null;
        int maxBucketsPerVisitor = 0;

        // Parameters in query object
        boolean defineGrouping = false; // "select=all(group(customer) each(output(count())))" requires GroupingQueryParser?

        void setNonDefaults() {
            query = "newquery";
            timeout = 10;
            offset = 1;
            hits = 1;
            traceLevel = 100;
            summary = "fancysummary";
            profile = "fancyprofile";
            location = "(2,10000,2000,0,0,1,0)";
            sortSpec = "+surname -yearofbirth";
            rankProperties = "rankfeature.something=2";

            userId = "1234";
            groupName = null;
            selection = null;
            from = 123;
            to = 456;
            loadTypeName = "low";
            priority = DocumentProtocol.Priority.HIGH_2;
            maxBucketsPerVisitor = 2;

            defineGrouping = true;
        }
    }

    private Query buildQuery(QueryArguments qa) throws Exception {
        StringBuilder queryString = new StringBuilder();
        queryString.append("/?query=").append(qa.query);
        if (qa.timeout != 0.5) {
            queryString.append("&timeout=").append(qa.timeout);
        }
        if (qa.offset != 0) {
            queryString.append("&offset=").append(qa.offset);
        }
        if (qa.hits != 10) {
            queryString.append("&hits=").append(qa.hits);
        }
        if (qa.traceLevel != 0) {
            queryString.append("&tracelevel=").append(qa.traceLevel);
        }
        if (qa.summary != null) {
            queryString.append("&summary=").append(qa.summary);
        }
        if (qa.profile != null) {
            queryString.append("&ranking.profile=").append(qa.profile);
        }
        if (qa.location != null) {
            queryString.append("&location=").append(qa.location);
        }
        if (qa.sortSpec != null) {
            queryString.append("&sorting=").append(URLEncoder.encode(qa.sortSpec, "UTF-8"));
        }
        if (qa.rankProperties != null) {
            queryString.append("&").append(qa.rankProperties);
        }

        if (qa.userId != null) {
            queryString.append("&streaming.userid=").append(qa.userId);
        }
        if (qa.groupName != null) {
            queryString.append("&streaming.groupname=").append(qa.groupName);
        }
        if (qa.selection != null) {
            queryString.append("&streaming.selection=").append(URLEncoder.encode(qa.selection, "UTF-8"));
        }
        if (qa.from != 0) {
            queryString.append("&streaming.fromtimestamp=").append(qa.from);
        }
        if (qa.to != 0) {
            queryString.append("&streaming.totimestamp=").append(qa.to);
        }
        if (qa.loadTypeName != null) {
            queryString.append("&streaming.loadtype=").append(qa.loadTypeName);
        }
        if (qa.priority != null) {
            queryString.append("&streaming.priority=").append(qa.priority);
        }
        if (qa.maxBucketsPerVisitor != 0) {
            queryString.append("&streaming.maxbucketspervisitor=").append(qa.maxBucketsPerVisitor);
        }
        //System.out.println("query string="+queryString.toString());

        Query query = new Query(queryString.toString());
        if (qa.defineGrouping) {
            List<Grouping> groupingList = new ArrayList<>();
            groupingList.add(new Grouping());
            query.properties().set(GroupingExecutor.class.getName() + ".GroupingList", groupingList);
        }
        return query;
    }

    private void verifyVisitorParameters(VisitorParameters params, QueryArguments qa, String searchCluster, String docType, Route route) {
        //System.out.println("params="+params);
        // Verify parameters based on properties
        if (qa.userId != null) {
            assertEquals(docType + " and ( id.user=="+qa.userId + " )", params.getDocumentSelection());
        } else if (qa.groupName != null) {
            assertEquals(docType + " and ( id.group==\""+qa.groupName+"\" )", params.getDocumentSelection());
        } else if ((qa.selection == null) || qa.selection.isEmpty()) {
            assertEquals(docType, params.getDocumentSelection());
        } else {
            assertEquals(docType + " and ( " + qa.selection + " )", params.getDocumentSelection());
        }
        assertEquals(qa.from, params.getFromTimestamp());
        assertEquals(qa.to, params.getToTimestamp());
        if (qa.loadTypeName != null && loadTypeSet.getNameMap().get(qa.loadTypeName) != null) {
            LoadType expectedLoadType = loadTypeSet.getNameMap().get(qa.loadTypeName);
            assertEquals(expectedLoadType, params.getLoadType());
            if (qa.priority != null) {
                assertEquals(qa.priority, params.getPriority());
            } else {
                assertEquals(expectedLoadType.getPriority(), params.getPriority());
            }
        } else {
            assertEquals(LoadType.DEFAULT, params.getLoadType());
            if (qa.priority != null) {
                assertEquals(qa.priority, params.getPriority());
            } else {
                assertEquals(DocumentProtocol.Priority.VERY_HIGH, params.getPriority());
            }
        }
        assertEquals(-1, params.getMaxFirstPassHits());
        if (qa.maxBucketsPerVisitor != 0) {
            assertEquals(qa.maxBucketsPerVisitor, params.getMaxBucketsPerVisitor());
        } else {
            assertEquals(VdsVisitor.MAX_BUCKETS_PER_VISITOR, params.getMaxBucketsPerVisitor());
        }
        assertEquals(false, params.getDynamicallyIncreaseMaxBucketsPerVisitor());

        // Verify parameters based only on query
        assertEquals(qa.timeout*1000, params.getTimeoutMs(),0.0000001);
        assertEquals(qa.timeout*1000, params.getSessionTimeoutMs(), 0.0000001);
        assertEquals("searchvisitor", params.getVisitorLibrary());
        assertEquals(Integer.MAX_VALUE, params.getMaxPending());
        assertEquals(qa.traceLevel, params.getTraceLevel());

        // Verify library parameters
        //System.err.println("query="+new String(params.getLibraryParameters().get("query")));
        assertNotNull(params.getLibraryParameters().get("query")); // TODO: Check contents
        //System.err.println("query="+new String(params.getLibraryParameters().get("querystackcount")));
        assertNotNull(params.getLibraryParameters().get("querystackcount")); // TODO: Check contents
        assertEquals(searchCluster, new String(params.getLibraryParameters().get("searchcluster")));
        if (qa.summary != null) {
            assertEquals(qa.summary, new String(params.getLibraryParameters().get("summaryclass")));
        } else {
            assertEquals("default", new String(params.getLibraryParameters().get("summaryclass")));
        }
        assertEquals(Integer.toString(qa.offset+qa.hits), new String(params.getLibraryParameters().get("summarycount")));
        if (qa.profile != null) {
            assertEquals(qa.profile, new String(params.getLibraryParameters().get("rankprofile")));
        } else {
            assertEquals("default", new String(params.getLibraryParameters().get("rankprofile")));
        }
        //System.err.println("queryflags="+new String(params.getLibraryParameters().get("queryflags")));
        assertNotNull(params.getLibraryParameters().get("queryflags")); // TODO: Check contents
        if (qa.location != null) {
            assertEquals(qa.location, new String(params.getLibraryParameters().get("location")));
        } else {
            assertNull(params.getLibraryParameters().get("location"));
        }
        if (qa.rankProperties != null) {
            //System.err.println("rankProperties="+new String(params.getLibraryParameters().get("rankproperties")));
            assertNotNull(params.getLibraryParameters().get("rankproperties")); // TODO: Check contents
        } else {
            assertNull(params.getLibraryParameters().get("rankproperties"));
        }
        if (qa.defineGrouping) {
            //System.err.println("aggregation="+new String(params.getLibraryParameters().get("aggregation")));
            assertNotNull(params.getLibraryParameters().get("aggregation")); // TODO: Check contents
        } else {
            assertNull(params.getLibraryParameters().get("aggregation"));
        }
        if (qa.sortSpec != null) {
            assertEquals(qa.sortSpec, new String(params.getLibraryParameters().get("sort")));
        } else {
            assertNull(params.getLibraryParameters().get("sort"));
        }

        assertEquals(route, params.getRoute());
    }

    @Test
    public void testGetQueryFlags() {
        assertEquals(0x00028000, VdsVisitor.getQueryFlags(new Query("/?query=test")));
        assertEquals(0x00028080, VdsVisitor.getQueryFlags(new Query("/?query=test&hitcountestimate=true")));
        assertEquals(0x00068000, VdsVisitor.getQueryFlags(new Query("/?query=test&rankfeatures=true")));
        assertEquals(0x00068080, VdsVisitor.getQueryFlags(new Query("/?query=test&hitcountestimate=true&rankfeatures=true")));

        Query query= new Query("/?query=test");
        assertEquals(0x00028000, VdsVisitor.getQueryFlags(query));
        query.setNoCache(true);
        assertEquals(0x00038000, VdsVisitor.getQueryFlags(query));
        query.getRanking().setFreshness("now");
        assertEquals(0x0003a000, VdsVisitor.getQueryFlags(query));
    }

    @Test
    public void testBasics() throws Exception {
        Route route = Route.parse("storageClusterRouteSpec");
        String searchCluster = "searchClusterConfigId";
        MockVisitorSessionFactory factory = new MockVisitorSessionFactory(loadTypeSet);

        // Default values and no selection
        QueryArguments qa = new QueryArguments();
        verifyVisitorOk(factory, qa, route, searchCluster);

        // Groupdoc
        qa.groupName = "group";
        qa.maxBucketsPerVisitor = 2; // non-default maxBucketsPerVisitor
        qa.loadTypeName = "normal"; // non-default loadTypeName, default priority
        verifyVisitorOk(factory, qa, route, searchCluster);

        qa.loadTypeName = "unknown"; // unknown loadTypeName, default priority
        verifyVisitorOk(factory, qa, route, searchCluster);

        qa.priority = DocumentProtocol.Priority.NORMAL_2; // unknown loadTypeName, non-default priority
        verifyVisitorOk(factory, qa, route, searchCluster);

        // Userdoc and lots of non-default parameters
        qa.setNonDefaults();
        verifyVisitorOk(factory, qa, route, searchCluster);
    }

    @Test
    public void testFailures() throws Exception {
        Route route = Route.parse("storageClusterRouteSpec");
        String searchCluster = "searchClusterConfigId";
        MockVisitorSessionFactory factory = new MockVisitorSessionFactory(loadTypeSet);

        // Default values and no selection
        QueryArguments qa = new QueryArguments();

        factory.failQuery = true;
        verifyVisitorFails(factory, qa, route, searchCluster);

        factory.failQuery = false;
        factory.timeoutQuery = true;
        verifyVisitorFails(factory, qa, route, searchCluster);
    }

    private void verifyVisitorOk(MockVisitorSessionFactory factory, QueryArguments qa, Route route, String searchCluster) throws Exception {
        VdsVisitor visitor = new VdsVisitor(buildQuery(qa), searchCluster, route, "mytype", factory, 0);
        visitor.doSearch();
        verifyVisitorParameters(factory.getParams(), qa, searchCluster, "mytype", route);
        supplyResults(visitor);
        verifyResults(qa, visitor);
    }

    private void verifyVisitorFails(MockVisitorSessionFactory factory, QueryArguments qa, Route route, String searchCluster) throws Exception {
        VdsVisitor visitor = new VdsVisitor(buildQuery(qa), searchCluster, route, "mytype", factory, 0);
        try {
            visitor.doSearch();
            assertTrue("Visitor did not fail", false);
        } catch (TimeoutException te) {
            assertTrue("Got TimeoutException unexpectedly", factory.timeoutQuery);
        } catch (IllegalArgumentException iae) {
            assertTrue("Got IllegalArgumentException unexpectedly", factory.failQuery);
        }
    }

    private void supplyResults(VdsVisitor visitor) {
        AckToken ackToken = null;
        visitor.onMessage(createQRM("id:ns:type::0", 0.3), ackToken);
        visitor.onMessage(createSRM("id:ns:type::1", 1.0), ackToken);
        visitor.onMessage(createSRM("id:ns:type::2", 0.5), ackToken);
        visitor.onMessage(createDSM("id:ns:type::1"), ackToken);
        visitor.onMessage(createDSM("id:ns:type::2"), ackToken);
        try {
            visitor.onMessage(createM(), ackToken);
            assertTrue("Unsupported message did not cause exception", false);
        } catch (UnsupportedOperationException uoe) {
            assertTrue(uoe.getMessage().contains("VdsVisitor can only accept query result, search result, and documentsummary messages"));
        }
    }

    private void verifyResults(QueryArguments qa, VdsVisitor visitor) {
        assertEquals(6, visitor.getTotalHitCount());
        assertEquals(Math.min(3 - qa.offset, qa.hits), visitor.getHits().size());
        assertEquals(3, visitor.getSummaryMap().size());
        assertEquals(0, visitor.getGroupings().size());
        assertNull(visitor.getStatistics());

        for (int i=0; i<visitor.getHits().size(); ++i) {
            SearchResult.Hit hit = visitor.getHits().get(i);
            int index = qa.offset + i;
            if (index==0) {
                assertEquals("id:ns:type::1", hit.getDocId());
                assertEquals(1.0, hit.getRank(), 0.01);
            } else if (index==1) {
                assertEquals("id:ns:type::2", hit.getDocId());
                assertEquals(0.5, hit.getRank(), 0.01);
            } else if (index==2) {
                assertEquals("id:ns:type::0", hit.getDocId());
                assertEquals(0.3, hit.getRank(), 0.01);
            } else {
                assertTrue("Got too many hits", false);
            }
            DocumentSummary.Summary summary = visitor.getSummaryMap().get(hit.getDocId());
            assertNotNull("Did not find summary for " + hit.getDocId(), summary);
        }
    }

    private static class MockVisitorSession implements VisitorSession {
        private VisitorParameters params;
        private boolean timeoutQuery = false;
        private boolean failQuery = false;

        public MockVisitorSession(VisitorParameters params, boolean timeoutQuery, boolean failQuery) {
            this.params = params;
            params.setControlHandler(new VisitorControlHandler());
            params.getLocalDataHandler().setSession(this);
            this.timeoutQuery = timeoutQuery;
            this.failQuery = failQuery;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public ProgressToken getProgress() {
            return null;
        }

        @Override
        public Trace getTrace() {
            return new Trace();
        }

        @Override
        public boolean waitUntilDone(long l) throws InterruptedException {
            if (timeoutQuery) {
                return false;
            }
            VisitorControlHandler.CompletionCode code = VisitorControlHandler.CompletionCode.SUCCESS;
            if (failQuery) {
                code = VisitorControlHandler.CompletionCode.FAILURE;
            }
            params.getControlHandler().onDone(code, "Message");
            return true;
        }

        @Override
        public void ack(AckToken ackToken) {
        }

        @Override
        public void abort() {
        }

        @Override
        public VisitorResponse getNext() {
            return null;
        }

        @Override
        public VisitorResponse getNext(int i) throws InterruptedException {
            return null;
        }

        @Override
        public void destroy() {
        }
    }

    private static class MockVisitorSessionFactory implements VdsVisitor.VisitorSessionFactory {
        private VisitorParameters params;
        private LoadTypeSet loadTypeSet;
        private boolean timeoutQuery = false;
        private boolean failQuery = false;

        private MockVisitorSessionFactory(LoadTypeSet loadTypeSet) {
            this.loadTypeSet = loadTypeSet;
        }

        @Override
        public VisitorSession createVisitorSession(VisitorParameters params) throws ParseException {
            this.params = params;
            return new MockVisitorSession(params, timeoutQuery, failQuery);
        }

        @Override
        public LoadTypeSet getLoadTypeSet() {
            return loadTypeSet;
        }

        public VisitorParameters getParams() {
            return params;
        }
    }

}
