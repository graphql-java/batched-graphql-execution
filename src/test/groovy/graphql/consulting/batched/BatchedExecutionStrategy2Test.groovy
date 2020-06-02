package graphql.consulting.batched

import graphql.nextgen.GraphQL
import reactor.core.publisher.Mono
import spock.lang.Specification

import java.util.function.Function

import static graphql.ExecutionInput.newExecutionInput
import static graphql.consulting.batched.TestUtil.schema
import static graphql.schema.FieldCoordinates.coordinates

class BatchedExecutionStrategy2Test extends Specification {

    def "test execution with lists"() {
        def fooData = [[id: "fooId1", bar: [[id: "barId1", name: "someBar1"], [id: "barId2", name: "someBar2"]]],
                       [id: "fooId2", bar: [[id: "barId3", name: "someBar3"], [id: "barId4", name: "someBar4"]]]]
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
        Function<Object, Mono<Object>> fooDF = {
            return Mono.fromSupplier({
                println "DataFetcher thread: " + Thread.currentThread()
                fooData
            })
        }

        Function<Object, Mono<Object>> barBatchedDf = {
            return Mono.fromSupplier({
                println "DataFetcher thread: " + Thread.currentThread()
                fooData
            })
        }
//
        def dataFetchers = [
                (coordinates("Query", "foo")): fooDF
        ]

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy2(dataFetchers)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: fooData]
    }

}
