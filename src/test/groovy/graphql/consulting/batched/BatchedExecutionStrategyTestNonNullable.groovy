package graphql.consulting.batched

import graphql.ErrorType
import graphql.nextgen.GraphQL
import reactor.core.publisher.Mono
import spock.lang.Ignore
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import static graphql.ExecutionInput.newExecutionInput
import static graphql.consulting.batched.BatchedExecutionStrategy.NULL_VALUE
import static graphql.consulting.batched.TestUtil.schema
import static graphql.schema.FieldCoordinates.coordinates

class BatchedExecutionStrategyTestNonNullable extends Specification {


    def "non nullable error"() {
        def schema = schema("""
        type Query {
            foo: Foo
        }
        type Foo {
            bar: String!
        }
        """)


        def query = """
        {foo { bar } }
        """
        when:

        def fooData = [bar: null]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "foo"), ({ fooData }) as TrivialDataFetcher)

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.errors.size() == 1
        result.errors[0].errorType == ErrorType.NullValueInNonNullableField
        result.errors[0].message.contains("Foo.bar")
        result.errors[0].path == ['foo', 'bar']
        result.getData() == [foo: null]
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


        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: [[id: "fooId1", bar: [[id: "barId1", name: "someBar1"], [id: "barId2", name: "someBar2"]]],
                                   null,
                                   [id: "fooId2", bar: null],
                                   null]]
        result.getErrors().size() == 1
        result.getErrors().get(0).errorType == ErrorType.NullValueInNonNullableField
        result.getErrors().get(0).message.contains("Bar.name")
        result.getErrors().get(0).path == ['foo', 2, 'bar', 2, 'name']
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
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == null
        result.getErrors().size() == 1
        result.getErrors().get(0).errorType == ErrorType.NullValueInNonNullableField
        result.getErrors().get(0).message.contains("Bar.name")
        result.getErrors().get(0).path == ['foo', 1, 'bar', 1, 'name']

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
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: null]
        result.getErrors().size() == 1
        result.getErrors().get(0).errorType == ErrorType.NullValueInNonNullableField
        result.getErrors().get(0).message.contains("Bar.name")
        result.getErrors().get(0).path == ['foo', 1, 'bar', 1, 'name']

    }


}
