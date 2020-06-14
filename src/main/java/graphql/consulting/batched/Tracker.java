package graphql.consulting.batched;

import graphql.Assert;
import graphql.GraphQLError;
import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ExecutionPath;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLUnionType;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static graphql.Assert.assertNotNull;
import static graphql.schema.GraphQLTypeUtil.unwrapAll;

public class Tracker {

    private static class NFData {
        public int isCurrentlyFetchingCount;
        public int fetchingFinishedCount;

        public boolean readyForBatching;
        public boolean fetchingFinished;

        public int nonNullChildren;
    }

    private final Deque<OneField> fieldsToFetch = new LinkedList<>();

    private final Map<NormalizedField, List<OneField>> batch = new LinkedHashMap<>();
    private final Map<ExecutionPath, GraphQLError> errors = new LinkedHashMap<>();

    private final Map<NormalizedField, NFData> nfDataMap = new LinkedHashMap<>();
    private final Map<NormalizedField, Set<GraphQLObjectType>> childTypesMap = new LinkedHashMap<>();

    private final Scheduler scheduler;
    private int pendingAsyncDataFetcher;

    private Consumer<List<NormalizedField>> fieldsFinishedBecauseNullParents;

    public Tracker(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void setFieldsFinishedBecauseNullParents(Consumer<List<NormalizedField>> fieldsFinishedBecauseNullParents) {
        Assert.assertNull(this.fieldsFinishedBecauseNullParents);
        this.fieldsFinishedBecauseNullParents = fieldsFinishedBecauseNullParents;
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
        OneField oneField = new OneField(executionPath, normalizedField, source);
        fieldsToFetch.add(oneField);
        oneField.resultMono = MonoProcessor.create();
        return oneField.resultMono.cache().doOnSubscribe(subscription -> {
        });
    }

    public void fetchingStarted(NormalizedField normalizedField) {
        NFData nfData = nfDataMap.computeIfAbsent(normalizedField, k -> new NFData());
        nfData.isCurrentlyFetchingCount++;
        NormalizedField parent = normalizedField.getParent();
        if (parent == null) {
            nfData.readyForBatching = true;
            return;
        }
        NFData nfDataParent = assertNotNull(nfDataMap.get(parent));
        if (nfDataParent.fetchingFinished && nfDataParent.nonNullChildren == nfData.isCurrentlyFetchingCount) {
            nfData.readyForBatching = true;
        }
    }

    public void fetchingFinished(NormalizedField normalizedField, ExecutionPath executionPath) {
        NFData nfData = nfDataMap.get(normalizedField);
        nfData.fetchingFinishedCount++;
        NormalizedField parent = normalizedField.getParent();
        if (parent == null) {
            // top level fields are always finished
            nfData.fetchingFinished = true;
            markNonFetchableChildrenAsDone(normalizedField, nfData);
            return;
        }
        NFData nfDataParent = assertNotNull(nfDataMap.get(parent));
        if (nfDataParent.fetchingFinished && nfDataParent.nonNullChildren == nfData.fetchingFinishedCount) {
            nfData.fetchingFinished = true;
            // this means no children will be fetched => mark all children as done
            markNonFetchableChildrenAsDone(normalizedField, nfData);
        }
    }

    private void markNonFetchableChildrenAsDone(NormalizedField normalizedField, NFData nfData) {
        if (nfData.nonNullChildren == 0) {
            markAllChildrenAsDone(normalizedField, false);
            return;
        }
        GraphQLOutputType fieldType = normalizedField.getFieldDefinition().getType();
        GraphQLOutputType unwrappedType = (GraphQLOutputType) unwrapAll(fieldType);
        // we are only concerned with interfaces or unions
        if (!(unwrappedType instanceof GraphQLUnionType) && !(unwrappedType instanceof GraphQLInterfaceType)) {
            return;
        }
        Set<GraphQLObjectType> childTypes = childTypesMap.get(normalizedField);
        for (NormalizedField child : normalizedField.getChildren()) {
            if (childTypes.contains(child.getObjectType())) {
                continue;
            }
            markAllChildrenAsDone(child, true);
        }

    }

    private void markAllChildrenAsDone(NormalizedField normalizedField, boolean includingItself) {
        List<NormalizedField> list = new ArrayList<>();
        if (includingItself) {
            NFData nfData = new NFData();
            nfData.readyForBatching = true;
            nfData.fetchingFinished = true;
            nfDataMap.put(normalizedField, nfData);
            list.add(normalizedField);
        }
        normalizedField.traverseSubTree(child -> {
            list.add(child);
            NFData nfData = new NFData();
            nfData.readyForBatching = true;
            nfData.fetchingFinished = true;
            nfDataMap.put(child, nfData);
        });
        if (list.size() > 0) {
            this.fieldsFinishedBecauseNullParents.accept(list);
        }
    }

    public void incrementNonNullCount(NormalizedField normalizedField, ExecutionPath executionPath) {
        nfDataMap.computeIfAbsent(normalizedField, k -> new NFData()).nonNullChildren++;
    }

    public int getNonNullCount(NormalizedField normalizedField) {
        return nfDataMap.computeIfAbsent(normalizedField, k -> new NFData()).nonNullChildren;
    }

    public boolean isNormalizedFieldFinishedFetching(NormalizedField normalizedField) {
        return nfDataMap.containsKey(normalizedField) && nfDataMap.get(normalizedField).fetchingFinished;
    }

    public boolean isReadyForBatching(NormalizedField normalizedField) {
        return nfDataMap.containsKey(normalizedField) && nfDataMap.get(normalizedField).readyForBatching;
    }

    public void addBatch(NormalizedField normalizedField, OneField oneField) {
        batch.computeIfAbsent(normalizedField, ignored -> new ArrayList<>()).add(oneField);
    }

    public List<OneField> getBatch(NormalizedField normalizedField) {
        return batch.get(normalizedField);
    }

    public Map<NormalizedField, List<OneField>> getBatches() {
        return batch;
    }

    public void addChildType(NormalizedField normalizedField, GraphQLObjectType resolvedObjectType) {
        childTypesMap.computeIfAbsent(normalizedField, k -> new LinkedHashSet<>()).add(resolvedObjectType);
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public Consumer<List<NormalizedField>> getFieldsFinishedBecauseNullParents() {
        return fieldsFinishedBecauseNullParents;
    }

    public Deque<OneField> getFieldsToFetch() {
        return fieldsToFetch;
    }

    public Map<NormalizedField, Integer> getFetchCountByNF() {
        Map<NormalizedField, Integer> result = new LinkedHashMap<>();
        for (NormalizedField nf : nfDataMap.keySet()) {
            result.put(nf, nfDataMap.get(nf).isCurrentlyFetchingCount);
        }
        return result;
    }
}
