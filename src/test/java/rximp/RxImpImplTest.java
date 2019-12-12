package rximp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reactivex.Observable;
import io.reactivex.observers.TestObserver;
import io.reactivex.subjects.AsyncSubject;
import io.reactivex.subjects.PublishSubject;
import rximp.api.RxImp;
import rximp.api.RxImpException;
import rximp.api.RxImpGateway;
import rximp.api.RxImpMapper;
import rximp.api.RxImpMessage;
import rximp.impl.RxImpImpl;

public class RxImpImplTest {

    private static final String TEST_TOPIC = "/topic/test";

    PublishSubject<byte[]> inSubject;
    PublishSubject<byte[]> outSubject;
    RxImpGateway gateway;
    RxImpImpl rxImp;

    @Test
    public void constructionTest() {

        RxImpGateway gateway = mock(RxImpGateway.class);
        PublishSubject<byte[]> inSubject = PublishSubject.create();
        PublishSubject<byte[]> outSubject = PublishSubject.create();
        when(gateway.in()).thenReturn(inSubject);
        when(gateway.out()).thenReturn(outSubject);

        RxImp rxImp = new RxImpImpl(gateway);
    }

    @BeforeEach
    public void beforeEach() {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "OFF");
        gateway = mock(RxImpGateway.class);
        inSubject = PublishSubject.create();
        outSubject = PublishSubject.create();
        when(gateway.in()).thenReturn(inSubject);
        when(gateway.out()).thenReturn(outSubject);

        RxImpMapper mapper = new RxImpMapper() {

            private ObjectMapper mapper = new ObjectMapper();

            @Override
            public <T> T read(String payload, Class<T> clazz) throws Exception {
                return mapper.readValue(payload, clazz);
            }

            @Override
            public String write(Object payload) throws Exception {
                return mapper.writeValueAsString(payload);
            }

        };
        when(gateway.mapper()).thenReturn(mapper);

        rxImp = new RxImpImpl(gateway);
    }

    @Test
    public void messagesSubscribeOnCall() throws Exception {
        TestObserver<String> tester = outSubject.map(rxImp::mapIncoming)
                .map(msg -> gateway.mapper().read(msg.payload, String.class)).test();
        rxImp.observableCall(TEST_TOPIC, "Hello World", String.class).subscribe();
        tester.awaitCount(1);
        tester.assertValue("Hello World");
    }

    @Test
    public void registerCatchesSubscriptions() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        called.set(false);
        rxImp.registerCall(TEST_TOPIC, (args) -> {
            called.set(true);
            return Observable.just(args);
        }, String.class);

        RxImpMessage message = new RxImpMessage(TEST_TOPIC, 0, RxImpMessage.STATE_SUBSCRIBE,
                gateway.mapper().write("Hello World"));
        inSubject.onNext(rxImp.mapOutgoing(message));
        assertTrue(called.get());
    }

    @Test
    public void simpleConnection() throws Exception {
        rxImp.registerCall(TEST_TOPIC, (args) -> {
            return Observable.just(args);
        }, String.class);

        outSubject.subscribe(inSubject); // Connect Input and Output
        TestObserver<String> tester = rxImp.observableCall(TEST_TOPIC, "Hello World", String.class).test();
        tester.awaitCount(1);
        tester.assertValue("Hello World");
    }

    @Test
    public void floodingConnection() throws Exception {

        int count = 100000;
        rxImp.registerCall(TEST_TOPIC, (args) -> {
            return Observable.range(0, args);
        }, Integer.class);
        outSubject.subscribe(inSubject); // Connect Input and Output
        TestObserver<Integer> tester = rxImp.observableCall(TEST_TOPIC, count, Integer.class).test();
        tester.awaitCount(count);
        tester.assertValueCount(count);
    }

    @Test
    public void throwsError() throws Exception {
        int count = 100000;
        rxImp.registerCall(TEST_TOPIC, (args) -> {
            return Observable.error(new IllegalArgumentException("This is not what I wanted!"));
        }, Integer.class);
        outSubject.subscribe(inSubject); // Connect Input and Output
        TestObserver<Integer> tester = rxImp.observableCall(TEST_TOPIC, count, Integer.class).test();
        tester.awaitDone(1, TimeUnit.SECONDS);
        tester.assertError(RxImpException.class);
        tester.assertErrorMessage("This is not what I wanted!");
    }

    @Test
    public void detectsUnsubscribeAndRelaysIt() throws Exception {
        outSubject.subscribe(inSubject); // Connect Input and Output

        AsyncSubject<Object> disposeTester = AsyncSubject.create();
        rxImp.registerCall(TEST_TOPIC, (args) -> {
            return Observable.interval(10, TimeUnit.MILLISECONDS).take(10)
                    .doOnDispose(() -> disposeTester.onComplete());
        }, String.class);

        TestObserver<Object> disposeObs = disposeTester.test();
        TestObserver<Integer> tester = rxImp.observableCall(TEST_TOPIC, "Hello World", Integer.class).take(5).test();
        tester.awaitCount(5);
        tester.assertResult(0, 1, 2, 3, 4);
        Thread.sleep(10);
        disposeObs.assertComplete();
    }
}
