package rximp.api;

import java.nio.charset.Charset;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RxImpMapper
 */
public interface RxImpMapper {

    <T> T read(byte[] payload, Class<T> clazz) throws Exception;

    byte[] write(Object payload) throws Exception;

}