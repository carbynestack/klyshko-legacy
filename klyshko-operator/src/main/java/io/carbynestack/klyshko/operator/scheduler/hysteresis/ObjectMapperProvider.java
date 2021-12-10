package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.inject.Singleton;
import javax.ws.rs.Produces;

@Singleton
public class ObjectMapperProvider {

    @Produces
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

}