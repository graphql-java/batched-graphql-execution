package graphql.consulting.batched;

import graphql.Scalars;
import graphql.SerializationError;
import graphql.TypeMismatchError;
import graphql.consulting.batched.normalized.NormalizedField;
import graphql.consulting.batched.normalized.NormalizedQueryFactory;
import graphql.consulting.batched.normalized.NormalizedQueryFromAst;
import graphql.consulting.batched.result.LeafExecutionResultNode;
import graphql.consulting.batched.result.NonNullableFieldWasNullError;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionPath;
import graphql.execution.nextgen.ExecutionStrategy;
import graphql.execution.nextgen.FieldSubSelection;
import graphql.execution.nextgen.result.RootExecutionResultNode;
import graphql.schema.CoercingSerializeException;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.util.FpKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static graphql.consulting.batched.result.LeafExecutionResultNode.newLeafExecutionResultNode;
import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLTypeUtil.isList;

public class BatchedExecutionStrategy2 implements ExecutionStrategy {

    Scheduler fetchingScheduler = Schedulers.newParallel("data-fetching-scheduler");
    Scheduler processingScheduler = Schedulers.newSingle("processing-thread");

    private static final Logger log = LoggerFactory.getLogger(BatchedExecutionStrategy2.class);
    private final DataFetchingConfiguration dataFetchingConfiguration;


    public BatchedExecutionStrategy2(DataFetchingConfiguration dataFetchingConfiguration) {
        this.dataFetchingConfiguration = dataFetchingConfiguration;
    }

    private static class OneField {
        ExecutionPath executionPath;
        NormalizedField normalizedField;
        Object source;
        MonoProcessor<Object> resultMono;
//        Mono<Object> listener;

        public OneField(ExecutionPath executionPath, NormalizedField normalizedField, Object source) {
            this.executionPath = executionPath;
            this.normalizedField = normalizedField;
            this.source = source;
        }

        @Override
        public String toString() {
            return "OneField{" +
                    "executionPath=" + executionPath +
                    '}';
        }
    }

    private static class Tracker {
        private final Deque<OneField> fieldsToFetch = new LinkedList<>();
        private final Map<NormalizedField, Integer> nonNullCount = new LinkedHashMap<>();

        private final Map<NormalizedField, List<OneField>> batch = new LinkedHashMap<>();

//        private final Set<NormalizedField> batchedFieldsFetched = new LinkedHashSet<>();

        public Mono<Object> addFieldToFetch(ExecutionPath executionPath, NormalizedField normalizedField, Object source) {
            OneField oneField = new OneField(executionPath, normalizedField, source);
            fieldsToFetch.add(oneField);
            oneField.resultMono = MonoProcessor.create();
            return oneField.resultMono.cache().doOnSubscribe(subscription -> {
//                System.out.println("subscribed to " + executionPath);
            });
        }

        public void incrementNonNullCount(NormalizedField normalizedField, ExecutionPath executionPath) {
            int value = nonNullCount.getOrDefault(normalizedField, 0) + 1;
//            System.out.println("increment non null count to " + value + " for " + normalizedField + " at path " + executionPath);
            nonNullCount.put(normalizedField, value);
        }

        public int addBatch(NormalizedField normalizedField, OneField oneField) {
            List<OneField> oneFields = batch.computeIfAbsent(normalizedField, ignored -> new ArrayList<>());
            oneFields.add(oneField);
            return oneFields.size();

//            NormalizedField curParent = normalizedField.getParent();
//            while (curParent != null) {
//                nonNullCount.get(curParent)
//            }
        }

//        public void addBatchFieldFetched(NormalizedField normalizedField) {
//            if (batchedFieldsFetched.contains(normalizedField)) {
//                throw new RuntimeException("" + normalizedField + " already fetched");
//            }
//            this.batchedFieldsFetched.add(normalizedField);
//        }
//
//
//        public long decrementNonNullCount(NormalizedField normalizedField) {
//            if (normalizedField == null) {
//                return 0;
//            }
//            if (nonNullCount.getOrDefault(normalizedField, 0) == 0) {
//                return 0;
//            }
//            int value = nonNullCount.getOrDefault(normalizedField, 0) - 1;
//            nonNullCount.put(normalizedField, value);
//            return value;
//        }
    }

