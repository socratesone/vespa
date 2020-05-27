// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.model.application.provider.*;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.io.IOUtils;
import java.util.logging.Level;
import com.yahoo.path.Path;
import com.yahoo.vespa.config.server.*;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.config.server.application.TenantApplications;
import com.yahoo.vespa.config.server.deploy.TenantFileSystemDirs;
import com.yahoo.vespa.config.server.host.HostValidator;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.config.server.zookeeper.SessionCounter;
import com.yahoo.vespa.config.server.zookeeper.ConfigCurator;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.flags.BooleanFlag;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;

import java.io.File;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Serves as the factory of sessions. Takes care of copying files to the correct folder and initializing the
 * session state.
 *
 * @author Ulf Lilleengen
 */
public class SessionFactoryImpl implements SessionFactory, LocalSessionLoader {

    private static final Logger log = Logger.getLogger(SessionFactoryImpl.class.getName());
    private static final long nonExistingActiveSession = 0;

    private final SessionPreparer sessionPreparer;
    private final Curator curator;
    private final ConfigCurator configCurator;
    private final SessionCounter sessionCounter;
    private final TenantApplications applicationRepo;
    private final Path sessionsPath;
    private final TenantFileSystemDirs tenantFileSystemDirs;
    private final HostValidator<ApplicationId> hostRegistry;
    private final TenantName tenant;
    private final String serverId;
    private final Optional<NodeFlavors> nodeFlavors;
    private final Clock clock;
    private final FlagSource flagSource;
    private final BooleanFlag distributeApplicationPackage;

    public SessionFactoryImpl(GlobalComponentRegistry globalComponentRegistry,
                              TenantApplications applicationRepo,
                              HostValidator<ApplicationId> hostRegistry,
                              TenantName tenant) {
        this.hostRegistry = hostRegistry;
        this.tenant = tenant;
        this.sessionPreparer = globalComponentRegistry.getSessionPreparer();
        this.curator = globalComponentRegistry.getCurator();
        this.configCurator = globalComponentRegistry.getConfigCurator();
        this.sessionCounter = new SessionCounter(globalComponentRegistry.getConfigCurator(), tenant);
        this.sessionsPath = TenantRepository.getSessionsPath(tenant);
        this.applicationRepo = applicationRepo;
        this.tenantFileSystemDirs = new TenantFileSystemDirs(globalComponentRegistry.getConfigServerDB(), tenant);
        this.serverId = globalComponentRegistry.getConfigserverConfig().serverId();
        this.nodeFlavors = globalComponentRegistry.getZone().nodeFlavors();
        this.clock = globalComponentRegistry.getClock();
        this.flagSource = globalComponentRegistry.getFlagSource();
        this.distributeApplicationPackage = Flags.CONFIGSERVER_DISTRIBUTE_APPLICATION_PACKAGE.bindTo(flagSource);
    }

    /** Create a session for a true application package change */
    @Override
    public LocalSession createSession(File applicationFile,
                                      ApplicationId applicationId,
                                      TimeoutBudget timeoutBudget) {
        return create(applicationFile, applicationId, nonExistingActiveSession, false, timeoutBudget);
    }

    private void ensureZKPathDoesNotExist(Path sessionPath) {
        if (configCurator.exists(sessionPath.getAbsolute())) {
            throw new IllegalArgumentException("Path " + sessionPath.getAbsolute() + " already exists in ZooKeeper");
        }
    }

    private ApplicationPackage createApplication(File userDir,
                                                 File configApplicationDir,
                                                 ApplicationId applicationId,
                                                 long sessionId,
                                                 long currentlyActiveSessionId,
                                                 boolean internalRedeploy) {
        long deployTimestamp = System.currentTimeMillis();
        String user = System.getenv("USER");
        if (user == null) {
            user = "unknown";
        }
        DeployData deployData = new DeployData(user, userDir.getAbsolutePath(), applicationId, deployTimestamp, internalRedeploy, sessionId, currentlyActiveSessionId);
        return FilesApplicationPackage.fromFileWithDeployData(configApplicationDir, deployData);
    }

