// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * @class vdslib::ClusterState
 * @ingroup state
 *
 * @brief Object to represent a system state
 */

#pragma once

#include "node.h"
#include "nodestate.h"
#include <map>

namespace storage::lib {

class Distribution;
class Group;
struct NodeData;

class ClusterState : public document::Printable {
    uint32_t _version;
    const State* _clusterState;
    std::map<Node, NodeState> _nodeStates;
    std::vector<uint16_t> _nodeCount;
    vespalib::string _description;
    uint16_t _distributionBits;

    void getTextualDifference(std::ostringstream& builder, const NodeType& type,
                              const ClusterState& other) const;

public:
    typedef std::shared_ptr<const ClusterState> CSP;
    typedef std::shared_ptr<ClusterState> SP;
    typedef std::unique_ptr<ClusterState> UP;

    ClusterState();
    ClusterState(const ClusterState&);
    // FIXME make ClusterState parsing not require null termination of string,
    // then move to vespalib::stringref
    explicit ClusterState(const vespalib::string& serialized);
    ClusterState& operator=(const ClusterState& other) = delete;
    ~ClusterState();

    std::string getTextualDifference(const ClusterState& other) const;
    void serialize(vespalib::asciistream & out, bool ignoreNewFeatures) const;

    bool operator==(const ClusterState& other) const;
    bool operator!=(const ClusterState& other) const;

    uint32_t getVersion() const { return _version; }
    /**
     * Returns the smallest number above the highest node index found of the
     * given type that is not down.
     */
    uint16_t getNodeCount(const NodeType& type) const;
    uint16_t getDistributionBitCount() const { return _distributionBits; }
    const State& getClusterState() const { return *_clusterState; }
    const NodeState& getNodeState(const Node& node) const;

    void setVersion(uint32_t version) { _version = version; }
    void setClusterState(const State& state);
    void setNodeState(const Node& node, const NodeState& state);
    void setDistributionBitCount(uint16_t count) { _distributionBits = count; }

    void print(std::ostream& out, bool verbose, const std::string& indent) const override;

    void printStateGroupwise(std::ostream& out,
                             const Distribution&, bool verbose = false,
                             const std::string& indent = "") const;

private:
    // Preconditions: `key` and `value` MUST point into null-terminated strings.
    bool parse(vespalib::stringref key, vespalib::stringref value, NodeData & nodeData);
    // Preconditions: `key` and `value` MUST point into null-terminated strings.
    bool parseSorD(vespalib::stringref key, vespalib::stringref value, NodeData & nodeData);
    void removeExtraElements();
    void printStateGroupwise(std::ostream& out, const Group&, bool verbose,
                             const std::string& indent, bool rootGroup) const;

};

}
