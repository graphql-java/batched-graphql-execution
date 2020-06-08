package graphql.consulting.batched;

import reactor.core.publisher.Mono;

public interface SingleDataFetcher<T> {

    Mono<Object> get(SingleDataFetcherEnvironment environment);
}
