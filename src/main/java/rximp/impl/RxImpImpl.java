package rximp.impl;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiConsumer;
import io.reactivex.observables.ConnectableObservable;
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

    private Charset charset = Charset.forName("UTF-16");

    private RxImpGateway gateway;

    private Observable<RxImpMessage> _in;

    private Subject<RxImpMessage> _out;

    private ObjectMapper mapper = new ObjectMapper();

    public RxImpImpl(RxImpGateway gateway) {
        this.gateway = gateway;
        this._in = gateway.in()
                          .map(this::mapIncoming)
                          .doOnNext(msg -> log.info("Incoming msg: " + msg.toString()))
                          .publish()
                          .autoConnect();
        this._out = PublishSubject.create();
        this._out.doOnNext(msg -> {
            log.info("Sending msg: " + msg.toString());
        })
                 .map(this::mapOutgoing)
                 .subscribe(next -> {
                     log.info(new String(next, charset));
                     gateway.out()
                            .onNext(next);
                 });
    }

    @Override
    public <T> Observable<T> observableCall(String topic, Object payload, Class<T> clazz) throws Exception {
        log.info("Calling: " + topic);
        final Object _payload = payload;
        RxImpMessage message = new RxImpMessage(topic, 0, RxImpMessage.STATE_SUBSCRIBE, gateway.mapper()
                                                                                               .write(_payload));
        return Observable.defer(() -> {

            ConnectableObservable<T> obs = _in.filter(msg -> msg.id.equals(message.id))
                                              .map(this::checkError)
                                              .takeWhile(this::checkNotComplete)
                                              .map(msg -> gateway.mapper()
                                                                 .read(msg.payload, clazz))
                                              .doOnDispose(() -> {
                                                  RxImpMessage disposeMessage = new RxImpMessage(message.id, topic, 0,
                                                          RxImpMessage.STATE_DISPOSE, new byte[0]);
                                                  this._out.onNext(disposeMessage);
                                              })
                                              .replay();
            obs.connect();
            this._out.onNext(message);
            return obs;
        });
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
    public <T> Disposable registerCall(String topic, BiConsumer<T, Subject<Object>> handler, Class<T> clazz) {
        return this._in.filter(msg -> msg.rx_state == RxImpMessage.STATE_SUBSCRIBE)
                       .filter(msg -> msg.topic.equals(topic))
                       .subscribe(msg -> {
                           PublishSubject<Object> subject = PublishSubject.create();

                           subject.subscribe((next) -> {
                               RxImpMessage nextMsg = new RxImpMessage(msg.id, msg.topic, 0, RxImpMessage.STATE_NEXT,
                                       gateway.mapper()
                                              .write(next));
                               _out.onNext(nextMsg);
                           }, (error) -> {
                               RxImpMessage errorMsg = new RxImpMessage(msg.id, msg.topic, 0, RxImpMessage.STATE_ERROR,
                                       gateway.mapper()
                                              .write(error.getMessage()));
                               _out.onNext(errorMsg);
                           }, () -> {
                               RxImpMessage completeMsg = new RxImpMessage(msg.id, msg.topic, 0,
                                       RxImpMessage.STATE_COMPLETE, null);
                               _out.onNext(completeMsg);
                           });
                           handler.accept(gateway.mapper()
                                                 .read(msg.payload, clazz),
                                   subject);
                       });
    }

    public RxImpMessage mapIncoming(byte[] data) throws JsonParseException, JsonMappingException, IOException {
        return mapper.readValue(data, RxImpMessage.class);
    }

    public byte[] mapOutgoing(RxImpMessage msg) throws JsonProcessingException {
        return mapper.writeValueAsString(msg)
                     .getBytes(charset);
    }
}