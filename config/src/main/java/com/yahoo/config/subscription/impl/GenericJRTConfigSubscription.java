// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.subscription.impl;

import java.util.List;

import com.yahoo.config.subscription.ConfigSource;
import com.yahoo.config.subscription.ConfigSubscriber;
import com.yahoo.vespa.config.ConfigKey;
import com.yahoo.vespa.config.RawConfig;
import com.yahoo.vespa.config.TimingValues;
import com.yahoo.vespa.config.protocol.DefContent;
import com.yahoo.vespa.config.protocol.JRTClientConfigRequest;

import static java.util.logging.Level.FINE;

/**
 * A JRT subscription which does not use the config class, but {@link com.yahoo.vespa.config.RawConfig} instead.
 * Used by config proxy.
 *
 * @author Vegard Havdal
 */
public class GenericJRTConfigSubscription extends JRTConfigSubscription<RawConfig> {

    private final List<String> defContent;

    public GenericJRTConfigSubscription(ConfigKey<RawConfig> key, List<String> defContent, ConfigSubscriber subscriber,
                                        ConfigSource source, TimingValues timingValues)
    {
        super(key, subscriber, source, timingValues);
        this.defContent = defContent;
    }

    @Override
    protected void setNewConfig(JRTClientConfigRequest jrtReq) {
        setConfig(jrtReq.getNewGeneration(), jrtReq.responseIsApplyOnRestart(), RawConfig.createFromResponseParameters(jrtReq) );
        log.log(FINE, () -> "in setNewConfig, config=" + this.getConfigState().getConfig());
    }

    // This method is overridden because config needs to have its generation
    // updated if _only_ generation has changed
    @Override
    void setGeneration(Long generation) {
        super.setGeneration(generation);
        ConfigState<RawConfig> configState = getConfigState();

        if (configState.getConfig() != null) {
            configState.getConfig().setGeneration(generation);
        }
    }

    // Override to propagate internal redeploy into the config value in addition to the config state
    @Override
    void setApplyOnRestart(boolean applyOnRestart) {
        super.setApplyOnRestart(applyOnRestart);
        ConfigState<RawConfig> configState = getConfigState();

        if (configState.getConfig() != null) {
            configState.getConfig().setApplyOnRestart(applyOnRestart);
        }
    }

    public RawConfig getRawConfig() {
        return getConfigState().getConfig();
    }

    /**
     * The config definition schema
     *
     * @return the config definition for this subscription
     */
    @Override
    public DefContent getDefContent() {
        return (DefContent.fromList(defContent));
    }
}
