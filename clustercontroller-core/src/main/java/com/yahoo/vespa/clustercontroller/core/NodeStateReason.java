// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

public enum NodeStateReason {

    // FIXME some of these reasons may be unnecessary as they are reported implicitly by reported/wanted state changes
    NODE_TOO_UNSTABLE,
    WITHIN_MAINTENANCE_GRACE_PERIOD,
    NODE_NOT_BACK_UP_WITHIN_GRACE_PERIOD,
    GROUP_IN_MAINTENANCE,
    GROUP_IS_DOWN,
    MAY_HAVE_MERGES_PENDING
}
