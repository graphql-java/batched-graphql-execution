package graphql.consulting.batched;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.Scalars;
import graphql.SerializationError;
import graphql.TypeMismatchError;
import graphql.consulting.batched.normalized.NormalizedField;
import graphql.consulting.batched.normalized.NormalizedQueryFactory;
import graphql.consulting.batched.normalized.NormalizedQueryFromAst;
import graphql.consulting.batched.result.NonNullableFieldWasNullError;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionPath;
import graphql.execution.nextgen.ExecutionStrategy;
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
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeUtil.isList;

public class BatchedExecutionStrategy2 implements ExecutionStrategy {

    Scheduler fetchingScheduler = Schedulers.newParallel("data-fetching-scheduler");
    Scheduler processingScheduler = Schedulers.newSingle("processing-thread");

    private static final Logger log = LoggerFactory.getLogger(BatchedExecutionStrategy2.class);
    private final DataFetchingConfiguration dataFetchingConfiguration;

    public static final Object NULL_VALUE = new Object() {
        @Override
        public String toString() {
            return "NULL_VALUE";
        }
    };

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
        private final Map<ExecutionPath, GraphQLError> errors = new LinkedHashMap<>();


        public void addError(ExecutionPath executionPath, GraphQLError error) {
            errors.put(executionPath, error);
        }

        public Map<ExecutionPath, GraphQLError> getErrors() {
            return errors;
        }

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
    public CompletableFuture<ExecutionResult> execute(ExecutionContext executionContext) {
        NormalizedQueryFromAst normalizedQueryFromAst = NormalizedQueryFactory
                .createNormalizedQuery(executionContext.getGraphQLSchema(),
                        executionContext.getDocument(),
                        executionContext.getOperationDefinition().getName(),
                        executionContext.getVariables());

        Object data = null;

        Mono<Tuple2<Map<String, Object>, Tracker>> rootMono = fetchTopLevelFields(
                executionContext,
                data,
                normalizedQueryFromAst);

        return rootMono
                .map(value -> {
//                    System.out.println("result: " + value.getT1());
//                    System.out.println("errors: " + value.getT2().getErrors().values());
//                    return new RootExecutionResultNode(Collections.emptyList());
                    return ExecutionResultImpl.newExecutionResult()
                            .addErrors(new ArrayList<>(value.getT2().getErrors().values()))
                            .data(value.getT1())
                            .build();

                })
                .cast(ExecutionResult.class)
                .toFuture();
    }


    private Mono<Tuple2<Map<String, Object>, Tracker>> fetchTopLevelFields(ExecutionContext executionContext,
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
                monoChildren.add(executionResultNode);
            }