    @Override
    public CompletableFuture<graphql.execution.nextgen.result.RootExecutionResultNode> execute(
            ExecutionContext executionContext,
            FieldSubSelection fieldSubSelection) {

        NormalizedQueryFromAst normalizedQueryFromAst = NormalizedQueryFactory
                .createNormalizedQuery(executionContext.getGraphQLSchema(),
                        executionContext.getDocument(),
                        executionContext.getOperationDefinition().getName(),
                        executionContext.getVariables());

        Object data = null;

        Mono<Map<String, Object>> rootMono = fetchTopLevelFields(
                executionContext,
                data,
                normalizedQueryFromAst);

        return rootMono
                .map(value -> {
                    System.out.println("result: " + value);
                    return new RootExecutionResultNode(Collections.emptyList());
                })
                .toFuture();
    }


    private Mono<Map<String, Object>> fetchTopLevelFields(ExecutionContext executionContext,
                                                          Object data,
                                                          NormalizedQueryFromAst normalizedQueryFromAst) {
        return Mono.defer(() -> {
            List<NormalizedField> topLevelFields = normalizedQueryFromAst.getTopLevelFields();
            ExecutionPath rootPath = ExecutionPath.rootPath();
            Tracker tracker = new Tracker();
            List<Mono<Tuple2<String, Object>>> monoChildren = new ArrayList<>(topLevelFields.size());
            for (NormalizedField topLevelField : topLevelFields) {
                ExecutionPath path = rootPath.segment(topLevelField.getResultKey());

                Mono<Tuple2<String, Object>> executionResultNode = fetchAndAnalyzeField(
                        executionContext,
                        tracker,
                        data,
                        topLevelField,
                        normalizedQueryFromAst,
                        path)
                        .map(resolvedValue -> Tuples.of(topLevelField.getResultKey(), resolvedValue));
                executionResultNode = executionResultNode;
//                executionResultNode.subscribe();
                monoChildren.add(executionResultNode);
            }

            Mono<Map<String, Object>> cache = Flux.concat(monoChildren).collectList().map(children -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Tuple2<String, Object> tuple : children) {
                    map.put(tuple.getT1(), tuple.getT2());
                }
                return map;
            }).cache();
            cache.subscribe();
            fetchFields(executionContext, normalizedQueryFromAst, tracker);
            return cache;
        }).subscribeOn(processingScheduler);
    }

    private static class FetchedValue {
        Object fetchedValue;
        OneField oneField;

        public FetchedValue(Object fetchedValue, OneField oneField) {
            this.fetchedValue = fetchedValue;
            this.oneField = oneField;
        }
    }

    private void fetchFields(ExecutionContext executionContext,
                             NormalizedQueryFromAst normalizedQueryFromAst,
                             Tracker tracker) {
        if (tracker.fieldsToFetch.size() == 0) {
            return;
        }
//        System.out.println("start fetch fields at level " + level + " size: " + tracker.fieldsToFetch.size() + " = " + tracker.fieldsToFetch);
        AtomicInteger count = new AtomicInteger(tracker.fieldsToFetch.size());
        while (!tracker.fieldsToFetch.isEmpty()) {
//            List<OneField> batch = batches.poll();


            OneField oneField = tracker.fieldsToFetch.poll();
            List<ExecutionPath> exPathsLeft = tracker.fieldsToFetch.stream().map(oneField1 -> oneField1.executionPath).collect(Collectors.toList());
            System.out.println("fetching " + oneField.executionPath + " with queue left " + exPathsLeft);
            NormalizedField normalizedField = oneField.normalizedField;

            FieldCoordinates coordinates = coordinates(normalizedField.getObjectType(), normalizedField.getFieldDefinition());
            if (dataFetchingConfiguration.isFieldBatched(coordinates)) {
                int curCount = tracker.addBatch(normalizedField, oneField);
                int expectedCount = tracker.nonNullCount.getOrDefault(normalizedField.getParent(), 1);
                if (curCount == expectedCount) {
                    BatchedDataFetcher batchedDataFetcher = dataFetchingConfiguration.getBatchedDataFetcher(coordinates);
                    List<OneField> oneFields = tracker.batch.get(normalizedField);
                    List<Object> sources = FpKit.map(oneFields, f -> f.source);
                    List<NormalizedField> normalizedFields = FpKit.map(oneFields, f -> f.normalizedField);
                    List<ExecutionPath> executionPaths = FpKit.map(oneFields, f -> f.executionPath);
                    System.out.println("fetching batched values for " + executionPaths);
                    BatchedDataFetcherEnvironment env = new BatchedDataFetcherEnvironment(sources, normalizedFields, executionPaths);
                    Mono<BatchedDataFetcherResult> batchedDataFetcherResultMono = batchedDataFetcher.get(env);
                    batchedDataFetcherResultMono
                            .publishOn(fetchingScheduler)
                            .publishOn(processingScheduler)
                            .subscribe(batchedDataFetcherResult -> {
                                for (int i = 0; i < batchedDataFetcherResult.getValues().size(); i++) {
                                    Object fetchedValue = batchedDataFetcherResult.getValues().get(i);
                                    oneFields.get(i).resultMono.onNext(fetchedValue);
                                }
                                fetchFields(executionContext, normalizedQueryFromAst, tracker);
                            });

                } else {
                    System.out.println("not fetching batched because " + curCount + " vs " + expectedCount);
                }
            } else {
                System.out.println("fetching trivial value in thread");
                TrivialDataFetcher trivialDataFetcher = this.dataFetchingConfiguration.getTrivialDataFetcher(coordinates);
                Object fetchedValue = trivialDataFetcher.get(new TrivialDataFetcherEnvironment(normalizedField, oneField.source));
//                System.out.println("trivial fetched value: " + fetchedValue);
                oneField.resultMono.onNext(fetchedValue);
//                System.out.println("after subscribe with " + tracker.fieldsToFetch.size());
            }
        }

//            Function<Object, Mono<Object>> objectMonoFunction = getDataFetcher(coordinates, normalizedField);
//            Mono<Object> mono = objectMonoFunction.apply(oneField.source);
//            mono
//                    .publishOn(fetchingScheduler)
//                    .publishOn(processingScheduler)
//                    .subscribe(resolvedObject -> {
//                        // this relies on the fact that there is already a real subscriber to
//                        // to the result mono
//                        oneField.resultMono.onNext(resolvedObject);
//                        oneField.resultMono.subscribe(o -> {
////                            System.out.println("Got " + oneField.executionPath);
//                            // this means we are waiting per level
////                            count.decrementAndGet();
////                            if (count.get() == 0 && tracker.fieldsToFetch.size() == 0) {
////                                System.out.println("finished overall " + tracker.nonNullCount);
////
////                                result.onNext("finished");
////                            } else if (count.get() == 0 && tracker.fieldsToFetch.size() > 0) {
//                            fetchFields(executionContext, normalizedQueryFromAst, tracker);
////                            }
//                        });
//                    });
    }

