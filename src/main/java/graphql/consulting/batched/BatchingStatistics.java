package graphql.consulting.batched;

import graphql.consulting.batched.normalized.NormalizedField;

import java.util.LinkedHashMap;
import java.util.Map;

public class BatchingStatistics {
    private Map<NormalizedField, Integer> countByNormalizedField = new LinkedHashMap<>();
}
