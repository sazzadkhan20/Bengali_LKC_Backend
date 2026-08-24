package com.bengalilkc.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PartOfSpeechConverter implements AttributeConverter<PartOfSpeech, String> {

    @Override
    public String convertToDatabaseColumn(PartOfSpeech attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public PartOfSpeech convertToEntityAttribute(String dbData) {
        return dbData == null ? null : PartOfSpeech.from(dbData);
    }
}
