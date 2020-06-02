package graphql.consulting.batched;

import graphql.Assert;
import graphql.Internal;
import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ExecutionPath;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import reactor.core.publisher.Mono;

import java.util.List;

public interface BatchedDataFetcher {

    default Mono<BatchedDataFetcherResult> get(BatchedDataFetcherEnvironment environment) {
        return null;
    }
}
