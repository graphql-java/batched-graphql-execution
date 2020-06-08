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

    def "batching with two dimensional lists"() {
        given:
        def schema = schema("""
        type Query {
            foo: [[Foo]]
        }
        type Foo {
            bar: [[Bar]]
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
        def bar1 = [name: "bar1"]
        def bar2 = [name: "bar2"]
        def bar3 = [name: "bar3"]
        def bar4 = [name: "bar4"]
        def bar5 = [name: "bar5"]
        def bar6 = [name: "bar6"]
        def bar7 = [name: "bar7"]
        def bar8 = [name: "bar8"]
        def bar9 = [name: "bar9"]
        def bar10 = [name: "bar10"]
        def bar11 = [name: "bar11"]
        def bar12 = [name: "bar12"]
        def bar13 = [name: "bar13"]
        def bar14 = [name: "bar14"]
        def bar15 = [name: "bar15"]
        def bar16 = [name: "bar16"]
        def bar17 = [name: "bar17"]
        def bar18 = [name: "bar18"]
        def bar19 = [name: "bar19"]
        def bar20 = [name: "bar20"]
        def bar21 = [name: "bar21"]

        def foo1 = [bar: [[bar1, bar2, null], [null], [bar3], [bar4, bar5, null, null], null, [bar6, bar7, bar8]]]
        def foo2 = [bar: [[bar9], [bar10, bar11], null]]
        def foo3 = [bar: [[bar12, bar13, null], [null, null], null, [bar14, bar15, bar16], null, [bar17, null]]]
        def foo4 = [bar: [[bar18, bar19, bar20, null], null, [bar21, null]]]

        def fooData = [[foo1], null, [foo2, foo3, foo4], null]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Query", "foo"), { Mono.just(fooData) } as SingleDataFetcher)

        AtomicInteger invokedCounter = new AtomicInteger()
        AtomicInteger barsCount = new AtomicInteger()

        def nameDF = { env ->
            invokedCounter.getAndIncrement()
            def result = env.sources.collect({ it.name })
            barsCount.set(result.size())
            println "batched result: " + result
            Mono.just(new BatchedDataFetcherResult(result))
        } as BatchedDataFetcher;
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Bar", "name"), nameDF);

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy2(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        invokedCounter.get() == 1
        barsCount.get() == 21
        result.getData() == [foo: fooData]
        result.getErrors().size() == 0

    }

}
