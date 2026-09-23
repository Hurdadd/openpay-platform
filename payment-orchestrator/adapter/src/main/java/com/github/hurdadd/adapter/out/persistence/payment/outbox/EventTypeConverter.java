package com.github.hurdadd.adapter.out.persistence.payment.outbox;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class EventTypeConverter implements AttributeConverter<EventTypes , String> {


    @Override
    public String convertToDatabaseColumn(EventTypes eventTypes) {
        return eventTypes == null ? null : eventTypes.wireFormat();
    }

    @Override
    public EventTypes convertToEntityAttribute(String db) {
        return db == null ? null : EventTypes.fromWireFormat(db);
    }
}
