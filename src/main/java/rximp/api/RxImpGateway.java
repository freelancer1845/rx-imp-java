package rximp.api;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.Subject;

/**
 * RxImpGateway
 */
public interface RxImpGateway {

    Observable<byte[]> in();

    Subject<byte[]> out();

    RxImpMapper mapper();

}