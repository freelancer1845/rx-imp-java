# Java implementation of Rx-Imp

* Currently the maven project is only available on github maven repository which cannot be access without access token...

## Other implemenations

* [Python](https://github.com/freelancer1845/rx-imp-python)
* [Javascript/Typescript](https://github.com/freelancer1845/rx-imp-js)


## Example using spring boot

### Create WebSocketConfigurer class and a Annotation interface

* For now just create the classes. Dont forget to add package definition on top
* In a Spring Bean you should be able to use them like this


```java

@RxImpHandler(topic="stupidTarget")
public Observable<String> stupidTarget() {
    return Observable.just("I'm not stupid!");
}

@RxImpHandler(topic="stupidTarget")
public Observable<String> stupidTarget(String payload) {
    return Observable.just("I'm not stupid! I can read " + payload);
}

@RxImpHandler(topic="/register/special")
public Observable<String> specialPartner(RxImp imp) {
    // using the RxImp object you can communicate reverse and for example ask for the version of the partner
    imp.observableCall("/version", null, String.class)
                               .subscribe(v -> log.info("Special Partner connected. Version: " + v));

    return Completable.complete();
}

```


#### WebsocketConfigurer

```java

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
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
@Component
@Slf4j
public class RxImpWebsocketHandler extends BinaryWebSocketHandler implements BeanPostProcessor {

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
        log.trace("Current List: " + registrations.stream()
                                                  .map(s -> s.topic)
                                                  .collect(Collectors.joining("-")));
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
                    if (p.getType()
                         .isAssignableFrom(RxImp.class)) {
                        invokeArgs[i] = imp;
                    } else {
                        invokeArgs[i] = args;
                    }
                }
                Observable<?> obs;
                if (registration.method.getReturnType()
                                       .isAssignableFrom(Observable.class)) {
                    obs = (Observable<?>) registration.method.invoke(registration.bean, invokeArgs);
                } else if (registration.method.getReturnType()
                                              .isAssignableFrom(Completable.class)) {
                    obs = ((Completable) registration.method.invoke(registration.bean, invokeArgs)).toObservable();
                } else if (registration.method.getReturnType()
                                              .isAssignableFrom(Single.class)) {
                    obs = ((Single<?>) registration.method.invoke(registration.bean, invokeArgs)).toObservable();
                } else {
                    obs = Observable.just(registration.method.invoke(registration.bean, invokeArgs));
                }
                return obs.takeUntil(this._closingSessions.filter(sess -> sess.getId()
                                                                              .equals(session.getId()))
                                                          .take(1));
            }, parameterType);
        }
        ConcurrentWebSocketSessionDecorator decorator = new ConcurrentWebSocketSessionDecorator(session, 1000, 1000000);
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
        decorators.get(session.getId())
                  .close();
        decorators.remove(session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        gateways.get(session.getId())
                .handleMessage(message);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        for (Method m : bean.getClass()
                            .getMethods()) {
            RxImpHandler annotation = m.getDeclaredAnnotation(RxImpHandler.class);
            if (annotation != null) {
                RxImpHandlerRegistration registration = new RxImpHandlerRegistration(bean, m, annotation.topic());
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
        private ObjectMapper _mapper = new ObjectMapper();
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
            _subject = PublishSubject.create();
            _in = PublishSubject.create();
        }

        public void handleMessage(BinaryMessage msg) {

            this._in.onNext(msg.getPayload()
                               .array());
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

```

#### Annotation Interface

```java

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RxImpHandler
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RxImpHandler {
    String topic();
}

```