import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.UnicastProcessor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedList;

public class Test {

    static Scheduler processingScheduler = Schedulers.newSingle("processing-thread");

    static class Node {
        Mono<Node> left;
        Mono<Node> right;
    }

    static void traverse(Node root) {
        DirectProcessor<Object> objectDirectProcessor = DirectProcessor.create();
        Flux<Object> nodes = Flux.from(objectDirectProcessor);

        nodes.subscribe(node -> {
        });
//
//        while (!queue.isEmpty()) {
//
//        }
//        root.left.map(node -> {
//            objectDirectProcessor.onNext(node);
//        });
    }

    public static void main(String[] args) throws InterruptedException {
//        MonoProcessor<Object> source = MonoProcessor.create();
//
//        Mono<Object> mono = source.doOnSubscribe(subscription -> {
//            System.out.println("on subscribe");
//        });
//
//        mono = mono.flatMap(resolveValue -> {
//            System.out.println("resolved original value " + resolveValue);
//            return Mono.just("sub value").map(subvalue -> {
//                System.out.println("sub value:" + subvalue);
//                return subvalue;
//            });
//        });
//
//        mono = mono.doFinally((signalType) -> {
//            System.out.println("terminate with " + signalType);
//        });
//        mono.subscribe(o -> {
//            System.out.println("finished got value: " + o);
//        });
//        source.doFinally(signalType -> {
//            System.out.println("Source do finally " + signalType);
//        });
//
//        source.onNext("original value");
//        UnicastProcessor<Long> data = UnicastProcessor.create();
//        data.subscribe(t -> {
//            System.out.println(t);
//        });
//        data.subscribe(t -> {
//            System.out.println(t);
//        });
//        data.sink().next(10L);
//        Mono<String> hello = Mono.fromCallable(() -> {
//            return Instant.now().toString();
//        }).cache();
//
//        hello.subscribe(System.out::println);
//        hello.subscribe(System.out::println);

        Flux<Long> startFlux = Flux.interval(Duration.ofMillis(1000)).publish().refCount();
//        Flux<Long> startFlux = Flux.interval(Duration.ofMillis(1000)).share();
//        Mono.just("hello").publish().r
//
//        for (int i = 0; i < 10; i++) {
//            final int subscriptionNumber = i;
//            Flux<Long> outputFlux = Flux.from(startFlux);
//            outputFlux.subscribe(out -> System.out.println("Flux " + subscriptionNumber + " " + out +" " + Thread.currentThread()));
//        }
//        Thread.sleep(10000);

        MonoProcessor<Object> objectMonoProcessor = MonoProcessor.create();
        Mono<Object> cache = objectMonoProcessor.cache();
        Mono<Object> mono = cache.doOnSubscribe(subscription -> {
            System.out.println("first consumer");

            cache.subscribe(o -> {
                System.out.println("second consumer");
            });
        });
        objectMonoProcessor.onNext("hello");
        mono.subscribe();
    }

}
