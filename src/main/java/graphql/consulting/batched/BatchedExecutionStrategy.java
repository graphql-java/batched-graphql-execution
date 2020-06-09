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
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionPath;
import graphql.execution.MergedField;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeUtil.isList;

public class BatchedExecutionStrategy implements ExecutionStrategy {
    private static final Logger log = LoggerFactory.getLogger(BatchedExecutionStrategy.class);

    Scheduler fetchingScheduler = Schedulers.newParallel("data-fetching-scheduler");
    List<Scheduler> processingSchedulers;

    private final DataFetchingConfiguration dataFetchingConfiguration;
    private ResolveType resolveType = new ResolveType();

    public static final Object NULL_VALUE = new Object() {
        @Override
        public String toString() {
            return "NULL_VALUE";
        }
    };

    public BatchedExecutionStrategy(DataFetchingConfiguration dataFetchingConfiguration) {
        this.dataFetchingConfiguration = dataFetchingConfiguration;

        processingSchedulers = new ArrayList<>();
        for (int i = 1; i <= Runtime.getRuntime().availableProcessors(); i++) {
            processingSchedulers.add(Schedulers.newSingle("processing-thread-" + i));
        }
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

        private final Scheduler scheduler;
        private int pendingAsyncDataFetcher;

        private Tracker(Scheduler scheduler) {
            this.scheduler = scheduler;
        }

        public int getPendingAsyncDataFetcher() {
            return pendingAsyncDataFetcher;
        }

        public void decrementPendingAsyncDataFetcher() {
            pendingAsyncDataFetcher--;
        }

        public void incrementPendingAsyncDataFetcher() {
            pendingAsyncDataFetcher++;
        }

        public void addError(ExecutionPath executionPath, GraphQLError error) {
            errors.put(executionPath, error);
        }

        public Map<ExecutionPath, GraphQLError> getErrors() {
            return errors;
        }

        public Mono<Object> addFieldToFetch(ExecutionPath executionPath, NormalizedField normalizedField, Object source) {
//            System.out.println("add field to fetch at " + executionPath);
            OneField oneField = new OneField(executionPath, normalizedField, source);
            fieldsToFetch.add(oneField);
            oneField.resultMono = MonoProcessor.create();
            return oneField.resultMono.cache().doOnSubscribe(subscription -> {
            });
        }

        public void incrementNonNullCount(NormalizedField normalizedField, ExecutionPath executionPath) {
            int value = nonNullCount.getOrDefault(normalizedField, 0) + 1;
            nonNullCount.put(normalizedField, value);
        }

        public int addBatch(NormalizedField normalizedField, OneField oneField) {
            List<OneField> oneFields = batch.computeIfAbsent(normalizedField, ignored -> new ArrayList<>());
            oneFields.add(oneField);
            return oneFields.size();
        }

        public List<OneField> getBatch(NormalizedField normalizedField) {
            return batch.get(normalizedField);
        }

        public Map<NormalizedField, List<OneField>> getBatches() {
            return batch;
        }
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
                    return ExecutionResultImpl.newExecutionResult()
                            .addErrors(new ArrayList<>(value.getT2().getErrors().values()))
                            .data(value.getT1())
                            .build();
                })
                .onErrorResume(NonNullableFieldWasNullError.class::isInstance,
                        throwable -> Mono.just(ExecutionResultImpl.newExecutionResult()
                                .addError((NonNullableFieldWasNullError) throwable)
                                .build()))
                .cast(ExecutionResult.class)
                .toFuture();
    }


    private Mono<Tuple2<Map<String, Object>, Tracker>> fetchTopLevelFields(ExecutionContext executionContext,
                                                                           Object data,
                                                                           NormalizedQueryFromAst normalizedQueryFromAst) {
        Scheduler scheduler = processingSchedulers.get(ThreadLocalRandom.current().nextInt(processingSchedulers.size()));
        Tracker tracker = new Tracker(scheduler);

        return Mono.defer(() -> {
            List<NormalizedField> topLevelFields = normalizedQueryFromAst.getTopLevelFields();
            ExecutionPath rootPath = ExecutionPath.rootPath();
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

            monoChildren.add(Mono.defer(() -> {
                fetchFields(executionContext, normalizedQueryFromAst, tracker);
                return Mono.empty();
            }));
            Flux<Tuple2<String, Object>> flux = Flux.mergeSequential(monoChildren);
            Mono<Map<String, Object>> resultMapMono = flux.collect(mapCollector());
            return resultMapMono.zipWith(Mono.just(tracker));
        }).subscribeOn(tracker.scheduler);
    }

    private void fetchFields(ExecutionContext executionContext,
                             NormalizedQueryFromAst normalizedQueryFromAst,
                             Tracker tracker) {
        if (tracker.fieldsToFetch.size() == 0 && tracker.getPendingAsyncDataFetcher() == 0) {
            System.out.println("END!!!!");
            // this means we are at the end
            fetchAllRemainingBatches(executionContext, normalizedQueryFromAst, tracker);
        }
        while (!tracker.fieldsToFetch.isEmpty()) {
            OneField oneField = tracker.fieldsToFetch.poll();
            NormalizedField normalizedField = oneField.normalizedField;
            System.out.println("fetching field at " + oneField.executionPath);

            FieldCoordinates coordinates = coordinates(normalizedField.getObjectType(), normalizedField.getFieldDefinition());
            if (dataFetchingConfiguration.isSingleFetch(coordinates)) {
                singleFetchField(executionContext, normalizedQueryFromAst, tracker, oneField, normalizedField, coordinates);
            } else if (dataFetchingConfiguration.isFieldBatched(coordinates)) {
                batchFetchField(executionContext, normalizedQueryFromAst, tracker, oneField, normalizedField, coordinates);
            } else {
                trivialFetchField(oneField, normalizedField, coordinates);
            }
        }

    }

    private void fetchAllRemainingBatches(ExecutionContext executionContext, NormalizedQueryFromAst normalizedQueryFromAst, Tracker tracker) {
    }

    private void trivialFetchField(OneField oneField, NormalizedField normalizedField, FieldCoordinates coordinates) {
        TrivialDataFetcher trivialDataFetcher = this.dataFetchingConfiguration.getTrivialDataFetcher(coordinates);
        Object fetchedValue = trivialDataFetcher.get(new TrivialDataFetcherEnvironment(normalizedField, oneField.source));
        fetchedValue = replaceNullValue(fetchedValue);
        oneField.resultMono.onNext(fetchedValue);
    }

    private Object replaceNullValue(Object fetchedValue) {
        return fetchedValue == null ? NULL_VALUE : fetchedValue;
    }

    private void singleFetchField(ExecutionContext executionContext,
                                  NormalizedQueryFromAst normalizedQueryFromAst,
                                  Tracker tracker,
                                  OneField oneField,
                                  NormalizedField normalizedField,
                                  FieldCoordinates coordinates) {
        SingleDataFetcher singleDataFetcher = dataFetchingConfiguration.getSingleDataFetcher(coordinates);
        SingleDataFetcherEnvironment singleDataFetcherEnvironment = new SingleDataFetcherEnvironment(oneField.source, oneField.normalizedField, oneField.executionPath);

        tracker.incrementPendingAsyncDataFetcher();
        singleDataFetcher
                .get(singleDataFetcherEnvironment)
                .publishOn(fetchingScheduler)
                .publishOn(tracker.scheduler)
                .subscribe(fetchedValue -> {
                    tracker.decrementPendingAsyncDataFetcher();
                    fetchedValue = replaceNullValue(fetchedValue);
                    oneField.resultMono.onNext(fetchedValue);
                    fetchFields(executionContext, normalizedQueryFromAst, tracker);
                });
    }

    private void batchFetchField(ExecutionContext executionContext,
                                 NormalizedQueryFromAst normalizedQueryFromAst,
                                 Tracker tracker,
                                 OneField oneField,
                                 NormalizedField normalizedField,
                                 FieldCoordinates coordinates) {
        int curCount;
        int expectedCount;
        boolean batchedOnCoordinates = dataFetchingConfiguration.isBatchedOnCoordinates(coordinates);
        List<OneField> oneFields;
        if (batchedOnCoordinates) {

            tracker.addBatch(normalizedField, oneField);
            List<NormalizedField> fieldsWithSameCoordinates = normalizedQueryFromAst.getCoordinatesToNormalizedFields().get(coordinates);
//            System.out.println("same coordinates fields: " + fieldsWithSameCoordinates.size());
            expectedCount = 0;
            oneFields = new ArrayList<>();
            for (NormalizedField nf : fieldsWithSameCoordinates) {
                System.out.println("checking normalized field " + nf);
                if (!tracker.nonNullCount.containsKey(nf.getParent())) {
                    System.out.println("parent didn't record anything, abort");
                    return;
                }
                System.out.println("non null count for parent: " + nf.getParent().getPath() + " is " + tracker.nonNullCount.get(nf.getParent()));

                expectedCount += tracker.nonNullCount.get(nf.getParent());
                System.out.println("parent non null count: " + tracker.nonNullCount.get(nf.getParent()));
                System.out.println("batch : " + tracker.getBatch(nf));
                if (tracker.getBatch(nf) == null) {
                    System.out.println("abort, batches is null ");
                    return;
                }
                oneFields.addAll(tracker.getBatch(nf));
            }
            curCount = oneFields.size();
            System.out.println("expected count: " + expectedCount + " vs curCount " + curCount);
        } else {
            curCount = tracker.addBatch(normalizedField, oneField);
            expectedCount = tracker.nonNullCount.getOrDefault(normalizedField.getParent(), 1);
            oneFields = tracker.getBatch(normalizedField);
        }
        if (curCount == expectedCount) {
            BatchedDataFetcher batchedDataFetcher = dataFetchingConfiguration.getBatchedDataFetcher(coordinates);
            List<Object> sources = FpKit.map(oneFields, f -> f.source);
            List<NormalizedField> normalizedFields = FpKit.map(oneFields, f -> f.normalizedField);
            List<ExecutionPath> executionPaths = FpKit.map(oneFields, f -> f.executionPath);
            BatchedDataFetcherEnvironment env = new BatchedDataFetcherEnvironment(sources, normalizedFields, executionPaths);
            Mono<BatchedDataFetcherResult> batchedDataFetcherResultMono = batchedDataFetcher.get(env);
            tracker.incrementPendingAsyncDataFetcher();
            batchedDataFetcherResultMono
                    .publishOn(fetchingScheduler)
                    .publishOn(tracker.scheduler)
                    .subscribe(batchedDataFetcherResult -> {
                        tracker.decrementPendingAsyncDataFetcher();
                        for (int i = 0; i < batchedDataFetcherResult.getValues().size(); i++) {
                            Object fetchedValue = batchedDataFetcherResult.getValues().get(i);
                            fetchedValue = replaceNullValue(fetchedValue);
                            oneFields.get(i).resultMono.onNext(fetchedValue);
                        }
                        fetchFields(executionContext, normalizedQueryFromAst, tracker);
                    });

        } else {
        }
    }

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
        return tracker.addFieldToFetch(executionPath, normalizedField, source).map(resolved -> {
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

        boolean isNonNull = GraphQLTypeUtil.isNonNull(curType);

        if ((toAnalyze == NULL_VALUE || toAnalyze == null) && isNonNull) {
            NonNullableFieldWasNullError nonNullableFieldWasNullError = new NonNullableFieldWasNullError((GraphQLNonNull) curType, executionPath);
            return Mono.error(nonNullableFieldWasNullError);
        } else if (toAnalyze == NULL_VALUE || toAnalyze == null) {
//            System.out.println("null value for path: " + executionPath);
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

        return resolveType(executionContext, toAnalyze, curTypeWithoutNonNull, normalizedField, normalizedQueryFromAst)
                .flatMap(resolvedObjectType -> resolveObject(executionContext, tracker, normalizedField, normalizedQueryFromAst, resolvedObjectType, isNonNull, toAnalyze, executionPath));
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
                .collect(mapCollector())
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
                .collect(listCollector())
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


    private Mono<GraphQLObjectType> resolveType(ExecutionContext executionContext,
                                                Object source,
                                                GraphQLType curType,
                                                NormalizedField normalizedField,
                                                NormalizedQueryFromAst normalizedQueryFromAst) {
        MergedField mergedField = normalizedQueryFromAst.getMergedField(normalizedField);
        return resolveType.resolveType(executionContext, mergedField, source, null, curType);
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

    private Collector<Tuple2<String, Object>, Map<String, Object>, Map<String, Object>> mapCollector() {
        Supplier<Map<String, Object>> supplier = LinkedHashMap::new;
        BiConsumer<Map<String, Object>, Tuple2<String, Object>> acc = (map, tuple) -> {
            map.put(tuple.getT1(), maybeNullValue(tuple.getT2()));
        };

        return Collector.of(supplier, acc, throwingMerger());
    }

    private static <T> BinaryOperator<T> throwingMerger() {
        return (u, v) -> {
            throw new IllegalStateException(String.format("Duplicate key %s", u));
        };
    }

    private Object maybeNullValue(Object o) {
        return o == NULL_VALUE ? null : o;
    }

    private Collector<Object, List<Object>, List<Object>> listCollector() {
        Collector<Object, List<Object>, List<Object>> result = Collector.of((Supplier<List<Object>>) ArrayList::new,
                (list, o) -> list.add(maybeNullValue(o)),
                (left, right) -> {
                    left.addAll(right);
                    return left;
                });
        return result;
    }


}
