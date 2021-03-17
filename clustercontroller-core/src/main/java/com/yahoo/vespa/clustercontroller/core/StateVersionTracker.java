// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.vdslib.state.ClusterState;
import com.yahoo.vespa.clustercontroller.core.hostinfo.HostInfo;

import java.util.List;

/**
 * Keeps track of the active cluster state and handles the transition edges between
 * one state to the next. In particular, it ensures that states have strictly increasing
 * version numbers.
 *
 * Wraps ClusterStateView to ensure its knowledge of available nodes stays up to date.
 */
public class StateVersionTracker {

    // We always increment the version _before_ publishing, so the effective first cluster
    // state version when starting from 1 will be 2. This matches legacy behavior and a bunch
    // of existing tests expect it.
    private int currentVersion = 1;
    private int lastZooKeeperVersion = 0;

    // The lowest published distribution bit count for the lifetime of this controller.
    // TODO this mirrors legacy behavior, but should be moved into stable ZK state.
    private int lowestObservedDistributionBits = 16;

    private ClusterStateBundle currentUnversionedState = ClusterStateBundle.ofBaselineOnly(AnnotatedClusterState.emptyState());
    private ClusterStateBundle latestCandidateState = ClusterStateBundle.ofBaselineOnly(AnnotatedClusterState.emptyState());
    private ClusterStateBundle currentClusterState = latestCandidateState;

    private ClusterStateView clusterStateView;
    private final ClusterStatsChangeTracker clusterStatsChangeTracker;

    private final ClusterStateHistory clusterStateHistory = new ClusterStateHistory();
    private double minMergeCompletionRatio;

    StateVersionTracker(double minMergeCompletionRatio) {
        clusterStateView = ClusterStateView.create(currentUnversionedState.getBaselineClusterState());
        clusterStatsChangeTracker = new ClusterStatsChangeTracker(clusterStateView.getStatsAggregator().getAggregatedStats(),
                minMergeCompletionRatio);
        this.minMergeCompletionRatio = minMergeCompletionRatio;
    }

    void setVersionRetrievedFromZooKeeper(final int version) {
        this.currentVersion = Math.max(1, version);
        this.lastZooKeeperVersion = this.currentVersion;
    }

    void setClusterStateBundleRetrievedFromZooKeeper(ClusterStateBundle bundle) {
        // There is an edge where the bundle version will mismatch with the version set
        // via setVersionRetrievedFromZooKeeper() if the controller (or ZK) crashes before
        // it can write both sequentially. But since we use the ZK-written version explicitly
        // when choosing a new version for our own published states, it should not matter in
        // practice. Worst case is that the current state reflects the same version that a
        // previous controller had, but we will never publish this state ourselves; publishing
        // only happens after we've generated our own, new candidate state and overwritten
        // the empty states set below. Publishing also, as mentioned, sets a version based on
        // the ZK version, not the version stored in the bundle itself.
        currentClusterState = bundle;
        currentUnversionedState = ClusterStateBundle.empty();
        latestCandidateState = ClusterStateBundle.empty();
    }

    /**
     * Sets limit on how many cluster states can be kept in the in-memory queue. Once
     * the list exceeds this limit, the oldest state is repeatedly removed until the limit
     * is no longer exceeded.
     *
     * Takes effect upon the next invocation of promoteCandidateToVersionedState().
     */
    void setMaxHistoryEntryCount(int maxHistoryEntryCount) {
        this.clusterStateHistory.setMaxHistoryEntryCount(maxHistoryEntryCount);
    }

    void setMinMergeCompletionRatio(double minMergeCompletionRatio) {
        this.minMergeCompletionRatio = minMergeCompletionRatio;
    }

    int getCurrentVersion() {
        return this.currentVersion;
    }

    boolean hasReceivedNewVersionFromZooKeeper() {
        return currentVersion <= lastZooKeeperVersion;
    }

    int getLowestObservedDistributionBits() {
        return lowestObservedDistributionBits;
    }

    AnnotatedClusterState getAnnotatedVersionedClusterState() {
        return currentClusterState.getBaselineAnnotatedState();
    }

    public ClusterState getVersionedClusterState() {
        return currentClusterState.getBaselineClusterState();
    }

    public ClusterStatsAggregator getAggregatedClusterStats() {
        return clusterStateView.getStatsAggregator();
    }

    public ClusterStateBundle getVersionedClusterStateBundle() {
        return currentClusterState;
    }

    public void updateLatestCandidateStateBundle(final ClusterStateBundle candidateBundle) {
        assert(latestCandidateState.getBaselineClusterState().getVersion() == 0);
        latestCandidateState = candidateBundle;
        clusterStatsChangeTracker.syncAggregatedStats();
    }

    /**
     * Returns the last state provided to updateLatestCandidateStateBundle, which _may or may not_ be
     * a published state. Primary use case for this function is a caller which is interested in
     * changes that may not be reflected in the published state. The best example of this would
     * be node state changes when a cluster is marked as Down.
     */
    public AnnotatedClusterState getLatestCandidateState() {
        return latestCandidateState.getBaselineAnnotatedState();
    }

    public ClusterStateBundle getLatestCandidateStateBundle() {
        return latestCandidateState;
    }

    public List<ClusterStateHistoryEntry> getClusterStateHistory() {
        return clusterStateHistory.getHistory();
    }

    boolean candidateChangedEnoughFromCurrentToWarrantPublish() {
        // Neither latestCandidateState nor currentUnversionedState has a version set, so the
        // similarity is only done on structural state metadata.
        return !currentUnversionedState.similarTo(latestCandidateState);
    }

    void promoteCandidateToVersionedState(final long currentTimeMs) {
        final int newVersion = currentVersion + 1;
        updateStatesForNewVersion(latestCandidateState, newVersion);
        currentVersion = newVersion;

        recordCurrentStateInHistoryAtTime(currentTimeMs);
    }

    private void updateStatesForNewVersion(final ClusterStateBundle newStateBundle, final int newVersion) {
        currentClusterState = newStateBundle.clonedWithVersionSet(newVersion);
        currentUnversionedState = newStateBundle; // TODO should we clone..? ClusterState really should be made immutable
        lowestObservedDistributionBits = Math.min(
                lowestObservedDistributionBits,
                newStateBundle.getBaselineClusterState().getDistributionBitCount());
        // TODO should this take place in updateLatestCandidateStateBundle instead? I.e. does it require a consolidated state?
        clusterStateView = ClusterStateView.create(currentClusterState.getBaselineClusterState());
        clusterStatsChangeTracker.updateAggregatedStats(clusterStateView.getStatsAggregator().getAggregatedStats(),
                minMergeCompletionRatio);
    }

    private void recordCurrentStateInHistoryAtTime(final long currentTimeMs) {
        clusterStateHistory.add(currentClusterState, currentTimeMs);
    }

    void handleUpdatedHostInfo(final NodeInfo node, final HostInfo hostInfo) {
        // TODO the wiring here isn't unit tested. Need mockable integration points.
        clusterStateView.handleUpdatedHostInfo(node, hostInfo);
    }

    boolean bucketSpaceMergeCompletionStateHasChanged() {
        return clusterStatsChangeTracker.statsHaveChanged();
    }

    MergePendingChecker createMergePendingChecker() {
        return clusterStateView.getStatsAggregator().createMergePendingChecker(minMergeCompletionRatio);
    }

    /*
    TODO test and implement
      - derived default space down-condition can only _keep_ a node in maintenance (down), not transition it from up -> maintenance
    */

}
