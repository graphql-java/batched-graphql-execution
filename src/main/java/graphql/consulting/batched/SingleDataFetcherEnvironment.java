package graphql.consulting.batched;

import graphql.PublicApi;
import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ExecutionPath;

@PublicApi
public class SingleDataFetcherEnvironment {

    private Object source;
    private NormalizedField normalizedFields;
    private ExecutionPath executionPaths;

    public SingleDataFetcherEnvironment(Object source, NormalizedField normalizedFields, ExecutionPath executionPaths) {
        this.source = source;
        this.normalizedFields = normalizedFields;
        this.executionPaths = executionPaths;
    }

    public Object getSource() {
        return source;
    }

    public NormalizedField getNormalizedFields() {
        return normalizedFields;
    }

    public ExecutionPath getExecutionPaths() {
        return executionPaths;
    }
}
