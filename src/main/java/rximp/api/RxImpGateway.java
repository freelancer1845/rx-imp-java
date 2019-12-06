package rximp.api;

import io.reactivex.Observable;
import io.reactivex.subjects.Subject;

/**
 * RxImpGateway
 */
public interface RxImpGateway {

    Observable<byte[]> in();

    Subject<byte[]> out();

    RxImpMapper mapper();

}