package rximp.api;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Function;

/**
 * RxImp
 */
public interface RxImp {

    <T> Observable<T> observableCall(String topic, Object payload, Class<T> clazz) throws Exception;

    <T> Disposable registerCall(String topic, Function<T, Observable<?>> handler, Class<T> clazz);
}