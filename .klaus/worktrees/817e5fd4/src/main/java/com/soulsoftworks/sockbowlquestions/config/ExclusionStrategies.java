package com.soulsoftworks.sockbowlquestions.config;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;

public class ExclusionStrategies {

    public static ExclusionStrategy gsonExclusionStrategy = new ExclusionStrategy() {
        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }

        @Override
        public boolean shouldSkipField(FieldAttributes field) {
            return field.getAnnotation(GsonExclude.class) != null;
        }
    };
}
