// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.application.api;

import java.util.Optional;

/**
 * Ids of validations that can be overridden
 *
 * @author bratseth
 */
public enum ValidationId {

    indexingChange("indexing-change"), // Changing what tokens are expected and stored in field indexes
    indexModeChange("indexing-mode-change"), // Changing the index mode (streaming, indexed, store-only) of documents 
    fieldTypeChange("field-type-change"), // Field type changes
    clusterSizeReduction("cluster-size-reduction"), // Large reductions in cluster size
    tensorTypeChange("tensor-type-change"), // Tensor type change
    resourcesReduction("resources-reduction"), // Large reductions in node resources
    contentTypeRemoval("content-type-removal"), // Removal of a data type (causes deletion of all data)
    contentClusterRemoval("content-cluster-removal"), // Removal (or id change) of content clusters
    deploymentRemoval("deployment-removal"), // Removal of production zones from deployment.xml
    globalDocumentChange("global-document-change"), // Changing global attribute for document types in content clusters
    configModelVersionMismatch("config-model-version-mismatch"), // Internal use
    skipOldConfigModels("skip-old-config-models"), // Internal use
    accessControl("access-control"), // Internal use, used in zones where there should be no access-control
    globalEndpointChange("global-endpoint-change"); // Changing global endpoints

    private final String id;

    ValidationId(String id) { this.id = id; }

    public String value() { return id; }

    @Override
    public String toString() { return id; }

    /**
     * Returns the validation id from this string.
     * Use this instead of valueOf to match string on the (canonical) dash-separated form.
     *
     * @return the matching validation id or empty if none
     */
    public static Optional<ValidationId> from(String id) {
        for (ValidationId candidate : ValidationId.values())
            if (id.equals(candidate.toString())) return Optional.of(candidate);
        return Optional.empty();
    }

}
