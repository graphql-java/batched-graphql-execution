package graphql.consulting.batched;

import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ResultPath;

import java.util.List;

public class BatchedDataFetcherEnvironment {
    private List<Object> sources;
    private List<NormalizedField> normalizedFields;
    private List<ResultPath> resultPaths;

    public BatchedDataFetcherEnvironment(List<Object> sources, List<NormalizedField> normalizedFields, List<ResultPath> resultPaths) {
        this.sources = sources;
        this.normalizedFields = normalizedFields;
        this.resultPaths = resultPaths;
    }

    public List<Object> getSources() {
        return sources;
    }

    public List<NormalizedField> getNormalizedFields() {
        return normalizedFields;
    }

    public List<ResultPath> getResultPaths() {
        return resultPaths;
    }
}
