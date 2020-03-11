// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.search.searchers;

import com.google.common.annotations.Beta;

import com.yahoo.prelude.query.Item;
import com.yahoo.prelude.query.NearestNeighborItem;
import com.yahoo.prelude.query.ToolBox;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.search.grouping.vespa.GroupingExecutor;
import com.yahoo.search.query.ranking.RankProperties;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.search.searchchain.Execution;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;
import com.yahoo.vespa.config.search.AttributesConfig;
import com.yahoo.yolean.chain.Before;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates any NearestNeighborItem query items.
 *
 * @author arnej
 */
@Beta
@Before(GroupingExecutor.COMPONENT_NAME) // Must happen before query.prepare()
public class ValidateNearestNeighborSearcher extends Searcher {

    private Map<String, TensorType> validAttributes = new HashMap<>();

    public ValidateNearestNeighborSearcher(AttributesConfig attributesConfig) {
        for (AttributesConfig.Attribute a : attributesConfig.attribute()) {
            TensorType tt = null;
            if (a.datatype() == AttributesConfig.Attribute.Datatype.TENSOR) {
                tt = TensorType.fromSpec(a.tensortype());
            }
            validAttributes.put(a.name(), tt);
        }
    }

    @Override
    public Result search(Query query, Execution execution) {
        Optional<ErrorMessage> e = validate(query);
        return e.isEmpty() ? execution.search(query) : new Result(query, e.get());
    }

    private Optional<ErrorMessage> validate(Query query) {
        NNVisitor visitor = new NNVisitor(query.getRanking().getProperties(), validAttributes, query);
        ToolBox.visit(visitor, query.getModel().getQueryTree().getRoot());
        return visitor.errorMessage;
    }

    private static class NNVisitor extends ToolBox.QueryVisitor {

        public Optional<ErrorMessage> errorMessage = Optional.empty();

        private final RankProperties rankProperties;
        private final Map<String, TensorType> validAttributes;
        private final Query query;

        public NNVisitor(RankProperties rankProperties, Map<String, TensorType> validAttributes, Query query) {
            this.rankProperties = rankProperties;
            this.validAttributes = validAttributes;
            this.query = query;
        }

        @Override
        public boolean visit(Item item) {
            if (item instanceof NearestNeighborItem) {
                String error = validate((NearestNeighborItem)item);
                if (error != null)
                    errorMessage = Optional.of(ErrorMessage.createIllegalQuery(error));
            }
            return true;
        }

        private static boolean isCompatible(TensorType lhs, TensorType rhs) {
            return lhs.dimensions().equals(rhs.dimensions());
        }

        private static boolean isDenseVector(TensorType tt) {
            List<TensorType.Dimension> dims = tt.dimensions();
            if (dims.size() != 1) return false;
            for (var d : dims) {
                if (d.type() != TensorType.Dimension.Type.indexedBound) return false;
            }
            return true;
        }

        /** Returns an error message if this is invalid, or null if it is valid */
        private String validate(NearestNeighborItem item) {
            if (item.getTargetNumHits() < 1)
                return item + " has invalid targetNumHits " + item.getTargetNumHits() + ": Must be >= 1";

            String queryFeatureName = "query(" + item.getQueryTensorName() + ")";
            Optional<Tensor> queryTensor = query.getRanking().getFeatures().getTensor(queryFeatureName);
            if (queryTensor.isEmpty())
                return item + " requires a tensor rank feature " + queryFeatureName + " but this is not present";

            if ( ! validAttributes.containsKey(item.getIndexName()))
                return item + " field is not an attribute";
            TensorType fieldType = validAttributes.get(item.getIndexName());
            if (fieldType == null) return item + " field is not a tensor";
            if ( ! isDenseVector(fieldType))
                return item + " tensor type " + fieldType + " is not a dense vector";

            if ( ! isCompatible(fieldType, queryTensor.get().type()))
                return item + " field type " + fieldType + " does not match query type " + queryTensor.get().type();

            return null;
        }

        @Override
        public void onExit() {}

    }

}
