// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.rpc;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.cloud.config.LbServicesConfig;
import com.yahoo.cloud.config.SentinelConfig;
import com.yahoo.component.Version;
import com.yahoo.config.SimpletypesConfig;
import com.yahoo.config.model.test.MockApplicationPackage;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.jrt.Request;
import com.yahoo.vespa.config.ConfigKey;
import com.yahoo.vespa.config.ConfigPayload;
import com.yahoo.vespa.config.ConfigPayloadApplier;
import com.yahoo.vespa.config.ErrorCode;
import com.yahoo.vespa.config.RawConfig;
import com.yahoo.vespa.config.protocol.CompressionType;
import com.yahoo.vespa.config.protocol.JRTClientConfigRequest;
import com.yahoo.vespa.config.protocol.JRTClientConfigRequestV3;
import com.yahoo.vespa.config.protocol.Trace;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.ServerCache;
import com.yahoo.vespa.config.server.application.Application;
import com.yahoo.vespa.config.server.application.ApplicationSet;
import com.yahoo.vespa.config.server.monitoring.MetricUpdater;
import com.yahoo.vespa.config.server.session.PrepareParams;
import com.yahoo.vespa.model.VespaModel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * @author Ulf Lilleengen
 */
public class RpcServerTest {

    private static final TenantName tenantName = TenantName.from("testTenant");
    private static final ApplicationId applicationId =
            ApplicationId.from(tenantName, ApplicationName.defaultName(), InstanceName.defaultName());
    private final static File testApp = new File("src/test/resources/deploy/validapp");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testRpcServer() throws IOException, SAXException, InterruptedException {
        try (RpcTester tester = new RpcTester(applicationId, temporaryFolder)) {
            ApplicationRepository applicationRepository = tester.applicationRepository();
            applicationRepository.deploy(testApp, new PrepareParams.Builder().applicationId(applicationId).build());
            testPrintStatistics(tester);
            testGetConfig(tester);
            testEnabled(tester);
            testApplicationNotLoadedErrorWhenAppDeleted(tester);
        }
    }

    private void testApplicationNotLoadedErrorWhenAppDeleted(RpcTester tester) {
        tester.applicationRepository().delete(applicationId);
        JRTClientConfigRequest clientReq = createSimpleRequest();
        tester.performRequest(clientReq.getRequest());
        assertFalse(clientReq.validateResponse());
        assertThat(clientReq.errorCode(), is(ErrorCode.APPLICATION_NOT_LOADED));
    }

    @Test
    public void testEmptySentinelConfigWhenAppDeletedOnHostedVespa() throws IOException, InterruptedException {
        ConfigserverConfig.Builder configBuilder = new ConfigserverConfig.Builder().canReturnEmptySentinelConfig(true);
        try (RpcTester tester = new RpcTester(applicationId, temporaryFolder, configBuilder)) {
            tester.rpcServer().onTenantDelete(tenantName);
            tester.rpcServer().onTenantsLoaded();
            JRTClientConfigRequest clientReq = createSentinelRequest();

            // Should get empty sentinel config when on hosted vespa
            tester.performRequest(clientReq.getRequest());
            assertTrue(clientReq.validateResponse());
            assertEquals(0, clientReq.errorCode());

            ConfigPayload payload = ConfigPayload.fromUtf8Array(clientReq.getNewPayload().getData());
            assertNotNull(payload);
            SentinelConfig.Builder builder = new SentinelConfig.Builder();
            new ConfigPayloadApplier<>(builder).applyPayload(payload);
            SentinelConfig config = new SentinelConfig(builder);
            assertEquals(0, config.service().size());
        }
    }

    private JRTClientConfigRequest createSimpleRequest() {
        ConfigKey<?> key = new ConfigKey<>(SimpletypesConfig.class, "");
        JRTClientConfigRequest clientReq = createRequest(new RawConfig(key, SimpletypesConfig.getDefMd5()));
        assertTrue(clientReq.validateParameters());
        return clientReq;
    }

    private JRTClientConfigRequest createSentinelRequest() {
        ConfigKey<?> key = new ConfigKey<>(SentinelConfig.class, "");
        JRTClientConfigRequest clientReq = createRequest(new RawConfig(key, SentinelConfig.getDefMd5()));
        assertTrue(clientReq.validateParameters());
        return clientReq;
    }

    private void testEnabled(RpcTester tester) throws IOException, SAXException {
        Application app = new Application(new VespaModel(MockApplicationPackage.createEmpty()),
                                          new ServerCache(),
                                          2L,
                                          new Version(1, 2, 3),
                                          MetricUpdater.createTestUpdater(),
                                          applicationId);
        ApplicationSet appSet = ApplicationSet.from(app);
        tester.rpcServer().configActivated(appSet);
        ConfigKey<?> key = new ConfigKey<>(LbServicesConfig.class, "*");
        JRTClientConfigRequest clientReq  = createRequest(new RawConfig(key, LbServicesConfig.getDefMd5()));
        assertTrue(clientReq.validateParameters());
        tester.performRequest(clientReq.getRequest());
        assertFalse(clientReq.validateResponse());
        assertThat(clientReq.errorCode(), is(ErrorCode.APPLICATION_NOT_LOADED));

        tester.rpcServer().onTenantsLoaded();
        clientReq = createRequest(new RawConfig(key, LbServicesConfig.getDefMd5()));
        assertTrue(clientReq.validateParameters());
        tester.performRequest(clientReq.getRequest());
        boolean validResponse = clientReq.validateResponse();
        assertTrue(clientReq.errorMessage(), validResponse);
        assertThat(clientReq.errorCode(), is(0));
    }

    private void testGetConfig(RpcTester tester) {
        ConfigKey<?> key = new ConfigKey<>(SimpletypesConfig.class, "brim");
        JRTClientConfigRequest req = createRequest(new RawConfig(key, SimpletypesConfig.getDefMd5()));
        assertTrue(req.validateParameters());
        tester.performRequest(req.getRequest());
        assertThat(req.errorCode(), is(0));
        assertTrue(req.validateResponse());
        ConfigPayload payload = ConfigPayload.fromUtf8Array(req.getNewPayload().getData());
        assertNotNull(payload);
        SimpletypesConfig.Builder builder = new SimpletypesConfig.Builder();
        new ConfigPayloadApplier<>(builder).applyPayload(payload);
        SimpletypesConfig config = new SimpletypesConfig(builder);
        assertThat(config.intval(), is(0));
    }

    private void testPrintStatistics(RpcTester tester) {
        Request req = new Request("printStatistics");
        tester.performRequest(req);
        assertThat(req.returnValues().get(0).asString(), is("Delayed responses queue size: 0"));
    }

    private JRTClientConfigRequest createRequest(RawConfig config) {
        return JRTClientConfigRequestV3.createFromRaw(config, 120_000, Trace.createDummy(), CompressionType.UNCOMPRESSED, Optional.empty());
    }

}
