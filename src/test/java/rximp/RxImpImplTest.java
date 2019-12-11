package rximp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reactivex.observers.TestObserver;
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
            public <T> T read(byte[] payload, Class<T> clazz) throws Exception {
                return mapper.readValue(payload, clazz);
            }

            @Override
            public byte[] write(Object payload) throws Exception {
                return mapper.writeValueAsString(payload)
                             .getBytes(Charset.forName("UTF-16"));
            }

        };
        when(gateway.mapper()).thenReturn(mapper);

        rxImp = new RxImpImpl(gateway);
    }

    @Test
    public void messagesSubscribeOnCall() throws Exception {
        TestObserver<String> tester = outSubject.map(rxImp::mapIncoming)
                                                .map(msg -> gateway.mapper()
                                                                   .read(msg.payload, String.class))
                                                .test();
        rxImp.observableCall(TEST_TOPIC, "Hello World", String.class)
             .subscribe();
        tester.awaitCount(1);
        tester.assertValue("Hello World");
    }

    @Test
    public void registerCatchesSubscriptions() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        called.set(false);
        rxImp.registerCall(TEST_TOPIC, (args, subj) -> {
            called.set(true);
            subj.onNext(args);
            subj.onComplete();
        }, String.class);

        RxImpMessage message = new RxImpMessage(TEST_TOPIC, 0, RxImpMessage.STATE_SUBSCRIBE, gateway.mapper()
                                                                                                    .write("Hello World"));
        inSubject.onNext(rxImp.mapOutgoing(message));
        assertTrue(called.get());
        // outSubject.subscribe(inSubject); // Connect Input and Output

        // TestObserver<String> tester = rxImp.observableCall(TEST_TOPIC, "Hello World",
        // String.class)
        // .test();
        // tester.awaitCount(1);
        // tester.assertValue("Hello World");
    }

    @Test
    public void simpleConnection() throws Exception {
        rxImp.registerCall(TEST_TOPIC, (args, subj) -> {
            subj.onNext(args);
            subj.onComplete();
        }, String.class);

        outSubject.subscribe(inSubject); // Connect Input and Output
        TestObserver<String> tester = rxImp.observableCall(TEST_TOPIC, "Hello World", String.class)
                                           .test();
        tester.awaitCount(1);
        tester.assertValue("Hello World");
    }

    @Test
    public void floodingConnection() throws Exception {

        int count = 100000;
        rxImp.registerCall(TEST_TOPIC, (args, subj) -> {
            for (int i = 0; i < args; i++) {
                subj.onNext(i);
            }
            subj.onComplete();
        }, Integer.class);
        outSubject.subscribe(inSubject); // Connect Input and Output
        TestObserver<Integer> tester = rxImp.observableCall(TEST_TOPIC, count, Integer.class)
                                            .test();
        tester.awaitCount(count);
        tester.assertValueCount(count);
    }

    @Test
    public void throwsError() throws Exception {
        int count = 100000;
        rxImp.registerCall(TEST_TOPIC, (args, subj) -> {
            subj.onError(new IllegalArgumentException("This is not what I wanted!"));
        }, Integer.class);
        outSubject.subscribe(inSubject); // Connect Input and Output
        TestObserver<Integer> tester = rxImp.observableCall(TEST_TOPIC, count, Integer.class)
                                            .test();
        tester.awaitDone(1, TimeUnit.SECONDS);
        tester.assertError(RxImpException.class);
        tester.assertErrorMessage("This is not what I wanted!");
    }
}
