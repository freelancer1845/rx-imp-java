package rximp.spring;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.extern.slf4j.Slf4j;
import rximp.api.RxImp;
import rximp.api.RxImpGateway;
import rximp.api.RxImpMapper;
import rximp.impl.RxImpImpl;

/**
 * RxImpWebsocketHandler
 */
@Slf4j
public class RxImpSpringWebsocketHandler extends BinaryWebSocketHandler implements BeanPostProcessor {

    private Map<String, WebSocketRxImpGateway> gateways = new HashMap<>();
    private Map<String, RxImp> handlers = new HashMap<>();
    private Map<String, ConcurrentWebSocketSessionDecorator> decorators = new HashMap<>();

    private static List<RxImpHandlerRegistration> registrations = new CopyOnWriteArrayList<>();

    private PublishSubject<WebSocketSession> _closingSessions = PublishSubject.create();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.trace("New Session");
        WebSocketRxImpGateway gateway = new WebSocketRxImpGateway(session);
        RxImp imp = new RxImpImpl(gateway);
        log.trace("Binding callbacks");
        log.trace("Current List: " + registrations.stream().map(s -> s.topic).collect(Collectors.joining("-")));
        for (RxImpHandlerRegistration registration : registrations) {
            log.trace("Registering handler for: " + registration.topic);
            Class<?> parameterType;
            if (registration.method.getParameterTypes().length > 0) {
                parameterType = registration.method.getParameterTypes()[0];
            } else {
                parameterType = Void.class;
            }
            imp.registerCall(registration.topic, (args) -> {
                log.trace("Invoking handler for " + registration.topic);
                Object[] invokeArgs = new Object[registration.method.getParameterCount()];
                for (int i = 0; i < invokeArgs.length; i++) {
                    Parameter p = registration.method.getParameters()[i];
                    if (p.getType().isAssignableFrom(RxImp.class)) {
                        invokeArgs[i] = imp;
                    } else {
                        invokeArgs[i] = args;
                    }
                }
                Observable<?> obs;
                if (registration.method.getReturnType().isAssignableFrom(Observable.class)) {
                    obs = (Observable<?>) registration.method.invoke(registration.bean, invokeArgs);
                } else if (registration.method.getReturnType().isAssignableFrom(Completable.class)) {
                    obs = ((Completable) registration.method.invoke(registration.bean, invokeArgs)).toObservable();
                } else if (registration.method.getReturnType().isAssignableFrom(Single.class)) {
                    obs = ((Single<?>) registration.method.invoke(registration.bean, invokeArgs)).toObservable();
                } else {
                    obs = Observable.just(registration.method.invoke(registration.bean, invokeArgs));
                }
                return obs
                        .takeUntil(this._closingSessions.filter(sess -> sess.getId().equals(session.getId())).take(1));
            }, parameterType);
        }
        ConcurrentWebSocketSessionDecorator decorator = new ConcurrentWebSocketSessionDecorator(session, 10000, 1000000);
        gateway._subject.subscribe(data -> {
            if (decorator.isOpen()) {
                decorator.sendMessage(new BinaryMessage(data));
            }
        });

        gateways.put(session.getId(), gateway);
        handlers.put(session.getId(), imp);
        decorators.put(session.getId(), decorator);

    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        this._closingSessions.onNext(session);
        gateways.remove(session.getId());
        handlers.remove(session.getId());
        decorators.get(session.getId()).close();
        decorators.remove(session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        gateways.get(session.getId()).handleMessage(message);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        for (Method m : bean.getClass().getMethods()) {
            RxImpHandler annotation = m.getDeclaredAnnotation(RxImpHandler.class);
            if (annotation != null) {
                RxImpHandlerRegistration registration = new RxImpHandlerRegistration(bean, m, annotation.value());
                registrations.add(registration);
                log.debug("Added registration with topic: " + registration.topic);
            }
        }
        return bean;
    }

    private class RxImpHandlerRegistration {

        private Object bean;
        private Method method;
        private String topic;

        RxImpHandlerRegistration(Object bean, Method method, String topic) {
            this.bean = bean;
            this.method = method;
            this.topic = topic;
        }

    }

    private class WebSocketRxImpGateway implements RxImpGateway {

        private PublishSubject<byte[]> _subject;
        private PublishSubject<byte[]> _in;
        private ObjectMapper _mapper;
        private RxImpMapper _RxImpMapper = new RxImpMapper() {

            @Override
            public String write(Object arg0) throws Exception {
                return _mapper.writeValueAsString(arg0);
            }

            @Override
            public <T> T read(String arg0, Class<T> arg1) throws Exception {
                return _mapper.readValue(arg0, arg1);
            }
        };

        public WebSocketRxImpGateway(WebSocketSession session) {
            _mapper = new ObjectMapper();
            _mapper.registerModule(new JavaTimeModule());
            _mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

            _subject = PublishSubject.create();
            _in = PublishSubject.create();

        }

        public void handleMessage(BinaryMessage msg) {

            this._in.onNext(msg.getPayload().array());
        }

        @Override
        public Observable<byte[]> in() {
            return this._in;
        }

        @Override
        public RxImpMapper mapper() {
            return this._RxImpMapper;
        }

        @Override
        public Subject<byte[]> out() {
            return _subject;
        }
    }
}