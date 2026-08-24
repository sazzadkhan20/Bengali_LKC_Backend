package com.bengalilkc.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SynsetActionConverter implements AttributeConverter<SynsetAction, String> {

    @Override
    public String convertToDatabaseColumn(SynsetAction attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public SynsetAction convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SynsetAction.from(dbData);
    }
}
