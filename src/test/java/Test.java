import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class Test {

    static Scheduler processingScheduler = Schedulers.newSingle("processing-thread");

    public static void main(String[] args) {
//        MonoProcessor<Object> source = MonoProcessor.create();
//        Mono<Object> mono = source.doOnSubscribe(subscription -> {
//            System.out.println("SUBSCCIBED");
//        });
//        mono.subscribe(value -> {
//            System.out.println("VALUE 1: " + value);
//        });
//        source.onNext("WORLD");
//        mono.subscribe(value -> {
//            System.out.println("VALUE 2: " + value);
//        });

        Mono<String> stringMono = Mono.fromCallable(() -> {
            System.out.println("1");
            return "hello";
        });

        Mono<String> cache = stringMono;
        cache.subscribe(s -> {
            System.out.println(s);
        });
        cache.subscribe(s -> {
            System.out.println(s);
        });
//        Mono.defer(() -> {
//            System.out.println("creating in " + Thread.currentThread());
//            return Mono.just("value");
//        }).subscribeOn(processingScheduler).subscribe();

    }
}
