// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * @class storage::ServiceLayerNodeContext
 * @ingroup storageserver
 *
 * @brief Context needed by node, that can also be used by others
 *
 * This utility class sets up the default component register implementation.
 * It also sets up the clock and the threadpool, such that the most basic
 * features are available to the provider, before the service layer is set up.
 */

#pragma once

#include <vespa/storage/frameworkimpl/component/servicelayercomponentregisterimpl.h>
#include <vespa/storage/storageserver/storagenodecontext.h>

namespace storage {

struct ServiceLayerNodeContext : public StorageNodeContext {
    // Typedefs to simplify the remainder of the interface
    typedef ServiceLayerComponentRegisterImpl ComponentRegister;

    /**
     * You can provide your own clock implementation. Useful in testing where
     * you want to fake the clock.
     */
    ServiceLayerNodeContext(framework::Clock::UP clock, const ContentBucketDbOptions& db_opts);

    /**
     * Get the actual component register. Available as the actual type as the
     * storage server need to set implementations, and the components need the
     * actual component register interface.
     */
    ComponentRegister& getComponentRegister() { return _componentRegister; }

private:
    ComponentRegister& _componentRegister;
};

} // storage

