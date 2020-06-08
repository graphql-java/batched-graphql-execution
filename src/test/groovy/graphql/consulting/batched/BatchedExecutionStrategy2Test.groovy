package graphql.consulting.batched

import graphql.ErrorType
import graphql.nextgen.GraphQL
import reactor.core.publisher.Mono
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

import static graphql.ExecutionInput.newExecutionInput
import static graphql.consulting.batched.TestUtil.schema
import static graphql.schema.FieldCoordinates.coordinates

class BatchedExecutionStrategy2Test extends Specification {

    def "two level batching"() {
        def fooData = [[id: "fooId1"],
                       null,
                       [id: "fooId2"],
                       null]
        def bar1 = [id: "barId1"];
        def bar2 = [id: "barId2"];
        def bar3 = [id: "barId3"];
        def bar4 = [id: "barId4"];
        def bar5 = [id: "barId5"];

        def barName1 = "someBar1"
        def barName2 = "someBar2"
        def barName3 = "someBar3"
        def barName4 = "someBar4"
        def barName5 = "someBar5"

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

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "foo"), ({ fooData }) as TrivialDataFetcher)

        AtomicInteger barDFCount = new AtomicInteger()
        BatchedDataFetcher barDF = { env ->
            return Mono.fromSupplier({
                barDFCount.incrementAndGet();
                println "fetching bars with env sources: " + env.sources + " in thread " + Thread.currentThread()
                return new BatchedDataFetcherResult([[bar1, bar2], [bar3, bar4, bar5]]);
            });
        } as BatchedDataFetcher;

        TrivialDataFetcher barTrivialDF = { env ->
            if (env.source.id == "fooId1") {
                return [bar1, bar2];
            } else {
                return [bar3, bar4, bar5];
            }
        } as TrivialDataFetcher;

        AtomicInteger barNameDFCount = new AtomicInteger()
        BatchedDataFetcher barNameDF = { env ->
            return Mono.fromSupplier({
                barNameDFCount.incrementAndGet();
                println "fetching barNames with sources: " + env.sources + " in thread " + Thread.currentThread()
                return new BatchedDataFetcherResult([barName1, barName2, barName3, barName4, barName5])
            });
        } as BatchedDataFetcher;

        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Foo", "bar"), barDF)
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Bar", "name"), barNameDF)


        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy2(dataFetchingConfiguration)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        barDFCount.get() == 1
        barNameDFCount.get() == 1
        result.getData() == [foo: [[id: "fooId1", bar: [[id: "barId1", name: "someBar1"], [id: "barId2", name: "someBar2"]]],
                                   null,
                                   [id: "fooId2", bar: [[id: "barId3", name: "someBar3"], [id: "barId4", name: "someBar4"], [id: "barId5", name: "someBar5"]]],
                                   null]]


    }

    def "non nullable field was null error bubbles up"() {

        def fooData = [[id: "fooId1"],
                       null,
                       [id: "fooId2"],
                       null]
        def bar1 = [id: "barId1"];
        def bar2 = [id: "barId2"];
        def bar3 = [id: "barId3"];
        def bar4 = [id: "barId4"];
        def bar5 = [id: "barId5"];

        def barName1 = "someBar1"
        def barName2 = "someBar2"
        def barName3 = "someBar3"
        def barName4 = "someBar4"
        def barName5 = "someBar5"

        def schema = schema("""
        type Query {
            foo: [Foo]
        }
        type Foo {
            id: ID
            bar: [Bar!]
        }
        type Bar {
            id: ID
            name: String!
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

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "foo"), ({ fooData }) as TrivialDataFetcher)

        AtomicInteger barDFCount = new AtomicInteger()

        TrivialDataFetcher barTrivialDF = { env ->
            if (env.source.id == "fooId1") {
                return [bar1, bar2];
            } else {
                return [bar3, bar4, bar5];
            }
        } as TrivialDataFetcher;

        AtomicInteger barNameDFCount = new AtomicInteger()
        BatchedDataFetcher barNameDF = { env ->
            return Mono.fromSupplier({
                barNameDFCount.incrementAndGet();
                println "fetching barNames with sources: " + env.sources + " in thread " + Thread.currentThread()
                return new BatchedDataFetcherResult([barName1, barName2, barName3, barName4, null])
            });
        } as BatchedDataFetcher;

        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Foo", "bar"), barTrivialDF)
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Bar", "name"), barNameDF)


        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy2(dataFetchingConfiguration)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: [[id: "fooId1", bar: [[id: "barId1", name: "someBar1"], [id: "barId2", name: "someBar2"]]],
                                   null,
                                   [id: "fooId2", bar: null],
                                   null]]
        result.getErrors().size() == 1
        result.getErrors().get(0).errorType == ErrorType.NullValueInNonNullableField
    }

    def "non null error bubbles up to the top"() {
        given:
        def schema = schema("""
        type Query {
            foo: [Foo!]!
        }
        type Foo {
            bar: [Bar!]!
        }
        type Bar {
            name: String!
        }
        """)

        def query = """
        {foo {
            bar {
                name
            }
        }}
        """
        def fooData = [[bar: [[name: "barName1"]]],
                       [bar: [[name: "barName2"], [name: null]]]]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Query", "foo"), { Mono.just(fooData) } as SingleDataFetcher)
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy2(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == null
        result.getErrors().size() == 1
        result.getErrors().get(0).errorType == ErrorType.NullValueInNonNullableField

    }

    def "non null error bubbles up to top level"() {
        given:
        def schema = schema("""
        type Query {
            foo: [Foo!]
        }
        type Foo {
            bar: [Bar!]!
        }
        type Bar {
            name: String!
        }
        """)

        def query = """
        {foo {
            bar {
                name
            }
        }}
        """
        def fooData = [[bar: [[name: "barName1"]]],
                       [bar: [[name: "barName2"], [name: null]]]]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Query", "foo"), { Mono.just(fooData) } as SingleDataFetcher)
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy2(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: null]
        result.getErrors().size() == 1
        result.getErrors().get(0).errorType == ErrorType.NullValueInNonNullableField

    }

}
