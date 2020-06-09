package graphql.consulting.batched;

import reactor.core.publisher.Mono;

public interface SingleDataFetcher {

    Mono<Object> get(SingleDataFetcherEnvironment environment);
}
