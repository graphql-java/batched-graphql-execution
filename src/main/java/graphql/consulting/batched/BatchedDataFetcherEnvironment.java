package graphql.consulting.batched;

import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ExecutionPath;

import java.util.List;

public class BatchedDataFetcherEnvironment {
    List<Object> sources;
    List<NormalizedField> normalizedFields;
    List<ExecutionPath> executionPaths;
}
