package graphql.consulting.batched

import graphql.nextgen.GraphQL
import reactor.core.publisher.Mono
import spock.lang.Specification

import static graphql.ExecutionInput.newExecutionInput
import static graphql.consulting.batched.TestUtil.schema
import static graphql.schema.FieldCoordinates.coordinates

class BatchedExecutionStrategy2Test extends Specification {

    def "test execution with lists"() {
        def fooData = [[id: "fooId1", bar: ["barId1", "barId2", null]],
                       null,
                       [id: "fooId2", bar: ["barId3", "barId4", "barId5"]],
                       null]
        def bar1 = [id: "barId1", name: "someBar1"];
        def bar2 = [id: "barId2", name: "someBar2"];
        def bar3 = [id: "barId3", name: "someBar3"];
        def bar4 = [id: "barId4", name: "someBar4"];
        def bar5 = [id: "barId5", name: "someBar5"];
//        def dataFetchers = [
//                Query: [foo: { env -> fooData } as DataFetcher]
//        ]
        def schema = schema("""
        type Query {
            foo: [Foo]
        }
        type Foo {
            id: ID
            bar: [Bar]
        }
        type Bar {
            id: ID
            name: String
        }
        """)


        def query = """
        {foo {
            id
            bar {
                id
                name
            }
        }}
        """

        when:
//        Function<Object, Mono<Object>> fooDF = {
//            return Mono.fromSupplier({
//                println "DataFetcher thread: " + Thread.currentThread()
//                fooData
//            })
//        }
//
//        Function<Object, Mono<Object>> barBatchedDf = {
//            return Mono.fromSupplier({
//                println "DataFetcher thread: " + Thread.currentThread()
//                fooData
//            })
//        }
//
        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "foo"), ({ fooData }) as TrivialDataFetcher)

        BatchedDataFetcher barDF = { env ->
            println "Batched df with " + Thread.currentThread();
            return Mono.fromSupplier({
                println "DataFetcher thread: " + Thread.currentThread()
                println "env sources: " + env.sources
                return new BatchedDataFetcherResult([[bar1, bar2], [bar3, bar4, bar5]]);
            });
        } as BatchedDataFetcher;

        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Foo", "bar"), barDF)


        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy2(dataFetchingConfiguration)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: fooData]
    }

}