            Mono<Map<String, Object>> cache = Flux.concat(monoChildren).collectList().map(children -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Tuple2<String, Object> tuple : children) {
                    map.put(tuple.getT1(), tuple.getT2() == NULL_VALUE ? null : tuple.getT2());
                }
                return map;
            }).cache();
            cache.subscribe();
            fetchFields(executionContext, normalizedQueryFromAst, tracker);
            return cache.map(map -> {
                return Tuples.of(map, tracker);
            });
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
                                    fetchedValue = fetchedValue == null ? NULL_VALUE : fetchedValue;
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
                fetchedValue = fetchedValue == null ? NULL_VALUE : fetchedValue;
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
        System.out.println("analyze " + toAnalyze + " for " + executionPath);

        boolean isNonNull = GraphQLTypeUtil.isNonNull(curType);

        if ((toAnalyze == NULL_VALUE || toAnalyze == null) && isNonNull) {
            NonNullableFieldWasNullError nonNullableFieldWasNullError = new NonNullableFieldWasNullError((GraphQLNonNull) curType, executionPath);
            System.out.println("ERROR: " + executionPath + " not allowed to be null");
            return Mono.error(nonNullableFieldWasNullError);
        } else if (toAnalyze == NULL_VALUE || toAnalyze == null) {
//            return Mono.just(createNullERN(normalizedField, executionPath));
            return Mono.just(NULL_VALUE);
        }

        GraphQLOutputType curTypeWithoutNonNull = (GraphQLOutputType) GraphQLTypeUtil.unwrapNonNull(curType);
        if (isList(curTypeWithoutNonNull)) {
            return analyzeList(executionContext, tracker, toAnalyze, (GraphQLList) curTypeWithoutNonNull, isNonNull, normalizedField, normalizedQueryFromAst, executionPath);
        } else if (curTypeWithoutNonNull instanceof GraphQLScalarType) {
            tracker.incrementNonNullCount(normalizedField, executionPath);
            return analyzeScalarValue(toAnalyze, (GraphQLScalarType) curTypeWithoutNonNull, isNonNull, normalizedField, executionPath, tracker);
        } else if (curTypeWithoutNonNull instanceof GraphQLEnumType) {
            tracker.incrementNonNullCount(normalizedField, executionPath);
            return analyzeEnumValue(toAnalyze, (GraphQLEnumType) curTypeWithoutNonNull, isNonNull, normalizedField, executionPath, tracker);
        }
        tracker.incrementNonNullCount(normalizedField, executionPath);

        GraphQLObjectType resolvedObjectType = resolveType(executionContext, toAnalyze, curTypeWithoutNonNull);
        return resolveObject(executionContext, tracker, normalizedField, normalizedQueryFromAst, resolvedObjectType, isNonNull, toAnalyze, executionPath);
    }

    private Mono<Object> resolveObject(ExecutionContext context,
                                       Tracker tracker,
                                       NormalizedField normalizedField,
                                       NormalizedQueryFromAst normalizedQueryFromAst,
                                       GraphQLObjectType resolvedType,
                                       boolean isNonNull,
                                       Object completedValue,
                                       ExecutionPath executionPath) {

        List<Mono<Tuple2<String, Object>>> nodeChildrenMono = new ArrayList<>(normalizedField.getChildren().size());
        for (NormalizedField child : normalizedField.getChildren()) {
            if (child.getObjectType() == resolvedType) {
                ExecutionPath pathForChild = executionPath.segment(child.getResultKey());
                Mono<Tuple2<String, Object>> childNode = fetchAndAnalyzeField(context, tracker, completedValue, child, normalizedQueryFromAst, pathForChild)
                        .map(object -> Tuples.of(child.getResultKey(), object));
                nodeChildrenMono.add(childNode);
            }
        }
        return Flux.fromIterable(nodeChildrenMono)
                .flatMapSequential(Function.identity())
                .collectList().map(tupleList -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (Tuple2<String, Object> tuple2 : tupleList) {
                        map.put(tuple2.getT1(), tuple2.getT2());
                    }
                    return map;
                })
                .cast(Object.class)
                .onErrorResume(error -> error instanceof NonNullableFieldWasNullError,
                        throwable -> {
                            if (isNonNull) {
                                NonNullableFieldWasNullError nonNullError = new NonNullableFieldWasNullError(nonNull(resolvedType), executionPath);
                                return Mono.error(nonNullError);
                            } else {
                                tracker.addError(executionPath, (GraphQLError) throwable);
                                return Mono.just(NULL_VALUE);
                            }
                        });

    }


    private Mono<Object> analyzeList(ExecutionContext executionContext,
                                     Tracker tracker,
                                     Object toAnalyze,
                                     GraphQLList curType,
                                     boolean isNonNull,
                                     NormalizedField normalizedField,
                                     NormalizedQueryFromAst normalizedQueryFromAst,
                                     ExecutionPath executionPath) {

        if (toAnalyze instanceof List) {
            return createListImpl(executionContext, tracker, toAnalyze, (List<Object>) toAnalyze, curType, isNonNull, normalizedField, normalizedQueryFromAst, executionPath);
        } else {
            TypeMismatchError error = new TypeMismatchError(executionPath, curType);
            tracker.addError(executionPath, error);
            if (isNonNull) {
                NonNullableFieldWasNullError nonNullError = new NonNullableFieldWasNullError(nonNull(curType), executionPath);
                return Mono.error(nonNullError);
            } else {
                return Mono.just(NULL_VALUE);
            }
        }
    }


    private Mono<Object> createListImpl(ExecutionContext executionContext,
                                        Tracker tracker,
                                        Object fetchedValue,
                                        List<Object> iterableValues,
                                        GraphQLList currentType,
                                        boolean isNonNull,
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

        return Flux.fromIterable(children).flatMapSequential(Function.identity())
                .collectList()
                .map(objects -> {
                    return objects.stream().map(o -> o == NULL_VALUE ? null : o).collect(Collectors.toList());
                })
                .cast(Object.class)
                .onErrorResume(NonNullableFieldWasNullError.class::isInstance,
                        throwable -> {
                            if (isNonNull) {
                                NonNullableFieldWasNullError nonNullError = new NonNullableFieldWasNullError(nonNull(currentType), executionPath);
                                return Mono.error(nonNullError);
                            } else {
                                tracker.addError(executionPath, (GraphQLError) throwable);
                                return Mono.just(NULL_VALUE);
                            }
                        });
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


    private Mono<Object> analyzeScalarValue(Object toAnalyze,
                                            GraphQLScalarType scalarType,
                                            boolean isNonNull,
                                            NormalizedField normalizedField,
                                            ExecutionPath executionPath,
                                            Tracker tracker) {
        try {
            return Mono.just(serializeScalarValue(toAnalyze, scalarType));
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(executionPath, e);
            tracker.addError(executionPath, error);
            if (isNonNull) {
                NonNullableFieldWasNullError nonNullError = new NonNullableFieldWasNullError(nonNull(scalarType), executionPath);
                return Mono.error(nonNullError);
            } else {
                return Mono.just(NULL_VALUE);
            }
        }


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

    private Mono<Object> analyzeEnumValue(Object toAnalyze,
                                          GraphQLEnumType enumType,
                                          boolean isNonNull,
                                          NormalizedField normalizedField,
                                          ExecutionPath executionPath,
                                          Tracker tracker) {
        try {
            return Mono.just(enumType.serialize(toAnalyze));
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(executionPath, e);
            tracker.addError(executionPath, error);
            if (isNonNull) {
                NonNullableFieldWasNullError nonNullError = new NonNullableFieldWasNullError(nonNull(enumType), executionPath);
                return Mono.error(nonNullError);
            } else {
                return Mono.just(NULL_VALUE);
            }
        }
    }


}
