import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class Test {

    static Scheduler processingScheduler = Schedulers.newSingle("processing-thread");

    public static void main(String[] args) {
        MonoProcessor<Object> source = MonoProcessor.create();

        Mono<Object> mono = source.doOnSubscribe(subscription -> {
            System.out.println("on subscribe");
        });

        mono = mono.flatMap(resolveValue -> {
            System.out.println("resolved original value " + resolveValue);
            return Mono.just("sub value").doOnSubscribe(subvalue -> {
                System.out.println("subscribed sub value");
            });
        });

        mono.subscribe();

        source.onNext("original value");

    }

}
