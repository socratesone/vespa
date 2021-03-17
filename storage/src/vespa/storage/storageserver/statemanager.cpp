// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "statemanager.h"
#include "storagemetricsset.h"
#include <vespa/defaults.h>
#include <vespa/document/bucket/fixed_bucket_spaces.h>
#include <vespa/metrics/jsonwriter.h>
#include <vespa/metrics/metricmanager.h>
#include <vespa/storageapi/messageapi/storagemessage.h>
#include <vespa/vdslib/state/cluster_state_bundle.h>
#include <vespa/vdslib/state/clusterstate.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/vespalib/util/stringfmt.h>

#include <fstream>
#include <unistd.h>

#include <vespa/log/log.h>
LOG_SETUP(".state.manager");

namespace storage {

using lib::ClusterStateBundle;

StateManager::StateManager(StorageComponentRegister& compReg,
                           metrics::MetricManager& metricManager,
                           std::unique_ptr<HostInfo> hostInfo,
                           bool testMode)
    : StorageLink("State manager"),
      framework::HtmlStatusReporter("systemstate", "Node and system state"),
      _component(compReg, "statemanager"),
      _metricManager(metricManager),
      _stateLock(),
      _stateCond(),
      _listenerLock(),
      _nodeState(std::make_shared<lib::NodeState>(_component.getNodeType(), lib::State::INITIALIZING)),
      _nextNodeState(),
      _systemState(std::make_shared<const ClusterStateBundle>(lib::ClusterState())),
      _nextSystemState(),
      _stateListeners(),
      _queuedStateRequests(),
      _threadLock(),
      _lastProgressUpdateCausingSend(0),
      _progressLastInitStateSend(-1),
      _systemStateHistory(),
      _systemStateHistorySize(50),
      _hostInfo(std::move(hostInfo)),
      _controllers_observed_explicit_node_state(),
      _noThreadTestMode(testMode),
      _grabbedExternalLock(false),
      _notifyingListeners(false),
      _requested_almost_immediate_node_state_replies(false)
{
    _nodeState->setMinUsedBits(58);
    _nodeState->setStartTimestamp(_component.getClock().getTimeInSeconds().getTime());
    _component.registerStatusPage(*this);
}

StateManager::~StateManager()
{
    closeNextLink();
    LOG(debug, "Deleting link %s.", toString().c_str());
    if (_thread) {
        LOG(debug, "onClose() not called before destructor");
        _thread->interruptAndJoin(_threadCond);
    }
}

void
StateManager::onOpen()
{
    if (!_noThreadTestMode) {
        _thread = _component.startThread(*this, 30s);
    }
}

void
StateManager::onClose()
{
    if (_thread) {
        _thread->interruptAndJoin(_threadCond);
        _thread.reset();
    }
    sendGetNodeStateReplies();
}

void
StateManager::print(std::ostream& out, bool verbose,
              const std::string& indent) const
{
    (void) verbose; (void) indent;
    out << "StateManager()";
}

#ifdef ENABLE_BUCKET_OPERATION_LOGGING
namespace {

vespalib::string
escapeHtml(vespalib::stringref str)
{
    vespalib::asciistream ss;
    for (size_t i = 0; i < str.size(); ++i) {
        switch (str[i]) {
        case '<':
            ss << "&lt;";
            break;
        case '>':
            ss << "&gt;";
            break;
        case '&':
            ss << "&amp;";
            break;
        default:
            ss << str[i];
        }
    }
    return ss.str();
}

}
#endif

void
StateManager::reportHtmlStatus(std::ostream& out,
                               const framework::HttpUrlPath& path) const
{
    (void) path;
#ifdef ENABLE_BUCKET_OPERATION_LOGGING
    if (path.hasAttribute("history")) {
        std::istringstream iss(path.getAttribute("history"), std::istringstream::in);
        uint64_t rawId;
        iss >> std::hex >> rawId;
        document::BucketId bid(rawId);
        out << "<h3>History for " << bid << "</h3>\n";
        vespalib::string history(
                debug::BucketOperationLogger::getInstance().getHistory(bid));
        out << "<pre>" << escapeHtml(history) << "</pre>\n";
        return;
    } else if (path.hasAttribute("search")) {
        vespalib::string substr(path.getAttribute("search"));
        out << debug::BucketOperationLogger::getInstance()
            .searchBucketHistories(substr, "/systemstate?history=");
        return;
    }
#endif

    {
        std::lock_guard lock(_stateLock);
        const auto &baseLineClusterState = _systemState->getBaselineClusterState();
        out << "<h1>Current system state</h1>\n"
            << "<code>" << baseLineClusterState->toString(true) << "</code>\n"
            << "<h1>Current node state</h1>\n"
            << "<code>" << baseLineClusterState->getNodeState(lib::Node(
                        _component.getNodeType(), _component.getIndex())
                                                     ).toString(true)
            << "</code>\n"
            << "<h1>Reported node state</h1>\n"
            << "<code>" << _nodeState->toString(true) << "</code>\n"
            << "<h1>Pending state requests</h1>\n"
            << _queuedStateRequests.size() << "\n"
            << "<h1>System state history</h1>\n"
            << "<table border=\"1\"><tr>"
            << "<th>Received at time</th><th>State</th></tr>\n";
        for (auto it = _systemStateHistory.rbegin(); it != _systemStateHistory.rend(); ++it) {
            out << "<tr><td>" << it->first << "</td><td>"
                << *it->second->getBaselineClusterState() << "</td></tr>\n";
        }
        out << "</table>\n";
    }
}

lib::Node
StateManager::thisNode() const
{
    return lib::Node(_component.getNodeType(), _component.getIndex());
}

lib::NodeState::CSP
StateManager::getReportedNodeState() const
{
    std::lock_guard lock(_stateLock);
    return _nodeState;
}

lib::NodeState::CSP
StateManager::getCurrentNodeState() const
{
    std::lock_guard lock(_stateLock);
    return std::make_shared<const lib::NodeState>
        (_systemState->getBaselineClusterState()->getNodeState(thisNode()));
}

std::shared_ptr<const lib::ClusterStateBundle>
StateManager::getClusterStateBundle() const
{
    std::lock_guard lock(_stateLock);
    return _systemState;
}

void
StateManager::addStateListener(StateListener& listener)
{
    std::lock_guard lock(_listenerLock);
    _stateListeners.push_back(&listener);
}

void
StateManager::removeStateListener(StateListener& listener)
{
    std::lock_guard lock(_listenerLock);
    for (auto it = _stateListeners.begin(); it != _stateListeners.end();) {
        if (*it == &listener) {
            it = _stateListeners.erase(it);
        } else {
            ++it;
        }
    }
}

struct StateManager::ExternalStateLock : public NodeStateUpdater::Lock {
    StateManager& _manager;