//}

//    private Deque<List<OneField>> groupIntoBatches(Collection<OneField> fields) {
//        Map<FieldCoordinates, List<OneField>> fieldCoordinatesListMap = FpKit.groupingBy(fields,
//                oneField -> coordinates(oneField.normalizedField.getObjectType(), oneField.normalizedField.getFieldDefinition()));
//        return new LinkedList<>(fieldCoordinatesListMap.values());
//    }

//    private Mono<List<Object>> fetchBatch(List<OneField> batch) {
//        return null;
//    }
//
//    private Mono<Object> fetchSingleValue(OneField oneField) {
//        FieldCoordinates coordinates = coordinates(oneField.normalizedField.getObjectType(), oneField.normalizedField.getFieldDefinition());
//        Function<Object, Mono<Object>> objectMonoFunction = getDataFetcher(coordinates, oneField.normalizedField);
//        Mono<Object> mono = objectMonoFunction.apply(oneField.source);
//        return mono;
//    }

//    private Function<Object, Mono<Object>> getDataFetcher(FieldCoordinates coordinates, NormalizedField normalizedField) {
//        Function<Object, Mono<Object>> objectMonoFunction = dataFetchers.get(coordinates);
//        if (objectMonoFunction != null) {
//            return objectMonoFunction;
//        }
//        return (source) -> {
//            Map map = (Map) source;
//            return Mono.just(map.get(normalizedField.getResultKey()));
//        };
//    }


    private Mono<Object> fetchAndAnalyzeField(ExecutionContext context,
                                              Tracker tracker,
                                              Object source,
                                              NormalizedField normalizedField,
                                              NormalizedQueryFromAst normalizedQueryFromAst,
                                              ExecutionPath executionPath) {
        // if should be batched we will add it to the list of sources that should be fetched
        return fetchValue(source, tracker, normalizedField, executionPath).flatMap(fetchedValue -> {
            return analyseValue(context, tracker, fetchedValue, normalizedField, normalizedQueryFromAst, executionPath);
        });
    }

    private Mono<Object> fetchValue(Object source, Tracker tracker, NormalizedField normalizedField, ExecutionPath executionPath) {
        System.out.println("new fetch: " + executionPath);
        return tracker.addFieldToFetch(executionPath, normalizedField, source).map(resolved -> {
//            System.out.println("WORKER: Got value for " + executionPath);
            return resolved;
        });

        // find out if we already have all source objects for a certain
//        if (isReadyForBatching(executionPath)) {
//
//        }
//        if (source == null) {
//            return null;
//        }
//        @SuppressWarnings("unchecked")
//        Map<String, Object> map = (Map<String, Object>) source;
//        return map.get(key);
//        return null;
    }

    private Mono<Object> fetchOneFieldImpl(Object source) {
        return null;
    }

    private Mono<List<Object>> batchFetch(List<Object> source) {
        return null;
    }


    private Mono<Object> analyseValue(ExecutionContext executionContext,
                                      Tracker tracker,
                                      Object fetchedValue,
                                      NormalizedField normalizedField,
                                      NormalizedQueryFromAst normalizedQueryFromAst,
                                      ExecutionPath executionPath) {
        return analyzeFetchedValueImpl(executionContext, tracker, fetchedValue, normalizedField, normalizedQueryFromAst, normalizedField.getFieldDefinition().getType(), executionPath);
    }

    private Mono<Object> analyzeFetchedValueImpl(ExecutionContext executionContext,
                                                 Tracker tracker,
                                                 Object toAnalyze,
                                                 NormalizedField normalizedField,
                                                 NormalizedQueryFromAst normalizedQueryFromAst,
                                                 GraphQLOutputType curType,
                                                 ExecutionPath executionPath) {

        boolean isNonNull = GraphQLTypeUtil.isNonNull(curType);
        if (toAnalyze == null && isNonNull) {
            NonNullableFieldWasNullError nonNullableFieldWasNullError = new NonNullableFieldWasNullError((GraphQLNonNull) curType, executionPath);
            return Mono.error(nonNullableFieldWasNullError);
        } else if (toAnalyze == null) {
//            return Mono.just(createNullERN(normalizedField, executionPath));
            return Mono.just("NULL");
        }

        curType = (GraphQLOutputType) GraphQLTypeUtil.unwrapNonNull(curType);
        if (isList(curType)) {
            return analyzeList(executionContext, tracker, toAnalyze, (GraphQLList) curType, normalizedField, normalizedQueryFromAst, executionPath);
        } else if (curType instanceof GraphQLScalarType) {
            tracker.incrementNonNullCount(normalizedField, executionPath);
            return Mono.just(analyzeScalarValue(toAnalyze, (GraphQLScalarType) curType, normalizedField, executionPath));
        } else if (curType instanceof GraphQLEnumType) {
            tracker.incrementNonNullCount(normalizedField, executionPath);
            return Mono.just(analyzeEnumValue(toAnalyze, (GraphQLEnumType) curType, normalizedField, executionPath));
        }
        tracker.incrementNonNullCount(normalizedField, executionPath);

        GraphQLObjectType resolvedObjectType = resolveType(executionContext, toAnalyze, curType);
        return resolveObject(executionContext, tracker, normalizedField, normalizedQueryFromAst, resolvedObjectType, toAnalyze, executionPath);
    }

    private Mono<Object> resolveObject(ExecutionContext context,
                                       Tracker tracker,
                                       NormalizedField normalizedField,
                                       NormalizedQueryFromAst normalizedQueryFromAst,
                                       GraphQLObjectType resolvedType,
                                       Object completedValue,
                                       ExecutionPath executionPath) {

        List<Mono<Tuple2<String, Object>>> nodeChildrenMono = new ArrayList<>(normalizedField.getChildren().size());

        for (NormalizedField child : normalizedField.getChildren()) {
            if (child.getObjectType() == resolvedType) {
                ExecutionPath pathForChild = executionPath.segment(child.getResultKey());
                Mono<Tuple2<String, Object>> childNode = fetchAndAnalyzeField(context, tracker, completedValue, child, normalizedQueryFromAst, pathForChild)
                        .map(object -> Tuples.of(child.getResultKey(), object));
                childNode = childNode;
//                childNode.subscribe();
                nodeChildrenMono.add(childNode);
            }
        }
//        System.out.println("resolving object at " + executionPath + " with " + nodeChildrenMono.size());
        return Flux.fromIterable(nodeChildrenMono)
                .flatMapSequential(Function.identity())
                .collectList().map(tupleList -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (Tuple2<String, Object> tuple2 : tupleList) {
                        map.put(tuple2.getT1(), tuple2.getT2());
                    }
                    return map;
                });
