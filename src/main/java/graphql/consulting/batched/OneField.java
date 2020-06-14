package graphql.consulting.batched;

import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ExecutionPath;
import reactor.core.publisher.MonoProcessor;

public class OneField {
    public ExecutionPath executionPath;
    public NormalizedField normalizedField;
    public Object source;
    public MonoProcessor<Object> resultMono;

    public OneField(ExecutionPath executionPath, NormalizedField normalizedField, Object source) {
        this.executionPath = executionPath;
        this.normalizedField = normalizedField;
        this.source = source;
    }

    @Override
    public String toString() {
        return "OneField{" +
                "executionPath=" + executionPath +
                '}';
    }
}