    explicit ExternalStateLock(StateManager& manager) noexcept : _manager(manager) {}
    ~ExternalStateLock() override {
        {
            std::lock_guard lock(_manager._stateLock);
            _manager._grabbedExternalLock = false;
        }
        _manager._stateCond.notify_all();
        _manager.notifyStateListeners();
    }
};

NodeStateUpdater::Lock::SP
StateManager::grabStateChangeLock()
{
    std::unique_lock guard(_stateLock);
    while (_grabbedExternalLock || _nextNodeState.get()) {
        _stateCond.wait(guard);
    }
    _grabbedExternalLock = true;
    return std::make_shared<ExternalStateLock>(*this);
}

void
StateManager::setReportedNodeState(const lib::NodeState& state)
{
    std::lock_guard lock(_stateLock);
    if (!_grabbedExternalLock) {
        LOG(error,
            "Cannot set reported node state without first having "
            "grabbed external lock");
        assert(false);
    }
    LOG(debug, "Adjusting reported node state to %s -> %s",
        _nodeState->toString().c_str(), state.toString().c_str());
    _nextNodeState = std::make_shared<lib::NodeState>(state);
}

/**
 * Allows reentrent calls, in case a listener calls setNodeState or similar.
 * We solve this by detecting that we're already notifying listeners, and then
 * doing it over and over again until noone alters the state in the callback.
 */
void
StateManager::notifyStateListeners()
{
    using lib::State;
    if (_notifyingListeners) {
        return;
    }
    std::lock_guard listenerLock(_listenerLock);
    _notifyingListeners = true;
    lib::NodeState::SP newState;
    while (true) {
        {
            std::lock_guard guard(_stateLock);
            if (!_nextNodeState && !_nextSystemState) {
                _notifyingListeners = false;
                _stateCond.notify_all();
                break; // No change
            }
            if (_nextNodeState) {
                assert(!(_nodeState->getState() == State::UP
                         && _nextNodeState->getState() == State::INITIALIZING));

                if (_nodeState->getState() == State::INITIALIZING
                    && _nextNodeState->getState() == State::INITIALIZING
                    && ((_component.getClock().getTimeInMillis() - _lastProgressUpdateCausingSend)
                        < framework::MilliSecTime(1000))
                    && _nextNodeState->getInitProgress() < 1
                    && (_nextNodeState->getInitProgress() - _progressLastInitStateSend) < 0.01)
                {
                    // For this special case, where we only have gotten a little
                    // initialization progress and we have reported recently,
                    // don't trigger sending get node state reply yet.
                } else {
                    newState = _nextNodeState;
                    if (!_queuedStateRequests.empty()
                        && _nextNodeState->getState() == State::INITIALIZING)
                    {
                        _lastProgressUpdateCausingSend = _component.getClock().getTimeInMillis();
                        _progressLastInitStateSend = newState->getInitProgress();
                    } else {
                        _lastProgressUpdateCausingSend = framework::MilliSecTime(0);
                        _progressLastInitStateSend = -1;
                    }
                }
                _nodeState = _nextNodeState;
                _nextNodeState.reset();
            }
            if (_nextSystemState) {
                enableNextClusterState();
            }
            _stateCond.notify_all();
        }
        for (auto* listener : _stateListeners) {
            listener->handleNewState();
                // If one of them actually altered the state again, abort
                // sending events, update states and send new one to all.
            if (_nextNodeState || _nextSystemState) {
                break;
            }
        }
    }
    if (newState) {
        sendGetNodeStateReplies();
    }
    _notifyingListeners = false;
}

void
StateManager::enableNextClusterState()
{
    if (_systemStateHistory.size() >= _systemStateHistorySize) {
        _systemStateHistory.pop_front();
    }
    // _systemState must be non-null due to being initially set to an empty,
    // new cluster state upon construction and because it can only be
    // overwritten by a non-null pending cluster state afterwards.
    logNodeClusterStateTransition(*_systemState, *_nextSystemState);
    _systemState = _nextSystemState;
    _nextSystemState.reset();
    _systemStateHistory.emplace_back(_component.getClock().getTimeInMillis(), _systemState);
}

namespace {

using BucketSpaceToTransitionString = std::unordered_map<document::BucketSpace,
                                                         vespalib::string,
                                                         document::BucketSpace::hash>;

void
considerInsertDerivedTransition(const lib::State &currentBaseline,
                                const lib::State &newBaseline,
                                const lib::State &currentDerived,
                                const lib::State &newDerived,
                                const document::BucketSpace &bucketSpace,
                                BucketSpaceToTransitionString &transitions)
{
    bool considerDerivedTransition = ((currentDerived != newDerived) &&
            ((currentDerived != currentBaseline) || (newDerived != newBaseline)));
    if (considerDerivedTransition && (transitions.find(bucketSpace) == transitions.end())) {
        transitions[bucketSpace] = vespalib::make_string("%s space: '%s' to '%s'",
                                                         document::FixedBucketSpaces::to_string(bucketSpace).data(),
                                                         currentDerived.getName().c_str(),
                                                         newDerived.getName().c_str());
    }
}

BucketSpaceToTransitionString
calculateDerivedClusterStateTransitions(const ClusterStateBundle &currentState,
                                        const ClusterStateBundle &newState,
                                        const lib::Node node)
{
    BucketSpaceToTransitionString result;
    const lib::State &currentBaseline = currentState.getBaselineClusterState()->getNodeState(node).getState();
    const lib::State &newBaseline = newState.getBaselineClusterState()->getNodeState(node).getState();
    for (const auto &entry : currentState.getDerivedClusterStates()) {
        const lib::State &currentDerived = entry.second->getNodeState(node).getState();
        const lib::State &newDerived = newState.getDerivedClusterState(entry.first)->getNodeState(node).getState();
        considerInsertDerivedTransition(currentBaseline, newBaseline, currentDerived, newDerived, entry.first, result);
    }
    for (const auto &entry : newState.getDerivedClusterStates()) {
        const lib::State &newDerived = entry.second->getNodeState(node).getState();
        const lib::State &currentDerived = currentState.getDerivedClusterState(entry.first)->getNodeState(node).getState();
        considerInsertDerivedTransition(currentBaseline, newBaseline, currentDerived, newDerived, entry.first, result);
    }
    return result;
}

vespalib::string
transitionsToString(const BucketSpaceToTransitionString &transitions)
{
    if (transitions.empty()) {
        return "";
    }
    vespalib::asciistream stream;
    stream << "[";
    bool first = true;
    for (const auto &entry : transitions) {
        if (!first) {
            stream << ", ";
        }
        stream << entry.second;
        first = false;
    }
    stream << "] ";
    return stream.str();
}

}

void
StateManager::logNodeClusterStateTransition(
        const ClusterStateBundle& currentState,
        const ClusterStateBundle& newState) const
{
    lib::Node self(thisNode());
    const lib::State& before(currentState.getBaselineClusterState()->getNodeState(self).getState());
    const lib::State& after(newState.getBaselineClusterState()->getNodeState(self).getState());
    auto derivedTransitions = calculateDerivedClusterStateTransitions(currentState, newState, self);
    if ((before != after) || !derivedTransitions.empty()) {
        LOG(info, "Transitioning from baseline state '%s' to '%s' %s"
                  "(cluster state version %u)",
            before.getName().c_str(),
            after.getName().c_str(),
            transitionsToString(derivedTransitions).c_str(),
            newState.getVersion());
    }
}

bool
StateManager::onGetNodeState(const api::GetNodeStateCommand::SP& cmd)
{
    bool sentReply = false;
    if (cmd->getSourceIndex() != 0xffff) {
        sentReply = sendGetNodeStateReplies(framework::MilliSecTime(0),
                                            cmd->getSourceIndex());
    }
    std::shared_ptr<api::GetNodeStateReply> reply;
    {
        std::unique_lock guard(_stateLock);
        const bool is_up_to_date = (_controllers_observed_explicit_node_state.find(cmd->getSourceIndex())
                                    != _controllers_observed_explicit_node_state.end());
        if (cmd->getExpectedState() != nullptr
            && (*cmd->getExpectedState() == *_nodeState || sentReply)
            && is_up_to_date)
        {
            int64_t msTimeout = vespalib::count_ms(cmd->getTimeout());
            LOG(debug, "Received get node state request with timeout of "
                       "%" PRId64 " milliseconds. Scheduling to be answered in "
                       "%" PRId64 " milliseconds unless a node state change "
                       "happens before that time.",
                msTimeout, msTimeout * 800 / 1000);
            TimeStatePair pair(
                    _component.getClock().getTimeInMillis()
                    + framework::MilliSecTime(msTimeout * 800 / 1000),
                    cmd);
            _queuedStateRequests.emplace_back(std::move(pair));
        } else {
            LOG(debug, "Answered get node state request right away since it "
                       "thought we were in nodestate %s, while our actual "
                       "node state is currently %s and we didn't just reply to "
                       "existing request.",
                cmd->getExpectedState() == nullptr ? "unknown"
                        : cmd->getExpectedState()->toString().c_str(),
                _nodeState->toString().c_str());
            reply = std::make_shared<api::GetNodeStateReply>(*cmd, *_nodeState);
            mark_controller_as_having_observed_explicit_node_state(guard, cmd->getSourceIndex());
            guard.unlock();
            reply->setNodeInfo(getNodeInfo());
        }
    }
    if (reply) {
        sendUp(reply);
    }
    return true;
}

void
StateManager::mark_controller_as_having_observed_explicit_node_state(const std::unique_lock<std::mutex> &, uint16_t controller_index) {
    _controllers_observed_explicit_node_state.emplace(controller_index);
}

void
StateManager::setClusterStateBundle(const ClusterStateBundle& c)
{
    {
        std::lock_guard lock(_stateLock);
        _nextSystemState = std::make_shared<const ClusterStateBundle>(c);
    }
    notifyStateListeners();
}

bool
StateManager::onSetSystemState(
        const std::shared_ptr<api::SetSystemStateCommand>& cmd)
{
    setClusterStateBundle(cmd->getClusterStateBundle());
    sendUp(std::make_shared<api::SetSystemStateReply>(*cmd));
    return true;
}

bool
StateManager::onActivateClusterStateVersion(
        const std::shared_ptr<api::ActivateClusterStateVersionCommand>& cmd)
{
    auto reply = std::make_shared<api::ActivateClusterStateVersionReply>(*cmd);
    {
        std::lock_guard lock(_stateLock);
        reply->setActualVersion(_systemState ? _systemState->getVersion() : 0);
    }
    sendUp(reply);
    return true;
}

void
StateManager::run(framework::ThreadHandle& thread)
{
    while (true) {
        thread.registerTick();
        if (thread.interrupted()) {
            break;
        }
        tick();
        std::unique_lock guard(_threadLock);
        if (!_requested_almost_immediate_node_state_replies.load(std::memory_order_relaxed)) {
            _threadCond.wait_for(guard, 1000ms);
        }
    }

}

void
StateManager::tick() {
    bool almost_immediate_replies = _requested_almost_immediate_node_state_replies.load(std::memory_order_relaxed);
    if (almost_immediate_replies) {
        _requested_almost_immediate_node_state_replies.store(false, std::memory_order_relaxed);
    }
    framework::MilliSecTime time(almost_immediate_replies ? framework::MilliSecTime(0) : _component.getClock().getTimeInMillis());
    sendGetNodeStateReplies(time);
}

bool
StateManager::sendGetNodeStateReplies(framework::MilliSecTime olderThanTime, uint16_t node)
{
    std::vector<std::shared_ptr<api::GetNodeStateReply>> replies;
    {
        std::unique_lock guard(_stateLock);
        for (auto it = _queuedStateRequests.begin(); it != _queuedStateRequests.end();) {
            if (node != 0xffff && node != it->second->getSourceIndex()) {
                ++it;
            } else if (!olderThanTime.isSet() || it->first < olderThanTime) {
                LOG(debug, "Sending reply to msg with id %" PRIu64,
                    it->second->getMsgId());

                replies.emplace_back(std::make_shared<api::GetNodeStateReply>(*it->second, *_nodeState));
                auto eraseIt = it++;
                mark_controller_as_having_observed_explicit_node_state(guard, eraseIt->second->getSourceIndex());
                _queuedStateRequests.erase(eraseIt);
            } else {
                ++it;
            }
        }
        if (replies.empty()) {
            return false;
        }
    }
    const std::string nodeInfo(getNodeInfo());
    for (auto& reply : replies) {
        reply->setNodeInfo(nodeInfo);
        sendUp(reply);
    }
    return true;
}

namespace {
    std::string getHostInfoFilename(bool advanceCount) {
        static uint32_t fileCounter = 0;
        static pid_t pid = getpid();
        if (advanceCount) {
            ++fileCounter;
        }
        uint32_t fileIndex = fileCounter % 8;
        std::ostringstream fileName;
        fileName << vespa::Defaults::underVespaHome("tmp/hostinfo")
                 << "." << pid << "." << fileIndex << ".report";
        return fileName.str();
    }
}

std::string
StateManager::getNodeInfo() const
{
    // Generate report from last to info
    vespalib::asciistream json;
    vespalib::JsonStream stream(json, true);
    stream << Object();
    { // Print metrics
        stream << "metrics";
        try {
            metrics::MetricLockGuard lock(_metricManager.getMetricLock());
            std::vector<uint32_t> periods(_metricManager.getSnapshotPeriods(lock));
            if (!periods.empty()) {
                uint32_t period = periods[0];
                const metrics::MetricSnapshot& snapshot(_metricManager.getMetricSnapshot(lock, period));
                metrics::JsonWriter metricJsonWriter(stream);
                _metricManager.visit(lock,  snapshot, metricJsonWriter, "fleetcontroller");
            } else {
                stream << Object() << "error" << "no snapshot periods" << End();
            }
        } catch (vespalib::Exception& e) {
            stream << Object() << "error" << e.getMessage() << End();
        }
    }

    // Report cluster version. It would have been tricky to encapsulate this in
    // a HostReporter, because:
    // - That HostReporter would somehow need to get hold of the version
    //   from the cluster state from this StateManager.
    // - the public getSystemState() need (and should) grab a lock on
    //   _systemLock.
    // - getNodeInfo() (this function) always acquires the same lock.
    std::lock_guard guard(_stateLock);
    stream << "cluster-state-version" << _systemState->getVersion();

    _hostInfo->printReport(stream);
    stream << End();
    stream.finalize();

    // Dump report to new report file.
    std::string oldFile(getHostInfoFilename(false));
    std::string newFile(getHostInfoFilename(true));
    std::ofstream of(newFile.c_str());
    of << json.str();
    of.close();
    // If dumping went ok, delete old report file
    vespalib::unlink(oldFile);
    // Return report
    return json.str();
}

void
StateManager::clear_controllers_observed_explicit_node_state_vector()
{
    std::lock_guard guard(_stateLock);
    // Next GetNodeState request from any controller will be replied to instantly
    _controllers_observed_explicit_node_state.clear();
}

void StateManager::immediately_send_get_node_state_replies() {
    LOG(debug, "Immediately replying to all pending GetNodeState requests");
    clear_controllers_observed_explicit_node_state_vector();
    sendGetNodeStateReplies();
}

void
StateManager::request_almost_immediate_node_state_replies()
{
    clear_controllers_observed_explicit_node_state_vector();
    std::unique_lock guard(_threadLock);
    _requested_almost_immediate_node_state_replies.store(true, std::memory_order_relaxed);
    _threadCond.notify_all();
}

} // storage
