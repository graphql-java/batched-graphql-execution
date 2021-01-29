package graphql.consulting.batched;

import graphql.PublicApi;
import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ResultPath;

@PublicApi
public class SingleDataFetcherEnvironment {

    private Object source;
    private NormalizedField normalizedFields;
    private ResultPath resultPaths;

    public SingleDataFetcherEnvironment(Object source, NormalizedField normalizedFields, ResultPath resultPaths) {
        this.source = source;
        this.normalizedFields = normalizedFields;
        this.resultPaths = resultPaths;
    }

    public Object getSource() {
        return source;
    }

    public NormalizedField getNormalizedFields() {
        return normalizedFields;
    }

    public ResultPath getResultPaths() {
        return resultPaths;
    }
}
