package rximp.api;


import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiConsumer;
import io.reactivex.subjects.Subject;

/**
 * RxImp
 */
public interface RxImp {

    <T> Observable<T> observableCall(String topic, Object payload, Class<T> clazz) throws Exception;

    <T> Disposable registerCall(String topic, BiConsumer<T, Subject<Object>> handler, Class<T> clazz);
}