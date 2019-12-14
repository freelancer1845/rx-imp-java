package rximp.impl;

import com.fasterxml.jackson.databind.ObjectMapper;

import rximp.api.RxImpMapper;

/**
 * RxImpStandardMapper
 */
public class RxImpStandardMapper implements RxImpMapper {

    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public <T> T read(String payload, Class<T> clazz) throws Exception {
        return mapper.readValue(payload, clazz);
    }

    @Override
    public String write(Object payload) throws Exception {
        return mapper.writeValueAsString(payload);
    }

}