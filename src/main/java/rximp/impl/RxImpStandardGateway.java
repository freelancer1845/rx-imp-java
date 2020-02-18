package rximp.impl;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.Subject;
import rximp.api.RxImpGateway;
import rximp.api.RxImpMapper;

/**
 * RxImpStandardGateway
 */
public class RxImpStandardGateway implements RxImpGateway {

    private RxImpMapper mapper = new RxImpStandardMapper();

    private final Observable<byte[]> _in;
    private final Subject<byte[]> _out;

    public RxImpStandardGateway(Observable<byte[]> _in, Subject<byte[]> _out) {
        this._in = _in;
        this._out = _out;
    }

    @Override
    public Observable<byte[]> in() {
        return _in;
    }

    @Override
    public Subject<byte[]> out() {
        return _out;
    }

    @Override
    public RxImpMapper mapper() {
        return mapper;
    }

}