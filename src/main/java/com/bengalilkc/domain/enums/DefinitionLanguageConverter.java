package com.bengalilkc.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DefinitionLanguageConverter implements AttributeConverter<DefinitionLanguage, String> {

    @Override
    public String convertToDatabaseColumn(DefinitionLanguage attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public DefinitionLanguage convertToEntityAttribute(String dbData) {
        return dbData == null ? null : DefinitionLanguage.from(dbData);
    }
}
