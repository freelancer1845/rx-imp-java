package rximp.api;

/**
 * RxImpMapper
 */
public interface RxImpMapper {

    <T> T read(String payload, Class<T> clazz) throws Exception;

    String write(Object payload) throws Exception;

}