//            return newObjectExecutionResultNode()
//                    .executionPath(executionPath)
//                    .normalizedField(normalizedField)
//                    .objectType(normalizedField.getObjectType())
//                    .fieldDefinition(normalizedField.getFieldDefinition())
//                    .completedValue(completedValue)
//                    .children(nodeChildren)
//                    .build();
//


    }


    private Mono<Object> analyzeList(ExecutionContext executionContext,
                                     Tracker tracker,
                                     Object toAnalyze,
                                     GraphQLList curType,
                                     NormalizedField normalizedField,
                                     NormalizedQueryFromAst normalizedQueryFromAst,
                                     ExecutionPath executionPath) {

        if (toAnalyze instanceof List) {
            // eagerly subscribe needed?
            return createListImpl(executionContext, tracker, toAnalyze, (List<Object>) toAnalyze, curType, normalizedField, normalizedQueryFromAst, executionPath);
        } else {
            TypeMismatchError error = new TypeMismatchError(executionPath, curType);
            return Mono.just(newLeafExecutionResultNode()
                    .executionPath(executionPath)
                    .normalizedField(normalizedField)
                    .fieldDefinition(normalizedField.getFieldDefinition())
                    .objectType(normalizedField.getObjectType())
                    .completedValue(null)
                    .addError(error)
                    .build());
        }
    }

    private LeafExecutionResultNode createNullERNWithNullableError(NormalizedField normalizedField,
                                                                   ExecutionPath executionPath,
                                                                   NonNullableFieldWasNullError nonNullableFieldWasNullError) {
        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .fieldDefinition(normalizedField.getFieldDefinition())
                .objectType(normalizedField.getObjectType())
                .completedValue(null)
                .normalizedField(normalizedField)
                .nonNullableFieldWasNullError(nonNullableFieldWasNullError)
                .build();
    }

    private LeafExecutionResultNode createNullERN(NormalizedField normalizedField,
                                                  ExecutionPath executionPath) {
        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .fieldDefinition(normalizedField.getFieldDefinition())
                .objectType(normalizedField.getObjectType())
                .completedValue(null)
                .normalizedField(normalizedField)
                .build();
    }

    private Mono<Object> createListImpl(ExecutionContext executionContext,
                                        Tracker tracker,
                                        Object fetchedValue,
                                        List<Object> iterableValues,
                                        GraphQLList currentType,
                                        NormalizedField normalizedField,
                                        NormalizedQueryFromAst normalizedQueryFromAst,
                                        ExecutionPath executionPath) {
        List<Mono<Object>> children = new ArrayList<>();
        int index = 0;
        for (Object item : iterableValues) {
            ExecutionPath indexedPath = executionPath.segment(index);
            children.add(analyzeFetchedValueImpl(executionContext, tracker, item, normalizedField, normalizedQueryFromAst, (GraphQLOutputType) GraphQLTypeUtil.unwrapOne(currentType), indexedPath));
            index++;
        }
        return Flux.fromIterable(children).flatMapSequential(Function.identity()).collectList().cast(Object.class);
//        .map(c ->
//                ListExecutionResultNode.newListExecutionResultNode()
//                        .executionPath(executionPath)
//                        .normalizedField(normalizedField)
//                        .fieldDefinition(normalizedField.getFieldDefinition())
//                        .objectType(normalizedField.getObjectType())
//                        .completedValue(fetchedValue)
//                        .children(c)
//                        .build()
//        );

    }


    private GraphQLObjectType resolveType(ExecutionContext executionContext, Object source, GraphQLType curType) {
        if (curType instanceof GraphQLObjectType) {
            return (GraphQLObjectType) curType;
        }
//        String underscoreTypeNameAlias = nadelContext.getUnderscoreTypeNameAlias();
//
//        assertTrue(source instanceof Map, "The Nadel result object MUST be a Map");
//
//        Map<String, Object> sourceMap = (Map<String, Object>) source;
//        assertTrue(sourceMap.containsKey(underscoreTypeNameAlias), "The Nadel result object for interfaces and unions MUST have an aliased __typename in them");
//
//        Object typeName = sourceMap.get(underscoreTypeNameAlias);
//        assertNotNull(typeName, "The Nadel result object for interfaces and unions MUST have an aliased__typename with a non null value in them");
//
//        GraphQLObjectType objectType = executionContext.getGraphQLSchema().getObjectType(typeName.toString());
//        assertNotNull(objectType, "There must be an underlying graphql object type called '%s'", typeName);
//        return objectType;
        return null;


    }


    private Object analyzeScalarValue(Object toAnalyze,
                                      GraphQLScalarType scalarType,
                                      NormalizedField normalizedField,
                                      ExecutionPath executionPath) {
        Object serialized;
        try {
            serialized = serializeScalarValue(toAnalyze, scalarType);
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(executionPath, e);
            return newLeafExecutionResultNode()
                    .executionPath(executionPath)
                    .normalizedField(normalizedField)
                    .fieldDefinition(normalizedField.getFieldDefinition())
                    .objectType(normalizedField.getObjectType())
                    .completedValue(null)
                    .addError(error)
                    .build();
        }

        return serialized;
//        return newLeafExecutionResultNode()
//                .executionPath(executionPath)
//                .normalizedField(normalizedField)
//                .fieldDefinition(normalizedField.getFieldDefinition())
//                .objectType(normalizedField.getObjectType())
//                .completedValue(serialized)
//                .build();

    }

    protected Object serializeScalarValue(Object toAnalyze, GraphQLScalarType scalarType) throws CoercingSerializeException {
        if (scalarType == Scalars.GraphQLString) {
            if (toAnalyze instanceof String) {
                return toAnalyze;
            } else {
                throw new CoercingSerializeException("Unexpected value '" + toAnalyze + "'. String expected");
            }
        }
        return scalarType.getCoercing().serialize(toAnalyze);
    }

    private Object analyzeEnumValue(Object toAnalyze,
                                    GraphQLEnumType enumType,
                                    NormalizedField normalizedField,
                                    ExecutionPath executionPath) {
        Object serialized;
        try {
            serialized = enumType.serialize(toAnalyze);
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(executionPath, e);
            return newLeafExecutionResultNode()
                    .executionPath(executionPath)
                    .normalizedField(normalizedField)
                    .fieldDefinition(normalizedField.getFieldDefinition())
                    .objectType(normalizedField.getObjectType())
                    .completedValue(null)
                    .addError(error)
                    .build();
        }
        return serialized;
//        return newLeafExecutionResultNode()
//                .executionPath(executionPath)
//                .normalizedField(normalizedField)
//                .fieldDefinition(normalizedField.getFieldDefinition())
//                .objectType(normalizedField.getObjectType())
//                .completedValue(serialized)
//                .build();
    }


}
