package rximp.impl;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.extern.slf4j.Slf4j;
import rximp.api.RxImp;
import rximp.api.RxImpException;
import rximp.api.RxImpGateway;
import rximp.api.RxImpMessage;

/**
 * RxImpImpl
 */
@Slf4j
public class RxImpImpl implements RxImp {

    private Charset charset = Charset.forName("UTF-8");

    private RxImpGateway gateway;

    private Observable<RxImpMessage> _in;

    private Subject<RxImpMessage> _out;

    private ObjectMapper mapper = new ObjectMapper();

    public RxImpImpl(Observable<byte[]> inStream, Subject<byte[]> outStream) {
        constructFromGateway(new RxImpStandardGateway(inStream, outStream));
    }

    public RxImpImpl(RxImpGateway gateway) {
        constructFromGateway(gateway);
    }

    private void constructFromGateway(RxImpGateway gateway) {
        this.gateway = gateway;
        this._in = gateway.in().map(this::mapIncoming).doOnNext(msg -> log.trace("Incoming msg: " + msg.toString()))
                .publish().autoConnect();
        this._out = PublishSubject.create();
        this._out.doOnNext(msg -> {
            log.trace("Sending msg: " + msg.toString());
        }).map(this::mapOutgoing).subscribe(next -> {
            gateway.out().onNext(next);
        });
    }

    @Override
    public <T> Observable<T> observableCall(String topic, Object payload, Class<T> clazz) throws Exception {
        log.trace("Calling: " + topic);
        final Object _payload = payload;
        RxImpMessage message = new RxImpMessage(topic, 0, RxImpMessage.STATE_SUBSCRIBE,
                gateway.mapper().write(_payload));

        return Observable.create(emitter -> {
            PublishSubject<RxImpMessage> publisher = PublishSubject.create();
            AtomicInteger currentCount = new AtomicInteger(0);
            List<RxImpMessage> queue = new CopyOnWriteArrayList<>();
            Disposable secondDisposable = this._in.filter(msg -> {
                return msg.id.equals(message.id);
            }).filter(msg -> msg.rx_state == RxImpMessage.STATE_COMPLETE || msg.rx_state == RxImpMessage.STATE_ERROR
                    || msg.rx_state == RxImpMessage.STATE_NEXT).map(this::checkError).subscribe(msg -> {
                        currentCount.incrementAndGet();
                        queue.add(msg);
                        queue.sort((a, b) -> Integer.compare(a.count, b.count));
                        queue.removeIf(t -> {
                            if (t.count < currentCount.get()) {
                                publisher.onNext(t);
                                return true;
                            } else {
                                return false;
                            }
                        });
                    }, error -> publisher.onError(error));

            Disposable disposable = publisher.takeWhile(this::checkNotComplete).map(msg -> {
                if (msg.payload != null) {
                    return gateway.mapper().read(msg.payload, clazz);
                } else {
                    return gateway.mapper().read("", clazz);
                }

            }).doOnDispose(() -> {
                RxImpMessage disposeMessage = new RxImpMessage(message.id, topic, 1, RxImpMessage.STATE_DISPOSE, null);
                this._out.onNext(disposeMessage);
                secondDisposable.dispose();
            }).subscribe(next -> emitter.onNext(next), error -> emitter.onError(error), () -> emitter.onComplete());
            this._out.onNext(message);
            emitter.setDisposable(disposable);
        });

        // return Observable.defer(() -> {
        // Observable<T> obs = this._in.filter(msg -> {
        // return msg.id.equals(message.id);
        // }).filter(msg -> {
        // return msg.rx_state != RxImpMessage.STATE_SUBSCRIBE;
        // }).map(this::checkError).takeWhile(this::checkNotComplete)
        // .map(msg -> gateway.mapper().read(msg.payload, clazz)).doOnDispose(() -> {
        // RxImpMessage disposeMessage = new RxImpMessage(message.id, topic, 0,
        // RxImpMessage.STATE_DISPOSE,
        // null);
        // this._out.onNext(disposeMessage);
        // });
        // this._out.onNext(message);
        // return obs;
        // }).share().doOnNext(n -> System.out.println("Next"));
    }

    private RxImpMessage checkError(RxImpMessage msg)
            throws RxImpException, JsonParseException, JsonMappingException, IOException {
        if (msg.rx_state == RxImpMessage.STATE_ERROR) {
            throw new RxImpException(mapper.readValue(msg.payload, String.class));
        } else {
            return msg;
        }
    }

    private boolean checkNotComplete(RxImpMessage msg) {
        if (msg.rx_state == RxImpMessage.STATE_COMPLETE) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public <T> Disposable registerCall(String topic, Function<T, Observable<?>> handler, Class<T> clazz) {
        AtomicInteger currentCount = new AtomicInteger(0);
        return this._in.filter(msg -> msg.rx_state == RxImpMessage.STATE_SUBSCRIBE)
                .filter(msg -> msg.topic.equals(topic)).subscribe(msg -> {
                    handler.apply(gateway.mapper().read(msg.payload, clazz))
                            .takeUntil(this._in.filter(disposeMsg -> disposeMsg.rx_state == RxImpMessage.STATE_DISPOSE)
                                    .filter(disposeMsg -> disposeMsg.id.equals(msg.id))
                                    .doOnNext(n -> log.trace("Disposed by Caller. " + topic + " id: " + n.id)))
                            .subscribe((next) -> {
                                RxImpMessage nextMsg = new RxImpMessage(msg.id, msg.topic,
                                        currentCount.getAndIncrement(), RxImpMessage.STATE_NEXT,
                                        gateway.mapper().write(next));
                                _out.onNext(nextMsg);
                            }, (error) -> {
                                RxImpMessage errorMsg = new RxImpMessage(msg.id, msg.topic,
                                        currentCount.getAndIncrement(), RxImpMessage.STATE_ERROR,
                                        gateway.mapper().write(error.getMessage()));
                                _out.onNext(errorMsg);
                            }, () -> {
                                RxImpMessage completeMsg = new RxImpMessage(msg.id, msg.topic,
                                        currentCount.getAndIncrement(), RxImpMessage.STATE_COMPLETE, null);
                                _out.onNext(completeMsg);
                            });
                });
    }

    public RxImpMessage mapIncoming(byte[] data) throws JsonParseException, JsonMappingException, IOException {
        return mapper.readValue(data, RxImpMessage.class);
    }

    public byte[] mapOutgoing(RxImpMessage msg) throws JsonProcessingException {
        return mapper.writeValueAsString(msg).getBytes(charset);
    }
}