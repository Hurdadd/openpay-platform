package com.github.hurdadd.adapter.in.persistence.idempotency.mapper;


import com.github.hurdadd.application.model.StoredResponse;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class StoredResponseMapper {
    private final ObjectMapper objectMapper;

    public StoredResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }



    public String toJson(StoredResponse storedResponse) {
        try {
            return objectMapper.writeValueAsString(storedResponse);
        } catch (Exception e) {
            throw new ResponseSerializationException("Failed to serialize StoredResponse to JSON", e);
        }
    }
    public StoredResponse fromJson(String json){
        try {
            return objectMapper.readValue(json, StoredResponse.class);
        } catch (Exception e) {
            throw new ResponseSerializationException("Failed to deserialize StoredResponse from JSON", e);
        }


    }
}
