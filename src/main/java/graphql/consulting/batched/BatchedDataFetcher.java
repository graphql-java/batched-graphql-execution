package graphql.consulting.batched;

import reactor.core.publisher.Mono;

public interface BatchedDataFetcher {

    Mono<BatchedDataFetcherResult> get(BatchedDataFetcherEnvironment environment);
}
