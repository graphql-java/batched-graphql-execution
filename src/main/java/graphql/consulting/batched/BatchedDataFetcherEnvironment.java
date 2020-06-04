package graphql.consulting.batched;

import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ExecutionPath;

import java.util.List;

public class BatchedDataFetcherEnvironment {
    private List<Object> sources;
    private List<NormalizedField> normalizedFields;
    private List<ExecutionPath> executionPaths;

    public BatchedDataFetcherEnvironment(List<Object> sources, List<NormalizedField> normalizedFields, List<ExecutionPath> executionPaths) {
        this.sources = sources;
        this.normalizedFields = normalizedFields;
        this.executionPaths = executionPaths;
    }

    public List<Object> getSources() {
        return sources;
    }

    public List<NormalizedField> getNormalizedFields() {
        return normalizedFields;
    }

    public List<ExecutionPath> getExecutionPaths() {
        return executionPaths;
    }
}