    private LocalSession createSessionFromApplication(ApplicationPackage applicationPackage,
                                                      long sessionId,
                                                      SessionZooKeeperClient sessionZKClient,
                                                      TimeoutBudget timeoutBudget,
                                                      Clock clock) {
        log.log(Level.FINE, TenantRepository.logPre(tenant) + "Creating session " + sessionId + " in ZooKeeper");
        sessionZKClient.createNewSession(clock.instant());
        log.log(Level.FINE, TenantRepository.logPre(tenant) + "Creating upload waiter for session " + sessionId);
        Curator.CompletionWaiter waiter = sessionZKClient.getUploadWaiter();
        log.log(Level.FINE, TenantRepository.logPre(tenant) + "Done creating upload waiter for session " + sessionId);
        SessionContext context = new SessionContext(applicationPackage, sessionZKClient, getSessionAppDir(sessionId), applicationRepo, hostRegistry, flagSource);
        LocalSession session = new LocalSession(tenant, sessionId, sessionPreparer, context);
        log.log(Level.FINE, TenantRepository.logPre(tenant) + "Waiting on upload waiter for session " + sessionId);
        waiter.awaitCompletion(timeoutBudget.timeLeft());
        log.log(Level.FINE, TenantRepository.logPre(tenant) + "Done waiting on upload waiter for session " + sessionId);
        return session;
    }

    @Override
    public LocalSession createSessionFromExisting(Session existingSession,
                                                  DeployLogger logger,
                                                  boolean internalRedeploy,
                                                  TimeoutBudget timeoutBudget) {
        File existingApp = getSessionAppDir(existingSession.getSessionId());
        ApplicationId existingApplicationId = existingSession.getApplicationId();

        long activeSessionId = getActiveSessionId(existingApplicationId);
        logger.log(Level.FINE, "Create new session for application id '" + existingApplicationId + "' from existing active session " + activeSessionId);
        LocalSession session = create(existingApp, existingApplicationId, activeSessionId, internalRedeploy, timeoutBudget);
        // Note: Needs to be kept in sync with calls in SessionPreparer.writeStateToZooKeeper()
        session.setApplicationId(existingApplicationId);
        if (distributeApplicationPackage.value()) session.setApplicationPackageReference(existingSession.getApplicationPackageReference());
        session.setVespaVersion(existingSession.getVespaVersion());
        session.setDockerImageRepository(existingSession.getDockerImageRepository());
        session.setAthenzDomain(existingSession.getAthenzDomain());
        return session;
    }

    private LocalSession create(File applicationFile, ApplicationId applicationId, long currentlyActiveSessionId,
                                boolean internalRedeploy, TimeoutBudget timeoutBudget) {
        long sessionId = sessionCounter.nextSessionId();
        Path sessionIdPath = sessionsPath.append(String.valueOf(sessionId));
        try {
            ensureZKPathDoesNotExist(sessionIdPath);
            SessionZooKeeperClient sessionZooKeeperClient = new SessionZooKeeperClient(curator,
                                                                                       configCurator,
                                                                                       sessionIdPath,
                                                                                       serverId,
                                                                                       nodeFlavors);
            File userApplicationDir = tenantFileSystemDirs.getUserApplicationDir(sessionId);
            IOUtils.copyDirectory(applicationFile, userApplicationDir);
            ApplicationPackage applicationPackage = createApplication(applicationFile,
                                                                      userApplicationDir,
                                                                      applicationId,
                                                                      sessionId,
                                                                      currentlyActiveSessionId,
                                                                      internalRedeploy);
            applicationPackage.writeMetaData();
            return createSessionFromApplication(applicationPackage, sessionId, sessionZooKeeperClient, timeoutBudget, clock);
        } catch (Exception e) {
            throw new RuntimeException("Error creating session " + sessionIdPath, e);
        }
    }

    private File getSessionAppDir(long sessionId) {
        File appDir = tenantFileSystemDirs.getUserApplicationDir(sessionId);
        if (!appDir.exists() || !appDir.isDirectory()) {
            throw new IllegalArgumentException("Unable to find correct application directory for session " + sessionId);
        }
        return appDir;
    }

    @Override
    public LocalSession loadSession(long sessionId) {
        File sessionDir = getSessionAppDir(sessionId);
        ApplicationPackage applicationPackage = FilesApplicationPackage.fromFile(sessionDir);
        Path sessionIdPath = sessionsPath.append(String.valueOf(sessionId));
        SessionZooKeeperClient sessionZKClient = new SessionZooKeeperClient(curator,
                                                                            configCurator,
                                                                            sessionIdPath,
                                                                            serverId,
                                                                            nodeFlavors);
        SessionContext context = new SessionContext(applicationPackage, sessionZKClient, sessionDir, applicationRepo,
                                                    hostRegistry, flagSource);
        return new LocalSession(tenant, sessionId, sessionPreparer, context);
    }

    private long getActiveSessionId(ApplicationId applicationId) {
        List<ApplicationId> applicationIds = applicationRepo.activeApplications();
        if (applicationIds.contains(applicationId)) {
            return applicationRepo.requireActiveSessionOf(applicationId);
        }
        return nonExistingActiveSession;
    }

}
