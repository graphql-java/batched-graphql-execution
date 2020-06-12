package graphql.consulting.batched


import graphql.nextgen.GraphQL
import reactor.core.publisher.Mono
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicReference

import static graphql.ExecutionInput.newExecutionInput
import static graphql.consulting.batched.TestUtil.schema
import static graphql.schema.FieldCoordinates.coordinates

class BatchedExecutionStrategyTestDataFetching extends Specification {

    def "arguments are available at BatchedDataFetcher"() {
        def schema = schema("""
        type Query {
            foo(arg1: String, arg2: ID): String
        }
        """)


        def query = '''
        query($var: ID){foo(arg1: "ARG1",arg2: $var) } 
       '''
        def variables = [var: 'ARG2']

        AtomicReference args = new AtomicReference()
        def df = { env ->
            args.set(env.normalizedFields[0].getArguments())
            return Mono.just(new BatchedDataFetcherResult(['BAR']))
        } as BatchedDataFetcher;

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Query", "foo"), df)

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query).variables(variables))
        then:
        args.get() == [arg1: 'ARG1', arg2: 'ARG2']
        result.getData() == [foo: 'BAR']
        result.errors.size() == 0
    }

}

