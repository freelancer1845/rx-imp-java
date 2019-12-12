package rximp.api;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;

/**
 * RxImp
 */
public interface RxImp {

    <T> Observable<T> observableCall(String topic, Object payload, Class<T> clazz) throws Exception;

    <T> Disposable registerCall(String topic, Function<T, Observable<?>> handler, Class<T> clazz);
}