package graphql.consulting.batched;

import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger atomicInteger = new AtomicInteger();

    public static int nextId() {
        return atomicInteger.getAndIncrement();
    }
}
